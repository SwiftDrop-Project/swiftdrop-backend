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
| 409 | `INVALID_STATUS_TRANSITION` | The parcel can't move to that status from its current one, e.g. DELIVERED → PICKED_UP or PICKED_UP → IN_TRANSIT (a picked-up parcel goes to a branch first). See [Parcel statuses](#parcel-statuses) |
| 409 | `PARCEL_NOT_ASSIGNABLE` | Assigning a courier to a parcel that was already picked up or is finished |
| 409 | `TASK_STEP_NOT_ALLOWED` | A courier's step isn't allowed for this parcel in this task now (e.g. handing over a parcel that wasn't collected, or a step that was already done) |
| 409 | `ASSIGNMENT_CLOSED` | Recording a step on, or completing, a courier task that is already completed or cancelled |
| 409 | `ASSIGNMENT_NOT_FINISHED` | Completing a courier task while one of its parcels is still open |
| 409 | `PARCEL_ALREADY_ASSIGNED` | Dispatch: the parcel is already in another open courier task (also when two dispatchers assign it at once) |
| 409 | `PARCEL_NOT_READY_FOR_TASK` | Dispatch: the parcel's status or branch doesn't suit the task (the message names the parcel) |
| 409 | `ASSIGNMENT_STARTED` | Dispatch: cancelling or reassigning a task the courier has already started |
| 409 | `ASSIGNMENT_NEEDS_PARCEL` | Dispatch: removing the last parcel of a task |
| 409 | `ASSIGNMENT_CHANGED` | Dispatch: someone else changed the task at the same moment |
| 409 | `WEBHOOK_LIMIT_REACHED` | Webhooks: creating or turning on a 6th active subscription |
| 409 | `WEBHOOK_DELIVERY_NOT_RETRYABLE` | Webhooks: retrying a delivery that is not `FAILED` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request body is not `application/json` |
| 429 | `TOO_MANY_REQUESTS` | Too many requests from this IP address; the `Retry-After` header says how many seconds to wait |
| 500 | `INTERNAL_ERROR` | Anything unexpected (generic message only) |
| 503 | `SERVICE_UNAVAILABLE` | A part of SwiftDrop isn't running yet (e.g. courier endpoints before parcels exist on the server) |

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
| `TASK_STEP_NOT_ALLOWED` | This step isn't possible for this parcel now. Refresh to see your task. |
| `ASSIGNMENT_CLOSED` | This task is already completed or was cancelled. |
| `ASSIGNMENT_NOT_FINISHED` | Update every parcel in this task before you finish it. |
| `PARCEL_ALREADY_ASSIGNED` | This parcel is already in another courier's task. Refresh to see the latest queue. |
| `PARCEL_NOT_READY_FOR_TASK` | This parcel can't be added to this task now. Refresh to see its latest status. |
| `ASSIGNMENT_STARTED` | The courier has already started this task. |
| `ASSIGNMENT_NEEDS_PARCEL` | A task needs at least one parcel. Cancel the task instead. |
| `ASSIGNMENT_CHANGED` | This task was just changed by someone else. Refresh and try again. |
| `WEBHOOK_LIMIT_REACHED` | You can have up to 5 active webhooks. Turn one off before adding another. |
| `WEBHOOK_DELIVERY_NOT_RETRYABLE` | Only failed deliveries can be sent again. |
| `SERVICE_UNAVAILABLE` | This isn't available yet. Please try again later. |

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

## Webhooks (WP6)

A merchant registers HTTPS URLs; SwiftDrop sends a signed `POST` to each one when one of the merchant's parcels
changes status. Only parcels with a merchant trigger webhooks (customer bookings never do). The merchant endpoints
require a logged-in `MERCHANT` cookie (`SD_TOKEN`) of an approved merchant; API keys can't manage webhooks (yet).
The admin endpoints require `ADMIN`.

### Event types

| Type | Sent when the parcel becomes |
|---|---|
| `parcel.created` | `CREATED` |
| `parcel.picked_up` | `PICKED_UP` |
| `parcel.at_branch` | `AT_BRANCH` |
| `parcel.in_transit` | `IN_TRANSIT` |
| `parcel.out_for_delivery` | `OUT_FOR_DELIVERY` |
| `parcel.delivered` | `DELIVERED` |
| `parcel.failed` | `FAILED` |
| `webhook.ping` | Never by a parcel: only `POST /merchant/webhooks/{id}/test`, so it can't be subscribed to |

### Subscription (response)

```json
{
  "id": "uuid", "url": "https://shop.example.com/swiftdrop", "eventTypes": ["parcel.delivered", "parcel.failed"],
  "active": true, "createdAt": "2026-11-13T09:00:00Z", "updatedAt": "2026-11-13T09:00:00Z"
}
```
The secret is never in this shape. The create and rotate responses add it once:
`"secret": "whsec_…"`. Store it securely; it can't be retrieved again (rotate to get a new one).

### Delivery (response)

```json
{
  "id": "uuid", "subscriptionId": "uuid", "eventId": "uuid", "eventType": "parcel.delivered",
  "status": "PENDING", "attempts": 1, "lastStatusCode": 500, "lastError": "HTTP_500",
  "nextAttemptAt": "2026-11-13T09:16:00Z", "createdAt": "2026-11-13T09:15:00Z", "deliveredAt": null
}
```
- `status`: `PENDING` (waiting for the next attempt), `SUCCEEDED`, `FAILED` (no more automatic attempts).
- `lastError`: `null`, `HTTP_<code>`, `TIMEOUT`, `CONNECTION_FAILED` or `URL_NOT_ALLOWED`. The merchant's response
  body is never stored.
- `nextAttemptAt` is `null` unless `status` is `PENDING`.

### Endpoints

| Method and path | What it does | Main errors |
|---|---|---|
| `POST /merchant/webhooks` `{"url", "eventTypes": [...]}` | Create an active subscription (201, subscription + `secret`) | 400 `url`, `eventTypes`; 403 merchant not approved; 409 `WEBHOOK_LIMIT_REACHED` |
| `GET /merchant/webhooks` | Your subscriptions, active and turned off, newest first (array of subscriptions) | — |
| `PATCH /merchant/webhooks/{id}` `{"url"?, "eventTypes"?, "active"?}` | Change only the fields sent (subscription) | 400; 404; 409 `WEBHOOK_LIMIT_REACHED` (turning one on) |
| `DELETE /merchant/webhooks/{id}` | Turn off (`active: false`; history is kept). 204. Idempotent | 404 |
| `POST /merchant/webhooks/{id}/rotate-secret` | New secret; the old one stops working at once, also for retries (200, subscription + `secret`) | 404 |
| `POST /merchant/webhooks/{id}/test` | Queue a `webhook.ping` for this subscription, sent within seconds (202, delivery). Also works while it is turned off, so a URL can be checked before turning it on | 404 |
| `GET /merchant/webhooks/{id}/deliveries?status=&page=0&size=20` | Delivery history of one subscription, newest first (page of deliveries) | 400 `status`, `page`, `size` (1-100); 404 |
| `POST /merchant/webhooks/deliveries/{id}/retry` | Send a `FAILED` delivery again, with a new set of attempts (delivery) | 404; 409 `WEBHOOK_DELIVERY_NOT_RETRYABLE` |
| `GET /admin/webhooks/deliveries?status=FAILED&merchantId=&page=0&size=20` | ADMIN: every merchant's deliveries, newest first (page of deliveries, each with `merchantId`) | 400 |
| `POST /admin/webhooks/deliveries/{id}/retry` | ADMIN: same as the merchant retry | 404; 409 `WEBHOOK_DELIVERY_NOT_RETRYABLE` |

Another merchant's subscription or delivery answers 404, never 403. A merchant has at most 5 active subscriptions.

### Validation rules (webhooks)

| Field | Rule | `fieldErrors` message |
|---|---|---|
| `url` | Required, at most 2048 characters, an absolute `https://` URL | Enter a full https:// address, like https://shop.example.com/webhooks |
| `url` | No user name or password in it, and the host must not be (or resolve to) a private, loopback or link-local address | This address can't receive webhooks. Use a public https:// address. |
| `eventTypes` | Required, 1-7 distinct parcel event types from the table above (`webhook.ping` isn't one) | Choose at least one event |
| `status` (query) | `PENDING`, `SUCCEEDED` or `FAILED` | — |

### What the merchant receives

`POST <url>`, `Content-Type: application/json`:

```json
{
  "id": "6f1c2a8e-4b0d-4c1e-9f3a-2d7b8e5c1a90",
  "type": "parcel.delivered",
  "createdAt": "2026-11-13T09:15:00Z",
  "data": {
    "parcelId": "uuid", "trackingNumber": "SD2611004817", "merchantOrderRef": "TECH-ORD-1001",
    "status": "DELIVERED", "previousStatus": "OUT_FOR_DELIVERY", "sequence": 7,
    "branch": null, "failureReason": null
  }
}
```
- `id` is the event id: the same on every attempt and retry. Ignore an `id` you already processed.
- `createdAt` is when the parcel changed status, not when this attempt was sent.
- `sequence` goes up with every status change of the parcel. Ignore an event whose `sequence` is lower than the last
  one you saw for that parcel (events can arrive late or out of order).
- `previousStatus` is `null` for `parcel.created`. `branch` (a district code) is set only for `parcel.at_branch`;
  `failureReason` (a `FailureReason` code) only for `parcel.failed`. `merchantOrderRef` is `null` when the parcel
  has none.
- No customer, sender or recipient names, phone numbers, addresses or courier notes are ever sent.
- A `webhook.ping` has `"data": { "subscriptionId": "uuid" }`.

Headers:

| Header | Value |
|---|---|
| `X-SwiftDrop-Event-Id` | Same as `id` |
| `X-SwiftDrop-Event-Type` | Same as `type` |
| `X-SwiftDrop-Timestamp` | Unix time in seconds when this attempt was sent (new on every attempt) |
| `X-SwiftDrop-Signature` | `sha256=` + lowercase hex of HMAC-SHA256(secret, `<timestamp>.<raw body>`) |

To verify a request: compute the HMAC over the timestamp header, a `.` and the **raw** request body (before any JSON
parsing), compare it with the signature in constant time, and reject timestamps more than 5 minutes old.

### Delivery and retries

- A 2xx answer within 10 seconds counts as delivered. Redirects are not followed.
- 408, 429, 5xx, timeouts and connection errors are retried: right away, then after 1 min, 5 min, 15 min, 1 h and
  6 h (6 attempts in all).
- Any other 4xx, or the 6th failed attempt, makes the delivery `FAILED`. The merchant or an admin can retry it.
- Delivery is at-least-once and not ordered: use `id` and `sequence`, as above.
- A change to a subscription (URL, event types, turned off) applies to new events. A turned-off subscription gets no
  new parcel events, and its `PENDING` parcel deliveries are not sent (a test ping still is).

---

## Deliveries (WP3)

### Parcel statuses
A parcel passes through SwiftDrop branches on its way, and several couriers handle it: one collects it, others move
it between branches, and one delivers it. Finishing a courier task does not mean the parcel was delivered.

| Status | Meaning | May move to |
|---|---|---|
| `CREATED` | Booked, waiting for pickup | `PICKED_UP` |
| `PICKED_UP` | Collected from the sender | `AT_BRANCH` |
| `AT_BRANCH` | At a SwiftDrop branch; `currentBranch` says which (a district, e.g. `GAMPAHA`) | `IN_TRANSIT`, `OUT_FOR_DELIVERY` |
| `IN_TRANSIT` | On the way to another branch | `AT_BRANCH` |
| `OUT_FOR_DELIVERY` | With the courier who delivers it | `DELIVERED`, `FAILED` |
| `FAILED` | Delivery attempt failed (`failureReason` says why) | `AT_BRANCH` (back at a branch for another try) |
| `DELIVERED` | Handed to the recipient. Final | - |

- Example: Delgoda → `PICKED_UP` → `AT_BRANCH` Gampaha → `IN_TRANSIT` → `AT_BRANCH` Matara → `OUT_FOR_DELIVERY` →
  `DELIVERED` in Deniyaya.
- `currentBranch` is set only while `AT_BRANCH`, and the branch is kept on that status history entry. The branch is
  not personal data, so tracking may show it ("At Gampaha branch").
- `failureReason` is set only while `FAILED`; when the parcel goes back to a branch it is cleared on the parcel and
  kept in the history.
- Any other move is `409` `INVALID_STATUS_TRANSITION`.

### Book a delivery
`POST /deliveries` (logged-in `CUSTOMER`; merchants use `POST /merchant/orders`)

Request:
```json
{
  "pickup":    { "name": "Nimal Perera", "phone": "077 123 4567", "addressLine": "12 Galle Road",
                 "city": "Colombo 03", "district": "COLOMBO" },
  "recipient": { "name": "Kamala Silva", "phone": "0719876543", "addressLine": "45 Peradeniya Road",
                 "city": "Kandy", "district": "KANDY" },
  "weightKg": 2.5,
  "codAmount": 2500.00,
  "instructions": "Call before coming"
}
```
There is no customer id and no fee: the customer comes from the login, and the server sets the fee and the
tracking number.

| Field | Rule | Message |
|---|---|---|
| `pickup`, `recipient` | Required | "Enter the pickup details." / "Enter the recipient's details." |
| `*.name` | 1–100 characters | "Enter a name." / "Use 100 characters or fewer." |
| `*.phone` | Sri Lankan mobile in any accepted form; stored as `+947XXXXXXXX` | "Enter a Sri Lankan mobile number, like 077 123 4567." |
| `*.addressLine` | 1–200 characters | "Enter the address." / "Use 200 characters or fewer." |
| `*.city` | 1–80 characters | "Enter the city." / "Use 80 characters or fewer." |
| `*.district` | One of `GET /meta/districts` | "Select a district." (missing). An unknown value gives `fieldErrors.district`: "This value is not valid." |
| `weightKg` | 0.01–50, up to 2 decimals | "Enter the weight." / "Enter a weight above 0 kg." / "A parcel can weigh up to 50 kg." / "Use up to 2 decimal places." |
| `codAmount` | Optional: cash to collect on delivery; leave out when already paid. 0.01–500000, up to 2 decimals | "Enter an amount above 0, or leave it out." / "Cash on delivery can be up to Rs. 500,000." |
| `instructions` | Optional, up to 500 characters (blank = none) | "Use 500 characters or fewer." |

Response `201`:
```json
{
  "id": "6f1c2b8e-3a4d-4c8e-9b1a-2f3e4d5c6b7a",
  "trackingNumber": "SD2611004817",
  "status": "CREATED",
  "pickup":    { "name": "Nimal Perera", "phone": "+94771234567", "addressLine": "12 Galle Road",
                 "city": "Colombo 03", "district": "COLOMBO" },
  "recipient": { "name": "Kamala Silva", "phone": "+94719876543", "addressLine": "45 Peradeniya Road",
                 "city": "Kandy", "district": "KANDY" },
  "weightKg": 2.5,
  "codAmount": 2500.00,
  "instructions": "Call before coming",
  "feeAmount": 450.00,
  "currency": "LKR",
  "createdAt": "2026-11-07T04:30:00Z"
}
```
- `feeAmount` is a flat 450.00 LKR until pricing (WP4) is connected.
- `400` `VALIDATION_FAILED` (keys like `pickup.phone`, `weightKg`) · `401` `UNAUTHENTICATED` · `403` `FORBIDDEN`
  (logged in, but not a `CUSTOMER`)

---

## Merchant Orders (WP5 — coming in PR 3, after WP3 merges)

Authenticated with either the `SD_TOKEN` cookie **or** an `X-Api-Key: <rawKey>` header.

### Create order
`POST /merchant/orders`

### List orders
`GET /merchant/orders`

> Full request/response shapes will be documented here when WP3's `DeliveryApi` contract is finalised.

---

## Courier (role `COURIER` only)

The logged-in courier's parcels (WP9). No login → `401 UNAUTHENTICATED`; any other role (including `ADMIN`) →
`403 FORBIDDEN`. A courier only sees parcels assigned to them: another courier's parcel, an unknown id, or a parcel
that was reassigned to someone else → `404 NOT_FOUND`. "Today" is the current day in Sri Lankan time (Asia/Colombo).

Until parcels exist on the server (the delivery module is not implemented yet), every courier endpoint answers
`503 SERVICE_UNAVAILABLE`. For local demos, `SWIFTDROP_FAKE_DELIVERY=true` (with `SEED_DEMO_USERS=true`) gives
`courier@swiftdrop.lk` a few in-memory demo parcels; they are not saved and a restart resets them (temporary).

### Courier delivery (response)
```json
{
  "parcelId": "0b6c7c1e-5d0f-4a51-9d2e-3a4f5b6c7d8e",
  "trackingNumber": "SD2611004817",
  "status": "OUT_FOR_DELIVERY",
  "stop": "DELIVERY",
  "pickup": { "name": "Lanka Crafts", "phone": "+94712345678", "address": "12 High Level Road",
              "city": "Nugegoda", "district": "COLOMBO" },
  "recipient": { "name": "Nimal Perera", "phone": "+94771234567", "address": "45 Peradeniya Road",
                 "city": "Kandy", "district": "KANDY" },
  "weightKg": 1.2,
  "codAmount": 2450.00,
  "instructions": "Call before you arrive.",
  "assignedAt": "2026-11-05T02:30:00Z",
  "updatedAt": "2026-11-05T05:10:00Z",
  "nextStatuses": ["DELIVERED", "FAILED"]
}
```
- `stop`: `PICKUP` while `status` is `CREATED`, otherwise `DELIVERY`.
- `codAmount`: cash to collect in rupees; `null` when already paid. `instructions` may be `null`.
- `nextStatuses`: what this courier may set now, in life-cycle order (empty once `DELIVERED` or `FAILED`).

| From | Allowed next |
|---|---|
| `CREATED` | `PICKED_UP` |
| `PICKED_UP` | `IN_TRANSIT` |
| `IN_TRANSIT` | `OUT_FOR_DELIVERY` |
| `OUT_FOR_DELIVERY` | `DELIVERED`, `FAILED` |
| `DELIVERED`, `FAILED` | none |

`failureReason`: `RECIPIENT_UNAVAILABLE` | `WRONG_ADDRESS` | `RECIPIENT_REFUSED` | `ADDRESS_UNREACHABLE` |
`PARCEL_DAMAGED` | `OTHER`

### `GET /courier/deliveries/today`
```json
{ "date": "2026-11-05", "toDo": 5, "done": 2, "items": [ /* Courier delivery */ ] }
```
Every open parcel assigned to the courier (whatever day it was assigned), plus the ones they finished today.
Order: pickups (`CREATED`, oldest `assignedAt` first), then the other open parcels (oldest `assignedAt` first), then
finished ones (newest first). `toDo` = not finished, `done` = finished today.
- `200` · `401` · `403` · `503`

### `GET /courier/deliveries/history?page=0&size=20&trackingNumber=SD2611004817`
Parcels this courier delivered or marked failed, newest first, as a page of Courier delivery:
```json
{ "content": [ /* Courier delivery */ ], "page": 0, "size": 20, "totalElements": 42, "totalPages": 3 }
```
`page` from 0 (default 0); `size` 1-100 (default 20); `trackingNumber` optional, spaces and case ignored.
- `200` · `400` `VALIDATION_FAILED` (`fieldErrors.page` / `fieldErrors.size`) · `401` · `403` · `503`

### `GET /courier/deliveries/{parcelId}`
All Courier delivery fields, plus:
```json
{
  "history": [
    { "status": "OUT_FOR_DELIVERY", "note": null, "failureReason": null, "occurredAt": "2026-11-05T05:10:00Z" },
    { "status": "PICKED_UP", "note": "Collected from front desk", "failureReason": null, "occurredAt": "2026-11-05T03:00:00Z" }
  ],
  "proof": null,
  "failureReason": null
}
```
- `history`: newest first. `proof`: `{ "recipientName": "Nimal Perera", "hasPhoto": false }` once `DELIVERED`,
  otherwise `null`. `failureReason`: only once `FAILED`.
- `200` · `400` (non-UUID id) · `401` · `403` · `404` `NOT_FOUND` · `503`

### `POST /courier/deliveries/{parcelId}/status`
```json
{ "status": "PICKED_UP", "note": "Collected from front desk" }
```
```json
{ "status": "DELIVERED", "recipientName": "Nimal Perera", "note": "Left with the guard" }
```
```json
{ "status": "FAILED", "failureReason": "RECIPIENT_UNAVAILABLE", "note": "No answer, called twice" }
```
- `DELIVERED` needs `recipientName`. `FAILED` needs `failureReason`; `OTHER` also needs a `note`.
- `recipientName` only with `DELIVERED`; `failureReason` only with `FAILED`. `note` is optional otherwise.
- `200`: the updated parcel (same shape as `GET /courier/deliveries/{parcelId}`). Customers, merchants and the
  tracking page are told through the delivery module's `ParcelStatusChanged` event (`actorRole: COURIER`).
- `400` `VALIDATION_FAILED` · `401` · `403` · `404` `NOT_FOUND` · `503`
- `409` `INVALID_STATUS_TRANSITION`: not allowed from the parcel's current status (e.g. a skipped step, the button
  pressed twice, or the dispatcher changed it). The frontend reloads the parcel.
- Checked in this order: the request (400), the parcel is yours (404), the step is allowed (409).

Proof-of-delivery photos (upload and download) come in a later version.

### Validation rules (courier)

| Field | Rule | Error message |
|---|---|---|
| `status` | Required, one of the parcel statuses | Select the new status. |
| `note` | Optional, max 500 chars | Keep the note under 500 characters. |
| `note` (`failureReason: OTHER`) | Required, 3-500 chars | Describe what went wrong. |
| `recipientName` (`DELIVERED`) | Required, 2-100 chars | Enter the name of the person who received the parcel. |
| `recipientName` with another status | Not allowed | Only for delivered parcels. |
| `failureReason` (`FAILED`) | Required, one of the values above | Select why the delivery failed. |
| `failureReason` with another status | Not allowed | Only for failed deliveries. |
| `page` (history) | 0 or more | Use a page number of 0 or more. |
| `size` (history) | 1-100 | Use a page size from 1 to 100. |

An unknown `status` or `failureReason` value gives `400` with that field in `fieldErrors`.

## Dispatch (WP8, roles `DISPATCHER` and `ADMIN`)

A parcel passes through several **courier tasks** on its way (multi-branch workflow):

| Task type | For parcels that are | Branches |
|---|---|---|
| `PICKUP` | `CREATED`, sender in the destination branch's district | `destinationBranch` only |
| `TRANSFER` | `AT_BRANCH` at the origin branch | `originBranch` and `destinationBranch`, different |
| `DELIVERY` | `AT_BRANCH` at the origin branch, which is the recipient's district | `originBranch` only |

A task is `ASSIGNED` → `IN_PROGRESS` (the courier recorded the first step) → `COMPLETED`, or `CANCELLED` by the
dispatcher while still `ASSIGNED`. A parcel is in at most one open task. A parcel the courier couldn't take
(`NOT_COLLECTED`, `NOT_LOADED`) or brought back (`RETURNED`) goes back to the queue. Branches and districts use the
codes from `GET /meta/districts`.

### Queue parcel (response)

```json
{
  "parcelId": "uuid", "trackingNumber": "SD2611004817", "status": "AT_BRANCH",
  "from": { "city": "Colombo 03", "district": "COLOMBO" }, "to": { "city": "Kandy", "district": "KANDY" },
  "weightKg": 1.50, "currentBranch": "COLOMBO", "waitingSince": "2026-11-11T04:30:00Z",
  "suggestedType": "TRANSFER", "suggestedOrigin": "COLOMBO", "suggestedDestination": "KANDY"
}
```
No names, phone numbers or street addresses.

### Task (response)

```json
{
  "id": "uuid", "type": "PICKUP", "status": "ASSIGNED",
  "courier": { "id": "uuid", "fullName": "Nimal Silva", "phone": "+94771234567" },
  "originBranch": null, "destinationBranch": "COLOMBO", "scheduledFor": "2026-11-11", "note": "Call before you go",
  "createdAt": "…", "startedAt": null, "completedAt": null, "cancelledAt": null, "version": 0,
  "items": [{
    "parcelId": "uuid", "sequence": 1, "trackingNumber": "SD2611004817", "parcelStatus": "CREATED",
    "from": { "city": "Colombo 03", "district": "COLOMBO" }, "to": { "city": "Kandy", "district": "KANDY" },
    "outcome": "PENDING", "problem": null, "failureReason": null, "note": null, "receivedBy": null,
    "recipientName": null, "updatedAt": "…", "closedAt": null
  }]
}
```
In lists (`GET /dispatch/assignments`) a task has `parcelCount` and `openParcelCount` instead of `note` and `items`.
`outcome`: `PENDING`, `COLLECTED`, `LOADED`, `HANDED_OVER`, `DELIVERED`, `FAILED`, `RETURNED`, `NOT_COLLECTED`,
`NOT_LOADED`. `problem` (not collected / not loaded): `SENDER_UNAVAILABLE`, `PARCEL_NOT_READY`, `PARCEL_MISSING`,
`PARCEL_DAMAGED`, `ADDRESS_UNREACHABLE`, `OTHER`.

### Endpoints

| Method and path | What it does | Main errors |
|---|---|---|
| `GET /dispatch/queue?branch=COLOMBO&kind=PICKUP` | Parcels waiting for a task, oldest first. `kind`: `PICKUP` (new parcels picked up in the branch's district) or `AT_BRANCH` | 400 missing or unknown `branch` |
| `GET /dispatch/overview?branch=COLOMBO` | `{branch, waitingPickup, waitingAtBranch, assignedTasks, inProgressTasks, completedToday, issuesThisWeek, activeCouriers}` | 400 |
| `GET /dispatch/couriers?branch=` | Active couriers (blocked and removed left out) | — |
| `GET /dispatch/couriers/workload?branch=` | `[{courierId, fullName, phone, branch, vehicleType, openTasks, openParcels, completedToday}]`, least busy first | — |
| `GET /dispatch/issues?branch=COLOMBO&days=7` | Parcels couriers couldn't collect, load or deliver in the last `days` (1-30) days, newest first: `[{assignmentId, assignmentType, parcelId, trackingNumber, parcelStatus, outcome, problem, failureReason, note, courier, updatedAt, backInQueue}]` | 400 `days` |
| `POST /dispatch/assignments` | Create a task (201, task) | see below |
| `GET /dispatch/assignments?status=&type=&courierId=&branch=&date=2026-11-11&page=0&size=20` | Tasks, newest first (page of tasks). `branch` matches the origin or the destination | 400 `page`, `size` (1-100) |
| `GET /dispatch/assignments/{id}` | One task | 404 |
| `POST /dispatch/assignments/{id}/parcels` `{"parcelIds": [...]}` | Add parcels to an open task (task) | 409 `ASSIGNMENT_CLOSED`, `PARCEL_ALREADY_ASSIGNED`, `PARCEL_NOT_READY_FOR_TASK`; 400 `parcelIds` (already in the task, over 50) |
| `DELETE /dispatch/assignments/{id}/parcels/{parcelId}` | Take out a parcel the courier hasn't updated (task) | 409 `TASK_STEP_NOT_ALLOWED`, `ASSIGNMENT_NEEDS_PARCEL`, `ASSIGNMENT_CLOSED` |
| `POST /dispatch/assignments/{id}/reassign` `{"courierId": "uuid"}` | Give a task that hasn't started to another courier (task) | 409 `ASSIGNMENT_STARTED`, `COURIER_NOT_ASSIGNABLE` |
| `POST /dispatch/assignments/{id}/cancel` | Cancel a task that hasn't started; its parcels go back to the queue (task) | 409 `ASSIGNMENT_STARTED`, `ASSIGNMENT_CLOSED` |
| `POST /dispatch/parcels/{parcelId}/assign` `{"courierId": "uuid"}` | One step: a one-parcel task of the type the parcel needs next, for today. 201 `{parcelId, trackingNumber, status, assignedCourierId, assignedAt, assignmentId, assignmentType}` | 404; 409 `COURIER_NOT_ASSIGNABLE`, `PARCEL_NOT_ASSIGNABLE` (delivered), `PARCEL_NOT_READY_FOR_TASK`, `PARCEL_ALREADY_ASSIGNED` |

Any 409 on a task: show the message and reload the task or the queue.

### `POST /dispatch/assignments`

```json
{ "courierId": "uuid", "type": "TRANSFER", "originBranch": "COLOMBO", "destinationBranch": "KANDY",
  "scheduledFor": "2026-11-11", "note": "Fragile items on top", "parcelIds": ["uuid", "uuid"] }
```
Checked in this order: the body (400), the courier (404 unknown, 409 `COURIER_NOT_ASSIGNABLE`), the date and the
branches for the type (400), then each parcel (404 unknown, 409 `PARCEL_NOT_READY_FOR_TASK`,
`PARCEL_ALREADY_ASSIGNED`).

| Field | Rule | Error message |
|---|---|---|
| `courierId` | Required | Select a courier. |
| `type` | Required: `PICKUP`, `TRANSFER`, `DELIVERY` | Choose the type of task. |
| `originBranch` / `destinationBranch` | As the table at the top of this section | Choose where the courier hands over the parcels. / Choose the branch the parcels leave from. / … |
| `scheduledFor` | Optional, today (Sri Lanka) or later; default today | Choose today or a later date. |
| `note` | Optional, max 500 chars | Keep the note under 500 characters. |
| `parcelIds` | 1-50, each once | Select between 1 and 50 parcels. / Select each parcel only once. |

**Pickup queue.** Delivery has no "waiting for pickup" query, so dispatch keeps its own list from the
`ParcelStatusChanged` events (RabbitMQ queue `dispatch.q`). Parcels appear there only while the outbox relay runs
(`SWIFTDROP_OUTBOX_RELAY_ENABLED=true`), and parcels created before dispatch was deployed are not in it.
