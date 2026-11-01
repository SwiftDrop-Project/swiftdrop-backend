# SwiftDrop Backend

The REST API for SwiftDrop, a parcel delivery and courier management system for Sri Lanka.
It is one Spring Boot application (a modular monolith) with a PostgreSQL database.

- Whole-project setup, Docker and team workflow: [swiftdrop-deploy](https://github.com/SwiftDrop-Project/swiftdrop-deploy)
- Web app: [swiftdrop-frontend](https://github.com/SwiftDrop-Project/swiftdrop-frontend)
- API contract (endpoints, request and response shapes, error codes): [API.md](API.md)
- Notes for Claude Code and the coding rules: [CLAUDE.md](CLAUDE.md)

## Tech stack

| Area | Technology |
|---|---|
| Language and framework | Java 21, Spring Boot 4.1 (Spring MVC, Spring Security 7, Jackson 3) |
| Database | PostgreSQL 17, Spring Data JPA (Hibernate), Flyway migrations |
| Security | JWT (HS256) in an httpOnly cookie, via Spring Security's OAuth2 resource server; Google sign-in via OAuth2 client (OpenID Connect); BCrypt passwords |
| Messaging and mail | RabbitMQ (Spring AMQP) and Spring Mail, connected but not used by any feature yet |
| API docs | springdoc-openapi (Swagger UI) |
| Build | Maven (wrapper included: `mvnw`, `mvnw.cmd`), Lombok |
| Tests | JUnit 5, Spring Boot Test, MockMvc, Testcontainers (PostgreSQL and RabbitMQ in Docker) |

## Modules

All code lives under `src/main/java/com/swiftdrop`. Each business area is one package (a module).

| Module | Owns | Status |
|---|---|---|
| `auth` | Register, log in, log out, current user, Google sign-in, completing a Google sign-up, issuing JWTs, seed accounts | Built |
| `users` | User accounts: name, email, mobile, password hash, role, status, linked Google ID | Built |
| `merchants` | Merchant business profiles and the admin approval workflow | Built |
| `parcels` | Parcels and orders and their statuses | Planned |
| `pricing` | Delivery rates by district and weight | Planned |
| `dispatch` | Assigning parcels to couriers by zone | Planned |
| `courier` | The courier's pickups and deliveries | Planned |
| `tracking` | Parcel tracking by tracking number | Planned |
| `notifications` | Emails and other messages to users | Planned |
| `webhooks` | Merchant API keys and webhooks | Planned |
| `reports` | Delivery and cash-on-delivery reports | Planned |
| `shared` | Cross-cutting code: `config`, `security`, `exception`, `event`, `util`, `meta` (districts) | Built |

### The four layers

Every module has the same four packages:

| Layer | Contains | Example |
|---|---|---|
| `api` | REST controllers and request/response DTOs (Java records) | `auth/api/AuthController.java` |
| `application` | Services (use cases). Transactions start here. | `auth/application/AuthService.java` |
| `domain` | Entities, enums, domain rules | `users/domain/User.java` |
| `infrastructure` | JPA repositories, messaging, external clients | `users/infrastructure/UserRepository.java` |

### Module boundary rules

- A module calls another module **only through its `application` service** (or by listening to its
  events). Example: `auth` uses `UserService` and `MerchantService`, never `UserRepository`.
- Never use another module's entity or repository directly. Pass records such as `UserView` instead.
- Controllers never return entities. They map to DTOs.
- All endpoints are under `/api/v1`. `WebConfig` adds this prefix to every controller.
- Every request body is validated with Bean Validation.
- Every error goes through `shared/exception/GlobalExceptionHandler` and uses the error shape in API.md.
  Throw `ApiException` with an `ErrorCode`.
- Never put passwords, tokens, emails or phone numbers in error messages or logs.

## Run locally

You need Java 21 and Docker Desktop (running). The database, RabbitMQ and Mailpit run in Docker from
[swiftdrop-deploy](https://github.com/SwiftDrop-Project/swiftdrop-deploy); clone it next to this folder.

**1. Start the infrastructure** (from `swiftdrop-deploy`, after creating its `.env`, see its README):

```bash
docker compose up -d postgres rabbitmq mailpit adminer
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

Use the same `DB_PASSWORD` as `POSTGRES_PASSWORD`, the same `RABBITMQ_PASSWORD` and the same
`JWT_SECRET` as in `swiftdrop-deploy/.env`. To generate a new `JWT_SECRET`:

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
`Started SwiftdropBackendApplication`. On every start, Flyway applies new migrations and the seeder
creates any missing admin and demo accounts.

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
| `DB_URL` | none | Yes | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/swiftdrop` |
| `DB_USER` | none | Yes | Database user |
| `DB_PASSWORD` | none | Yes | Database password |
| `RABBITMQ_HOST` | `localhost` | No | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | No | RabbitMQ port |
| `RABBITMQ_USER` | none | Yes | RabbitMQ user |
| `RABBITMQ_PASSWORD` | none | Yes | RabbitMQ password |
| `MAIL_HOST` | `localhost` | No | SMTP host (Mailpit in development) |
| `MAIL_PORT` | `1025` | No | SMTP port |
| `JWT_SECRET` | none | Yes | Signs login tokens. At least 32 characters, or the backend refuses to start. |
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

How to create the Google client: see "Google sign-in setup" in the
[swiftdrop-deploy README](https://github.com/SwiftDrop-Project/swiftdrop-deploy#google-sign-in-setup).

## Database migrations (Flyway)

- Schema changes happen **only** through Flyway files in `src/main/resources/db/migration`.
- Hibernate never changes the schema. `ddl-auto: validate` stops the backend if entities and tables
  don't match.
- File name: `V<yyyyMMddHHmm>__<description>.sql`, using the current date and time. Example:
  `V202611011400__create_users.sql`. Timestamps stop two teammates from picking the same version number.
- **Never edit a migration that is already merged into `dev`.** Teammates' databases have already run
  it, and Flyway would stop with a checksum error. Add a new migration instead.

Current migrations:

| File | What it does |
|---|---|
| `V202611011400__create_users.sql` | `users` table |
| `V202611011410__create_merchant_profiles.sql` | `merchant_profiles` table |
| `V202611011500__google_sign_in.sql` | Google sign-in: `google_sub` column, nullable password and phone, status `PROFILE_INCOMPLETE` |

## Authentication design

The full contract is in [API.md](API.md) ("Cookie and token" and "Google sign-in").

**Token and cookie**

- After login the backend sets the cookie `SD_TOKEN`: `HttpOnly; Path=/; SameSite=Lax`, plus `Secure`
  when `COOKIE_SECURE=true`. JavaScript can't read it, so it can't be stolen by a script.
- The cookie holds a JWT signed with HS256 using `JWT_SECRET`. Claims: `sub` (user ID), `role`, `status`,
  `iat`, `exp`. No personal data.
- `CookieBearerTokenResolver` reads the token from the cookie. Spring Security's resource server checks
  the signature and expiry. Public endpoints ignore the cookie, so an old cookie never blocks login.
- "Keep me logged in" gives a 7-day cookie and token. Without it, the cookie ends when the browser closes
  and the token lasts 60 minutes.
- Passwords are hashed with BCrypt. Wrong password and unknown user give the same `401` response.

**Roles and access**

- Roles: `CUSTOMER`, `MERCHANT`, `COURIER`, `ADMIN`. The `role` claim becomes the authority
  `ROLE_<role>`.
- Everything is denied by default. Public endpoints are listed in `shared/security/PublicEndpoints.java`:
  register, login, logout, districts, Swagger, health, and the Google sign-in URLs.
- `/api/v1/admin/**` needs `ADMIN`. A missing or invalid cookie gives `401 UNAUTHENTICATED`; a wrong
  role gives `403 FORBIDDEN`.
- Account status is checked at login: `PENDING_APPROVAL`, `REJECTED` and `DISABLED` can't log in.

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
- Tests don't use your `.env`: `IntegrationTest` sets its own values, so results are the same on every
  laptop. Google is never called; the tests use a mocked Google user.
- Every endpoint has tests, including authorization tests (no login → 401, wrong role → 403).
- Run one test class: `./mvnw test -Dtest=GoogleSignInTest`
- Reports: `target/surefire-reports/` (a `.txt` summary and a `TEST-*.xml` file per test class).

| Test class | Covers |
|---|---|
| `auth/AuthRegisterTest` | Register customer and merchant, validation, duplicates |
| `auth/AuthLoginTest` | Login with email or mobile, remember me, account statuses, logout |
| `auth/AuthMeTest` | Current user |
| `auth/GoogleSignInTest` | Google redirects, account linking, statuses, complete-profile, `PROFILE_INCOMPLETE` |
| `merchants/AdminMerchantTest` | Admin list, approve, reject, and access rules |
| `shared/...` | Districts, phone number rules, the Google request cookie |
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
3. Put the code in the module's package, using the four layers (`api`, `application`, `domain`,
   `infrastructure`). For a new module, create all four packages.
4. Call other modules only through their `application` services.
5. Add a new Flyway migration for any table change. Never edit an existing one.
6. Validate request bodies with Bean Validation. Use the same messages as API.md.
7. Throw `ApiException` with an `ErrorCode`. Add new codes to `ErrorCode` and to the table in API.md.
8. Check security: new endpoints need a login by default. Open a public endpoint only if API.md says it
   is public (add it to `PublicEndpoints`). Restrict by role in `SecurityConfig`.
9. Add any new setting as a `${VARIABLE}` in `application.yml`, to `.env.example`, and to
   `swiftdrop-deploy/docker-compose.yml` and its `.env.example`.
10. Write tests that extend `IntegrationTest`, including 401 and 403 cases.
11. Run `./mvnw test`. Check that Swagger UI still loads.
12. Commit (`feat(<module>): ...`), push and open a PR into `dev`. Tell the frontend team about API changes.
