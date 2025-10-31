package com.lms.party360.app.command;

import com.lms.party360.api.model.request.CreatePersonRequest;
import com.lms.party360.api.model.response.CreatePartyResponse;
import com.lms.party360.events.publisher.OutboxWriter;
import com.lms.party360.exception.Problem;
import com.lms.party360.integration.tokenizer.TokenizationClient;
import com.lms.party360.integration.usps.AddressStandardizer;
import com.lms.party360.idem.IdempotencyStore;
import com.lms.party360.repo.PartyRepository;
import com.lms.party360.repo.PersonProfileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
//import org.slf4j.Logger;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Application-layer handler: framework-agnostic (no Spring Security types here).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreatePersonHandler {

    // ----------- Collaborators (ports) ------------
    private final IdempotencyStore idemStore;
    private final TokenizationClient tokenizer;
    private final AddressStandardizer addressStandardizer;
    private final ContactNormalizer contactNormalizer;
    private final PartyRepository partyRepo;
    private final PersonProfileRepository personRepo;
    private final AddressRepository addressRepo;
    private final ContactRepository contactRepo;
    private final OutboxWriter outbox;
    private final ScreeningOrchestrator screening;
    private final TenantClock clock;
    private final MeterRegistry metrics;

    // ----------- Metrics --------------
    private static final String MTR_CREATE_LATENCY = "party.create.person.latency";
    private static final String MTR_CREATE_ERRORS  = "party.create.person.errors";
    private static final String MTR_IDEMPOTENCY_HIT= "party.create.person.idempotency.hit";

    /**
     * New, clean signature: pass only what the handler needs, not Authentication.
     */
    @Transactional
    public CreatePartyResponse handle(@NonNull UUID idempotencyKey,
                                      @NonNull CreatePersonRequest req,
                                      @NonNull String tenant,
                                      @NonNull String actorId,
                                      @NonNull String correlationId) throws Exception {

        final Timer.Sample t = Timer.start(metrics);
        try {
            Supplier<CreatePartyResponse> supplier =
                    () -> createNewPerson(req, tenant, actorId, correlationId);

            CreatePartyResponse resp = idemStore.execute(
                    "party:create:person", idempotencyKey, hashRequest(req), supplier,
                    () -> metrics.counter(MTR_IDEMPOTENCY_HIT).increment());

            t.stop(Timer.builder(MTR_CREATE_LATENCY)
                    .tag("tenant", tenant)
                    .tag("screening", Boolean.TRUE.equals(req.asyncScreen()) ? "async" : "sync")
                    .register(metrics));

            return resp;

        } catch (Problem p) {
            t.stop(Timer.builder(MTR_CREATE_LATENCY).tag("error", p.code()).register(metrics));
            metrics.counter(MTR_CREATE_ERRORS, "code", p.code()).increment();
            throw p;

        } catch (DataIntegrityViolationException dup) {
            metrics.counter(MTR_CREATE_ERRORS, "code", "PARTY_ALREADY_EXISTS").increment();
            throw Problem.conflict("PARTY_ALREADY_EXISTS",
                    "A party with the same SSN and DOB already exists.");

        } catch (Exception e) {
            metrics.counter(MTR_CREATE_ERRORS, "code", "UNEXPECTED").increment();
            log.error("CreatePerson unexpected failure corrId={}, tenant={}, actor={}", correlationId, tenant, actorId, e);
            throw Problem.internal("CREATE_PERSON_FAILED", "Unable to create party at this time.");
        }
    }

    // -------------------- Core create logic --------------------

    private CreatePartyResponse createNewPerson(CreatePersonRequest req,
                                                String tenant,
                                                String actorId,
                                                String corrId) {
        LocalDate dob = parseDob(req.dob());

        String ssnRaw   = req.ssn();
        String ssnToken = safeTokenizeSsn(ssnRaw, tenant);
        String last4    = last4(ssnRaw);

        personRepo.findBySsnTokenAndDob(ssnToken, dob)
                .ifPresent(existing -> {
                    throw Problem.conflict("PARTY_ALREADY_EXISTS",
                            "A party with the same SSN and DOB already exists.");
                });

        List<AddressDraft>  normalizedAddresses = addressStandardizer.normalizeAll(req.addresses(), tenant);
        List<ContactDraft>  normalizedContacts  = contactNormalizer.normalizeAll(req.contacts(), tenant);

        String          partyId = Ids.newPartyId();
        OffsetDateTime  now     = clock.now();

        partyRepo.insert(new PartyEntity(partyId, "PERSON", "ACTIVE", "LOW", tenant, now, now));
        personRepo.insert(new PersonProfileEntity(partyId,
                req.firstName().trim(), req.lastName().trim(), dob, ssnToken, last4));

        addressRepo.batchInsert(partyId, normalizedAddresses);
        contactRepo.batchInsert(partyId, normalizedContacts);

        outbox.enqueue(partyId, "party.v1.PartyCreated",
                EventPayloads.partyCreated(partyId, "PERSON", "LOW", now, corrId),
                Headers.of("tenant", tenant, "correlationId", corrId, "actorId", actorId));

        boolean async = req.asyncScreen() == null || req.asyncScreen();
        String consentId = req.consentId();

        if (async) {
            String kycReqId  = screening.enqueueKyc(partyId, consentId, tenant, corrId);
            String ofacReqId = screening.enqueueOfac(partyId, consentId, tenant, corrId);
            return new CreatePartyResponse(partyId, "PERSON", "LOW",
                    "QUEUED", kycReqId, ofacReqId, now);
        } else {
            screening.runSyncAll(partyId, consentId, tenant, corrId);
            return new CreatePartyResponse(partyId, "PERSON", "LOW",
                    "COMPLETED", null, null, now);
        }
    }

    // -------------------- Helpers --------------------

    private static byte[] hashRequest(CreatePersonRequest req) {
        String material = String.join("|",
                safe(req.firstName()), safe(req.lastName()), safe(req.dob()),
                safe(req.ssn()),
                Boolean.toString(Boolean.TRUE.equals(req.asyncScreen()))
        );
        return Hashing.sha256(material);
    }

    private static String last4(String ssnRaw) {
        String digits = ssnRaw.replaceAll("[^0-9]", "");
        if (digits.length() != 9) throw Problem.badRequest("INVALID_SSN", "Invalid SSN format.");
        return digits.substring(5);
    }

    private static LocalDate parseDob(String dob) throws Throwable {
        try { return LocalDate.parse(dob); }
        catch (DateTimeParseException e) {
            throw Problem.badRequest("INVALID_DOB", "dob must be ISO date (yyyy-MM-dd).");
        }
    }

    private String safeTokenizeSsn(String ssnRaw, String tenant) {
        try {
            return tokenizer.tokenizeSsn(ssnRaw, tenant);
        } catch (Exception e) {
            log.warn("Tokenization failed (maskedSSN={}***) tenant={}", Masker.maskSsn(ssnRaw), tenant);
            try {
                throw Problem.upstream("TOKENIZATION_FAILED", "Unable to tokenize SSN.");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    // ---- (ports & value records identical to the previous version; omitted for brevity) ----
    // Keep IdempotencyStore, TokenizationClient, AddressStandardizer, ContactNormalizer,
    // repositories, OutboxWriter, ScreeningOrchestrator, TenantClock, plus AddressDraft, ContactDraft, etc.
    // If you need the interfaces again, reuse them exactly as in my previous message.
}

