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
| 401 | `API_KEY_REVOKED` | A request uses an `X-Api-Key` that was revoked |
| 403 | `FORBIDDEN` | Logged in, wrong role |
| 403 | `EMAIL_NOT_VERIFIED` | Login with the right password, but the email is not confirmed yet |
| 403 | `ACCOUNT_PENDING_APPROVAL` | Merchant not approved yet |
| 403 | `ACCOUNT_REJECTED` | Merchant application rejected |
| 403 | `ACCOUNT_DISABLED` | Account disabled by admin |
| 403 | `PASSWORD_CHANGE_REQUIRED` | A staff account with a temporary password calls any protected endpoint except the password-change endpoints |
| 403 | `MERCHANT_NOT_APPROVED` | A merchant whose account isn't approved (API key requests; creating a webhook) |
| 403 | `PROFILE_INCOMPLETE` | Google sign-up not finished: only `GET /auth/me`, `POST /auth/logout` and `POST /auth/complete-profile` work |
| 404 | `NOT_FOUND` | Resource not found |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method for this path |
| 409 | `EMAIL_ALREADY_REGISTERED` | Email already in use |
| 409 | `PHONE_ALREADY_REGISTERED` | Mobile number already in use |
| 409 | `PROFILE_ALREADY_COMPLETE` | `POST /auth/complete-profile` for an account that is not `PROFILE_INCOMPLETE` |
| 409 | `INVALID_STATUS_TRANSITION` | The parcel can't move to that status from its current one, e.g. DELIVERED → PICKED_UP or PICKED_UP → IN_TRANSIT (a picked-up parcel goes to a branch first). See [Parcel statuses](#parcel-statuses) |
| 409 | `PARCEL_NOT_ASSIGNABLE` | Assigning a courier to a parcel that was already picked up or is finished |
| 409 | `PARCEL_NEEDS_MANUAL_QUOTE` | Booking a parcel that no vehicle carries or fits (pricing's `MANUAL_QUOTE`) |
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
| 409 | `WEBHOOK_URL_IN_USE` | Webhooks: another of your active subscriptions already uses this callback URL |
| 409 | `WEBHOOK_CHANGED` | Webhooks: someone else changed the same subscription at the same moment (e.g. two tabs); nothing was saved |
| 409 | `ACCOUNT_REMOVED` | An admin action or password action on a removed account |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Request body is not `application/json` |
| 429 | `TOO_MANY_REQUESTS` | Too many requests from this IP address (or, for webhook tests and retries, from this merchant); the `Retry-After` header says how many seconds to wait |
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
| `PARCEL_NEEDS_MANUAL_QUOTE` | This parcel is too big or too heavy for our vehicles. Contact us for a quote. |
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
| `WEBHOOK_URL_IN_USE` | You already have a webhook for this address. Add the events to it instead. |
| `WEBHOOK_CHANGED` | This webhook was just changed somewhere else. Refresh and try again. |
| `CANNOT_CHANGE_OWN_ACCOUNT` (409) | You can't block your own account. |
| `ACCOUNT_NOT_ACTIVE` (409) | Only active accounts can be blocked. Refresh to see the account's latest status. |
| `ACCOUNT_NOT_BLOCKED` (409) | Only blocked accounts can be unblocked. Refresh to see the account's latest status. |
| `PASSWORD_CHANGE_REQUIRED` | Change your temporary password to continue. |
| `API_KEY_REVOKED` | This API key has been revoked. |
| `ACCOUNT_REMOVED` | This account has been permanently removed and cannot be modified. |
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
- After either decision is saved, the merchant is emailed it ("Your SwiftDrop merchant account is approved" /
  "Your SwiftDrop merchant application", with the business name). The email goes out in the background and never
  changes the response; a mail problem doesn't undo the decision. The demo merchant seeder sends no email.

### Users (admin)
Every account (customers, merchants, couriers, dispatchers, admins). Role `ADMIN` only: no login →
`401 UNAUTHENTICATED`; any other role → `403 FORBIDDEN`. Responses never contain passwords, hashes, tokens or the
Google account id.

#### `GET /admin/users/stats`
```json
{ "total": 1250, "active": 1100, "blocked": 100, "pending": 50, "newLast30Days": 50 }
```
Removed accounts are never counted. `blocked` = status `DISABLED`; `pending` = `PENDING_EMAIL_VERIFICATION`,
`PENDING_APPROVAL` or `PROFILE_INCOMPLETE`; `newLast30Days` = created in the last 30 days.

#### `GET /admin/users?q=&role=&status=&registeredFrom=&registeredTo=&sort=NEWEST&page=0&size=20`
All parameters are optional. A page of accounts (`{content, page, size, totalElements, totalPages}`, page 0-based):
```json
{ "content": [ { "id": "...", "fullName": "Kasun Perera", "email": "kasun@example.com", "phone": "+94771234567",
                 "role": "CUSTOMER", "status": "ACTIVE", "createdAt": "2026-11-10T04:10:00Z" } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
```
- `q`: part of the name, email or phone (`0771234567` and `+94771234567` both work), or a full user id.
- `role`: `CUSTOMER` | `MERCHANT` | `COURIER` | `DISPATCHER` | `ADMIN`.
- `status`: `ACTIVE` | `BLOCKED` (`DISABLED`) | `PENDING` (the three pending statuses above) | `REJECTED` |
  `REMOVED`. Without `status`, removed accounts are left out.
- `registeredFrom`, `registeredTo`: `2026-11-01` (Sri Lanka days, both included).
- `sort`: `NEWEST` (default) | `OLDEST` | `NAME` (A–Z). `size` 1–100.
- `400 VALIDATION_FAILED`: unknown `role`/`status`/`sort`, a bad date, or `registeredFrom` after `registeredTo`
  (field `registeredFrom`).
- `phone` is `null` only while a Google sign-up is not finished.

#### `GET /admin/users/{id}`
```json
{ "id": "...", "fullName": "Kasun Perera", "email": "kasun@example.com", "phone": "+94771234567",
  "role": "MERCHANT", "status": "ACTIVE", "mustChangePassword": false, "googleLinked": false,
  "branch": null, "vehicleType": null,
  "merchant": { "businessName": "Lanka Crafts", "pickupDistrict": "COLOMBO", "city": "Nugegoda",
                "approvalStatus": "APPROVED" },
  "createdAt": "2026-11-10T04:10:00Z", "updatedAt": "2026-11-12T08:00:00Z", "deletedAt": null }
```
`branch` for couriers and dispatchers, `vehicleType` for couriers, `merchant` for merchants (otherwise `null`).
Removed accounts can be viewed. Unknown id → `404 NOT_FOUND`.

#### `PUT /admin/users/{id}` `{ "fullName": "...", "phone": "0771234567", "email": "..." }`
Edits the name, phone and email (same rules as sign-up). Role, status, password, branch and vehicle cannot be changed
here. A new email ends the user's sessions and unlinks Google sign-in. Returns the account as in `GET`.
`400 VALIDATION_FAILED` · `404 NOT_FOUND` · `409 EMAIL_ALREADY_REGISTERED` / `PHONE_ALREADY_REGISTERED` (another
account has it) · `409 ACCOUNT_REMOVED`.

#### `PATCH /admin/users/{id}/status` `{ "status": "BLOCKED" }` or `{ "status": "ACTIVE" }`
- `BLOCKED`: an `ACTIVE` account becomes `DISABLED`; the user is logged out at once and cannot log in.
- `ACTIVE`: a `DISABLED` account becomes `ACTIVE` again.
- Asking for the status the account already has changes nothing (`200`). Returns the account as in `GET`.
- Only `ACTIVE` accounts can be blocked (pending merchants are approved or rejected on `/admin/merchants`), so
  unblocking never skips email verification or merchant approval.
- Who made the change and when is written to the server log (ids only).
- `400 VALIDATION_FAILED` (field `status`) · `404 NOT_FOUND` · `409 CANNOT_CHANGE_OWN_ACCOUNT` (an admin's own
  account) · `409 ACCOUNT_REMOVED` · `409 ACCOUNT_NOT_ACTIVE` (blocking an account that isn't `ACTIVE`) ·
  `409 ACCOUNT_NOT_BLOCKED` (unblocking an account that isn't blocked).

### Parcels (admin, WP4)
Every parcel on the platform, read only. Role `ADMIN` only: no login → `401 UNAUTHENTICATED`; any other role →
`403 FORBIDDEN`. Statuses are those of "Parcel statuses" below (there is no cancelled status).
Admins monitor parcels but do not assign or reassign couriers: that is the dispatchers' job (Dispatch, WP8), so
there is no assign or reassign endpoint here.

#### `GET /admin/parcels/stats`
```json
{ "total": 2450, "pending": 180, "inTransit": 320, "delivered": 1800, "failed": 150,
  "byStatus": { "CREATED": 180, "PICKED_UP": 40, "AT_BRANCH": 120, "IN_TRANSIT": 100, "OUT_FOR_DELIVERY": 60,
                "DELIVERED": 1800, "FAILED": 150 } }
```
`pending` = `CREATED`; `inTransit` = `PICKED_UP` + `AT_BRANCH` + `IN_TRANSIT` + `OUT_FOR_DELIVERY`.

#### `GET /admin/parcels?q=&status=&pickupDistrict=&deliveryDistrict=&createdFrom=&createdTo=&payment=&sort=NEWEST&page=0&size=20`
All parameters are optional. A page of parcels (`{content, page, size, totalElements, totalPages}`, page 0-based):
```json
{ "content": [ {
    "id": "...", "trackingNumber": "SD2611004817", "status": "IN_TRANSIT",
    "senderType": "CUSTOMER", "senderName": "Saman Perera", "receiverName": "Nimal Silva",
    "pickupCity": "Colombo 05", "pickupDistrict": "COLOMBO", "deliveryCity": "Kandy", "deliveryDistrict": "KANDY",
    "currentBranch": null, "weightKg": 2.50, "feeAmount": 450.00, "currency": "LKR", "codAmount": null,
    "createdAt": "2026-11-12T04:10:00Z", "updatedAt": "2026-11-13T06:00:00Z" } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
```
- `q`: part of the tracking number (spaces ignored), the sender's or receiver's name or phone (`0771234567` or
  `+94771234567`), or the merchant's order ref; a full parcel id finds exactly that parcel.
- `status`: one or more parcel statuses, repeated (`status=PICKED_UP&status=AT_BRANCH`).
- `pickupDistrict`, `deliveryDistrict`: district codes from `GET /meta/districts`.
- `createdFrom`, `createdTo`: booking days `2026-11-01` (Sri Lanka days, both included).
- `payment`: `PREPAID` (nothing to collect) | `CASH_ON_DELIVERY`.
- `sort`: `NEWEST` (default) | `OLDEST` | `FEE_HIGH` | `FEE_LOW`. `size` 1–100.
- `senderType`: `CUSTOMER` or `MERCHANT`; `senderName` is the pickup contact. `codAmount` is `null` when already paid;
  `currentBranch` is set only while `AT_BRANCH`. Rows have no phones or street addresses (see the details).
- `400 VALIDATION_FAILED`: an unknown `status`/district/`payment`/`sort`, a bad date, or `createdFrom` after
  `createdTo` (field `createdFrom`).
- The assigned courier is not in the list yet: courier tasks are dispatch's (WP8).

#### `GET /admin/parcels/{id}`
```json
{ "id": "...", "trackingNumber": "SD2611004817", "status": "IN_TRANSIT",
  "sender": { "type": "MERCHANT", "accountId": "...", "accountName": "Nimal Perera",
              "accountEmail": "nimal@example.com", "businessName": "Lanka Crafts" },
  "pickup":    { "name": "Saman Perera", "phone": "+94771234567", "addressLine": "12 Galle Road",
                 "city": "Colombo 05", "district": "COLOMBO" },
  "recipient": { "name": "Nimal Silva", "phone": "+94711234567", "addressLine": "45 Peradeniya Road",
                 "city": "Kandy", "district": "KANDY" },
  "weightKg": 2.50, "codAmount": null, "instructions": "Call before coming",
  "feeAmount": 450.00, "currency": "LKR", "merchantOrderRef": "ORD-1001", "currentBranch": null,
  "failureReason": null, "proofRecipientName": null, "hasProofPhoto": false,
  "createdAt": "2026-11-12T04:10:00Z", "updatedAt": "2026-11-13T06:00:00Z",
  "history": [ { "status": "IN_TRANSIT", "occurredAt": "2026-11-13T06:00:00Z", "actorRole": "COURIER",
                 "branch": null, "note": null, "failureReason": null } ] }
```
`sender.businessName` only for merchants; `accountName`/`accountEmail` are `null` if the account no longer exists.
`history` is newest first. Unknown id → `404 NOT_FOUND`.

#### `GET /admin/parcels/{id}/tracking`
The `history` list above on its own, newest first. Unknown id → `404 NOT_FOUND`.

#### `GET /admin/parcels/export` (same filters as the list, without `page`/`size`)
`200 text/csv; charset=UTF-8`, downloaded as `swiftdrop-parcels-<date>.csv`: one row per parcel in the list's order,
at most 5,000 rows (narrow the filters for more). Columns: tracking number, parcel id, status, sender type, sender
name/phone/address/city/district, receiver name/phone/address/city/district, weight, fee, currency, cash on delivery,
created and last updated (UTC). It holds personal data: keep the file private. Values a spreadsheet would treat as a
formula start with `'`.

### Reports (admin, WP4)
Role `ADMIN` only: no login → `401 UNAUTHENTICATED`; any other role → `403 FORBIDDEN`.

#### `GET /admin/reports/overview`
The figures on the admin home page (`/admin`). Days are Sri Lanka days (Asia/Colombo).
```json
{
  "users": { "total": 120, "customers": 90, "merchants": 20, "couriers": 6, "dispatchers": 3, "admins": 1,
             "pendingMerchants": 4 },
  "today": { "date": "2026-11-13", "created": 12, "delivered": 8, "failed": 1 },
  "last7Days": {
    "from": "2026-11-07", "to": "2026-11-13",
    "created": 60, "delivered": 40, "failed": 5, "successRate": 88.9,
    "perDay": [ { "date": "2026-11-07", "created": 5, "delivered": 3, "failed": 0 } ]
  },
  "recentUsers": [
    { "id": "...", "fullName": "Ayesha Fernando", "role": "CUSTOMER", "status": "ACTIVE",
      "createdAt": "2026-11-13T04:10:00Z" }
  ]
}
```
- `users`: accounts per role, every status except `REMOVED`. `pendingMerchants` = merchants with status
  `PENDING_APPROVAL` (the ones on `GET /admin/merchants?status=PENDING`).
- `today` and `perDay`: `created` = parcels booked that day; `delivered` / `failed` = parcels that reached that status
  that day. `perDay` has all 7 days, oldest first, also days with nothing (all 0). `today` is the last day of `perDay`.
- `successRate` = `delivered / (delivered + failed) × 100` over the 7 days, one decimal; `0.0` when both are 0.
- `recentUsers`: the 5 newest accounts (not `REMOVED`), newest first. No email or phone.

#### `GET /admin/reports/summary?from=2026-10-22&to=2026-11-20`
The Reports & Analytics page (`/admin/reports`). Read only. `from` and `to` are Sri Lanka days, both included.
```json
{
  "totals":   { "from": "2026-10-22", "to": "2026-11-20", "created": 240, "delivered": 180, "failed": 12,
                "successRate": 93.8 },
  "previous": { "from": "2026-09-22", "to": "2026-10-21", "created": 210, "delivered": 160, "failed": 15,
                "successRate": 91.4 },
  "perDay": [ { "date": "2026-10-22", "created": 8, "delivered": 6, "failed": 0 } ],
  "byStatus": { "CREATED": 14, "PICKED_UP": 3, "AT_BRANCH": 9, "IN_TRANSIT": 5, "OUT_FOR_DELIVERY": 4,
                "DELIVERED": 410, "FAILED": 22 }
}
```
- `totals`: the range. `created` = parcels booked in it; `delivered` / `failed` = parcels that reached that status in
  it. `successRate` = `delivered / (delivered + failed) × 100`, one decimal; `0.0` when both are 0.
- `previous`: the same number of days just before `from`, to compare with.
- `perDay`: every day of the range, oldest first, also days with nothing (all 0).
- `byStatus`: how many parcels are in each status **right now** (all 7 statuses, 0 for none), not limited to the range.
- Both parameters required, `YYYY-MM-DD`; missing or not a date → `400 VALIDATION_FAILED`. Field errors (`400
  VALIDATION_FAILED`): `from` "The start date must be on or before the end date." · `to` "The end date can't be in
  the future." · `to` "Choose a range of up to 366 days."

#### `GET /admin/reports/summary/export?from=&to=`
The same range as CSV (`text/csv; charset=UTF-8`, download `swiftdrop-report-<from>-to-<to>.csv`): header
`date,booked,delivered,failed`, one row per day (oldest first), then `total,...`. Same parameters and errors as the
summary.

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
require a logged-in `MERCHANT` cookie (`SD_TOKEN`) of an approved merchant; API keys can't manage webhooks (yet):
a request authenticated with an `X-Api-Key` header gets `403 FORBIDDEN`.
The admin endpoints require `ADMIN`.

### One subscription per destination

A subscription is one destination: a callback URL, its signing secret and the events that destination needs.
Use one subscription per server or system and select all the events it handles; your endpoint tells them apart by
`type` (or the `X-SwiftDrop-Event-Type` header). You don't need a separate subscription for each status: change the
events of an existing one with `PATCH`. Use several subscriptions only for different destinations, for example:

| Callback URL | Events |
|---|---|
| `https://shop.example.com/webhooks` | All parcel events |
| `https://shop.example.com/billing-webhook` | `parcel.delivered` |
| `https://shop.example.com/support-webhook` | `parcel.failed` |

Each event is sent once to every active subscription that selected it, so two subscriptions that both select an
event both receive it.

A callback URL can be used by only one of your active subscriptions (`409 WEBHOOK_URL_IN_USE` when creating one,
changing the URL of an active one, or turning one on); turned-off subscriptions don't count, and other merchants may
use the same URL.
Two URLs are the same when they differ only in the letter case of the scheme or host, a default port written out
(`:443`), an empty path vs `/`, or a `#fragment`. A different path (including a trailing slash), query, port or scheme
is a different URL: `https://shop.example.com/hook` and `https://shop.example.com/hook/` can both be active.

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
  "trackingNumber": "SD2611004817", "merchantOrderRef": "TECH-ORD-1001",
  "status": "PENDING", "attempts": 1, "lastStatusCode": 500, "lastError": "HTTP_500",
  "nextAttemptAt": "2026-11-13T09:16:00Z", "createdAt": "2026-11-13T09:15:00Z", "deliveredAt": null
}
```
- `trackingNumber` and `merchantOrderRef`: the parcel the event is about; both `null` for a `webhook.ping`, and
  `merchantOrderRef` is `null` when the parcel has none.
- `status`: `PENDING` (waiting for the next attempt), `SUCCEEDED`, `FAILED` (no more automatic attempts).
- `lastError`: `null`, `HTTP_<code>`, `TIMEOUT`, `CONNECTION_FAILED`, `URL_NOT_ALLOWED` (the URL resolved to a
  private address or couldn't be resolved; nothing was sent) or `SUBSCRIPTION_INACTIVE` (the subscription was turned
  off before it was sent; not counted as an attempt). The merchant's response body is never stored.
- `nextAttemptAt` is `null` unless `status` is `PENDING`.

### Endpoints

| Method and path | What it does | Main errors |
|---|---|---|
| `POST /merchant/webhooks` `{"url", "eventTypes": [...]}` | Create an active subscription (201, subscription + `secret`) | 400 `url`, `eventTypes`; 403 `MERCHANT_NOT_APPROVED`; 409 `WEBHOOK_LIMIT_REACHED`, `WEBHOOK_URL_IN_USE` |
| `GET /merchant/webhooks` | Your subscriptions, active and turned off, newest first (array of subscriptions) | — |
| `PATCH /merchant/webhooks/{id}` `{"url"?, "eventTypes"?, "active"?}` | Change only the fields sent (subscription) | 400; 404; 409 `WEBHOOK_LIMIT_REACHED` (turning one on), `WEBHOOK_URL_IN_USE` (new URL or turning one on), `WEBHOOK_CHANGED` |
| `DELETE /merchant/webhooks/{id}` | Turn off (`active: false`; history is kept). 204. Idempotent, also when two requests turn it off at once | 404; 409 `WEBHOOK_CHANGED` (someone turned it back on at that moment) |
| `POST /merchant/webhooks/{id}/rotate-secret` | New secret; the old one stops working at once, also for retries (200, subscription + `secret`). If two rotations clash, the loser gets 409 and no secret | 404; 409 `WEBHOOK_CHANGED` |
| `POST /merchant/webhooks/{id}/test` | Queue a `webhook.ping` for this subscription, sent within seconds and tried once (202, delivery). Also works while it is turned off, so a URL can be checked before turning it on. If a ping for it is still waiting to be sent, that one is returned (202) instead of queuing another. A new ping only 10 seconds after the previous one (see "Limits") | 404; 429 `TOO_MANY_REQUESTS` |
| `GET /merchant/webhooks/{id}/deliveries?status=&eventType=&search=&from=&to=&page=0&size=20` | Delivery history of one subscription, newest first (page of deliveries). Optional filters: `status`, `eventType`, `search`, `from`, `to` (see "Searching deliveries") | 400 `status`, `eventType`, `search`, `from`, `to`, `page`, `size` (1-100); 404 |
| `GET /merchant/webhooks/deliveries?status=&eventType=&search=&from=&to=&page=0&size=20` | All your deliveries, from every subscription (active or turned off), newest first (page of deliveries; `subscriptionId` says which one). Same optional filters. `search=<parcel id>` gives every message sent about that parcel, e.g. for an order's details. Only your own deliveries are ever searched | 400 `status`, `eventType`, `search`, `from`, `to`, `page`, `size` (1-100) |
| `POST /merchant/webhooks/deliveries/{id}/retry` | Send a `FAILED` delivery again, with a new set of attempts (one for a test ping): `PENDING`, `attempts: 0`, due now; `lastStatusCode`/`lastError` keep the old values until the next attempt (delivery). A parcel delivery of a turned-off subscription fails again with `SUBSCRIPTION_INACTIVE`: turn the subscription on first. Only 10 seconds after the delivery's last attempt (see "Limits") | 404; 409 `WEBHOOK_DELIVERY_NOT_RETRYABLE`; 429 `TOO_MANY_REQUESTS` |
| `GET /admin/webhooks/deliveries?status=FAILED&merchantId=&eventType=&search=&from=&to=&page=0&size=20` | ADMIN: every merchant's deliveries, newest first (page of deliveries, each with `merchantId`). Optional filters: `status`, `merchantId`, `eventType`, `search`, `from`, `to` | 400 `status`, `eventType`, `search`, `from`, `to`, `page`, `size` |
| `POST /admin/webhooks/deliveries/{id}/retry` | ADMIN: same as the merchant retry | 404; 409 `WEBHOOK_DELIVERY_NOT_RETRYABLE` |

Another merchant's subscription or delivery answers 404, never 403. A merchant has at most 5 active subscriptions.
Delivery lists are newest first (`createdAt`, then `id`, both descending) and never include the payload.

**Limits.** "Send test" and "Retry" make SwiftDrop send a real request to the merchant's URL, so they are limited
(over a limit: `429 TOO_MANY_REQUESTS`, with `Retry-After: <seconds>`; show "Try again in N seconds"):
- **Send test:** a new ping for a subscription only 10 seconds after its previous ping was queued. While a ping is
  still waiting, the same one is returned (202), as above.
- **Retry:** a `FAILED` delivery only 10 seconds after its last attempt. An older failure can be retried at once. A
  delivery that isn't `FAILED` is still `409 WEBHOOK_DELIVERY_NOT_RETRYABLE`.
- **Per merchant:** at most 30 tests and retries a minute, all subscriptions together.
- Admin retries are not limited. The numbers are settings: `SWIFTDROP_WEBHOOKS_COOLDOWN_SECONDS` (default 10, 0 turns
  the cooldown off) and `SWIFTDROP_WEBHOOKS_ACTIONS_PER_MINUTE` (default 30).

**Searching deliveries.** All filters are optional and combine (AND).
- `search`: a delivery id, event id or parcel id (exact; a test ping is found only by its own id), or else a tracking
  number (exact, ignoring spaces and letter case: `sd 2611 0048 17` finds `SD2611004817`) or part of your order
  number (ignoring case: `ord-1001` finds `TECH-ORD-1001`). A merchant only ever searches their own deliveries.
- `from` / `to`: days written like `2026-11-15`, in Sri Lankan time, both included (`to=2026-11-15` includes
  everything created on 15 Nov). Either may be left out.

### Validation rules (webhooks)

| Field | Rule | `fieldErrors` message |
|---|---|---|
| `url` | Required, at most 2048 characters, an absolute `https://` URL | Enter a full https:// address, like https://shop.example.com/webhooks |
| `url` | No user name or password in it, and the host must not be (or resolve to) a private, loopback or link-local address | This address can't receive webhooks. Use a public https:// address. |
| `eventTypes` | Required, 1-7 distinct parcel event types from the table above (`webhook.ping` isn't one) | Choose at least one event (empty); Choose at most 7 events; `'parcel.lost' isn't a webhook event` (unknown name) |
| `status` (query) | `PENDING`, `SUCCEEDED` or `FAILED` | — |
| `eventType` (query) | Optional: one event type from the table above, or `webhook.ping` (test pings). Empty means all | `'parcel.lost' isn't a webhook event` (unknown name) |
| `search` (query) | Optional, at most 64 characters | Search with at most 64 characters. |
| `from`, `to` (query) | Optional days like `2026-11-15`; `to` not before `from` | Enter a date like 2026-11-15. (`from` or `to`); The end date can't be before the start date. (`to`) |

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

The HMAC key is the whole secret, including the `whsec_` prefix, as UTF-8 bytes.
To verify a request: compute the HMAC over the timestamp header, a `.` and the **raw** request body (before any JSON
parsing), compare it with the signature in constant time, and reject timestamps more than 5 minutes old.

### Delivery and retries

- A 2xx answer within 10 seconds counts as delivered. Redirects are not followed.
- 408, 429, 5xx, timeouts, connection errors and `URL_NOT_ALLOWED` are retried: right away, then after 1 min, 5 min,
  15 min, 1 h and 6 h (6 attempts in all).
- Any other 4xx or a 3xx, or the 6th failed attempt, makes the delivery `FAILED`. The merchant or an admin can retry
  it.
- A test ping (`webhook.ping`) is tried once: if that attempt fails for any reason, it is `FAILED` at once with its
  `lastStatusCode`/`lastError` (no automatic retries). Send another test, or retry it (one more attempt).
- Delivery is at-least-once and not ordered: use `id` and `sequence`, as above.
- A change to a subscription (URL, event types, turned off) applies to new events. A turned-off subscription gets no
  new parcel events, and its `PENDING` parcel deliveries are not sent: they become `FAILED` with `lastError`
  `SUBSCRIPTION_INACTIVE` (a test ping is still sent).

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
  "instructions": "Call before coming",
  "lengthCm": 40,
  "widthCm": 30,
  "heightCm": 12,
  "keepUpright": false,
  "paymentMethod": "AT_PICKUP"
}
```
There is no customer id and no fee: the customer comes from the login, and the server sets the fee (with pricing,
the same quote as `POST /pricing/estimate`) and the tracking number.

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
| `lengthCm`, `widthCm`, `heightCm` | Required: packed outside measurements, whole cm 1–300 | "Enter the length." / "Enter the width." / "Enter the height." / "Use whole centimetres from 1 to 300." |
| `keepUpright` | Optional, default `false`: the parcel may not lie on its side | |
| `paymentMethod` | Required: how the delivery fee is paid. `ONLINE` (paid online), `AT_PICKUP` (the sender pays the courier in cash at pickup) or `AT_DROP_OFF` (the recipient pays the courier in cash at delivery, with any cash on delivery). Fixed once booked | "Choose how the delivery fee will be paid." (missing or an unknown value) |

The payment method only says how the fee will be paid, so the courier knows whether to collect it and from whom. It
is not the cash on delivery (`codAmount`, the price of the goods), and taking an online payment is not part of this
endpoint.

Response `201`: a Delivery.
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
  "feeAmount": 522.60,
  "currency": "LKR",
  "createdAt": "2026-11-07T04:30:00Z",
  "updatedAt": "2026-11-07T04:30:00Z",
  "currentBranch": null,
  "failureReason": null,
  "proof": null,
  "size": { "lengthCm": 40, "widthCm": 30, "heightCm": 12, "keepUpright": false },
  "vehicle": "BIKE",
  "billableWeightKg": 3,
  "paymentMethod": "AT_PICKUP"
}
```
- `feeAmount` is pricing's quote for the route, weight, size and cash on delivery (see "Pricing (WP4)"). The parcel
  keeps the vehicle, billable weight, rate card version and fee lines. `size`, `vehicle` and `billableWeightKg` are
  `null` for parcels booked before sizes.
- `paymentMethod`: how the fee is paid, as booked; `null` for parcels booked before payment methods.
- `currentBranch`: set only while `AT_BRANCH` (a district, e.g. `GAMPAHA`). `failureReason`: only while `FAILED`.
  `proof`: `{ "recipientName": "Kamala Silva", "hasPhoto": false }` once `DELIVERED`. Otherwise all three are `null`.
- `400` `VALIDATION_FAILED` (keys like `pickup.phone`, `weightKg`, `lengthCm`, `paymentMethod`) · `401` `UNAUTHENTICATED` · `403`
  `FORBIDDEN` (logged in, but not a `CUSTOMER`) · `409` `PARCEL_NEEDS_MANUAL_QUOTE` (no vehicle carries or fits it)

### My deliveries
`GET /deliveries?page=0&size=20&status=AT_BRANCH` (logged-in `CUSTOMER`)

The customer's own deliveries, newest first, as a page of Delivery (the shape above):
```json
{ "content": [ /* Delivery */ ], "page": 0, "size": 20, "totalElements": 3, "totalPages": 1 }
```
- `page` from 0 (default 0); `size` 1-100 (default 20); `status` optional, one of the parcel statuses.
- `200` · `400` `VALIDATION_FAILED` (`fieldErrors.page`: "Use a page number of 0 or more.", `fieldErrors.size`:
  "Use a page size from 1 to 100."; an unknown `status` too) · `401` · `403` (not a `CUSTOMER`)

### One delivery with its history
`GET /deliveries/{id}` (logged-in `CUSTOMER`)
```json
{
  "delivery": { /* Delivery */ },
  "history": [
    { "status": "AT_BRANCH", "branch": "GAMPAHA", "note": null, "failureReason": null,
      "occurredAt": "2026-11-08T09:15:00Z" },
    { "status": "PICKED_UP", "branch": null, "note": "Collected from front desk", "failureReason": null,
      "occurredAt": "2026-11-08T07:40:00Z" },
    { "status": "CREATED", "branch": null, "note": null, "failureReason": null,
      "occurredAt": "2026-11-07T04:30:00Z" }
  ]
}
```
- `history`: newest first. `branch` only on `AT_BRANCH` entries, `failureReason` only on `FAILED` entries.
- `200` · `400` (`id` is not a UUID) · `401` · `403` (not a `CUSTOMER`) · `404` `NOT_FOUND`: no such delivery, or it
  isn't yours (the same answer, so ids can't be probed).

### Receipt of a delivery
`GET /deliveries/{id}/receipt` (logged-in `CUSTOMER`): the receipt as JSON, for the preview on "My deliveries".
```json
{
  "trackingNumber": "SD2611003152",
  "status": "DELIVERED",
  "bookedAt": "2026-11-09T05:12:00Z",
  "pickup": { "name": "Nimali Perera", "phone": "+94771234567", "addressLine": "24 Temple Road",
              "city": "Gampaha", "district": "GAMPAHA" },
  "recipient": { "name": "Kamal Silva", "phone": "+94719876543", "addressLine": "118 Galle Road",
                 "city": "Colombo 03", "district": "COLOMBO" },
  "weightKg": 2.00,
  "billableWeightKg": 2,
  "size": { "lengthCm": 30, "widthCm": 20, "heightCm": 15, "keepUpright": false },
  "vehicle": "BIKE",
  "lines": [
    { "component": "BASE", "label": "Base fee", "amount": 650.00 },
    { "component": "WEIGHT", "label": "Extra weight (1 kg)", "amount": 330.00 }
  ],
  "total": 980.00,
  "currency": "LKR",
  "codAmount": null,
  "paymentMethod": "AT_PICKUP",
  "paymentStatus": "PAID"
}
```
- The fee is the one fixed at booking (the parcel's stored fee lines), never a new quote, so a receipt doesn't change
  when the rate card does. `lines` add up to `total`; a parcel booked before pricing has one line
  `{ "component": "FEE", "label": "Delivery fee" }`. `label` is the text to show: "Base fee", "Extra weight (N kg)",
  "Pickup", "Drop-off", "Between branches", "Cash on delivery handling".
- `billableWeightKg`, `size` and `vehicle` are null for parcels booked before sizes; `paymentMethod` for parcels booked
  before payment methods. `codAmount` is the cash collected from the recipient for the sender, not part of the fee.
- `paymentStatus`: `PAID` once the courier has collected the fee (`AT_PICKUP`: from `PICKED_UP` on; `AT_DROP_OFF`:
  once `DELIVERED`), otherwise `DUE`. `null` for `ONLINE` (online payments aren't recorded yet) and without a method.
- `200` · `400` (`id` is not a UUID) · `401` · `403` (not a `CUSTOMER`) · `404` `NOT_FOUND` (no such delivery, or it
  isn't yours).

`GET /deliveries/{id}/receipt/pdf` (logged-in `CUSTOMER`): the same receipt as a one-page A4 PDF to download.
- `200` `application/pdf` with `Content-Disposition: attachment; filename="SwiftDrop-receipt-SD2611003152.pdf"` and
  `Cache-Control: no-store` (it holds personal data). Dates in Sri Lankan time; amounts "Rs. 1,250.00".
- The frontend links to it directly (the browser sends the `SD_TOKEN` cookie), so the download needs no JavaScript.
- Errors as above, as the usual JSON error body.

---

## Pricing (WP4)

### Estimate a delivery fee
`POST /pricing/estimate` (logged-in `CUSTOMER` or `MERCHANT`). Nothing is saved.

A parcel goes pickup address → pickup district branch → drop-off district branch → drop-off address. Only the two
local legs use a vehicle; between the branches SwiftDrop's own transport carries it, whatever its size.

**Estimated fee = weight charge + pickup leg + delivery leg + linehaul**, from the active rate card (version 5):
- No base fee: the pickup and delivery legs pay for collecting and handing over the parcel (rate cards 1–4 had one,
  LKR 150 in version 4).
- Billable weight = the greater of the actual and the volumetric weight (L × W × H ÷ 5000), rounded up to a whole kg.
  The first kg is included; weight charge = LKR 50 × billable kg above the first.
- Vehicle = the first of Bike → Tuk-tuk → Lorry that carries the actual weight and fits the parcel (any orientation;
  with `keepUpright` only length and width may swap). Each local leg costs the vehicle's fixed leg fee (Bike 150,
  Tuk-tuk 300, Lorry 1,500), with no distance charge.
- Linehaul = LKR 1.00 × the distance between the branches: the straight line between the district centres × 1.3
  (0 inside one district).
- No cash on delivery handling fee: `cashOnDelivery` is still accepted but adds nothing (rate cards 1 and 2 added
  LKR 100). The COD amount itself is never part of the fee.

Request:
```json
{ "pickupDistrict": "COLOMBO", "dropOffDistrict": "KANDY", "weightKg": 1.8,
  "lengthCm": 40, "widthCm": 30, "heightCm": 12, "keepUpright": false, "cashOnDelivery": false }
```

| Field | Rule | Message |
|---|---|---|
| `pickupDistrict`, `dropOffDistrict` | Required, one of `GET /meta/districts` | "Select the pickup district." / "Select the drop-off district." |
| `weightKg` | 0.01–50, up to 2 decimals (the booking's rule) | Same messages as `POST /deliveries` |
| `lengthCm`, `widthCm`, `heightCm` | Optional, whole cm 1–300; all three or none | "Use whole centimetres from 1 to 300." / "Enter all three measurements, or leave them all empty." (on the first empty one) |
| `keepUpright`, `cashOnDelivery` | Optional, default `false` | |

Response `200`:
```json
{
  "status": "COMPLETE",
  "total": 522.60,
  "currency": "LKR",
  "rateCardVersion": 5,
  "billableWeightKg": 3,
  "volumetricWeightKg": 2.88,
  "vehicle": "BIKE",
  "distanceKm": 122.6,
  "lines": [
    { "component": "WEIGHT", "amount": 100.00 },
    { "component": "PICKUP", "amount": 150.00 },
    { "component": "DELIVERY", "amount": 150.00 },
    { "component": "LINEHAUL", "amount": 122.60 }
  ]
}
```
- `distanceKm`: between the two branches. `lines`: `BASE` when the rate card has a base fee (none since version 5),
  `WEIGHT`, `PICKUP`, `DELIVERY`, `LINEHAUL`, then `COD` when `cashOnDelivery` is true and the rate card has a COD fee
  (none since version 3).
- `status`: `COMPLETE`; `PROVISIONAL` without measurements (no vehicle, so no `PICKUP` and `DELIVERY` lines;
  `vehicle` and `volumetricWeightKg` are `null`); `MANUAL_QUOTE` when no vehicle takes the parcel for the local legs
  (`total` is `null`).
- `400` `VALIDATION_FAILED` · `401` `UNAUTHENTICATED` · `403` `FORBIDDEN` (not a `CUSTOMER` or `MERCHANT`)

### Zones & pricing (admin, WP4)
The admin's Zones & Pricing page (`/admin/zones`). Role `ADMIN` only: no login → `401 UNAUTHENTICATED`; any other
role → `403 FORBIDDEN`. **Read only**: nothing is saved, and rate cards and district centres change only through
Flyway migrations. Every figure comes from the active rate card and the same distance rule as a quote, so it is
what a booking on that route is charged.

#### `GET /admin/pricing/rate-card`
The active rate card with its vehicles, smallest first.
```json
{ "version": 5, "active": true, "currency": "LKR", "baseFee": 0.00, "baseWeightKg": 1, "perKgFee": 50.00,
  "volumetricDivisor": 5000, "codFee": 0.00, "linehaulPerKm": 1.00, "roadFactor": 1.30,
  "createdAt": "2026-11-14T05:55:00Z",
  "vehicles": [ { "vehicle": "BIKE", "checkOrder": 1, "maxLengthCm": 45, "maxWidthCm": 35, "maxHeightCm": 35,
                  "maxWeightKg": 10.00, "legFee": 150.00 } ] }
```

#### `GET /admin/pricing/rate-cards`
Every rate card version, **newest first**, each in the same shape as `GET /admin/pricing/rate-card` with its own
vehicles. Exactly one has `"active": true`. Previous versions never change: a parcel keeps the `rateCardVersion` it
was priced with, so its fee can be worked out again from that version.
```json
[ { "version": 5, "active": true, "baseFee": 0.00, "...": "...", "vehicles": [ ... ] },
  { "version": 4, "active": false, "baseFee": 150.00, "...": "...", "vehicles": [ ... ] } ]
```

#### `GET /admin/pricing/districts`
All 25 districts (a branch is a district) in `GET /meta/districts` order, with the centre distances are measured
from. `priced` is false (and `latitude`/`longitude` null) for a district without a centre: routes to it can't be
priced.
```json
[ { "district": "AMPARA", "name": "Ampara", "priced": true, "latitude": 7.29760, "longitude": 81.68200 } ]
```

#### `GET /admin/pricing/routes?from=COLOMBO`
Distance and linehaul from one branch to every district (25 rows, district order). `from` is required, one of
`GET /meta/districts`; missing or unknown → `400 VALIDATION_FAILED`.
```json
{ "from": "COLOMBO", "fromName": "Colombo", "rateCardVersion": 5, "currency": "LKR", "roadFactor": 1.30,
  "linehaulPerKm": 1.00,
  "routes": [ { "to": "KANDY", "toName": "Kandy", "distanceKm": 122.6, "linehaul": 122.60 } ] }
```
- `distanceKm` = straight line between the centres × `roadFactor`, one decimal; `0.0` for the `from` district itself.
- `linehaul` = `distanceKm` × `linehaulPerKm`, two decimals (the quote's `LINEHAUL` line).
- Both are `null` when either district has no centre.

#### `POST /admin/pricing/estimate`
The price calculator: the same request, rules, messages and response as `POST /pricing/estimate`, for an admin.
Nothing is saved. A `MANUAL_QUOTE` result is a parcel that booking refuses with `409 PARCEL_NEEDS_MANUAL_QUOTE`.
- `400` `VALIDATION_FAILED` · `401` `UNAUTHENTICATED` · `403` `FORBIDDEN` (not an `ADMIN`)

### Delivery rates (admin, WP4)
The admin's Delivery Rates page (`/admin/rates`). Role `ADMIN` only (`401` / `403` as above). **Read only.** Every
amount is worked out by the same fee calculator as a quote, so it is what customers pay with that rate card.

#### `GET /admin/pricing/tariff?version=`
What one rate card charges. `version` is optional: without it, the active card; with it, that version (for the
history). Unknown version → `404 NOT_FOUND`; not a whole number → `400 VALIDATION_FAILED`.
```json
{
  "rateCardVersion": 5, "active": true, "currency": "LKR", "createdAt": "2026-11-14T05:55:00Z",
  "baseFee": 0.00, "baseWeightKg": 1, "perKgFee": 50.00, "volumetricDivisor": 5000, "linehaulPerKm": 1.00,
  "codFee": 0.00,
  "weightTiers": [ { "billableKg": 1, "weightCharge": 0.00 }, { "billableKg": 2, "weightCharge": 50.00 } ],
  "vehicles": [ { "vehicle": "BIKE", "legFee": 150.00, "bothLegs": 300.00, "maxLengthCm": 45, "maxWidthCm": 35,
                  "maxHeightCm": 35, "maxWeightKg": 10.00 } ],
  "distancesKm": [ 0.0, 25.0, 50.0, 100.0, 200.0, 300.0 ],
  "samples": [
    { "key": "SMALL_BOX", "name": "Small box", "weightKg": 2, "lengthCm": 30, "widthCm": 20, "heightCm": 15,
      "vehicle": "BIKE", "billableWeightKg": 2,
      "fees": [ { "distanceKm": 0.0, "total": 350.00 }, { "distanceKm": 100.0, "total": 450.00 } ] }
  ]
}
```
- `weightTiers`: billable kg 1–50 (the heaviest bookable parcel). `weightCharge` = `perKgFee` × the kg above
  `baseWeightKg`; the base fee, when a card has one, is a separate line and not in it.
- `vehicles`: smallest first. `bothLegs` = pickup leg + delivery leg, which every parcel on that vehicle pays.
- `samples`: five typical parcels (`DOCUMENTS`, `SMALL_BOX`, `MEDIUM_BOX`, `LARGE_BOX`, `BULKY_ITEM`), each with its
  full fee (base fee, weight, both legs and between-branches charge; no cash on delivery) at each of `distancesKm`
  (road km between the branches; 0 = same district). `vehicle` and every `total` are `null` when no vehicle takes the
  parcel (booking answers `409 PARCEL_NEEDS_MANUAL_QUOTE`).

---

## Merchant Orders (WP5)

Authenticated with either the `SD_TOKEN` cookie **or** an `X-Api-Key: <rawKey>` header (role `MERCHANT`).
The merchant id always comes from the login — it is never in the request body.
Only `APPROVED` merchants may create or list orders; a `PENDING` or `REJECTED` account gets `403 MERCHANT_NOT_APPROVED`.

### Create order
`POST /merchant/orders`

Request:
```json
{
  "merchantOrderRef": "TECH-ORD-1001",
  "pickup":    { "name": "Lanka Crafts", "phone": "077 123 4567", "addressLine": "12 High Level Road",
                 "city": "Nugegoda", "district": "COLOMBO" },
  "recipient": { "name": "Nimal Perera", "phone": "0719876543", "addressLine": "45 Peradeniya Road",
                 "city": "Kandy", "district": "KANDY" },
  "weightKg": 1.5,
  "codAmount": 2500.00,
  "instructions": "Call before coming",
  "lengthCm": 30,
  "widthCm": 20,
  "heightCm": 10,
  "keepUpright": false,
  "paymentMethod": "AT_PICKUP"
}
```

| Field | Rule | Message |
|---|---|---|
| `merchantOrderRef` | Optional, up to 100 characters. Idempotency key: resending the same value returns the original parcel. | "Use 100 characters or fewer." |
| `pickup`, `recipient` | Required | "Enter the pickup details." / "Enter the recipient's details." |
| `*.name` | 1–100 characters | "Enter a name." / "Use 100 characters or fewer." |
| `*.phone` | Sri Lankan mobile in any accepted form; stored as `+947XXXXXXXX` | "Enter a Sri Lankan mobile number, like 077 123 4567." |
| `*.addressLine` | 1–200 characters | "Enter the address." / "Use 200 characters or fewer." |
| `*.city` | 1–80 characters | "Enter the city." / "Use 80 characters or fewer." |
| `*.district` | One of `GET /meta/districts` | "Select a district." |
| `weightKg` | 0.01–50, up to 2 decimals | "Enter the weight." / "Enter a weight above 0 kg." / "A parcel can weigh up to 50 kg." / "Use up to 2 decimal places." |
| `codAmount` | Optional; 0.01–500000, up to 2 decimals | "Enter an amount above 0, or leave it out." / "Cash on delivery can be up to Rs. 500,000." |
| `instructions` | Optional, up to 500 characters | "Use 500 characters or fewer." |
| `lengthCm`, `widthCm`, `heightCm` | Required; whole cm 1–300 | "Enter the length." / "Enter the width." / "Enter the height." / "Use whole centimetres from 1 to 300." |
| `keepUpright` | Optional, default `false` | |
| `paymentMethod` | Required: how the delivery fee is paid. `ONLINE` (paid online), `AT_PICKUP` (your shop pays the courier in cash at pickup) or `AT_DROP_OFF` (the recipient pays the courier in cash at delivery, with any cash on delivery). Fixed once created | "Choose how the delivery fee will be paid." (missing or an unknown value) |

Response `201`: a Merchant Order.
```json
{
  "id": "6f1c2b8e-3a4d-4c8e-9b1a-2f3e4d5c6b7a",
  "trackingNumber": "SD2611004817",
  "status": "CREATED",
  "merchantOrderRef": "TECH-ORD-1001",
  "pickup":    { "name": "Lanka Crafts", "phone": "+94771234567", "addressLine": "12 High Level Road",
                 "city": "Nugegoda", "district": "COLOMBO" },
  "recipient": { "name": "Nimal Perera", "phone": "+94719876543", "addressLine": "45 Peradeniya Road",
                 "city": "Kandy", "district": "KANDY" },
  "weightKg": 1.5,
  "codAmount": 2500.00,
  "instructions": "Call before coming",
  "feeAmount": 450.00,
  "currency": "LKR",
  "createdAt": "2026-11-07T04:30:00Z",
  "updatedAt": "2026-11-07T04:30:00Z",
  "currentBranch": null,
  "failureReason": null,
  "proof": null,
  "size": { "lengthCm": 30, "widthCm": 20, "heightCm": 10, "keepUpright": false },
  "vehicle": "BIKE",
  "billableWeightKg": 1,
  "paymentMethod": "AT_PICKUP"
}
```
- `merchantOrderRef`: the value you supplied; `null` when omitted. Resending the same ref returns the original parcel unchanged (idempotent retry).
- `paymentMethod`: how the fee is paid, as created; `null` for orders made before payment methods.
- `feeAmount` / `currency`: set by pricing at creation time (same quote as `POST /pricing/estimate`).
- `currentBranch`: set only while `AT_BRANCH`. `failureReason`: only while `FAILED`. `proof`: `{ "recipientName": "Nimal Perera", "hasPhoto": false }` once `DELIVERED`.
- `400` `VALIDATION_FAILED` (keys like `weightKg`, `lengthCm`, `paymentMethod`) · `401` `UNAUTHENTICATED` · `403` `MERCHANT_NOT_APPROVED` (account not yet approved) · `403` `FORBIDDEN` (not a `MERCHANT`) · `409` `PARCEL_NEEDS_MANUAL_QUOTE` (no vehicle carries or fits it)

### List orders
`GET /merchant/orders?page=0&size=20&status=CREATED`

The merchant's own orders, newest first, as a page of Merchant Order (the shape above):
```json
{ "content": [ /* Merchant Order */ ], "page": 0, "size": 20, "totalElements": 5, "totalPages": 1 }
```
- `page` from 0 (default 0); `size` 1–100 (default 20); `status` optional, one of the parcel statuses.
- `200` · `400` `VALIDATION_FAILED` (`fieldErrors.page`: "Use a page number of 0 or more.", `fieldErrors.size`: "Use a page size from 1 to 100.") · `401` · `403` `MERCHANT_NOT_APPROVED` · `403` `FORBIDDEN`

---

## Courier (role `COURIER` only)

The logged-in courier's parcels (WP9): the parcels in the dispatch tasks a dispatcher gave them (see "Dispatch"). A
dispatcher may group several parcels in one task; the courier sees and works **each parcel on its own**. No login →
`401 UNAUTHENTICATED`; any other role (including `ADMIN`) → `403 FORBIDDEN`. A courier only sees parcels that were in
one of their tasks: another courier's parcel or an unknown id → `404 NOT_FOUND`. "Today" is the current day in Sri
Lankan time (Asia/Colombo).

Each step changes the parcel (delivery) and records the task outcome (dispatch) in one transaction: both or neither
are kept. A parcel is **completed** for the courier as soon as its own outcome closes it (dispatch's rule):

| Task | Completed after | Still active |
|---|---|---|
| `PICKUP` | `HANDED_OVER` (handed over at the branch), `NOT_COLLECTED` (Unable to Collect) | `PENDING`, `COLLECTED` (must be handed over) |
| `DELIVERY` | `DELIVERED`, `RETURNED` (failed and returned to the branch), `NOT_LOADED` (Unable to Collect) | `PENDING`, `LOADED` (out for delivery), `FAILED` (must be returned) |

The **task** becomes `COMPLETED` by itself, in the same transaction, when its last parcel is completed. There is no
"complete task" call.

### Courier delivery (response)
```json
{
  "parcelId": "0b6c7c1e-5d0f-4a51-9d2e-3a4f5b6c7d8e",
  "trackingNumber": "SD2611004817",
  "status": "OUT_FOR_DELIVERY",
  "stop": "DELIVERY",
  "pickup": null,
  "recipient": { "name": "Nimal Perera", "phone": "+94771234567", "address": "45 Peradeniya Road",
                 "city": "Kandy", "district": "KANDY" },
  "from": { "city": "Nugegoda", "district": "COLOMBO" },
  "to": { "city": "Kandy", "district": "KANDY" },
  "weightKg": 1.2,
  "codAmount": 2450.00,
  "instructions": "Call before you arrive.",
  "assignedAt": "2026-11-05T02:30:00Z",
  "updatedAt": "2026-11-05T05:10:00Z",
  "nextStatuses": ["DELIVERED", "FAILED"],
  "canMarkUnableToCollect": false,
  "completed": false,
  "completedAt": null,
  "proof": null,
  "task": { "assignmentId": "uuid", "type": "DELIVERY", "status": "IN_PROGRESS", "outcome": "LOADED",
            "branch": "COLOMBO", "problem": null, "receivedBy": null }
}
```
- `status`: the parcel's own status (delivery). "Unable to Collect" doesn't change it.
- `stop`: `PICKUP` for a pickup task, otherwise `DELIVERY` (`task.type` tells a transfer from a delivery).
- `pickup` / `recipient` (privacy): only the contact the courier's step needs, and only while the parcel is still
  theirs to work: the sender (`pickup`) in a pickup, the `recipient` in a delivery, nobody in a transfer. Both are
  `null` once the parcel is completed or the task is closed. `from` / `to` (city and district) are always there.
- `assignedAt`: when the dispatcher created the task.
- `codAmount`: cash to collect in rupees; `null` when already paid. `instructions` may be `null`.
- `completed`: the courier is done with this parcel (table above), whatever the other parcels of its task are doing.
  `completedAt`: when; `null` while it is active.
- `proof`: `{ "recipientName": "Nimal Perera", "hasPhoto": true }` (who received it, and whether the photo of the
  signed delivery slip is attached; see "Proof of delivery" below) **only on the entry of the `DELIVERY` step that
  delivered the parcel** (`task.outcome` `DELIVERED`); `null` on every other entry, also for a parcel delivered later:
  a pickup, a failed or returned delivery, Unable to Collect, a cancelled task. A courier who only collected or
  returned the parcel never gets the recipient's name or learns that a signed slip exists.
- `canMarkUnableToCollect`: "Unable to Collect" is possible now (the parcel's step is `PENDING` in an open task).
- `task`: the courier's task for this parcel. `status` is the whole task's status. `outcome` is the dispatch item
  outcome. `branch` is where the parcel is handed over (a pickup's or transfer's destination branch; a delivery's
  origin branch, for a return). `problem`: why it couldn't be collected (`NOT_COLLECTED`, `NOT_LOADED`).
  `receivedBy`: who took it at the branch.
- `nextStatuses`: the statuses this courier may set now, from the task step and the parcel's status (empty once the
  parcel is completed or the task closed). Loading (`IN_TRANSIT`, `OUT_FOR_DELIVERY`) also needs the parcel at the
  task's origin branch:

| Task | Step (`outcome`) | Allowed next | Records |
|---|---|---|---|
| `PICKUP` | `PENDING` | `PICKED_UP`, or Unable to Collect | `COLLECTED`, `NOT_COLLECTED` |
| `PICKUP` | `COLLECTED` | `AT_BRANCH` (at `task.branch`) | `HANDED_OVER` |
| `TRANSFER` | `PENDING` | `IN_TRANSIT`, or Unable to Collect | `LOADED`, `NOT_LOADED` |
| `TRANSFER` | `LOADED` | `AT_BRANCH` (at `task.branch`) | `HANDED_OVER` |
| `DELIVERY` | `PENDING` | `OUT_FOR_DELIVERY`, or Unable to Collect | `LOADED`, `NOT_LOADED` |
| `DELIVERY` | `LOADED` | `DELIVERED`, `FAILED` | `DELIVERED`, `FAILED` |
| `DELIVERY` | `FAILED` | `AT_BRANCH` (back at `task.branch`) | `RETURNED` |

Couriers are given `PICKUP` and `DELIVERY` tasks; `TRANSFER` stays supported because dispatch still has it.

`failureReason`: `RECIPIENT_UNAVAILABLE` | `WRONG_ADDRESS` | `RECIPIENT_REFUSED` | `ADDRESS_UNREACHABLE` |
`PARCEL_DAMAGED` | `OTHER`

`problem` (Unable to Collect): `SENDER_UNAVAILABLE` | `PARCEL_NOT_READY` | `PARCEL_MISSING` | `PARCEL_DAMAGED` |
`ADDRESS_UNREACHABLE` | `OTHER`

### `GET /courier/deliveries/today`
```json
{ "date": "2026-11-05", "toDo": 5, "done": 2, "items": [ /* Courier delivery */ ] }
```
The parcels in the courier's open tasks (whatever day they were assigned or planned for), plus the parcels they
completed today. A parcel in two of their tasks today shows once, with the open step. Order: open pickups (oldest
`assignedAt` first), then the other active parcels (oldest `assignedAt` first), then completed ones (newest first).
`toDo` = active parcels, `done` = parcels completed today. Cancelled tasks are left out.
- `200` · `401` · `403`

### `GET /courier/deliveries/history?page=0&size=20&trackingNumber=SD2611004817&type=PICKUP&outcome=HANDED_OVER&completedFrom=2026-11-01&completedTo=2026-11-21`
The parcels this courier completed, pickups and deliveries (`HANDED_OVER`, `NOT_COLLECTED`, `DELIVERED`, `RETURNED`,
`NOT_LOADED`), newest completion first, as a page of Courier delivery (`completed: true`, `nextStatuses` empty, no
contacts). A parcel is listed as soon as it is completed, even while the rest of its task is still in progress;
`COLLECTED` and `FAILED` parcels are not listed until they are handed over or returned. A parcel the courier worked in
two tasks (e.g. a failed delivery, then a new delivery) has one entry per completed task.
```json
{ "content": [ /* Courier delivery */ ], "page": 0, "size": 20, "totalElements": 42, "totalPages": 3 }
```
`page` from 0 (default 0); `size` 1-100 (default 20); `trackingNumber` optional, spaces and case ignored; an unknown
number gives an empty page.

Optional filters, combined with each other and with `trackingNumber`, applied to all of the courier's entries
**before** paging (`totalElements` / `totalPages` count only matching entries); blank means no filter:

| Filter | Values |
|---|---|
| `type` | `PICKUP`, `DELIVERY` (case ignored) |
| `outcome` | `HANDED_OVER`, `DELIVERED`, `RETURNED`, `NOT_COLLECTED`, `NOT_LOADED` (the outcomes a history entry can have) |
| `completedFrom`, `completedTo` | Days `yyyy-MM-dd` in Sri Lankan time (Asia/Colombo), both included, compared with when that parcel's own step was completed |

Dispatcher-cancelled tasks are not history entries (only completed steps are), so there is no "cancelled" outcome here.
- `200` · `400` `VALIDATION_FAILED` (`fieldErrors.page` / `size` / `type` / `outcome` / `completedFrom` / `completedTo`:
  "Choose pickups or deliveries.", "Choose one of the listed outcomes.", "Use a date like 2026-11-21.", "Choose an end
  date on or after the start date.") · `401` · `403`

### `GET /courier/deliveries/{parcelId}`
All Courier delivery fields (for the open step if the parcel is in one of the courier's open tasks, else the most
recent one), plus:
```json
{
  "history": [
    { "status": "OUT_FOR_DELIVERY", "branch": null, "failureReason": null, "occurredAt": "2026-11-05T05:10:00Z" },
    { "status": "AT_BRANCH", "branch": "COLOMBO", "failureReason": null, "occurredAt": "2026-11-05T03:00:00Z" }
  ],
  "proof": null,
  "failureReason": null
}
```
- `history`: newest first, without notes (they are written by other people for their own work). `branch` only for
  `AT_BRANCH`, `failureReason` only for `FAILED`.
- `proof`: as above, only on the entry of the delivery that delivered it, otherwise `null`.
  `failureReason`: only on a delivery that failed and is waiting to be returned (`task.outcome` `FAILED`, the parcel is
  `FAILED`); `null` on any other entry. A returned delivery shows the reason in its `history`.
- `200` · `400` (non-UUID id) · `401` · `403` · `404` `NOT_FOUND`

### `GET /courier/deliveries/{parcelId}/history/{assignmentId}`
One finished entry of the courier's history (one item of `GET /courier/deliveries/history`) exactly as it was: the
parcel's step in **that** task, not its latest one, so a parcel's entries (e.g. its pickup and later its delivery) are
each opened on their own. Same fields as `GET /courier/deliveries/{parcelId}`, read-only:
- `task`, `completed`, `completedAt`, `proof`: that entry's (e.g. a pickup entry: `HANDED_OVER`, `receivedBy`,
  `proof: null`).
- `nextStatuses: []`, `canMarkUnableToCollect: false`, `pickup` / `recipient`: `null` (contacts are only for open steps).
- `history`: the parcel's events **until that entry finished**: its item's close time (not the task's: other parcels of
  the task may finish later), or, for a parcel left open when the dispatcher closed the task, when the task was
  closed. Nothing that happened afterwards is included. Delivery's status events carry no task, so this is the
  parcel's timeline up to that moment; that entry's own step is its newest event.
- `status`: the parcel's status **now** (it may have moved on since the entry), not the entry's outcome.
- `200` · `400` (non-UUID id) · `401` · `403` · `404` `NOT_FOUND`: the task is unknown or not yours, the parcel isn't in
  it, or its step isn't finished yet (an open step is live work: use `GET /courier/deliveries/{parcelId}`). The same
  404 for all of these, with no parcel data.

### `POST /courier/deliveries/{parcelId}/status`
```json
{ "status": "PICKED_UP", "note": "Collected from front desk" }
```
```json
{ "status": "AT_BRANCH", "receivedBy": "S. Silva", "note": "Counter 2" }
```
```json
{ "status": "OUT_FOR_DELIVERY" }
```
```json
{ "status": "DELIVERED", "recipientName": "Nimal Perera", "proofPhotoId": "3f1d…", "note": "Left with the guard" }
```
```json
{ "status": "FAILED", "failureReason": "RECIPIENT_UNAVAILABLE", "note": "No answer, called twice" }
```
- `AT_BRANCH` hands the parcel over at `task.branch` (pickup, transfer) or returns a failed delivery there; the request
  has no branch field. It needs `receivedBy`: the person at the branch who took the parcel.
- `DELIVERED` needs `recipientName` and `proofPhotoId`: the photo of the signed delivery slip, uploaded first with
  `POST /courier/deliveries/{parcelId}/proof` by the same courier for the same parcel. It is attached in the same
  transaction as the status change and can never be replaced. `FAILED` needs `failureReason`; `OTHER` also needs a
  `note`. Pickups, `FAILED`, the return to the branch (`AT_BRANCH`) and Unable to Collect take no photo.
- `recipientName` and `proofPhotoId` only with `DELIVERED`; `failureReason` only with `FAILED`; `receivedBy` only with
  `AT_BRANCH`. `note` is optional otherwise.
- `200`: the updated parcel (same shape as `GET /courier/deliveries/{parcelId}`), with `completed` and `task.status`
  as they are after the step (`COMPLETED` when this was the task's last open parcel). Customers, merchants and the
  tracking page are told through the delivery module's `ParcelStatusChanged` event (`actorRole: COURIER`).
- `400` `VALIDATION_FAILED` · `401` · `403` · `404` `NOT_FOUND`
- `409` `ASSIGNMENT_CLOSED`: the task was completed or cancelled. `409` `TASK_STEP_NOT_ALLOWED`: not the task's next
  step (a skipped step, the button pressed twice, a completed parcel). `409` `INVALID_STATUS_TRANSITION`: the parcel
  can't make the move now (its status changed, or it isn't at the task's origin branch for loading). `409`
  `ASSIGNMENT_CHANGED`: the dispatcher changed the task at the same moment. Nothing was saved; the frontend reloads the
  parcel.
- Checked in this order: the request (400), the parcel is yours (404), the task is open, the step is allowed, the
  parcel can move (409).

### `POST /courier/deliveries/{parcelId}/unable-to-collect`
"Unable to Collect": the courier couldn't take the parcel (one action for both task types).
```json
{ "problem": "SENDER_UNAVAILABLE", "note": "Shop closed until 2 pm" }
```
- Records `NOT_COLLECTED` for a pickup, `NOT_LOADED` for a delivery (or transfer), with the `problem`. The parcel's
  `status` doesn't change (it never moved); the parcel is completed for the courier and goes back to the dispatcher's
  queue. Allowed only while `canMarkUnableToCollect` is true.
- `200`: the parcel (same shape as `GET /courier/deliveries/{parcelId}`), `completed: true`.
- `400` `VALIDATION_FAILED` · `401` · `403` · `404` `NOT_FOUND`
- `409` `ASSIGNMENT_CLOSED` · `409` `TASK_STEP_NOT_ALLOWED` (already collected or out for delivery) · `409`
  `ASSIGNMENT_CHANGED`

### Proof of delivery

When handing over a parcel, the courier photographs the delivery slip the recipient signed (or picks the photo from
the device), uploads it, then marks the parcel `DELIVERED` with the returned `proofPhotoId`. The image is kept in
private file storage (Cloudinary `authenticated` assets in production; there is no public URL), with a durable
reference in `delivery.proof_photos` linked to the parcel id; `delivery.parcels.proof_photo_id` points to it once the
parcel is `DELIVERED`. Uploads that are never attached are removed after 24 hours; an attached proof is never removed
or replaced.

#### `POST /courier/deliveries/{parcelId}/proof`
`multipart/form-data` with one field `file`: a JPEG, PNG or WebP photo up to 5 MB. The type comes from the file's
content, not its name. EXIF (camera, time, GPS), XMP and comments are removed before storing, without re-encoding.
Only for a parcel this courier is delivering now (an open `DELIVERY` step, `OUT_FOR_DELIVERY`). Until the parcel is
`DELIVERED`, uploading again gives a new id (the unused one is cleaned up later).
```json
{ "proofPhotoId": "3f1d2c4b-…", "contentType": "image/jpeg", "sizeBytes": 412345, "uploadedAt": "2026-11-20T10:15:00Z" }
```
- `201` · `400` `VALIDATION_FAILED` `fieldErrors.file`: "Add a photo of the signed delivery slip." (missing), "Use a
  photo up to 5 MB.", "Use a JPEG, PNG or WebP photo.", "This photo can't be read. Take it again.", "This file is too
  large." (over 6 MB, refused before it is read) · `401` · `403` · `404` `NOT_FOUND` (not your parcel)
- `409` `ASSIGNMENT_CLOSED` · `409` `TASK_STEP_NOT_ALLOWED` (a pickup, not out for delivery yet, or already delivered:
  a proof can't be replaced) · `409` `INVALID_STATUS_TRANSITION` · `503` `SERVICE_UNAVAILABLE` (the image storage
  can't be reached; nothing was kept)

#### `GET /courier/deliveries/{parcelId}/proof`
The proof photo of a parcel this courier delivered, as the image itself (`Content-Type: image/jpeg|png|webp`,
`Cache-Control: no-store, private`). Another courier, including the one who picked the parcel up, gets `404`.
- `200` · `401` · `403` · `404` `NOT_FOUND` (not delivered by you, or no proof) · `503` `SERVICE_UNAVAILABLE`

Other modules read the proof through `DeliveryApi.findProofPhoto(parcelId)` (content type and bytes) and check
`DeliverySnapshot.hasProofPhoto()` first; they decide who may see it and serve it themselves.

### Validation rules (courier)

| Field | Rule | Error message |
|---|---|---|
| `status` | Required, one of the parcel statuses | Select the new status. |
| `note` | Optional, max 500 chars | Keep the note under 500 characters. |
| `note` (`failureReason` or `problem` `OTHER`) | Required, 3-500 chars | Describe what went wrong. |
| `recipientName` (`DELIVERED`) | Required, 2-100 chars | Enter the name of the person who received the parcel. |
| `recipientName` with another status | Not allowed | Only for delivered parcels. |
| `proofPhotoId` (`DELIVERED`) | Required: a photo uploaded for this parcel by you, not attached yet | Add a photo of the signed delivery slip. / Add the photo again. |
| `proofPhotoId` with another status | Not allowed | Only for delivered parcels. |
| `file` (proof upload) | Required: JPEG, PNG or WebP, up to 5 MB, a readable image | See "Proof of delivery". |
| `failureReason` (`FAILED`) | Required, one of the values above | Select why the delivery failed. |
| `failureReason` with another status | Not allowed | Only for failed deliveries. |
| `receivedBy` (`AT_BRANCH`) | Required, 2-100 chars | Enter the name of the person at the branch who received the parcel. |
| `receivedBy` with another status | Not allowed | Only when you hand the parcel over at a branch. |
| `problem` (Unable to Collect) | Required, one of the values above | Select why you couldn't take the parcel. |
| `page` (history) | 0 or more | Use a page number of 0 or more. |
| `size` (history) | 1-100 | Use a page size from 1 to 100. |

An unknown `status`, `failureReason` or `problem` value gives `400` with that field in `fieldErrors`.

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

## Tracking (WP8, live tracking)

Public tracking by tracking number, and live updates pushed over **WebSocket with STOMP** (design document 2.4.10,
4.5). The REST lookup gives the starting point; the WebSocket sends each change after it. Updates arrive only while
the outbox relay runs (`SWIFTDROP_OUTBOX_RELAY_ENABLED=true`).

**Privacy.** Public tracking never shows names, phone numbers, addresses, notes, amounts, user ids or who made a
change: only the status, the town and district at each end, the branch a parcel is at, and a failed delivery's
reason code.

### `GET /track/{trackingNumber}` (public)

Spaces and letter case are ignored (`sd 2611 0048 17` works). At most 30 lookups per minute per IP
(`swiftdrop.tracking.lookups-per-minute`), then `429 TOO_MANY_REQUESTS`. An unknown number, or one in the wrong
format, is `404 NOT_FOUND` "We couldn't find a parcel with this tracking number. Check the number and try again."

```json
{
  "trackingNumber": "SD2611004817",
  "status": "AT_BRANCH",
  "currentBranch": "COLOMBO",
  "from": { "city": "Colombo 03", "district": "COLOMBO" },
  "to": { "city": "Kandy", "district": "KANDY" },
  "createdAt": "2026-11-13T04:10:00Z",
  "updatedAt": "2026-11-13T06:45:00Z",
  "version": 3,
  "history": [
    { "status": "CREATED", "branch": null, "failureReason": null, "occurredAt": "2026-11-13T04:10:00Z" },
    { "status": "PICKED_UP", "branch": null, "failureReason": null, "occurredAt": "2026-11-13T05:30:00Z" },
    { "status": "AT_BRANCH", "branch": "COLOMBO", "failureReason": null, "occurredAt": "2026-11-13T06:45:00Z" }
  ]
}
```

`history` is oldest first. `currentBranch` is set only while the status is `AT_BRANCH`.

### WebSocket (STOMP)

Plain WebSocket (no SockJS), STOMP 1.2, heart-beats every 20 s. Clients only subscribe: a `SEND`, or a subscription
to anything not listed, gets a STOMP `ERROR` frame and the connection closes.

| Handshake URL | Who | May subscribe to |
|---|---|---|
| `ws://<host>/api/v1/ws/public` | anyone (cookie ignored) | `/topic/parcels/{trackingNumber}` |
| `ws://<host>/api/v1/ws` | logged in: the handshake checks the `SD_TOKEN` cookie like any request (401 without it) | the above, `/topic/live` (DISPATCHER, ADMIN) and `/user/queue/parcels` (CUSTOMER, MERCHANT) |

The tracking number in the topic must be the normalised form (`SD2611004817`). The browser origin must be
`FRONTEND_URL`.

**`/topic/parcels/{trackingNumber}`**, one message per status change:

```json
{ "trackingNumber": "SD2611004817", "status": "OUT_FOR_DELIVERY", "branch": null, "failureReason": null,
  "occurredAt": "2026-11-13T08:02:00Z", "version": 5 }
```

Apply a message only if its `version` is higher than the one you have (events can repeat or arrive late), then add
it to the history. After a reconnect, load `GET /track/{trackingNumber}` again: messages sent while disconnected are
not replayed. If the WebSocket keeps failing, poll that endpoint (the frontend polls every 30 s).

**`/topic/live`** (DISPATCHER, ADMIN): every parcel's status changes, for the live board.

```json
{ "eventId": "…", "parcelId": "…", "trackingNumber": "SD2611004817", "fromStatus": "PICKED_UP",
  "toStatus": "AT_BRANCH", "branch": "COLOMBO", "origin": "COLOMBO", "destination": "KANDY",
  "failureReason": null, "actorRole": "COURIER", "occurredAt": "2026-11-13T06:45:00Z", "version": 3 }
```

`origin` and `destination` are the sender's and recipient's districts.

**`/user/queue/parcels`** (CUSTOMER, MERCHANT, logged-in endpoint only): private updates for the parcels the user
booked (customer) or created (merchant), and nobody else's. The server picks the parcels by the logged-in user's
id, so there is nothing to choose. Same shape as `/topic/parcels/{trackingNumber}` plus `parcelId`:

```json
{ "parcelId": "…", "trackingNumber": "SD2611004817", "status": "AT_BRANCH", "branch": "KANDY",
  "failureReason": null, "occurredAt": "2026-11-13T06:45:00Z", "version": 3 }
```

Same rules: apply only a higher `version`; after a reconnect load `GET /tracking/mine` again.

### `GET /tracking/live/recent?branch=COLOMBO` (DISPATCHER, ADMIN)

The last 100 changes this server has seen (it keeps 200 in memory, cleared on restart), newest first, in the
`/topic/live` shape. With `branch`, only changes whose origin, destination or arrival branch is that branch.

### `GET /tracking/mine?status=&page=0&size=20` (CUSTOMER, MERCHANT)

The logged-in customer's bookings or merchant's orders, newest first (page of rows in the
`GET /admin/tracking/parcels` row shape), as the starting point for `/user/queue/parcels`. `status` optional.
`400` `page` < 0, `size` outside 1-100 · `401` · `403` (any other role).

### `GET /admin/tracking/parcels?q=&status=&district=&from=&to=&page=0&size=20` (ADMIN)

Every parcel's latest status, most recently updated first. All filters are optional:

| Param | Meaning |
|---|---|
| `q` | Part of a tracking number (spaces and case ignored, at most 40 characters). |
| `status` | One `ParcelStatus`. |
| `district` | The sender's or recipient's district, or the branch the parcel is at. |
| `from`, `to` | `yyyy-MM-dd`, last updated on or after / on or before that day (Sri Lankan time). `to` before `from` is 400. |
| `page`, `size` | `page` ≥ 0, `size` 1-100 (default 20); otherwise 400 `VALIDATION_FAILED`. |

```json
{
  "content": [{
    "parcelId": "0c9d82dd-b94a-40be-9a29-baeb71d909b8",
    "trackingNumber": "SD2611406946",
    "status": "AT_BRANCH",
    "from": { "city": "Colombo 03", "district": "COLOMBO" },
    "to": { "city": "Kandy", "district": "KANDY" },
    "currentBranch": "COLOMBO",
    "failureReason": null,
    "createdAt": "2026-11-13T04:10:00Z",
    "updatedAt": "2026-11-13T06:45:12Z",
    "version": 3
  }],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

The list is tracking's own index (`tracking.parcel_index`): a parcel is added or updated when a `ParcelStatusChanged`
event arrives and whenever it is looked up with `GET /track/{trackingNumber}`. A parcel that has done neither since
the index was added is not listed yet. Live changes come on `/topic/live`.

### `GET /admin/tracking/parcels/counts?q=&district=&from=&to=` (ADMIN)

How many indexed parcels match the same filters, per status (the status filter is ignored, so a status picker can
show the numbers): `{ "total": 12, "byStatus": { "CREATED": 3, "AT_BRANCH": 2, ..., "DELIVERED": 7 } }`. Every
status is present, 0 when none.

## Assistant (AI delivery assistant)

A chatbot that answers questions such as "Where is my parcel?" or "How much to send 2 kg to Kandy?", and gives
admins daily, weekly and monthly service reports ("This week's report") and answers to their other questions about
the service ("How many parcels are out for delivery?", "Success rate this month?", "Show failed parcels going to
Kandy"). It never
invents facts: every answer is built only from the data of SwiftDrop's own APIs. Each message is answered in two
steps:

1. **Data.** SwiftDrop's code understands the question (tracking numbers, price details, "my parcels", districts,
   reports for admins),
   calls the APIs below and builds a plain answer and cards from their data. If something is missing (a district,
   the weight), it asks for it.
2. **Wording.** With `ASSISTANT_PROVIDER=gemini` and `GEMINI_API_KEY` set, Google Gemini rewrites an answer that has
   data in clear, friendly sentences (`provider: "gemini"`). Gemini gets only the question, the data and the plain
   answer; it has no tools and can't fetch anything. An answer with a number (amount, count, percentage, date) or a
   tracking number that isn't in the data or the plain answer is thrown away. Without Gemini, when it fails or times out, and for answers without data, the plain answer is
   sent (`provider: "rules"`).

The API key stays on the server and is never sent to the browser. The same data tools are also offered to other AI
clients over MCP (below).

**Privacy.** The assistant sees only what public tracking shows (status, towns, branch, failure reason) and, for a
logged-in customer or merchant, their own parcels. It never sees names, phone numbers, addresses or amounts due.

### Where the data comes from

| Step | Who | Needs | Uses |
|---|---|---|---|
| `track_parcel` | anyone | `trackingNumber` (spaces and case ignored) | `GET /track/{trackingNumber}` (counts against its 30/minute limit) |
| `estimate_price` | anyone | `pickupDistrict`, `dropOffDistrict` (district codes), `weightKg` (0.01-50), `lengthCm`, `widthCm`, `heightCm` (1-300; the size picks the vehicle, whose fee is added for the pickup and the delivery, so the assistant asks for it) | `POST /pricing/estimate` |
| `list_districts` | anyone | none | `GET /meta/districts` |
| `my_parcels` | logged-in CUSTOMER or MERCHANT (`/assistant/chat/me`) | optional `status` | `GET /tracking/mine` (their latest 10) |
| `service_report` | logged-in ADMIN (`/assistant/chat/me`); never on MCP | `period` (`DAILY`, `WEEKLY`, `MONTHLY`), optional `date` (yyyy-MM-dd, any day in the period; today when left out) | `DeliveryApi.stats` (booked, delivered, failed per day; the same figures as the admin dashboard), `DeliveryApi.countByStatus`, `TrackingApi.breakdown` (districts and failure reasons from `tracking.parcel_index`, like `/admin/tracking/parcels`), `IdentityApi.getUserRoleCounts` |
| `parcel_counts` | ADMIN | none | `DeliveryApi.countByStatus` (parcels in each status now) |
| `account_counts` | ADMIN | none | `IdentityApi.getUserRoleCounts` (accounts per role, merchants waiting for approval; counts only) |
| `find_parcels` | ADMIN | optional `status`, `pickupDistrict`, `dropOffDistrict`, `branch` (parcels at that branch now), `bookedFrom`, `bookedTo` (yyyy-MM-dd) | `DeliveryApi.search` (the admin All Parcels search) or `DeliveryApi.findAtBranch`; how many match and the newest 10, public view only (no names, phones, addresses or amounts) |

The data is exactly what these endpoints return: the assistant reads it through the same services
(`TrackingApi`, `PricingApi`, `DeliveryApi`, `IdentityApi`), inside the backend, so the same rules, limits and
privacy apply.

**Reports (admins).** An admin asks in words: "today's report", "report for yesterday", "this week's report",
"summary of last month", "monthly report for October", "daily report for 2026-11-10". Without a period the assistant
asks "Which report would you like: daily, weekly or monthly?". Days are Sri Lanka days; a week runs Monday to Sunday
and a month is a calendar month. A period that hasn't ended is counted up to today (`partial: true`) and compared with
the same days of the period before (a month never borrows days of the next month). A period that starts after today
is refused in the reply. Other roles asking for a report get the normal help answer.

**Other admin questions.** Answered from one of the admin tools above:

| Question | Tool | Card |
|---|---|---|
| "How many parcels are out for delivery / waiting for pickup / at a branch?", "How many parcels do we have?" | `parcel_counts` | `FACTS` |
| "How many couriers do we have?", "Any merchants waiting for approval?" | `account_counts` | `FACTS` |
| "How many parcels were booked / delivered / failed yesterday?", "Success rate this month?", "Busiest day this week?", "Top districts last month?", "Why did deliveries fail this week?" (no period: today; busiest day: this week) | `service_report` | `FACTS` |
| "Show failed parcels going to Kandy", "Parcels at the Colombo branch", "How many parcels from Galle are in transit?", "Which parcels were booked today?" | `find_parcels` | `PARCEL_LIST` with `total` and `filters` |

A district after "from" is the pickup district, one before "branch" is the branch, any other is the drop-off
district; a period in a search filters by booking day. The reply always says which filters were used.

### `POST /assistant/chat` (public) and `POST /assistant/chat/me` (logged in)

Same body and response. `/assistant/chat` ignores the login cookie (so it never lists anyone's parcels);
`/assistant/chat/me` needs a login and adds `my_parcels` for customers and merchants and `service_report` for
admins. The client keeps the
conversation and sends it each time, oldest first; the last message must be the user's.

```json
{
  "messages": [
    { "role": "user", "text": "How much to send 2 kg to Kandy?" },
    { "role": "assistant", "text": "Sure. Which district should we collect it from?" },
    { "role": "user", "text": "Colombo, it's 30 x 20 x 10 cm" }
  ]
}
```

```json
{
  "reply": "Sending 2 kg from Colombo to Kandy costs Rs. 472.60.",
  "provider": "gemini",
  "cards": [
    {
      "type": "PRICE",
      "data": {
        "pickupDistrict": "Colombo", "dropOffDistrict": "Kandy", "weightKg": 2,
        "status": "COMPLETE", "currency": "LKR", "total": 472.60, "totalText": "Rs. 472.60",
        "billableWeightKg": 2, "vehicle": "BIKE", "distanceKm": 122.6,
        "lines": [
          { "component": "WEIGHT", "amount": 50.00 }, { "component": "PICKUP", "amount": 150.00 },
          { "component": "DELIVERY", "amount": 150.00 }, { "component": "LINEHAUL", "amount": 122.60 }
        ],
        "note": "Full estimate."
      }
    }
  ]
}
```

`cards` holds the data the answer was built from, for the page to show next to the reply:

| `type` | `data` |
|---|---|
| `TRACKING` | `trackingNumber`, `status`, `statusText`, `from`, `to` (town, district), `currentBranch`, `lastUpdate` (text), `updatedAt` |
| `PARCEL_LIST` | `parcels`: up to 10 rows shaped like `TRACKING`; an admin's search adds `total` (how many match) and `filters` (in words) |
| `FACTS` | `title`, `subtitle` (the period, or null for figures of right now), `facts`: `label`, `value` (text) |
| `PRICE` | as above, plus `vehicleText` ("bike", "tuk-tuk", "lorry") and `sizeCm` ("30 x 20 x 10"); `status` is `COMPLETE` (with the `PICKUP` and `DELIVERY` lines of the vehicle) or `MANUAL_QUOTE` (`total` null) |
| `REPORT` | `period`, `title`, `from`, `to`, `countedTo`, `partial`, `rangeText`, `booked`, `delivered`, `failed`, `successRate` (delivered / (delivered + failed) in %, one decimal, null when none finished), `previous` (`from`, `to`, `text`, the same four figures), `changes` (`booked`, `delivered`, `failed`: `difference`, `percent` or null), `perDay` and `busiestDay` (weekly and monthly only), `topPickupDistricts` / `topDropOffDistricts` (top 5 `district`, `count`), `failureReasons` (`reason`, `count`; parcels that failed in the period and are still FAILED), `statusNow` (`status`, `label`, `count`, all parcels now), `accountsNow` (`total`, `customers`, `merchants`, `couriers`, `dispatchers`, `pendingMerchants`) |

Rules and errors:
- 1-20 messages; `role` is `user` or `assistant`; `text` not blank, at most 4000 characters; the last message is the
  user's and at most 500 characters. Otherwise `400 VALIDATION_FAILED` (field `messages`).
- At most 20 messages per minute per user (or per IP when not logged in; `ASSISTANT_MESSAGES_PER_MINUTE`), then
  `429 TOO_MANY_REQUESTS`.
- `/assistant/chat/me` without a login: `401 UNAUTHENTICATED`.
- A lookup that finds nothing (unknown number, bad district, too heavy) is not an error: the reply explains it.

### MCP server: `POST /mcp` (public)

The public data tools (`track_parcel`, `estimate_price`, `list_districts`) as a
[Model Context Protocol](https://modelcontextprotocol.io) server, so AI clients such as Claude Desktop or MCP
Inspector can use SwiftDrop's data directly. The client's own AI decides which tool to call; SwiftDrop only returns
the data (the same data as the endpoints above). Gemini is not used here, and `my_parcels` is not offered. Streamable HTTP transport, JSON responses only (no event stream), no session id.

| JSON-RPC method | Result |
|---|---|
| `initialize` | `protocolVersion` (the client's if supported: 2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05; otherwise the newest), `capabilities.tools`, `serverInfo` `{ "name": "swiftdrop" }` |
| `ping` | `{}` |
| `tools/list` | `tools`: `name`, `title`, `description`, `inputSchema` (JSON Schema), `annotations.readOnlyHint: true` |
| `tools/call` | `content` (one `text` item, the result as JSON), `structuredContent` (the result), `isError` |
| a notification (no `id`) | `202 Accepted`, no body |

A bad request is a JSON-RPC error with HTTP 200: `-32600` invalid request, `-32601` unknown method, `-32602`
unknown tool or bad params. A tool's own failure, including the per-IP limit (20 calls per minute), is a normal
result with `isError: true`. `GET /mcp` is `405` (no server-sent events).

```json
{ "jsonrpc": "2.0", "id": 3, "method": "tools/call",
  "params": { "name": "track_parcel", "arguments": { "trackingNumber": "SD2611004817" } } }
```
## Admin Settings (ADMIN only)

All Admin Settings endpoints require an authenticated user with the `ADMIN` role.

**Base path:** `/api/v1/admin/settings`

Requests that update settings use `Content-Type: application/json`. Successful PUT requests return the saved settings object. Settings are maintained as singleton records with ID `1`.

### Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/company` | Retrieve company details |
| PUT | `/company` | Update company details |
| GET | `/delivery` | Retrieve delivery operations settings |
| PUT | `/delivery` | Update delivery operations settings |
| GET | `/notifications` | Retrieve notification preferences |
| PUT | `/notifications` | Update notification preferences |
| GET | `/system-info` | Retrieve system information and infrastructure status |

### Company Details

**GET `/admin/settings/company`**

Returns HTTP `200` with company details when configured, or HTTP `404` when no settings record exists.

**PUT `/admin/settings/company`**

Example request:

```json
{
  "companyName": "SwiftDrop",
  "supportEmail": "support@example.com",
  "supportPhone": "0771234567",
  "headOfficeAddress": "Colombo, Sri Lanka",
  "registrationNumber": "PV123456"
}
```

Validation:
- `companyName`, `supportEmail`, `supportPhone` and `headOfficeAddress` must not be blank.
- `supportEmail` must be a valid email address.
- `supportPhone` must match the Sri Lankan mobile-number pattern.
- `registrationNumber` is optional.
- All string fields are trimmed before saving, including the optional registration number when supplied.

A successful PUT returns HTTP `200` with the saved company details.

### Delivery Operations

**GET `/admin/settings/delivery`**

Returns HTTP `200` with delivery settings when configured, or HTTP `404` if no record exists.

**PUT `/admin/settings/delivery`**

Example request:

```json
{
  "pickupStart": "08:00:00",
  "pickupEnd": "18:00:00",
  "sameDayCutoff": "14:00:00",
  "operatingDays": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"],
  "maxWeightKg": 25.0,
  "maxLengthCm": 100.0,
  "maxWidthCm": 80.0,
  "maxHeightCm": 80.0,
  "failedAttempts": 3
}
```

Validation:
- Pickup start, pickup end, same-day cutoff and operating days are required.
- Pickup end must be later than pickup start.
- Same-day cutoff must be within the pickup hours, inclusive of the boundaries.
- At least one operating day must be selected.
- Operating days must use full English weekday names without duplicates.
- Supplied weight and dimension limits must be at least `0.01`.
- Failed attempts must be between `1` and `5`.

Operating days are stored as comma-separated text and returned as a JSON array.

A successful PUT returns HTTP `200` with the saved delivery settings.

**Validation fix dependency:** The delivery service's business-rule errors are mapped to HTTP `400 VALIDATION_FAILED` by the changes proposed in PR #46. Until that PR is merged, some business-rule errors on `dev` may return HTTP `500`.

### Notification Settings

**GET `/admin/settings/notifications`**

Returns HTTP `200` with notification settings when configured, or HTTP `404` when no record exists.

**PUT `/admin/settings/notifications`**

Example request:

```json
{
  "parcelStatusEmails": true,
  "merchantDecisionEmails": true,
  "senderName": "SwiftDrop"
}
```

Validation:
- Both email preference fields are required Boolean values.
- `senderName` must not be blank.
- `senderName` must not exceed 100 characters.
- The sender name is trimmed before saving.

A successful PUT returns HTTP `200` with the saved notification settings.

**Implementation note:** These endpoints store and retrieve preferences. Enforcement of the toggles by individual notification workers must be verified separately; saving a preference does not itself guarantee that every email sender honors it.

### System Information

**GET `/admin/settings/system-info`**

Returns HTTP `200` with a read-only snapshot of application and infrastructure information.

Example response (illustrative values):

```json
{
  "applicationVersion": "0.0.1-SNAPSHOT",
  "environment": "default",
  "databaseStatus": "Connected",
  "rabbitMqStatus": "Disconnected",
  "outboxRelayStatus": "Disabled"
}
```

Fields:
- `applicationVersion`: configured application version, defaulting to `0.0.1-SNAPSHOT`.
- `environment`: active Spring profiles joined by commas, or `default`.
- `databaseStatus`: `Connected`, `Disconnected` or `Not available`.
- `rabbitMqStatus`: `Connected`, `Disconnected` or `Not available`.
- `outboxRelayStatus`: `Enabled` or `Disabled`, based on the relay configuration flag.

Health status values depend on the registered Spring Boot health contributors. Missing contributors are reported as `Not available`; health-check failures are reported as `Disconnected`.

This endpoint does not change application configuration or enable infrastructure components.

### Authentication and Error Responses

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Invalid request data |
| 401 | `UNAUTHENTICATED` | Missing, expired or invalid authentication session |
| 403 | `FORBIDDEN` | Authenticated user does not have the ADMIN role |
| 404 | See note below | Settings have not yet been configured |
| 500 | `INTERNAL_ERROR` | Unexpected server error |

Example validation error:

```json
{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Some fields need attention.",
  "fieldErrors": {
    "pickupEnd": "Pickup end time must be after start time."
  }
}
```

The field-specific example above depends on PR #46.

**Known behavior:** The GET endpoints for company, delivery and notification settings use `ResponseEntity.of(Optional)`. If no record exists, they return HTTP `404` with an empty body rather than the standard API error JSON. Frontend clients should handle this case.

For shared error conventions, refer to the Error section near the beginning of this document.