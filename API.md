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
- `status`: `ACTIVE` | `PENDING_APPROVAL` | `REJECTED` | `DISABLED`
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
| 404 | `NOT_FOUND` | Resource not found |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method for this path |
| 409 | `EMAIL_ALREADY_REGISTERED` | Email already in use |
| 409 | `PHONE_ALREADY_REGISTERED` | Mobile number already in use |
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
- `401` `INVALID_CREDENTIALS`: "The mobile number, email or password is incorrect." (same response for unknown user and wrong password)
- `403` `ACCOUNT_PENDING_APPROVAL`: "Your merchant account is waiting for approval. We'll email you when it's ready."
- `403` `ACCOUNT_REJECTED` / `ACCOUNT_DISABLED`
- The 403 codes are only returned when the password is correct (otherwise `401`).

### `POST /auth/logout` (any)
- `204`, clears the cookie (`Max-Age=0`)

### `GET /auth/me` (logged in)
- `200`: User · `401` `UNAUTHENTICATED`

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
  Claims: `sub` (user id), `role`, `iat`, `exp`. No personal data in the token.
- A token stays valid until it expires, even if the account is disabled later (disabled users cannot log in again).
- Public endpoints ignore the cookie, so an expired cookie never blocks login, register or logout.
- CSRF: the cookie is `SameSite=Lax`, CORS allows only `FRONTEND_URL` with credentials, and write endpoints only accept `application/json`.

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
