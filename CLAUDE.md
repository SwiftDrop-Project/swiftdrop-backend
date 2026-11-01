# SwiftDrop Backend - project notes for Claude Code

Spring Boot 4 · Java 21 · Maven · PostgreSQL · Flyway · RabbitMQ.
Part of SwiftDrop, a parcel delivery & courier management system (university group project, 9 developers).
Related repos, cloned side by side: `../swiftdrop-frontend` (Next.js), `../swiftdrop-deploy` (docker-compose, docs).
Check pom.xml for exact versions and use APIs that match them (Spring Boot 4 / Spring Security 7 / Jackson 3).

## Architecture: modular monolith
- One package per business module under `com.swiftdrop`: auth, users, merchants, parcels, pricing, dispatch,
  courier, tracking, notifications, webhooks, reports. Cross-cutting code goes in `shared/`
  (config, security, exception, event, util).
- Every module has 4 layers:
  - `api`: REST controllers and request/response DTOs (Java records)
  - `application`: services / use cases (transactions live here)
  - `domain`: entities, enums, domain rules
  - `infrastructure`: JPA repositories, messaging, external clients
- A module calls another module ONLY through that module's `application` service, or by listening to its events.
  Never use another module's repository or entity directly.
- Controllers never return entities. Map to DTOs.

## API rules
- The contract is `API.md`. Implement it exactly; update it in the same PR when it changes.
- All endpoints under `/api/v1`. JSON only.
- Validate every request with Bean Validation.
- All errors go through the global handler in `shared/exception` using the error shape in API.md.
- Never put stack traces, passwords, tokens, emails or phone numbers in error messages or logs.
- Keep Swagger (springdoc-openapi) working at `/swagger-ui.html`.

## Configuration
- No hard-coded secrets. `application.yml` reads `${VAR}` placeholders; the full list is in `.env.example`.
- Local runs load `./.env` through `spring.config.import: optional:file:.env[.properties]`.
- In Docker the same variable names come from `../swiftdrop-deploy/docker-compose.yml`.

## Database
- Schema changes ONLY through Flyway files in `src/main/resources/db/migration`.
- File name: `V<yyyyMMddHHmm>__<description>.sql`, e.g. `V202611011400__create_users.sql`
  (timestamps stop two teammates creating the same version number).
- Never edit a migration that is already merged into `dev`. Add a new one.
- `spring.jpa.hibernate.ddl-auto: validate`.

## Security
- JWT (HS256) using Spring Security's OAuth2 resource server with Nimbus encoder/decoder (no extra JWT library).
- Token is stored in the httpOnly cookie `SD_TOKEN` (see API.md), read by a custom BearerTokenResolver.
- Roles: CUSTOMER, MERCHANT, COURIER, ADMIN. Deny by default; open only what API.md marks public.
- Passwords hashed with BCrypt.

## Testing
- JUnit 5 + Spring Boot Test + Testcontainers (PostgreSQL). Docker Desktop must be running.
- Every endpoint gets tests, including authorization tests (no login → 401, wrong role → 403).
- Run `./mvnw test` and keep it green before finishing a task.

## Commands
- Start infrastructure: `cd ../swiftdrop-deploy && docker compose up -d postgres rabbitmq mailpit adminer`
- Run: `./mvnw spring-boot:run`
- Test: `./mvnw test`
- Build: `./mvnw clean package`

## Git
- Work on `feature/*` branches; PRs into `dev`. Commit style: `feat(auth): add login endpoint`.
