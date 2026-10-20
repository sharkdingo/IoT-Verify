# IoT-Verify Backend

Spring Boot backend for the IoT-Verify formal-verification platform. This file covers
running the backend locally and its package layout. For everything else, see the
project **[Documentation Hub](../docs/README.md)**.

## Run locally

Prerequisites: JDK 17+, Maven 3.6+, MySQL 8.0+, NuSMV 2.6–2.7 (**not** nuXmv);
Redis 6.0+ optional (fail-open). Full setup:
[docs/getting-started/installation.md](../docs/getting-started/installation.md).

```bash
# Required env vars (defaults & full list: docs/getting-started/configuration.md)
export DB_PASSWORD="..." JWT_SECRET="..." IOT_VERIFY_OPENAI_API_KEY="..." NUSMV_PATH="/path/to/NuSMV"

mvn spring-boot:run        # http://localhost:8080, auto-creates tables
# or
mvn clean package -DskipTests
java -jar target/Iot-Verify-0.0.1-SNAPSHOT.jar
```

Tests: `mvn test`.

## Package layout

The base package is `cn.edu.nju.Iot_Verify`; the entry point is `IotVerifyApplication`.
See the [architecture overview](../docs/architecture/overview.md#backend-package-layout)
for packages and the canonical template-schema location.

## Documentation

- Configuration (all env vars): [docs/getting-started/configuration.md](../docs/getting-started/configuration.md)
- Deployment: [docs/getting-started/deployment.md](../docs/getting-started/deployment.md)
- API — [conventions](../docs/api/overview.md) ·
  [endpoint index](../docs/api/rest-endpoints.md) ·
  [auth](../docs/api/auth.md) · [board](../docs/api/board.md) ·
  [verification](../docs/api/verification.md) · [chat (SSE)](../docs/api/chat-sse.md) ·
  [counterexample exploration](../docs/api/fuzzing.md) ·
  [AI tools](../docs/api/ai-tools.md)
- Architecture — [device identity](../docs/architecture/device-identity.md) ·
  [data authority](../docs/architecture/data-authority-model.md) ·
  [verification flow](../docs/architecture/verification-flow.md) ·
  [counterexample exploration flow](../docs/architecture/fuzzing-flow.md) ·
  [NuSMV model](../docs/architecture/nusmv-model.md) ·
  [spec templates & P1–P5](../docs/architecture/spec-templates.md) ·
  [auto-fix](../docs/architecture/auto-fix.md)
- Troubleshooting: [docs/guides/troubleshooting.md](../docs/guides/troubleshooting.md)

## License

See the LICENSE note in the project [README](../README.md#license).
