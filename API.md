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
- `status`: `ACTIVE` | `PENDING_EMAIL_VERIFICATION` | `PENDING_APPROVAL` | `REJECTED` | `DISABLED` | `PROFILE_INCOMPLETE`
  - `PENDING_EMAIL_VERIFICATION`: signed up with a password and has not entered the 6-digit code from the
    verification email yet (see [Email verification](#email-verification)). Cannot log in.
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
| 400 | `INVALID_CODE` | Wrong 6-digit code, or no code waiting for this email/identifier (also for unknown accounts) |
| 400 | `CODE_EXPIRED` | The code is older than 10 minutes: ask for a new one |
| 400 | `TOO_MANY_ATTEMPTS` | 5 wrong tries: this code no longer works, even the right one. Ask for a new one |
| 401 | `UNAUTHENTICATED` | Not logged in / token expired / session ended (password changed, account disabled) |
| 401 | `INVALID_CREDENTIALS` | Wrong identifier or password |
| 403 | `FORBIDDEN` | Logged in, wrong role |
| 403 | `EMAIL_NOT_VERIFIED` | Login with the right password, but the email is not confirmed yet |
| 403 | `ACCOUNT_PENDING_APPROVAL` | Merchant not approved yet |
| 403 | `ACCOUNT_REJECTED` | Merchant application rejected |
| 403 | `ACCOUNT_DISABLED` | Account disabled by admin |
| 403 | `PROFILE_INCOMPLETE` | Google sign-up not finished: only `GET /auth/me`, `POST /auth/logout` and `POST /auth/complete-profile` work |
| 404 | `NOT_FOUND` | Resource not found |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method for this path |
| 409 | `EMAIL_ALREADY_REGISTERED` | Email already in use |
| 409 | `PHONE_ALREADY_REGISTERED` | Mobile number already in use |
| 409 | `PROFILE_ALREADY_COMPLETE` | `POST /auth/complete-profile` for an account that is not `PROFILE_INCOMPLETE` |
| 409 | `INVALID_STATUS_TRANSITION` | The parcel can't move from its current status to the one asked for (e.g. DELIVERED → PICKED_UP) |
| 409 | `PARCEL_NOT_ASSIGNABLE` | Assigning a courier to a parcel that was already picked up or is finished |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request body is not `application/json` |
| 429 | `TOO_MANY_REQUESTS` | Too many requests from this IP address; the `Retry-After` header says how many seconds to wait |
| 500 | `INTERNAL_ERROR` | Anything unexpected (generic message only) |

Messages of the new codes (show them as-is):

| code | message |
|---|---|
| `EMAIL_NOT_VERIFIED` | Confirm your email address to continue. Enter the 6-digit code we emailed you. |
| `INVALID_CODE` | That code isn't right. Check it and try again. |
| `CODE_EXPIRED` | This code has expired. Ask for a new one. |
| `TOO_MANY_ATTEMPTS` | Too many wrong codes. Ask for a new one. |
| `TOO_MANY_REQUESTS` | Too many attempts. Please wait a moment and try again. |
| `INVALID_STATUS_TRANSITION` | This parcel can't move to that status now. Refresh to see its latest status. |
| `PARCEL_NOT_ASSIGNABLE` | A courier can only be assigned before the parcel is picked up. |

**Rate limits** (per client IP; over the limit → `429 TOO_MANY_REQUESTS` + `Retry-After: <seconds>`; CORS exposes
`Retry-After`, so the frontend can read it):

| Endpoint | Limit |
|---|---|
| `POST /auth/login` | 10 per minute |
| `POST /auth/register` | 10 per 10 minutes |
| `POST /auth/email-verification/verify` | 10 per minute |
| `POST /auth/email-verification/resend` | 5 per minute |
| `POST /auth/password/forgot` | 5 per minute |
| `POST /auth/password/reset` | 10 per minute |
| `POST /auth/password/change` | 10 per minute |

`VALIDATION_FAILED` is also returned for unreadable JSON, unknown enum values (e.g. `pickupDistrict: "XYZ"` → `fieldErrors.pickupDistrict`)
and bad query/path parameters (e.g. a non-UUID id).

---

## Validation rules (backend and frontend use the same rules)

| Field | Rule | Error message |
|---|---|---|
| `fullName`, `contactPerson` | 2-100 chars | Enter your full name. |
| `phone` | Sri Lankan mobile. Accept `0771234567`, `771234567`, `+94771234567`, spaces allowed. Stored as `+94771234567` (`+947` + 8 digits) | Enter a Sri Lankan mobile number, like 077 123 4567. |
| `email` | Valid email, max 254, stored lower-case | Enter a full email address, like name@example.com |
| `email` (register only) | The domain must have MX records (if DNS itself fails, the email is allowed) | We couldn't find this email domain. Check for typos. |
| `email` (register only) | Not a disposable-inbox domain (e.g. mailinator.com, yopmail.com) | Use a permanent email address. |
| `password`, `newPassword` | 8-72 chars, at least one letter and one number | Use at least 8 characters, including a number. |
| `code` | Exactly 6 digits | Enter the 6-digit code. |
| `identifier` (forgot/reset) | Not blank, max 254 | Enter your mobile number or email. |
| `currentPassword` | Not blank | Enter your current password. |
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
- `201` CUSTOMER and MERCHANT: User with `status: PENDING_EMAIL_VERIFICATION`, **no cookie**. A 6-digit code is
  emailed to the address ("Your SwiftDrop verification code", valid 10 minutes). The frontend shows the
  "enter the code" page and calls [`POST /auth/email-verification/verify`](#post-authemail-verificationverify-public).
  Merchants also get `merchant` with `approvalStatus: PENDING`.
- `400` `VALIDATION_FAILED` (including the email domain checks) · `409` `EMAIL_ALREADY_REGISTERED` / `PHONE_ALREADY_REGISTERED`
- `429` `TOO_MANY_REQUESTS`

```json
{
  "id": "6f1c2a0e-1b2c-4d5e-9f00-1a2b3c4d5e6f",
  "fullName": "Ayesha Fernando",
  "email": "ayesha@example.com",
  "phone": "+94771234567",
  "role": "CUSTOMER",
  "status": "PENDING_EMAIL_VERIFICATION",
  "merchant": null
}
```

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
- `403` `EMAIL_NOT_VERIFIED`: the email was never confirmed. The frontend shows the "enter the code" page
  (and may call `POST /auth/email-verification/resend` for a fresh code).
- `403` `ACCOUNT_PENDING_APPROVAL`: "Your merchant account is waiting for approval. We'll email you when it's ready."
- `403` `ACCOUNT_REJECTED` / `ACCOUNT_DISABLED`
- The 403 codes are only returned when the password is correct (otherwise `401`).
- `429` `TOO_MANY_REQUESTS`

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

### Email verification

Every password sign-up (`POST /auth/register`) must confirm its email with a 6-digit code before it can log in.
Google accounts are verified by Google, and seeded accounts are created verified: neither gets a code.

Codes (also for password reset): 6 random digits, valid **10 minutes**, dead after **5 wrong tries**
(`TOO_MANY_ATTEMPTS`), used once, and replaced by a newer code of the same kind. At most one new code per
**60 seconds** and **5 codes per hour** per account; requests beyond that are answered the same way but send nothing
(the frontend shows a 60-second countdown on its "Send a new code" button).

#### `POST /auth/email-verification/verify` (public)
```json
{ "email": "ayesha@example.com", "code": "482913" }
```
- `200` CUSTOMER: User with `status: ACTIVE` + `Set-Cookie: SD_TOKEN` (logged in, as with `rememberMe: false`)
- `200` MERCHANT: User with `status: PENDING_APPROVAL`, `merchant.approvalStatus: PENDING`, **no cookie**
  (waits for an admin; only now does the application appear in `GET /admin/merchants`)
- `400` `VALIDATION_FAILED` (`fieldErrors.email` / `fieldErrors.code`)
- `400` `INVALID_CODE` (also for an unknown email or an already verified account) · `400` `CODE_EXPIRED` ·
  `400` `TOO_MANY_ATTEMPTS` · `429` `TOO_MANY_REQUESTS`

#### `POST /auth/email-verification/resend` (public)
```json
{ "email": "ayesha@example.com" }
```
- `202`, always (whether or not the email is registered or waiting for verification):
  ```json
  { "message": "If this email is waiting for verification, we've sent a new code." }
  ```
- `400` `VALIDATION_FAILED` · `429` `TOO_MANY_REQUESTS`

### Passwords

#### `POST /auth/password/forgot` (public)
```json
{ "identifier": "0771234567 or name@example.com" }
```
Same `identifier` rules as login. If an account matches, a 6-digit code ("Your SwiftDrop password reset code")
is emailed to **that account's email** (also when the identifier was a mobile number).
- `202`, always, with the same body:
  ```json
  { "message": "If an account matches, we've sent a code to its email." }
  ```
- `400` `VALIDATION_FAILED` (`fieldErrors.identifier`) · `429` `TOO_MANY_REQUESTS`

#### `POST /auth/password/reset` (public)
```json
{ "identifier": "0771234567", "code": "482913", "newPassword": "newSecret456" }
```
`identifier` is any identifier of the same account (email or mobile). `newPassword` follows the register
password rules.
- `204`, no body, no cookie: the password is changed, **every** existing session of the account ends, and a
  "Your SwiftDrop password was changed" email is sent. The frontend sends the user to the login page.
- Also works for accounts created with Google (they get their first password) and for accounts still waiting for
  email verification (the code proves the email, so they become `ACTIVE` / `PENDING_APPROVAL`).
- `400` `VALIDATION_FAILED` (`fieldErrors.identifier` / `code` / `newPassword`)
- `400` `INVALID_CODE` (also for unknown identifiers) · `400` `CODE_EXPIRED` · `400` `TOO_MANY_ATTEMPTS` ·
  `429` `TOO_MANY_REQUESTS`

#### `POST /auth/password/change` (logged in)
```json
{ "currentPassword": "secret123", "newPassword": "newSecret456" }
```
- `204` + `Set-Cookie: SD_TOKEN` (a fresh token with the same lifetime as the current one). Every **other**
  session of the user ends; a "password was changed" email is sent.
- `400` `VALIDATION_FAILED`:
  - `fieldErrors.currentPassword`: "Your current password is incorrect." (or, for an account created with Google:
    "This account has no password yet. Use Forgot password to set one.")
  - `fieldErrors.newPassword`: the register password rule
- `401` `UNAUTHENTICATED` · `429` `TOO_MANY_REQUESTS`

### `GET /meta/districts` (public)
```json
[ { "code": "AMPARA", "name": "Ampara" }, { "code": "COLOMBO", "name": "Colombo" } ]
```
All 25 districts of Sri Lanka, sorted by name.

### Admin (role `ADMIN` only)
No login → `401 UNAUTHENTICATED`; any other role → `403 FORBIDDEN`.
- `GET /admin/merchants?status=PENDING`
  `status`: `PENDING` (default) | `APPROVED` | `REJECTED`. Oldest application first. `createdAt` = sign-up time.
  Merchants who have not confirmed their email yet (`PENDING_EMAIL_VERIFICATION`) are not listed.
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
  Claims: `sub` (user id), `role`, `status`, `ver` (token version), `iat`, `exp`. No personal data in the token.
  A token with `status: PROFILE_INCOMPLETE` only works for `GET /auth/me` and `POST /auth/complete-profile`
  (`403 PROFILE_INCOMPLETE` everywhere else that needs a login).
- Sessions can end before the token expires. On every request that needs a login the backend checks the account
  (cached for at most 30 seconds): it must still exist, be `ACTIVE` or `PROFILE_INCOMPLETE`, and `ver` must match.
  Otherwise `401 UNAUTHENTICATED` and the cookie is cleared (`Max-Age=0`); the frontend sends the user to login.
  This happens after a password reset (all sessions), a password change (the other sessions), when an admin
  disables or rejects the account, and to tokens issued before this check existed (no `ver`).
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
   - Found by email, but still `PENDING_EMAIL_VERIFICATION`: Google has proved the email, so the account is
     verified (customer → `ACTIVE`, merchant → `PENDING_APPROVAL`) and its password is **removed** (whoever chose it
     never proved they own the address). The owner can set a password with `POST /auth/password/forgot`.
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
- Seed accounts are created with a verified email (no code needed).

## Emails

Sent by the backend over SMTP (Mailpit locally: <http://localhost:8025>; Gmail on the shared setup), in the
background, only after the request's changes are saved. Subjects:

| When | Subject |
|---|---|
| Register, resend | Your SwiftDrop verification code |
| Forgot password | Your SwiftDrop password reset code |
| Password reset or changed | Your SwiftDrop password was changed |

---

## Merchant API Keys (WP5)

All endpoints require a logged-in `MERCHANT` cookie (`SD_TOKEN`).

### Create API key
`POST /merchant/api-keys`

Request:
```json
{ "name": "Production key" }
```
- `name`: 1–80 characters, required.

Response `201`:
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "Production key",
  "rawKey": "sd_a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
  "createdAt": "2026-11-05T10:00:00Z"
}
```
> `rawKey` is shown **only once**. Store it securely — it cannot be retrieved again.

---

### List API keys
`GET /merchant/api-keys`

Response `200` — array, newest first:
```json
[
  {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "name": "Production key",
    "createdAt": "2026-11-05T10:00:00Z",
    "revokedAt": null
  }
]
```
- `revokedAt`: `null` = active, ISO-8601 timestamp = revoked.

---

### Revoke API key
`DELETE /merchant/api-keys/{id}`

Response `200` — the key with `revokedAt` set. Idempotent.

---

### Rotate API key
`POST /merchant/api-keys/{id}/rotate`

Revokes the existing key and creates a new one with the same name.

Response `200` — same shape as **Create**, with the new `rawKey` shown once.

---

## Merchant Orders (WP5 — coming in PR 3, after WP3 merges)

Authenticated with either the `SD_TOKEN` cookie **or** an `X-Api-Key: <rawKey>` header.

### Create order
`POST /merchant/orders`

### List orders
`GET /merchant/orders`

> Full request/response shapes will be documented here when WP3's `DeliveryApi` contract is finalised.

