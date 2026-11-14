# SwiftDrop Backend - project notes for Claude Code

Spring Boot 4 · Java 21 · Maven · PostgreSQL (Neon) · Flyway · RabbitMQ · Spring Modulith.
Part of SwiftDrop, a parcel delivery & courier management system (university group project, 9 developers).
Related repos, cloned side by side: `../swiftdrop-frontend` (Next.js), `../swiftdrop-deploy` (docker-compose, docs).
Check pom.xml for exact versions and use APIs that match them (Spring Boot 4.1 / Spring Security 7 / Jackson 3 /
Spring Modulith 2.1).

## Architecture: modular monolith (Spring Modulith)
Every direct sub-package of `com.swiftdrop` is a module. `SwiftdropBackendApplication` stays at the root.

| Module | Public (top-level) types | Schema | Owner |
|---|---|---|---|
| `shared` (OPEN) | `error/` ApiError, ErrorCode, ApiException + NotFound/Forbidden/Conflict/BusinessRule/FieldValidation exceptions; `web/` CurrentUser, PageResponse, PublicEndpoints, ApiPaths; `reference/` District, ParcelsPerMonth; `validation/` PhoneNumberNormalizer, @SriLankanMobile | - | TBD |
| `infrastructure` | none (only `internal/`: web, config, messaging) | - | TBD |
| `identity` | IdentityApi, UserSummary, CourierSummary, UserRoleCounts, Role, UserStatus, MerchantProfiles (implemented by merchants) | `identity` | Viduranga |
| `merchants` | MerchantApi, MerchantSummary, ApprovalStatus | `merchants` | Viduranga |
| `pricing` | PricingApi, QuoteRequest, PriceQuote, Vehicle | `pricing` (rate_cards, vehicle_rates, district_locations) | TBD (fee estimate: Pasindu) |
| `delivery` | DeliveryApi, DeliveryOutboxApi, OutboxMessage, DeliverySnapshot, DeliveryContact, CreateDeliveryCommand, ChangeStatusCommand, ParcelStatus (incl. `AT_BRANCH`; branch = `District`), ParcelStatusEntry, ParcelStatusChanged, FailureReason, ProofPhoto, ProofPhotoRef, DeliveryStats | `delivery` (incl. `outbox_events`) | Pasindu |
| `dispatch` | AssignmentApi (courier-facing), AssignmentView, AssignmentItemView, RecordOutcomeCommand, AssignmentType, AssignmentStatus, ItemOutcome, TaskProblem | `dispatch` (`courier_assignments`, `assignment_items`, `pickup_queue`) | D.M.A.I. Disanayaka (WP8) |
| `reporting` | - | none | TBD |
| `tracking` | - (endpoints, WebSocket/STOMP and a RabbitMQ listener only) | `tracking` (`parcel_index`) | D.M.A.I. Disanayaka (WP8) |
| `notifications` | NotificationsApi, AccountEmail | `notifications` | TBD |
| `webhooks` | - | `webhooks` | TBD |
| `outbox` | - (reads the outbox only through `DeliveryOutboxApi`) | none | TBD |
| `courier` | - (endpoints only; uses `DeliveryApi` and dispatch's `AssignmentApi`) | none | Kalpani |

```
com.swiftdrop.<module>/
  package-info.java        @ApplicationModule + Javadoc: purpose, owner, schema, events published/consumed
  <Module>Api.java, records, enums, events   <- the ONLY types other modules may use
  internal/
    controller/   REST controllers, @PreAuthorize on every endpoint
    dto/          request/response records
    application/  services implementing the module's Api; transactions start here
    domain/       entities, enums, domain rules
    persistence/  Spring Data repositories
    (special folders, named for what they hold: security, messaging, strategy, storage, receipt, sse, provider, client)
```
Empty folders hold a `package-info.java` (no `.gitkeep`).

### Module rules (checked by `ModuleArchitectureTest` -> `ApplicationModules.verify()`)
- A module may use ONLY another module's top-level types and `shared`. Never anything under another module's `internal/`.
- No cycles between modules. If two modules need each other, the lower-level one owns an interface the other implements
  (example: `identity.MerchantProfiles` is implemented by merchants, so identity never depends on merchants), or use an event.
- Never use another module's entity or repository. Pass records (`UserSummary`, `MerchantSummary`, ...).
- Controllers never return entities. Map to DTOs in `internal/dto`.
- A module's public URLs: implement `shared.web.PublicEndpoints` as a bean in the module. Never edit identity's SecurityConfig for that.
- Unbuilt modules have placeholder contracts ("Contract owned by <module> - extend in Phase 0"); the owner extends them.
- Current dependencies: identity -> notifications, shared; merchants -> identity, shared; delivery -> pricing, shared;
  dispatch -> delivery, identity, shared; tracking -> delivery, shared; courier -> delivery, dispatch, identity, shared;
  pricing -> shared; infrastructure -> shared. Nothing depends on courier.
- dispatch: `AssignmentApi` is implemented; dispatcher endpoints are under `/dispatch` (DISPATCHER and ADMIN, API.md
  "Dispatch"). Delivery has no "waiting for pickup" query, so dispatch keeps `dispatch.pickup_queue` from
  `ParcelStatusChanged` (RabbitMQ queue `dispatch.q`); it fills only while the outbox relay runs
  (`SWIFTDROP_OUTBOX_RELAY_ENABLED=true`).
  Assignment status and item outcomes are separate from the parcel status: a courier's task can be completed while
  its parcels are still on their way.
- tracking: public lookup `GET /track/{trackingNumber}` and live updates over WebSocket/STOMP (API.md "Tracking"):
  `/api/v1/ws/public` (public, `/topic/parcels/{trackingNumber}`) and `/api/v1/ws` (cookie checked; DISPATCHER/ADMIN
  may follow `/topic/live`; CUSTOMER/MERCHANT get their own parcels on `/user/queue/parcels`, loaded first with
  `GET /tracking/mine`). In-memory simple broker; each instance has its own auto-delete RabbitMQ queue.
  The admin's all-parcels list (`/admin/tracking/parcels`) reads tracking's own `tracking.parcel_index`, filled from
  `ParcelStatusChanged` and from lookups (delivery has no admin search).
- courier shows the parcels of the courier's dispatch tasks (`AssignmentApi`) and, on each status change, calls
  `DeliveryApi.changeStatus` and then `AssignmentApi.recordOutcome` in one transaction (completing the task when its
  last item closes). It never reads delivery's deprecated `assignedCourierId`. `courier/internal/fake`
  (`SWIFTDROP_FAKE_DELIVERY`) no longer works with this and its two wiring tests are `@Disabled`: delete them.
- delivery must never depend on identity: identity -> notifications, and notifications will consume
  `ParcelStatusChanged` (notifications -> delivery), so that would be a cycle. User ids are plain UUIDs and roles are
  plain names (e.g. `COURIER`). Multi-branch: several couriers handle one parcel, so callers authorize
  `changeStatus` (delivery checks only the role), and the single-courier members of `DeliveryApi` (`assignCourier`,
  `findUnassigned`, `countActiveByCourier`, `findForCourier`, `findFinishedForCourier`) are `@Deprecated`.
- Emails are sent only through `NotificationsApi.sendAccountEmail(AccountEmail)` (async, after the caller's
  transaction commits; templates in `resources/templates/email/`; outcome in `notifications.email_log`).
  notifications must never depend on identity (that would be a cycle): pass the recipient and name in `AccountEmail`.

## API rules
- The contract is `API.md`. Implement it exactly; update it in the same PR when it changes.
- All endpoints under `/api/v1` (`ApiPaths.PREFIX`, added automatically). JSON only.
- Validate every request with Bean Validation.
- All errors go through `infrastructure/internal/web/GlobalExceptionHandler` using `ApiError` (the shape in API.md).
  Throw a `shared.error` exception with an `ErrorCode`.
- Never put stack traces, passwords, tokens, emails or phone numbers in error messages or logs.
- Keep Swagger (springdoc-openapi) working at `/swagger-ui.html`.
- Every response carries `X-Correlation-Id` (also in every log line), set by `CorrelationIdFilter`.

## Configuration
- One `application.yml`, driven by environment variables (no profile files). Full list in `.env.example`.
- Local runs load `./.env` through `spring.config.import: optional:file:.env[.properties]`.
- In Docker the same variable names come from `../swiftdrop-deploy/docker-compose.yml`.
- Database: Neon. `DB_URL=jdbc:postgresql://<neon-host>/swiftdrop?sslmode=require` using the direct host (not `-pooler`).
  Hikari pool: max 3, min idle 0, idle timeout 60 s, max lifetime 5 min, connection timeout 30 s.

## Database
- One schema per stateful module: identity, merchants, pricing, delivery, dispatch, tracking, webhooks, notifications.
  reporting, outbox and courier own no schema.
- Entities use `@Table(schema = "<module>")`. No foreign keys or joins across schemas; refer to other modules' rows by UUID only.
- Schema changes ONLY through Flyway, in the module's folder: `src/main/resources/db/migration/<module>/`.
- File name: `V<yyyyMMddHHmm>__<description>.sql` (current date and time, e.g. `V202611051430__add_parcels.sql`).
  Never V1, V2... A module's first migration starts with `CREATE SCHEMA IF NOT EXISTS <module>;`.
  Flyway orders versions across all module folders: a migration must sort after the ones it changes.
- No foreign keys at all, not even inside one schema (`DatabaseIsolationTest` checks it).
- Additive only: never drop or rename a column or table (add a new one, migrate data, stop using the old one).
- Never edit a migration after it has run once anywhere (the shared database has it). Add a new one.
- 9 developers share one database, so Flyway runs with `out-of-order: true`, `ignore-migration-patterns: "*:missing,*:future"`
  and `validate-on-migrate: true`; the history table is `public.flyway_schema_history`.
- `spring.jpa.hibernate.ddl-auto: validate`.
- Seeders must be idempotent and safe when several backends start at once (catch the duplicate, log, continue).

## Security
- JWT (HS256) using Spring Security's OAuth2 resource server with Nimbus encoder/decoder (no extra JWT library).
- Token is stored in the httpOnly cookie `SD_TOKEN` (see API.md), read by a custom BearerTokenResolver.
- Roles: CUSTOMER, MERCHANT, COURIER, ADMIN. Deny by default; open only what API.md marks public (via `PublicEndpoints`).
- `@PreAuthorize` on every controller endpoint; `/api/v1/admin/**` additionally requires ADMIN in the filter chain.
- Passwords hashed with BCrypt.
- JWT claim `ver` = `users.token_version`. `SessionValidityFilter` checks status + version on every authenticated
  request (`SessionStateCache`, 30 s); call `SessionStateCache.evict(userId)` whenever a user's status, password or
  token version changes. Incrementing `token_version` ends all sessions.
- Password sign-ups start as `PENDING_EMAIL_VERIFICATION` and confirm a 6-digit email code. One-time codes live in
  `identity.verification_codes`, only as HMAC-SHA256 (`CodeHasher`); never log codes.
- Per-IP rate limits on the auth endpoints: `AuthRateLimitFilter` (429 + Retry-After). Tests reset it per test.
- Tests never send real email or do DNS: `CapturingMailSender` and `FakeMxLookup` (see `IntegrationTest`).

## Testing
- JUnit 5 + Spring Boot Test + Testcontainers (PostgreSQL, RabbitMQ). Docker Desktop must be running.
- Tests NEVER touch the `.env` / Neon database: `IntegrationTest` uses Testcontainers (`DatabaseIsolationTest` proves it).
- `ModuleArchitectureTest` verifies module boundaries and writes docs to `target/spring-modulith-docs`.
- Test packages mirror the modules (`src/test/java/com/swiftdrop/<module>/`).
- Every endpoint gets tests, including authorization tests (no login -> 401, wrong role -> 403).
- Raw SQL in tests uses schema-qualified tables (`identity.users`).
- Run `./mvnw test` and keep it green before finishing a task.

## Commands
- Start local infrastructure (optional, instead of Neon): `cd ../swiftdrop-deploy && docker compose up -d postgres rabbitmq mailpit adminer`
- Run: `./mvnw spring-boot:run`
- Test: `./mvnw test`
- Build: `./mvnw clean package`

## Git
- Work on `feature/*` branches; PRs into `dev`. Commit style: `feat(identity): add login endpoint`.
