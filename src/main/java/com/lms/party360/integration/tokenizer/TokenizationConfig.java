package com.lms.party360.integration.tokenizer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import static org.springframework.http.HttpHeaders.*;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

/**
 * TokenizationConfig
 *
 * Wires a hardened WebClient for Vault and exports a TokenizationClient bean.
 * - Strongly-typed properties with validation
 * - Connection/read timeouts & small buffer sizes
 * - No PII logging (request/response redaction)
 * - Optional proxy + namespace support
 * - Conditional beans to avoid double-registration
 */
@Configuration
@EnableConfigurationProperties(TokenizationConfig.TokenizationProps.class)
@Slf4j
public class TokenizationConfig {

    @Bean(name = "vaultWebClient")
    @ConditionalOnMissingBean(name = "vaultWebClient")
    public WebClient vaultWebClient(TokenizationProps props) {
        validateProps(props);

        // Hardened Reactor Netty client
        HttpClient http =
                HttpClient.create()
                        .compress(true)
                        .responseTimeout(props.getTimeout())
                        .followRedirect(true)
                        .wiretap(false)
                        .resolver(spec -> spec.queryTimeout(Duration.of(2000, ChronoUnit.MILLIS)))
                        .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) props.getConnectTimeout().toMillis());

        // Optional HTTP proxy (corp env)
        if (props.getProxy() != null && props.getProxy().isEnabled()) {
            http = http.proxy(p -> p
                    .type(ProxyProvider.Proxy.HTTP)
                    .host(props.getProxy().getHost())
                    .port(props.getProxy().getPort()));
        }

        // Limit buffer to prevent large responses from eating memory
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(props.getMaxInMemoryBytes()))
                .build();

        WebClient.Builder b = WebClient.builder()
                .baseUrl(props.getUrl())
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(http))
                .exchangeStrategies(strategies)
                .defaultHeader(ACCEPT, APPLICATION_JSON_VALUE)
                .defaultHeader(CACHE_CONTROL, "no-store")
                .filter(redactingRequestFilter())
                .filter(redactingResponseFilter());

        // Namespace header (if you use Vault namespaces per tenant or BU)
        Optional.ofNullable(props.getNamespaceHeader()).filter(h -> !h.isBlank())
                .ifPresent(h -> b.defaultHeader(h, props.getNamespaceValue()));

        // If you *must* use a static token, set it as default header.
        // (Preferred: use Vault Agent / short-lived auth; rotate via container sidecar.)
        if (props.getAuth().getMode() == TokenizationProps.Auth.Mode.STATIC_TOKEN) {
            b.defaultHeader("X-Vault-Token", Objects.requireNonNull(props.getAuth().getToken(),
                    "tokenization.vault.auth.token is required for STATIC_TOKEN mode"));
        }

        return b.build();
    }

    @Bean
    @ConditionalOnMissingBean(TokenizationClient.class)
    public TokenizationClient tokenizationClient(WebClient vaultWebClient, TokenizationProps props) {
        // This aligns with TokenizationClient’s constructor that expects WebClient + Props.
        // If your TokenizationClient already uses @Component, this bean won't be created.
        return new TokenizationClient(vaultWebClient, props.asVaultProps());
    }

    // ---------- Filters (safe logging) ----------

    /** Logs method + path only (no headers, no body). */
    private static ExchangeFilterFunction redactingRequestFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            URI u = req.url();
            // redact query (PII could leak via value params)
            String path = u.getPath();
            log.debug("Vault request: {} {}", req.method(), path);
            return reactor.core.publisher.Mono.just(req);
        });
    }

    /** Logs status + path only (no body). */
    private static ExchangeFilterFunction redactingResponseFilter() {
        return ExchangeFilterFunction.ofResponseProcessor(resp -> {
            // attempt to log the path via attributes if present (optional)
            log.debug("Vault response: {} {}", resp.statusCode(), "(redacted)");
            return reactor.core.publisher.Mono.just(resp);
        });
    }

    private static void validateProps(TokenizationProps p) {
        if (!p.getUrl().startsWith("http")) {
            throw new IllegalArgumentException("tokenization.vault.url must be http(s)");
        }
    }

    // ---------- Strongly-typed properties ----------

    @Getter @Setter
    @Validated
    @ConfigurationProperties(prefix = "tokenization.vault")
    public static class TokenizationProps {

        /** Base URL of Vault, e.g. https://vault.mycorp.internal */
        @NotBlank
        private String url = "http://vault:8200";

        /** Client operation mode against Vault. */
        @NotNull
        private Mode mode = Mode.TRANSFORM;

        /** Per-PII roles & transformations (Transform/Transit). */
        @Valid @NotNull
        private Roles roles = new Roles();

        @Valid @NotNull
        private Transformations transformations = new Transformations();

        /** Request timeout (read). */
        @NotNull
        private Duration timeout = Duration.ofSeconds(2);

        /** Connect timeout. */
        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(2);

        /** Max in-memory buffer for WebClient. */
        @Min(16 * 1024)
        private int maxInMemoryBytes = 256 * 1024;

        /** Optional Vault namespace header/value (if you use namespaces). */
        private String namespaceHeader;   // e.g., "X-Vault-Namespace"
        private String namespaceValue;    // e.g., "team-a/"

        /** Optional proxy config. */
        @Valid
        private Proxy proxy;

        /** Authentication mode (STATIC_TOKEN is simplest; prefer Vault Agent for prod). */
        @Valid @NotNull
        private Auth auth = new Auth();

        public enum Mode { TRANSFORM, TRANSIT }

        @Getter @Setter
        public static class Roles {
            @NotBlank private String ssnRole = "party-ssn-role";
            @NotBlank private String einRole = "party-ein-role";
        }

        @Getter @Setter
        public static class Transformations {
            /** For TRANSFORM: transformation names; for TRANSIT: key names. */
            @NotBlank private String ssn = "ssn";
            @NotBlank private String ein = "ein";
        }

        @Getter @Setter
        public static class Proxy {
            private boolean enabled = false;
            @NotBlank private String host = "proxy.local";
            @Min(1) private int port = 8080;
        }

        @Getter @Setter
        public static class Auth {
            public enum Mode { STATIC_TOKEN, AGENT_TOKEN_FILE }
            @NotNull private Mode mode = Mode.STATIC_TOKEN;

            /** Used when mode=STATIC_TOKEN (e.g., read from env/secret). */
            private String token;

            /**
             * Used when mode=AGENT_TOKEN_FILE (e.g., Vault Agent writes token to /vault/token).
             * If set, prefer mounting file as read-only; the WebClient here reads token once at startup.
             * For live rotation, inject a filter that resolves token on each request instead.
             */
            private String tokenFile = "/vault/token";
        }

        // Adapter so the existing TokenizationClient can reuse these props directly.
        public TokenizationClient.Props asVaultProps() {
            TokenizationClient.Props p = new TokenizationClient.Props();
            p.setUrl(this.url);
            p.setMode(switch (this.mode) {
                case TRANSFORM -> TokenizationClient.Props.Mode.TRANSFORM;
                case TRANSIT   -> TokenizationClient.Props.Mode.TRANSIT;
            });
            TokenizationClient.Props.Roles r = new TokenizationClient.Props.Roles();
            r.setSsnRole(this.roles.getSsnRole());
            r.setEinRole(this.roles.getEinRole());
            p.setRoles(r);

            TokenizationClient.Props.Transformations t = new TokenizationClient.Props.Transformations();
            t.setSsn(this.transformations.getSsn());
            t.setEin(this.transformations.getEin());
            p.setTransformations(t);

            p.setTimeout(this.timeout);

            // Resolve token based on auth mode.
            String tokenValue = switch (this.auth.getMode()) {
                case STATIC_TOKEN -> this.auth.getToken();
                case AGENT_TOKEN_FILE -> safeReadFile(this.auth.getTokenFile());
            };
            p.setToken(Objects.requireNonNull(tokenValue, """
                Vault token not resolved. Configure either:
                - tokenization.vault.auth.mode=STATIC_TOKEN and tokenization.vault.auth.token
                - or tokenization.vault.auth.mode=AGENT_TOKEN_FILE and tokenization.vault.auth.token-file
                """));
            return p;
        }

        private static String safeReadFile(String path) {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(path)).trim();
            } catch (Exception e) {
                log.error("Failed to read Vault token file at {}", path, e);
                return null;
            }
        }
    }
}

