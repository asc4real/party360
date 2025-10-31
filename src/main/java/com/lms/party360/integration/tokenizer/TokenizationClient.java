package com.lms.party360.integration.tokenizer;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import com.lms.party360.exception.Problem;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import static java.util.Map.of;

/**
 * TokenizationClientVault
 *
 * Production-grade client to HashiCorp Vault Transform/Transit for PII tokenization.
 * - Deterministic tokenization for SSN/EIN (strip noise, validate length)
 * - No raw PII ever logged; masked values in warnings
 * - Resilience via Resilience4j (timeout, retry, circuit breaker)
 * - Tenant header passthrough (optional multi-tenant routing)
 *
 * Supports Vault Transform (preferred):
 *   POST /v1/transform/encode/{role}  { "transformation":"ssn", "value":"123456789" }
 *
 * Optionally supports Transit (format-preserving) if you set mode=TRANSIT, using:
 *   POST /v1/transit/transform  { "name":"ssn", "plaintext":"MTIzNDU2Nzg5" }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenizationClient {

    private final WebClient vaultClient;         // configured bean (see inner Config below)
    private final Props props;

    /** SSN → token (deterministic) */
    @CircuitBreaker(name = "vaultTokenize")
    @Retry(name = "vaultTokenize")
    @TimeLimiter(name = "vaultTokenize")
    public String tokenizeSsn(@NotNull String ssnRaw, @NotNull String tenant) throws Throwable {
        String digits = normalizeDigits(ssnRaw, 9, "INVALID_SSN");
        return switch (props.mode()) {
            case TRANSFORM -> encodeTransform(props.roles().ssnRole(), props.transformations().ssn(), digits, tenant);
            case TRANSIT    -> transformTransit(props.transformations().ssn(), digits, tenant);
        };
    }

    /** EIN → token (deterministic) */
    public String tokenizeEin(@NotNull String einRaw, @NotNull String tenant) throws Throwable {
        String digits = normalizeDigits(einRaw, 9, "INVALID_EIN");
        return switch (props.mode()) {
            case TRANSFORM -> encodeTransform(props.roles().einRole(), props.transformations().ein(), digits, tenant);
            case TRANSIT    -> transformTransit(props.transformations().ein(), digits, tenant);
        };
    }

    // ---------- Core calls ----------

    private String encodeTransform(String role, String transformation, String value, String tenant) throws Exception {
        try {
            var payload = of("transformation", transformation, "value", value);
            var req = vaultClient.post()
                    .uri("/v1/transform/encode/{role}", role)
                    .header("X-Vault-Token", props.token())
                    .headers(h -> addTenant(h, tenant))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(payload))
                    .retrieve()
                    .onStatus(s -> s.value() == 400, r -> r.bodyToMono(String.class)
                            .map(b -> Problem.upstream("VAULT_BAD_REQUEST", "Vault rejected request")))
                    .onStatus(s -> s.is4xxClientError(), r -> r.bodyToMono(String.class)
                            .map(b -> Problem.upstream("VAULT_4XX", "Vault auth/perm error")))
                    .onStatus(s -> s.is5xxServerError(), r -> r.bodyToMono(String.class)
                            .map(b -> Problem.upstream("VAULT_5XX", "Vault server error")))
                    .bodyToMono(TransformEncodeResponse.class);

            TransformEncodeResponse resp = blockWithTimeout(req, props.timeout());
            if (resp == null || resp.data == null || !StringUtils.hasText(resp.data.encoded_value)) {
                throw Problem.upstream("VAULT_EMPTY_RESPONSE", "Vault returned empty encoded value.");
            }
            return resp.data.encoded_value;
        } catch (WebClientResponseException e) {
            warnMasked("Transform encode failed", value, tenant, e);
            throw Problem.upstream("VAULT_HTTP_" + e.getStatusCode().value(), "Vault request failed");
        } catch (Exception e) {
            warnMasked("Transform encode unexpected failure", value, tenant, e);
            throw Problem.upstream("TOKENIZATION_FAILED", "Unable to tokenize value.");
        }
    }

    private String transformTransit(String name, String digits, String tenant) throws Exception {
        try {
            String b64 = java.util.Base64.getEncoder().encodeToString(digits.getBytes(StandardCharsets.UTF_8));
            var payload = of("name", name, "plaintext", b64, "tweak", "", "transformation", "FPE_AES256_GCM");
            var req = vaultClient.post()
                    .uri("/v1/transit/transform")
                    .header("X-Vault-Token", props.token())
                    .headers(h -> addTenant(h, tenant))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(payload))
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError(), r -> r.bodyToMono(String.class)
                            .map(b -> Problem.upstream("VAULT_4XX", "Vault auth/perm error")))
                    .onStatus(s -> s.is5xxServerError(), r -> r.bodyToMono(String.class)
                            .map(b -> Problem.upstream("VAULT_5XX", "Vault server error")))
                    .bodyToMono(TransitTransformResponse.class);

            TransitTransformResponse resp = blockWithTimeout(req, props.timeout());
            if (resp == null || resp.data == null || !StringUtils.hasText(resp.data.ciphertext)) {
                throw Problem.upstream("VAULT_EMPTY_RESPONSE", "Vault returned empty ciphertext.");
            }
            return resp.data.ciphertext; // deterministic if configured so on Vault side
        } catch (WebClientResponseException e) {
            warnMasked("Transit transform failed", digits, tenant, e);
            throw Problem.upstream("VAULT_HTTP_" + e.getStatusCode().value(), "Vault request failed");
        } catch (Exception e) {
            warnMasked("Transit transform unexpected failure", digits, tenant, e);
            throw Problem.upstream("TOKENIZATION_FAILED", "Unable to tokenize value.");
        }
    }

    // ---------- Helpers ----------

    private static String normalizeDigits(String raw, int expectedLen, String code) throws Throwable {
        if (raw == null) throw Problem.badRequest(code, "Value is required.");
        String d = raw.replaceAll("[^0-9]", "");
        if (d.length() != expectedLen) {
            throw Problem.badRequest(code, "Value must contain exactly " + expectedLen + " digits.");
        }
        return d;
    }

    private static void addTenant(HttpHeaders h, String tenant) {
        if (StringUtils.hasText(tenant)) {
            // Optional: use a Vault policy/namespace header; adjust to your setup.
            // Example for namespaces: h.add("X-Vault-Namespace", tenant);
            h.add("X-Tenant-Id", tenant);
        }
    }

    private static void warnMasked(String msg, String digits, String tenant, Exception e) {
        String masked = (digits == null || digits.length() < 4)
                ? "****"
                : "*****" + digits.substring(digits.length() - 4);
        log.warn("{} (masked={} tenant={})", msg, masked, tenant, e);
    }

    private static <T> T blockWithTimeout(Mono<T> mono, Duration timeout) {
        return mono.block(timeout == null ? Duration.ofSeconds(2) : timeout);
    }

    // ---------- DTOs (Vault responses) ----------

    private record TransformEncodeResponse(Data data, Map<String, Object> warnings) {
        private record Data(String encoded_value) {}
    }

    private record TransitTransformResponse(Data data) {
        private record Data(String ciphertext) {}
    }

    // ---------- Properties & WebClient config ----------

    @Component
    @ConfigurationProperties(prefix = "tokenization.vault")
    public static class Props {
        public enum Mode { TRANSFORM, TRANSIT }

        private String url = "http://vault:8200";
        private String token; // use Vault Agent/JWT auth in prod; token here for simplicity
        private Mode mode = Mode.TRANSFORM;
        private Roles roles = new Roles();
        private Transformations transformations = new Transformations();
        private Duration timeout = Duration.ofSeconds(2);

        public String url() { return url; }
        public String token() { return token; }
        public Mode mode() { return mode; }
        public Roles roles() { return roles; }
        public Transformations transformations() { return transformations; }
        public Duration timeout() { return timeout; }

        public void setUrl(String url) { this.url = url; }
        public void setToken(String token) { this.token = token; }
        public void setMode(Mode mode) { this.mode = mode; }
        public void setRoles(Roles roles) { this.roles = roles; }
        public void setTransformations(Transformations t) { this.transformations = t; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }

        public static class Roles {
            private String ssnRole = "party-ssn-role";
            private String einRole = "party-ein-role";
            public String ssnRole() { return ssnRole; }
            public String einRole() { return einRole; }
            public void setSsnRole(String r) { this.ssnRole = r; }
            public void setEinRole(String r) { this.einRole = r; }
        }
        public static class Transformations {
            private String ssn = "ssn";
            private String ein = "ein";
            public String ssn() { return ssn; }
            public String ein() { return ein; }
            public void setSsn(String v) { this.ssn = v; }
            public void setEin(String v) { this.ein = v; }
        }
    }

    /** WebClient bean factory (can also be centralized in a @Configuration). */
    @org.springframework.context.annotation.Bean("vaultWebClient")
    public WebClient vaultWebClient(Props props) {
        Objects.requireNonNull(props.token(), "Vault token must be configured (or wire Vault Agent auth)");
        return WebClient.builder()
                .baseUrl(props.url())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClientWithTimeout(props.timeout())))
                .filter(loggingFilter()) // redacts bodies automatically
                .build();
    }

    private static reactor.netty.http.client.HttpClient httpClientWithTimeout(Duration timeout) {
        var t = timeout == null ? Duration.ofSeconds(2) : timeout;
        return reactor.netty.http.client.HttpClient.create()
                .responseTimeout(t)
                .compress(true);
    }

    /** Minimal redacting filter (no request body logged). */
    private static ExchangeFilterFunction loggingFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            // Log only method + URI; never log headers with tokens nor body (PII)
            log.debug("Vault request: {} {}", req.method(), req.url());
            return Mono.just(req);
        });
    }
}

