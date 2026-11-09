# SwiftDrop Backend

The REST API for SwiftDrop, a parcel delivery and courier management system for Sri Lanka.
It is one Spring Boot application (a modular monolith, with boundaries checked by Spring Modulith) with a PostgreSQL database
(Neon, shared by the team).

- Whole-project setup, Docker and team workflow: [swiftdrop-deploy](https://github.com/SwiftDrop-Project/swiftdrop-deploy)
- Web app: [swiftdrop-frontend](https://github.com/SwiftDrop-Project/swiftdrop-frontend)
- API contract (endpoints, request and response shapes, error codes): [API.md](API.md)
- Notes for Claude Code and the coding rules: [CLAUDE.md](CLAUDE.md)

## Tech stack

| Area | Technology |
|---|---|
| Language and framework | Java 21, Spring Boot 4.1 (Spring MVC, Spring Security 7, Jackson 3) |
| Database | PostgreSQL 17 on Neon (one schema per module), Spring Data JPA (Hibernate), Flyway migrations |
| Security | JWT (HS256) in an httpOnly cookie, via Spring Security's OAuth2 resource server; Google sign-in via OAuth2 client (OpenID Connect); BCrypt passwords |
| Messaging and mail | RabbitMQ (Spring AMQP): exchange `parcel.events`, queues `notifications.q` and `webhooks.q` (declared, no feature uses them yet); Spring Mail |
| Architecture | Spring Modulith 2.1 (`ModuleArchitectureTest` checks the module boundaries) |
| API docs | springdoc-openapi (Swagger UI) |
| Build | Maven (wrapper included: `mvnw`, `mvnw.cmd`), Lombok |
| Tests | JUnit 5, Spring Boot Test, MockMvc, Testcontainers (PostgreSQL and RabbitMQ in Docker) |

## Modules

All code lives under `src/main/java/com/swiftdrop`. Every direct sub-package is a module (it follows the
architecture diagram). `SwiftdropBackendApplication` stays at the root.

| Module | Owns | Schema | Owner | Status |
|---|---|---|---|---|
| `shared` | Open to all: `ApiError`, `ErrorCode`, exceptions, `PageResponse`, `CurrentUser`, `PublicEndpoints`, `District`, `ParcelsPerMonth`, phone normaliser and validation | - | TBD | Built |
| `infrastructure` | Technical wiring: global error handler, correlation-ID filter, `GET /meta/districts`, OpenAPI, CORS, RabbitMQ declarations | - | TBD | Built |
| `identity` | Register, log in, log out, current user, Google sign-in, complete-profile, JWTs, the `SD_TOKEN` cookie, roles, seed accounts, the security filter chain | `identity` | Viduranga | Built |
| `merchants` | Merchant business profiles and the admin approval workflow (API keys later) | `merchants` | Viduranga | Built |
| `pricing` | Delivery rates and price quotes | `pricing` | TBD | Placeholder |
| `delivery` | Parcels, status changes, proof of delivery, receipts, outbox events | `delivery` | Pasindu | Contracts ready |
| `dispatch` | Courier assignments (pickup, transfer between branches, final delivery) and what happened to each parcel in them | `dispatch` | D.M.A.I. Disanayaka | Tables and domain model (`AssignmentApi` and dispatcher endpoints next) |
| `reporting` | Delivery and cash-on-delivery reports | - | TBD | Placeholder |
| `tracking` | Parcel tracking by tracking number, live updates (SSE) | - | TBD | Placeholder |
| `notifications` | Emails (and later SMS) to users | `notifications` | TBD | Placeholder |
| `webhooks` | Merchant webhooks | `webhooks` | TBD | Placeholder |
| `outbox` | Relays delivery's outbox events to RabbitMQ | - | TBD | Placeholder |
| `courier` | The courier's parcels: today's pickups and deliveries, status updates, delivery history (through `DeliveryApi`) | - | Kalpani | Built (answers 503 until delivery is implemented) |

Each module's `package-info.java` describes its purpose, owner, schema and the events it publishes and consumes.
Placeholder modules already have their public contract types (for example `DeliveryApi`, `ParcelStatusChanged`)
so other modules can code against them; the owner extends them in Phase 0.

### Inside a module

```
com.swiftdrop.<module>/
  package-info.java   @ApplicationModule and the module description
  <Module>Api.java    plus public records, enums and events: the only types other modules may use
  internal/
    controller/       REST controllers, @PreAuthorize on every endpoint
    dto/              request and response records
    application/      services that implement the module's Api; transactions start here
    domain/           entities, enums, domain rules
    persistence/      Spring Data repositories
    ...               special folders named for what they hold (security, messaging, strategy, storage, sse, ...)
```

Example: `identity/IdentityApi.java` (public), `identity/internal/application/UserService.java` (implements it),
`identity/internal/domain/User.java`, `identity/internal/persistence/UserRepository.java`.

### Module boundary rules

- A module uses **only another module's top-level types** (its Api, public records, events) and `shared`.
  Never anything under another module's `internal/`. `ModuleArchitectureTest` fails the build otherwise.
- **No cycles.** merchants depends on identity (it calls `IdentityApi` to approve merchants). identity must not
  depend on merchants, so for merchant sign-up and `/auth/me` it calls `identity.MerchantProfiles`, an interface
  that merchants implements.
- Never use another module's entity or repository. Pass records such as `UserSummary` instead.
- Controllers never return entities. They map to DTOs.
- All endpoints are under `/api/v1`. `WebConfig` adds this prefix to every controller.
- Every request body is validated with Bean Validation.
- Every error goes through `GlobalExceptionHandler` and uses the error shape in API.md. Throw a `shared.error`
  exception (`NotFoundException`, `ForbiddenException`, `ConflictException`, `BusinessRuleException`, or
  `ApiException` for 401) with an `ErrorCode`.
- Never put passwords, tokens, emails or phone numbers in error messages or logs.
- Every response has an `X-Correlation-Id` header, and every log line shows the same id.

## Run locally

You need Java 21 and Docker Desktop (running). The database is the team's shared Neon database (or, if you
prefer, a local PostgreSQL from swiftdrop-deploy). RabbitMQ and Mailpit run in Docker from
[swiftdrop-deploy](https://github.com/SwiftDrop-Project/swiftdrop-deploy); clone it next to this folder.

**1. Start the infrastructure** (from `swiftdrop-deploy`, after creating its `.env`, see its README):

```bash
docker compose up -d rabbitmq mailpit
# only if you want a local database instead of Neon:
docker compose up -d postgres adminer
```

**2. Create your `.env`** (in this folder).

Git Bash:

```bash
cp .env.example .env
notepad .env
```

PowerShell:

```powershell
Copy-Item .env.example .env
notepad .env
```

Set `DB_URL`, `DB_USER` and `DB_PASSWORD` to the Neon database (Neon console > Connect):
`DB_URL=jdbc:postgresql://<neon-host>/swiftdrop?sslmode=require`. Use the **direct** host, not the `-pooler` one,
because Flyway needs a normal session connection. For a local database use
`jdbc:postgresql://localhost:5432/swiftdrop` and the `POSTGRES_PASSWORD` from `swiftdrop-deploy/.env`.
Use the same `RABBITMQ_PASSWORD` and `JWT_SECRET` as in `swiftdrop-deploy/.env`. To generate a new `JWT_SECRET`:

- Git Bash: `openssl rand -base64 48`
- PowerShell: `$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`

**3. Start the backend.**

Git Bash:

```bash
./mvnw spring-boot:run
```

PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

The first run downloads dependencies and takes a few minutes. The backend is ready when the log shows
`Started SwiftdropBackendApplication`. On every start, Flyway applies new migrations and the seeders
create any missing admin and demo accounts (safe when several teammates start at the same time).

| What | URL |
|---|---|
| API base | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |

In Swagger UI, call `POST /api/v1/auth/login` first (for example with `customer@swiftdrop.lk` and your
`DEMO_PASSWORD`). The browser then keeps the login cookie and sends it with the other calls.

The `.env` file is read through `spring.config.import: optional:file:.env[.properties]` in
`application.yml`. In Docker the same variable names come from `swiftdrop-deploy/docker-compose.yml`.

## Configuration

Every setting is an environment variable. The full list with example values is in
[.env.example](.env.example). `application.yml` only reads `${VARIABLE}` placeholders; there are no
secrets in the code.

| Variable | Default | Required | What it does |
|---|---|---|---|
| `DB_URL` | none | Yes | JDBC URL. Neon: `jdbc:postgresql://<neon-host>/swiftdrop?sslmode=require` (direct host, not `-pooler`); local: `jdbc:postgresql://localhost:5432/swiftdrop` |
| `DB_USER` | none | Yes | Database user |
| `DB_PASSWORD` | none | Yes | Database password |
| `RABBITMQ_HOST` | `localhost` | No | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | No | RabbitMQ port |
| `RABBITMQ_USER` | none | Yes | RabbitMQ user |
| `RABBITMQ_PASSWORD` | none | Yes | RabbitMQ password |
| `MAIL_HOST` | `localhost` | No | SMTP host (Mailpit in development, `smtp.gmail.com` for real emails) |
| `MAIL_PORT` | `1025` | No | SMTP port (`587` for Gmail) |
| `MAIL_USERNAME` | empty | No | SMTP user (the Gmail address). Empty for Mailpit. |
| `MAIL_PASSWORD` | empty | No | SMTP password (a Gmail **App Password**, not the account password). Never commit it. |
| `MAIL_SMTP_AUTH` | `false` | No | `true` for Gmail |
| `MAIL_STARTTLS` | `false` | No | `true` for Gmail |
| `MAIL_FROM` | `no-reply@swiftdrop.lk` | No | Sender: `name@example.com` or `SwiftDrop <name@example.com>`. With Gmail it must be the Gmail address itself. An invalid value stops the backend at startup. |
| `MAIL_DEBUG` | `false` | No | `true` prints the SMTP conversation, **including the base64-encoded password**. Local troubleshooting only; never share the output. |
| `EMAIL_MX_CHECK` | `true` | No | Sign-up rejects email domains without MX records (DNS). `false` skips the lookup (offline). |
| `JWT_SECRET` | none | Yes | Signs login tokens. At least 32 characters, or the backend refuses to start. |
| `VERIFICATION_CODE_SECRET` | empty | No | HMAC key for stored email codes (at least 32 characters). Empty = derived from `JWT_SECRET`. |
| `JWT_EXPIRY_MINUTES` | `60` | No | Token lifetime without "Keep me logged in" |
| `JWT_REMEMBER_ME_DAYS` | `7` | No | Token and cookie lifetime with "Keep me logged in" and after Google sign-in |
| `FRONTEND_URL` | `http://localhost:3000` | No | The only origin allowed by CORS; also where Google sign-in redirects back to |
| `COOKIE_SECURE` | `false` | No | `true` adds `Secure` to the cookies. Only for HTTPS. |
| `ADMIN_EMAIL` | empty | No | Admin account created on start if missing. Empty = no admin is seeded. |
| `ADMIN_PASSWORD` | empty | No | Password for that admin |
| `SEED_DEMO_USERS` | `false` | No | `true` creates demo customer, merchant and courier accounts |
| `DEMO_PASSWORD` | empty | No | Password for the demo accounts |
| `GOOGLE_CLIENT_ID` | empty | No | Google OAuth client ID. Google sign-in is on only when both Google values are set. |
| `GOOGLE_CLIENT_SECRET` | empty | No | Google OAuth client secret. Never commit it. |
| `SWIFTDROP_FAKE_DELIVERY` | `false` | No | **Temporary**, local demos only: `true` gives the courier endpoints in-memory demo parcels for `courier@swiftdrop.lk` (needs `SEED_DEMO_USERS=true`). Nothing is saved. Removed once delivery is implemented. |

The connection pool is small on purpose (at most 3 connections, none kept while idle), because the
whole team shares one Neon database. These values are fixed in `application.yml`.

How to create the Google client: see "Google sign-in setup" in the
[swiftdrop-deploy README](https://github.com/SwiftDrop-Project/swiftdrop-deploy#google-sign-in-setup).

## Database migrations (Flyway)

- Each stateful module owns one schema: `identity`, `merchants`, `pricing`, `delivery`, `dispatch`, `webhooks`,
  `notifications`. reporting, tracking, outbox and courier own none. No foreign keys or joins across schemas;
  a module refers to another module's rows by UUID only. Entities use `@Table(schema = "<module>")`.
- Schema changes happen **only** through Flyway files in the module's own folder:
  `src/main/resources/db/migration/<module>/`. A module's first migration starts with
  `CREATE SCHEMA IF NOT EXISTS <module>;`.
- File name: `V<yyyyMMddHHmm>__<description>.sql`, using the current date and time. Example:
  `V202611051430__create_parcels.sql`. Never `V1`, `V2`: timestamps stop two teammates picking the same version.
- **Additive only.** Never drop or rename a column or table; add a new one instead.
- **Never edit a migration after it has run once** (it has run on the shared database). Add a new one.
- Hibernate never changes the schema. `ddl-auto: validate` stops the backend if entities and tables don't match.
- Because 9 developers on different branches share one database, Flyway runs with `out-of-order: true`
  (a migration merged later with an older timestamp still runs), `ignore-migration-patterns: "*:missing,*:future"`
  (migrations that exist only on someone else's branch don't fail your start) and `validate-on-migrate: true`.
  The history table is `public.flyway_schema_history`.

Current migrations (the baseline for the new Neon database; the old `V2026110114..` files were replaced):

| File | What it does |
|---|---|
| `identity/V202611030900__identity_baseline.sql` | `identity` schema, `identity.users` (incl. Google sign-in columns) |
| `merchants/V202611030905__merchants_baseline.sql` | `merchants` schema, `merchants.merchant_profiles` (no foreign keys) |
| `identity/V202611031000__email_verification.sql` | Status `PENDING_EMAIL_VERIFICATION` (column widened to 30), `users.token_version`, `identity.verification_codes` |
| `notifications/V202611031005__notifications_baseline.sql` | `notifications` schema, `notifications.email_log` |
| `delivery/V202611052245__create_parcels.sql` | `delivery` schema, `delivery.parcels` (no foreign keys) |
| `delivery/V202611060017__create_parcel_status_history.sql` | `delivery.parcel_status_history` (one row per status change) |
| `delivery/V202611060024__create_outbox_events.sql` | `delivery.outbox_events` (the transactional outbox) |
| `dispatch/V202611101200__create_dispatch_schema.sql` | `dispatch` schema, `dispatch.courier_assignments` and `dispatch.assignment_items` (a parcel is open in at most one assignment; no foreign keys) |

Flyway orders migrations by version across **all** module folders, so a new file must also be newer than the
migrations it depends on (e.g. a change to `identity.users` must sort after the identity baseline).

## Authentication design

The full contract is in [API.md](API.md) ("Cookie and token" and "Google sign-in").

**Token and cookie**

- After login the backend sets the cookie `SD_TOKEN`: `HttpOnly; Path=/; SameSite=Lax`, plus `Secure`
  when `COOKIE_SECURE=true`. JavaScript can't read it, so it can't be stolen by a script.
- The cookie holds a JWT signed with HS256 using `JWT_SECRET`. Claims: `sub` (user ID), `role`, `status`,
  `ver` (token version), `iat`, `exp`. No personal data.
- `CookieBearerTokenResolver` reads the token from the cookie. Spring Security's resource server checks
  the signature and expiry. Public endpoints ignore the cookie, so an old cookie never blocks login.
- `SessionValidityFilter` then checks the database (cached up to 30 s by `SessionStateCache`): the user must
  exist, be `ACTIVE` or `PROFILE_INCOMPLETE`, and the token's `ver` must equal `users.token_version`. Otherwise
  `401` and the cookie is cleared. A password change or reset increments `token_version`, which ends every
  other session; disabling an account ends its sessions too.
- "Keep me logged in" gives a 7-day cookie and token. Without it, the cookie ends when the browser closes
  and the token lasts 60 minutes.
- Passwords are hashed with BCrypt. Wrong password and unknown user give the same `401` response.

**Roles and access**

- Roles: `CUSTOMER`, `MERCHANT`, `COURIER`, `ADMIN`. The `role` claim becomes the authority
  `ROLE_<role>`.
- Everything is denied by default. Each module lists its public endpoints in a `PublicEndpoints` bean
  (`IdentityPublicEndpoints`: register, login, logout, Google sign-in URLs; `InfrastructurePublicEndpoints`:
  districts, Swagger, health). The filter chain in `identity/internal/security/SecurityConfig` collects them all.
- Controllers use `@PreAuthorize`. `/api/v1/admin/**` needs `ADMIN`. A missing or invalid cookie gives `401 UNAUTHENTICATED`; a wrong
  role gives `403 FORBIDDEN`.
- Account status is checked at login: `PENDING_EMAIL_VERIFICATION`, `PENDING_APPROVAL`, `REJECTED` and
  `DISABLED` can't log in.
- `AuthRateLimitFilter` limits register, login, verify, resend, forgot, reset and change password per client IP
  (`swiftdrop.security.rate-limit` in `application.yml`): over the limit → `429 TOO_MANY_REQUESTS` with
  `Retry-After`. Counters live in memory, per backend instance.

**Email verification and passwords**

- Password sign-ups start as `PENDING_EMAIL_VERIFICATION`, get no cookie, and receive a 6-digit code by email.
  `POST /auth/email-verification/verify` makes a customer `ACTIVE` (with cookie) and a merchant
  `PENDING_APPROVAL`. Google accounts and seeded accounts are verified already.
- Forgot/reset password sends a 6-digit code to the account's email; reset ends every session. Change password
  (logged in) ends the other sessions.
- Codes: `SecureRandom`, stored only as HMAC-SHA256 (`CodeHasher`, key `VERIFICATION_CODE_SECRET` or derived from
  `JWT_SECRET`), valid 10 minutes, dead after 5 wrong tries, replaced by a newer code, at most one per 60 s and
  5 per hour per account. Resend and forgot always answer `202`, so nobody can find out which emails are registered.
- Sign-up also rejects disposable email domains (`resources/email/disposable-domains.txt`) and domains without
  MX records (DNS, 3 s timeout, cached; if DNS fails the address is allowed).
- Emails go through `NotificationsApi.sendAccountEmail` (notifications module): rendered from
  `resources/templates/email/`, sent over SMTP in the background after the transaction commits, and recorded in
  `notifications.email_log` with a masked address. Codes and addresses never appear in logs.
- Locally, read the emails in Mailpit: <http://localhost:8025>.

**Why CSRF protection is off**

- The cookie is `SameSite=Lax`, so browsers don't send it on cross-site `POST`, `PUT` or `DELETE`.
- CORS allows only `FRONTEND_URL`, with credentials.
- Write endpoints accept only `application/json`, which a plain HTML form can't send without a CORS
  preflight.
- The Google flow has its own protection: the OAuth2 `state` parameter.

**Google sign-in**

1. `GET /oauth2/authorization/google` (outside `/api/v1`) sends the browser to Google. The pending
   request (state, nonce) is kept in the signed, 5-minute cookie `SD_OAUTH2_REQUEST`, not in a server
   session. The backend stays stateless.
2. Google redirects back to `GET /login/oauth2/code/google`. `GoogleSignInHandler` checks that Google
   verified the email, then finds the user by Google ID, then by email (and links the account).
3. A new user is created as a `CUSTOMER` with status `PROFILE_INCOMPLETE`, with no password and no phone.
4. The handler sets `SD_TOKEN` (remember me) and redirects to `FRONTEND_URL/auth/callback`. Pending,
   rejected and disabled accounts get no cookie and go to `FRONTEND_URL/login?error=<code>`. Any other
   failure goes to `FRONTEND_URL/login?error=google_failed`.
5. A token with `status: PROFILE_INCOMPLETE` works only for `GET /auth/me` and
   `POST /auth/complete-profile`. `ProfileIncompleteFilter` answers `403 PROFILE_INCOMPLETE` everywhere
   else.
6. `POST /auth/complete-profile` adds the mobile number. A customer becomes `ACTIVE` and gets a fresh
   cookie. A merchant also adds business details, becomes `PENDING_APPROVAL` and the cookie is cleared.

Without `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` the Google URLs return `404` and everything else
works normally. Google tokens are never stored or logged.

## Testing

```bash
./mvnw test
```

PowerShell: `.\mvnw.cmd test`

- **Docker Desktop must be running.** Testcontainers starts `postgres:17` and `rabbitmq:4-management`
  containers for the tests and removes them afterwards.
- Tests **never** touch the database in your `.env` (the shared Neon database): `IntegrationTest` uses
  Testcontainers and points the normal datasource settings at a host that cannot exist, and
  `DatabaseIsolationTest` checks it. Google is never called; the tests use a mocked Google user.
- No real email or DNS either: `CapturingMailSender` keeps the emails in memory (tests read the codes from it)
  and `FakeMxLookup` answers MX lookups by domain name.
- `ModuleArchitectureTest` checks the module boundaries (no Docker needed) and writes module diagrams to
  `target/spring-modulith-docs`.
- Test packages mirror the modules: `src/test/java/com/swiftdrop/<module>/`.
- Every endpoint has tests, including authorization tests (no login → 401, wrong role → 403).
- Run one test class: `./mvnw test -Dtest=GoogleSignInTest`
- Reports: `target/surefire-reports/` (a `.txt` summary and a `TEST-*.xml` file per test class).

| Test class | Covers |
|---|---|
| `ModuleArchitectureTest` | Module list, boundaries and cycles (`ApplicationModules.verify()`), module docs |
| `DatabaseIsolationTest` | Tests use Testcontainers, never `.env`; one schema per module, no cross-schema foreign keys |
| `dispatch/...` | Assignment rules (branches per type, outcomes, completing), the dispatch tables' constraints, one open assignment per parcel, courier queries |
| `identity/AuthRegisterTest` | Register customer and merchant, validation, duplicates |
| `identity/AuthLoginTest` | Login with email or mobile, remember me, account statuses (incl. unverified email), logout |
| `identity/AuthMeTest` | Current user |
| `identity/GoogleSignInTest` | Google redirects, account linking (incl. unverified accounts), statuses, complete-profile, `PROFILE_INCOMPLETE` |
| `identity/EmailVerificationTest` | Code email, verify (customer/merchant), wrong/expired codes, resend cooldown and hourly cap |
| `identity/PasswordRecoveryTest` | Forgot by email/mobile, reset, unknown identifiers, Google-only accounts |
| `identity/PasswordChangeTest` | Change password, other sessions end, wrong current password |
| `identity/SessionValidityTest` | Disabled users and old token versions are logged out |
| `identity/RateLimitTest` | 429 + `Retry-After` per client IP |
| `identity/RegisterEmailDomainTest` | MX and disposable-domain checks on sign-up (fake DNS) |
| `identity/NoSecretsInLogsTest` | Codes, emails and passwords never reach the logs |
| `identity/internal/...` | The Google request cookie; seeders started in parallel; code hashing; email domain checks |
| `notifications/AccountEmailTest` | Email content, `email_log` (SENT/FAILED, masked address), nothing sent on rollback |
| `merchants/AdminMerchantTest` | Admin list, approve, reject, and access rules |
| `merchants/internal/...` | Demo merchant seeding (approved, can log in, parallel-safe) |
| `infrastructure/...` | Districts, correlation ID, RabbitMQ exchange and queues |
| `courier/Courier*Test` | Courier endpoints with `DeliveryApi` mocked: access (401/403), ownership (404), today's list and order, status changes and their rules (400/409), history paging; 503 without a delivery implementation |
| `courier/FakeDeliveryWiringTest`, `courier/internal/...` | Temporary demo fake: starts with the flag on, seeded parcels, its rules; "today" in Sri Lankan time |
| `shared/validation/...` | Phone number rules |
| `SwiftdropBackendApplicationTests` | App starts, health, Swagger, Google off without credentials |

## Docker

The `Dockerfile` builds the jar in a JDK image, then runs it in a small JRE image as a non-root user.
Tests are not run during the image build; run `./mvnw test` before you push.

Build and run it through Docker Compose (from `swiftdrop-deploy`):

```bash
docker compose up -d --build backend
```

Or build only the image (from this folder):

```bash
docker build -t swiftdrop-backend .
```

`.env` is in `.dockerignore`, so your secrets never end up in the image.

## Checklist: adding a feature or module

1. Create a branch from `dev`: `git switch dev`, `git pull`, `git switch -c feature/<name>`.
2. Agree on the endpoints and write them in [API.md](API.md) first: paths, bodies, responses, error codes.
3. Put the code in your module: public contract types at the module root, everything else under `internal/`
   (`controller`, `dto`, `application`, `domain`, `persistence`, ...). Replace placeholder `package-info.java`
   files once a folder has classes, and keep the module's `package-info.java` description up to date.
4. Use other modules only through their top-level types (`IdentityApi`, `MerchantApi`, records, events).
5. Add a migration in `db/migration/<module>/` with a timestamp version. Additive only; never edit one that ran.
6. Validate request bodies with Bean Validation. Use the same messages as API.md.
7. Throw a `shared.error` exception with an `ErrorCode`. Add new codes to `ErrorCode` and to the table in API.md.
8. Check security: new endpoints need a login by default. Open a public endpoint only if API.md says it is public,
   by adding it to your module's `PublicEndpoints` bean. Put `@PreAuthorize` on every controller endpoint.
9. Add any new setting as a `${VARIABLE}` in `application.yml`, to `.env.example`, and to
   `swiftdrop-deploy/docker-compose.yml` and its `.env.example`.
10. Write tests that extend `IntegrationTest`, including 401 and 403 cases, in `src/test/java/com/swiftdrop/<module>/`.
11. Run `./mvnw test` (includes `ModuleArchitectureTest`). Check that Swagger UI still loads.
12. Commit (`feat(<module>): ...`), push and open a PR into `dev`. Tell the frontend team about API changes.
