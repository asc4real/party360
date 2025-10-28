# party360
Party 360 for LMS

apps/services/party-360/
├─ README.md
├─ CHANGELOG.md
├─ Makefile
├─ Dockerfile
├─ .dockerignore
├─ .gitignore
├─ build.gradle.kts                  # or pom.xml
├─ settings.gradle.kts
├─ openapi.yaml                      # Source of truth for REST
├─ charts/party-360/                 # Helm chart
│  ├─ Chart.yaml
│  ├─ values.yaml
│  ├─ templates/
│  │  ├─ deployment.yaml
│  │  ├─ service.yaml
│  │  ├─ hpa.yaml
│  │  ├─ ingress.yaml
│  │  ├─ configmap.yaml
│  │  ├─ secret.yaml                 # generated in CI, no secrets in git
│  │  └─ networkpolicy.yaml
│  └─ values-*.yaml                  # dev/qa/uat/prod overlays (no secrets)
├─ k8s/                              # Optional raw manifests (if not Helm-only)
│  └─ podsecurity.yaml
├─ scripts/
│  ├─ local-up.sh                    # Compose: postgres, kafka, redis, minio
│  ├─ local-down.sh
│  ├─ publish-schemas.sh             # Avro → registry
│  ├─ verify-worm-lock.sh
│  └─ tokenization-smoke.sh
├─ docs/
│  ├─ adr/                           # Decision records for this service
│  ├─ arch/                          # C4 diagrams, sequence charts
│  ├─ api/                           # Endpoint guides, error catalog
│  ├─ runbooks/
│  │  ├─ kyc-vendor-outage.md
│  │  ├─ ofac-surge.md
│  │  ├─ outbox-replay.md
│  │  ├─ rescreen-missed-window.md
│  │  └─ pii-incident.md
│  └─ security/
│     └─ data-classification.md
├─ contracts/                        # API & event contracts for CI
│  ├─ rest/openapi.yaml              # symlink or copy from root openapi.yaml
│  └─ kafka/avro/
│     ├─ party.v1.PartyCreated.avsc
│     ├─ party.v1.PartyUpdated.avsc
│     ├─ party.v1.KycVerified.avsc
│     ├─ party.v1.OfacScreened.avsc
│     └─ party.v1.RiskFlagChanged.avsc
├─ src/
│  ├─ main/
│  │  ├─ java/com/acme/lms/party/
│  │  │  ├─ Party360Application.java
│  │  │  ├─ api/                    # REST controllers & DTO mappers
│  │  │  │  ├─ PartyController.java
│  │  │  │  ├─ ScreeningController.java
│  │  │  │  ├─ SearchController.java
│  │  │  │  └─ PiiController.java
│  │  │  ├─ domain/                 # Aggregates + policies
│  │  │  │  ├─ model/
│  │  │  │  │  ├─ Party.java
│  │  │  │  │  ├─ PersonProfile.java
│  │  │  │  │  ├─ BusinessProfile.java
│  │  │  │  │  ├─ ContactMethod.java
│  │  │  │  │  ├─ Address.java
│  │  │  │  │  ├─ ScreeningResult.java
│  │  │  │  │  ├─ RiskFlag.java
│  │  │  │  │  └─ PartyLink.java
│  │  │  │  ├─ policy/
│  │  │  │  │  ├─ ScreeningPolicy.java
│  │  │  │  │  ├─ RescreenPolicy.java
│  │  │  │  │  └─ DedupePolicy.java
│  │  │  │  └─ service/
│  │  │  │     ├─ PartyService.java
│  │  │  │     ├─ ScreeningService.java
│  │  │  │     ├─ RescreenScheduler.java
│  │  │  │     └─ DedupeService.java
│  │  │  ├─ app/                    # Use cases / command & query handlers (CQRS)
│  │  │  │  ├─ command/
│  │  │  │  │  ├─ CreatePersonHandler.java
│  │  │  │  │  ├─ UpdatePartyHandler.java
│  │  │  │  │  ├─ RequestScreeningHandler.java
│  │  │  │  │  └─ LinkPartiesHandler.java
│  │  │  │  └─ query/
│  │  │  │     ├─ GetPartyQuery.java
│  │  │  │     └─ SearchPartyQuery.java
│  │  │  ├─ repo/                   # Persistence adapters (Spring Data JPA)
│  │  │  │  ├─ PartyRepository.java
│  │  │  │  ├─ PersonProfileRepository.java
│  │  │  │  ├─ ScreeningResultRepository.java
│  │  │  │  ├─ RiskFlagRepository.java
│  │  │  │  └─ PartyLinkRepository.java
│  │  │  ├─ events/                 # Outbox + Kafka producers/consumers
│  │  │  │  ├─ publisher/
│  │  │  │  │  ├─ OutboxPublisher.java
│  │  │  │  │  ├─ PartyEventsProducer.java
│  │  │  │  │  └─ EventPayloadFactory.java
│  │  │  │  ├─ consumer/
│  │  │  │  │  └─ (optional consumers for cross-service events)
│  │  │  │  └─ model/               # Avro mappers
│  │  │  ├─ integration/            # Vendor adapters (via integration-gateway)
│  │  │  │  ├─ tokenizer/
│  │  │  │  │  ├─ TokenizationClient.java
│  │  │  │  │  └─ TokenizationConfig.java
│  │  │  │  ├─ kyc/
│  │  │  │  │  ├─ KycClient.java
│  │  │  │  │  ├─ KycFallbackClient.java
│  │  │  │  │  └─ KycMapper.java
│  │  │  │  ├─ ofac/
│  │  │  │  │  ├─ OfacClient.java
│  │  │  │  │  └─ OfacMapper.java
│  │  │  │  ├─ usps/
│  │  │  │  │  ├─ UspsClient.java
│  │  │  │  │  └─ AddressStandardizer.java
│  │  │  │  └─ reputation/
│  │  │  │     ├─ PhoneReputationClient.java
│  │  │  │     └─ EmailReputationClient.java
│  │  │  ├─ evidence/               # WORM evidence writer
│  │  │  │  ├─ EvidenceWriter.java
│  │  │  │  └─ EvidenceEnvelopeFactory.java
│  │  │  ├─ config/                 # App config & security
│  │  │  │  ├─ WebConfig.java       # Jackson, validation, problem+json
│  │  │  │  ├─ SecurityConfig.java  # OIDC resource server, scopes → OPA
│  │  │  │  ├─ KafkaConfig.java
│  │  │  │  ├─ OutboxConfig.java
│  │  │  │  ├─ RedisConfig.java
│  │  │  │  ├─ OpaClientConfig.java
│  │  │  │  └─ ObservabilityConfig.java
│  │  │  └─ util/                   # Rounding, masking, idempotency, validators
│  │  │     ├─ MaskingUtil.java
│  │  │     ├─ IdempotencyFilter.java
│  │  │     └─ Validators.java
│  │  ├─ resources/
│  │  │  ├─ application.yml         # defaults (no secrets)
│  │  │  ├─ application-prod.yml    # prod overrides (non-secret)
│  │  │  ├─ db/migration/           # Flyway/Liquibase migrations
│  │  │  │  ├─ V1__init_party_tables.sql
│  │  │  │  ├─ V2__screening_tables.sql
│  │  │  │  ├─ V3__risk_flags_and_outbox.sql
│  │  │  │  └─ V4__search_indexes.sql
│  │  │  ├─ avro/                   # local copy used in build (published in CI)
│  │  │  │  ├─ party.v1.PartyCreated.avsc
│  │  │  │  ├─ party.v1.PartyUpdated.avsc
│  │  │  │  ├─ party.v1.KycVerified.avsc
│  │  │  │  └─ party.v1.OfacScreened.avsc
│  │  │  ├─ policy/                 # service-level policy configs
│  │  │  │  ├─ screening-policy.yaml
│  │  │  │  ├─ rescreen-policy.yaml
│  │  │  │  └─ dedupe-policy.yaml
│  │  │  ├─ mapping/
│  │  │  │  ├─ kyc-vendor-mapping.json
│  │  │  │  └─ ofac-vendor-mapping.json
│  │  │  └─ logback-spring.xml
│  └─ test/
│     ├─ java/com/acme/lms/party/
│     │  ├─ api/PartyControllerTest.java
│     │  ├─ integration/           # Testcontainers: PG, Kafka, Redis, MinIO
│     │  │  ├─ CreateAndScreenIT.java
│     │  │  ├─ RescreenPolicyIT.java
│     │  │  └─ OutboxToKafkaIT.java
│     │  ├─ contracts/             # Pact (BFF/consumers) tests
│     │  │  ├─ PartyApiPactTest.java
│     │  │  └─ SearchApiPactTest.java
│     │  ├─ policy/PolicyTests.java
│     │  ├─ security/OpaAuthZTests.java
│     │  └─ util/MaskingUtilTest.java
│     └─ resources/
│        ├─ application-test.yml
│        └─ wiremock/              # Stubs for vendors
│           ├─ kyc/
│           └─ ofac/
├─ opa/                             # Local OPA policies (compiled or referenced)
│  ├─ policy.rego
│  └─ test/
│     └─ policy_test.rego
├─ quality/
│  ├─ checkstyle.xml
│  ├─ spotbugs-exclude.xml
│  └─ sonar-project.properties
└─ .github/
├─ workflows/
│  ├─ service-ci.yml            # build, unit, integration, SAST, contract tests
│  ├─ publish-avro.yml
│  ├─ helm-release.yml
│  └─ trivy-scan.yml
└─ CODEOWNERS
