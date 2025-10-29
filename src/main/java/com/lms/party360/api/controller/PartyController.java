package com.lms.party360.api.controller;

import com.lms.party360.api.model.request.CreatePersonRequest;
import com.lms.party360.api.model.response.CreatePartyResponse;
import com.lms.party360.app.command.CreateBusinessHandler;
import com.lms.party360.app.command.CreatePersonHandler;
import com.lms.party360.app.command.RequestScreeningHandler;
import com.lms.party360.app.command.UpdatePartyHandler;
import com.lms.party360.app.query.GetPartyQuery;
import com.lms.party360.app.query.GetPiiQuery;
import com.lms.party360.app.query.SearchPartyQuery;
import com.lms.party360.domain.policy.PolicyEnforcer;
import com.lms.party360.util.Headers;
import io.spring.gradle.dependencymanagement.org.apache.maven.artifact.repository.Authentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Validated
@Slf4j
public class PartyController {

    private final CreatePersonHandler createPersonHandler;
    private final CreateBusinessHandler createBusinessHandler;
    private final UpdatePartyHandler updatePartyHandler;
    private final RequestScreeningHandler requestScreeningHandler;

    private final GetPartyQuery getPartyQuery;
    private final SearchPartyQuery searchPartyQuery;
    private final GetPiiQuery getPiiQuery;

    private final PolicyEnforcer policyEnforcer;

    public ResponseEntity<CreatePartyResponse> createPerson(
            @RequestHeader(name = Headers.IDEMPOTENCY_KEY) @NotBlank String idempotencyKey,
            @Valid @RequestBody CreatePersonRequest request,
            Authentication auth
            ) {

        policyEnforcer.allow(auth, "CREATE", resourceFromTenant(request.tenant()));

    }

    private static ResourceRef resourceFromTenant(String tenant) {
        return new ResourceRef("tenant", nullSafe(tenant), null);
    }

    private static String nullSafe(String v) {
        return v == null ? "default" : v;
    }

    public record ResourceRef(String kind, String id, String scope) {}

}
