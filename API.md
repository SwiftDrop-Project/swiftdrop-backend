# SwiftDrop API - Authentication (v1)

This file is the contract between `swiftdrop-backend` and `swiftdrop-frontend`.
Change it in the same PR as the code, and tell the frontend team.

- Base URL (local): `http://localhost:8080/api/v1`
- Format: JSON (`Content-Type: application/json`)
- Auth: httpOnly cookie `SD_TOKEN`, sent automatically by the browser.
  The frontend always calls the API with `credentials: "include"`.
- Live docs: Swagger UI at `http://localhost:8080/swagger-ui.html`

---

## Shared types

### User (response)
```json
{
  "id": "6f1c2a0e-1b2c-4d5e-9f00-1a2b3c4d5e6f",
  "fullName": "Ayesha Fernando",
  "email": "ayesha@example.com",
  "phone": "+94771234567",
  "role": "CUSTOMER",
  "status": "ACTIVE",
  "merchant": null
}
```
- `role`: `CUSTOMER` | `MERCHANT` | `COURIER` | `ADMIN`
- `status`: `ACTIVE` | `PENDING_APPROVAL` | `REJECTED` | `DISABLED` | `PROFILE_INCOMPLETE`
  - `PROFILE_INCOMPLETE`: signed up with Google and has not called `POST /auth/complete-profile` yet
    (see [Google sign-in](#google-sign-in)). The frontend sends these users to the complete-profile page.
- `phone`: `null` only while `status` is `PROFILE_INCOMPLETE`.
- `merchant`: only for role `MERCHANT`, otherwise `null`:
  ```json
  { "businessName": "Lanka Crafts", "pickupDistrict": "COLOMBO", "city": "Nugegoda", "approvalStatus": "PENDING" }
  ```
  `approvalStatus`: `PENDING` | `APPROVED` | `REJECTED`

### Error (every non-2xx response)
```json
{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Some fields need attention.",
  "fieldErrors": { "email": "Enter a full email address, like name@example.com" }
}
```
`fieldErrors` is present only for `VALIDATION_FAILED`. Messages never contain passwords, tokens or stack traces.

| HTTP | code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Invalid request body |
| 401 | `UNAUTHENTICATED` | Not logged in / token expired |
| 401 | `INVALID_CREDENTIALS` | Wrong identifier or password |
| 403 | `FORBIDDEN` | Logged in, wrong role |
| 403 | `ACCOUNT_PENDING_APPROVAL` | Merchant not approved yet |
| 403 | `ACCOUNT_REJECTED` | Merchant application rejected |
| 403 | `ACCOUNT_DISABLED` | Account disabled by admin |
| 403 | `PROFILE_INCOMPLETE` | Google sign-up not finished: only `GET /auth/me`, `POST /auth/logout` and `POST /auth/complete-profile` work |
| 404 | `NOT_FOUND` | Resource not found |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method for this path |
| 409 | `EMAIL_ALREADY_REGISTERED` | Email already in use |
| 409 | `PHONE_ALREADY_REGISTERED` | Mobile number already in use |
| 409 | `PROFILE_ALREADY_COMPLETE` | `POST /auth/complete-profile` for an account that is not `PROFILE_INCOMPLETE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request body is not `application/json` |
| 500 | `INTERNAL_ERROR` | Anything unexpected (generic message only) |

`VALIDATION_FAILED` is also returned for unreadable JSON, unknown enum values (e.g. `pickupDistrict: "XYZ"` → `fieldErrors.pickupDistrict`)
and bad query/path parameters (e.g. a non-UUID id).

---

## Validation rules (backend and frontend use the same rules)

| Field | Rule | Error message |
|---|---|---|
| `fullName`, `contactPerson` | 2-100 chars | Enter your full name. |
| `phone` | Sri Lankan mobile. Accept `0771234567`, `771234567`, `+94771234567`, spaces allowed. Stored as `+94771234567` (`+947` + 8 digits) | Enter a Sri Lankan mobile number, like 077 123 4567. |
| `email` | Valid email, max 254, stored lower-case | Enter a full email address, like name@example.com |
| `password` | 8-72 chars, at least one letter and one number | Use at least 8 characters, including a number. |
| `businessName` | 2-120 chars | Enter your business name. |
| `businessRegNo` | Optional, max 30 | - |
| `parcelsPerMonth` | `UNDER_50` \| `FROM_50_TO_200` \| `FROM_200_TO_1000` \| `OVER_1000` | Select a range. |
| `pickupDistrict` | One of the 25 district codes (see `/meta/districts`) | Select a district. |
| `city` | 2-80 chars | Enter your city or town. |
| `acceptTerms` | Must be `true` | Accept the terms to continue. |

---

## Endpoints

### `POST /auth/register` (public)

Customer:
```json
{
  "accountType": "CUSTOMER",
  "fullName": "Ayesha Fernando",
  "phone": "077 123 4567",
  "email": "ayesha@example.com",
  "password": "secret123",
  "acceptTerms": true
}
```

Merchant:
```json
{
  "accountType": "MERCHANT",
  "businessName": "Lanka Crafts",
  "businessRegNo": "PV 00012345",
  "parcelsPerMonth": "FROM_50_TO_200",
  "contactPerson": "Nimal Perera",
  "phone": "0712345678",
  "email": "orders@lankacrafts.lk",
  "pickupDistrict": "COLOMBO",
  "city": "Nugegoda",
  "password": "secret123",
  "acceptTerms": true
}
```

Responses:
- `201` CUSTOMER: User + `Set-Cookie: SD_TOKEN` (customer is logged in immediately, as with `rememberMe: false`)
- `201` MERCHANT: User with `status: PENDING_APPROVAL`, **no cookie** (cannot log in until approved)
- `400` `VALIDATION_FAILED` · `409` `EMAIL_ALREADY_REGISTERED` / `PHONE_ALREADY_REGISTERED`

Only `CUSTOMER` and `MERCHANT` can self-register. Couriers and admins are created by an admin (or the seeder).
A missing or other `accountType` (e.g. `COURIER`) → `400` with `fieldErrors.accountType`.
Email duplicates are checked case-insensitively, phone duplicates after normalising (`0771234567` = `+94771234567`).

### `POST /auth/login` (public)
```json
{ "identifier": "0771234567 or name@example.com", "password": "secret123", "rememberMe": true }
```
- `identifier` containing `@` is treated as an email (case-insensitive); anything else as a mobile number in any accepted format.
- `rememberMe` is optional (default `false`).
- `200`: User + `Set-Cookie: SD_TOKEN`
- `400` `VALIDATION_FAILED`: blank `identifier` or `password`
- `401` `INVALID_CREDENTIALS`: "The mobile number, email or password is incorrect." (same response for unknown user,
  wrong password, and accounts created with Google, which have no password)
- `403` `ACCOUNT_PENDING_APPROVAL`: "Your merchant account is waiting for approval. We'll email you when it's ready."
- `403` `ACCOUNT_REJECTED` / `ACCOUNT_DISABLED`
- The 403 codes are only returned when the password is correct (otherwise `401`).

### `POST /auth/logout` (any)
- `204`, clears the cookie (`Max-Age=0`)

### `GET /auth/me` (logged in)
- `200`: User · `401` `UNAUTHENTICATED`
- Also works for `PROFILE_INCOMPLETE` users, so the frontend can see that the profile must be completed.

### `POST /auth/complete-profile` (logged in, `PROFILE_INCOMPLETE` only)
Finishes a Google sign-up. Same fields, rules and messages as `POST /auth/register`, without `email` and
`password` (the email comes from Google; Google accounts have no password).

Customer:
```json
{ "accountType": "CUSTOMER", "phone": "077 123 4567", "acceptTerms": true }
```

Merchant:
```json
{
  "accountType": "MERCHANT",
  "businessName": "Lanka Crafts",
  "businessRegNo": "PV 00012345",
  "parcelsPerMonth": "FROM_50_TO_200",
  "contactPerson": "Nimal Perera",
  "phone": "0712345678",
  "pickupDistrict": "COLOMBO",
  "city": "Nugegoda",
  "acceptTerms": true
}
```

Responses:
- `200` CUSTOMER: User with `status: ACTIVE` + `Set-Cookie: SD_TOKEN` (a fresh token, as with `rememberMe: true`,
  so the user stays logged in and the `PROFILE_INCOMPLETE` restriction is lifted at once)
- `200` MERCHANT: User with `role: MERCHANT`, `status: PENDING_APPROVAL`, `merchant.approvalStatus: PENDING`,
  `fullName` = `contactPerson`, and the cookie **cleared** (`Max-Age=0`): the merchant waits for admin approval,
  like after `POST /auth/register`
- `400` `VALIDATION_FAILED` (missing or other `accountType` → `fieldErrors.accountType`)
- `401` `UNAUTHENTICATED` · `409` `PHONE_ALREADY_REGISTERED` · `409` `PROFILE_ALREADY_COMPLETE`

### `GET /meta/districts` (public)
```json
[ { "code": "AMPARA", "name": "Ampara" }, { "code": "COLOMBO", "name": "Colombo" } ]
```
All 25 districts of Sri Lanka, sorted by name.

### Admin (role `ADMIN` only)
No login → `401 UNAUTHENTICATED`; any other role → `403 FORBIDDEN`.
- `GET /admin/merchants?status=PENDING`
  `status`: `PENDING` (default) | `APPROVED` | `REJECTED`. Oldest application first. `createdAt` = sign-up time.
  ```json
  [ { "userId": "...", "businessName": "...", "contactPerson": "...", "email": "...", "phone": "...",
      "pickupDistrict": "COLOMBO", "city": "...", "parcelsPerMonth": "UNDER_50", "createdAt": "2026-11-01T10:15:00Z" } ]
  ```
- `POST /admin/merchants/{userId}/approve` → `200` User (status becomes `ACTIVE`, `merchant.approvalStatus` `APPROVED`)
- `POST /admin/merchants/{userId}/reject` → `200` User (status becomes `REJECTED`, `merchant.approvalStatus` `REJECTED`)
- Both work from any current state (so an admin can correct a mistake) and record who reviewed it and when.
  Unknown `userId` → `404 NOT_FOUND`.

---

## Cookie and token

- `SD_TOKEN`: `HttpOnly; Path=/; SameSite=Lax`, plus `Secure` only when `COOKIE_SECURE=true`.
- `rememberMe: true` → `Max-Age` = `JWT_REMEMBER_ME_DAYS` (7 days), token valid 7 days.
- `rememberMe: false` → no `Max-Age` (ends when the browser closes), token valid `JWT_EXPIRY_MINUTES` (60).
- JWT: HS256 signed with `JWT_SECRET` (at least 32 characters; the backend refuses to start otherwise).
  Claims: `sub` (user id), `role`, `status`, `iat`, `exp`. No personal data in the token.
  A token with `status: PROFILE_INCOMPLETE` only works for `GET /auth/me` and `POST /auth/complete-profile`
  (`403 PROFILE_INCOMPLETE` everywhere else that needs a login).
- A token stays valid until it expires, even if the account is disabled later (disabled users cannot log in again).
- Public endpoints ignore the cookie, so an expired cookie never blocks login, register or logout.
- CSRF: the cookie is `SameSite=Lax`, CORS allows only `FRONTEND_URL` with credentials, and write endpoints only accept `application/json`.

## Google sign-in

"Continue with Google" (OAuth 2.0 / OpenID Connect, scopes `openid email profile`) works for both login and sign-up.
It is on only when `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` are both set; otherwise the backend starts
normally and the two URLs below return `404 NOT_FOUND`. These URLs are **not** under `/api/v1`.

| URL (backend) | Who calls it |
|---|---|
| `GET http://localhost:8080/oauth2/authorization/google` | The browser: the "Continue with Google" button is a normal link/navigation to it (not `fetch`) |
| `GET http://localhost:8080/login/oauth2/code/google` | Google redirects back here. Register it as an **Authorized redirect URI** in Google Cloud Console |

Flow:
1. The browser opens `/oauth2/authorization/google`. The backend stores the pending request (state, nonce) in the
   short-lived cookie `SD_OAUTH2_REQUEST` (`HttpOnly; SameSite=Lax`, 5 minutes, signed; no server session)
   and redirects to Google.
2. The user picks an account and agrees. Google redirects back to `/login/oauth2/code/google`.
3. The backend checks Google's `email_verified` claim, then finds the account by Google id, then by email.
   - Found by email (e.g. a password account): the Google account is linked to it. The user keeps their role
     and can still log in with their password.
   - Not found: a new `CUSTOMER` with `status: PROFILE_INCOMPLETE` is created (name and email from Google,
     no password, no phone).
4. The browser is redirected to the frontend:

| Result | Cookie | Redirect |
|---|---|---|
| Signed in (`ACTIVE` or `PROFILE_INCOMPLETE`) | `SD_TOKEN` set, as with `rememberMe: true` | `FRONTEND_URL/auth/callback` |
| Merchant not approved yet | none | `FRONTEND_URL/login?error=account_pending_approval` |
| Merchant rejected | none | `FRONTEND_URL/login?error=account_rejected` |
| Account disabled | none | `FRONTEND_URL/login?error=account_disabled` |
| Cancelled consent, email not verified by Google, email already linked to another Google account, expired (5 min) or invalid request, any other error | none | `FRONTEND_URL/login?error=google_failed` |

On `/auth/callback` the frontend calls `GET /auth/me`: `PROFILE_INCOMPLETE` → show the complete-profile form
(customer or merchant) and send `POST /auth/complete-profile`; otherwise go to the role's home page.

Google tokens are used once to read the user and never stored. Logs never contain Google tokens, emails or names.

## Role → home page (frontend)

| Role | Home |
|---|---|
| CUSTOMER | `/customer` |
| MERCHANT | `/merchant` |
| COURIER | `/courier` |
| ADMIN | `/dispatcher` |

## Seed accounts (local/demo only)

- Always: one ADMIN from `ADMIN_EMAIL` / `ADMIN_PASSWORD` (created only if missing).
- When `SEED_DEMO_USERS=true`, password `DEMO_PASSWORD`:
  - `customer@swiftdrop.lk` (CUSTOMER), mobile `0770000001`
  - `merchant@swiftdrop.lk` (MERCHANT, approved), mobile `0770000002`
  - `courier@swiftdrop.lk` (COURIER), mobile `0770000003`
- The admin uses mobile `0770000000`. Existing accounts (same email) are never changed.
