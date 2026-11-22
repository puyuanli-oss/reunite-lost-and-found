# HTTP integration API

Same-origin JSON API. Call `GET /api/session` first and retain its `csrfToken`. Every POST/PUT/DELETE must send `X-CSRF-Token`; JSON requests use `Content-Type: application/json`. Send credentials/cookies through the browser. Authentication rotates both session and CSRF token; use the new token returned by login/register/logout or refetch session. No CORS grant is issued.

Errors use `{error,message,fields?}`. Statuses include400 validation,401 sign-in required,403 permission/CSRF,404 nonexistent or inaccessible,409 stale version/inactive/pending review,413 too large,415 wrong type,429 limit,500 failed operation. Responses omit SQL/error internals.

| Method / path | Body or query | Result |
|---|---|---|
| GET `/api/session` | — | `{user,csrfToken,demoMode}` |
| GET `/api/categories` | — | Primary categories and child subtype IDs/names |
| POST `/api/login` | `{username,password}` | `{user,csrfToken}` |
| POST `/api/logout` | — | `{ok,csrfToken}` |
| POST `/api/register` | `{username,name,email,password}` | Local demo account; no email verification is claimed |
| GET `/api/search` | `q,category,type,location,from,to,page` optional | `{reports,total,page,pageSize,privateCount,privateCountMessage}`; privateCount is null if unavailable |
| GET `/api/me` | Authenticated | Own history, potential matches, own claims, notifications |
| POST `/api/reports` | Report JSON below | `{report}` |
| GET `/api/reports/{id}` | Current authorization | `{report,canEdit,canClaim,verificationQuestion}` |
| PUT `/api/reports/{id}` | Report JSON including version | `{report}` |
| DELETE `/api/reports/{id}` | `?version=N` | Soft removal `{ok}` |
| POST `/api/reports/{id}/photo` | Raw JPEG/PNG/WebP bytes, correct MIME, `X-Report-Version:N` | `{ok,report}` including incremented version |
| GET `/api/reports/{id}/photo` | Same read policy as report | Sanitized PNG bytes, no-store |
| DELETE `/api/reports/{id}/photo` | `X-Report-Version:N` | `{ok,report}` with new version |
| POST `/api/claims` | `{foundReportId,lostReportId?,answer,explanation}` | Pending claim ID; no answer-comparison feedback |
| GET `/api/admin` | Admin | Report overview, users, claims with `answerMatches`, audit |
| POST `/api/admin/claims/{id}/decision` | `{decision:"APPROVED"|"REJECTED",reason}` | `{ok}`; transactional decision |

Report JSON:

```json
{
  "type": "FOUND",
  "visibility": "PRIVATE",
  "title": "Black canvas backpack",
  "description": "Black canvas backpack with silver zipper and red front loop.",
  "category": "BAGS",
  "subcategory": "BACKPACK",
  "color": "Black",
  "brand": "",
  "eventDate": "2026-11-21",
  "location": "Leavey Library",
  "privateDetails": "Optional identifying notes for owner/admin only.",
  "verificationQuestion": "Describe the exterior patch near the handle.",
  "verificationAnswer": "small yellow moon",
  "version": 1
}
```

Use an actual nonfuture date when testing. `version` is required for PUT, ignored on POST. LOST visibility is forced PRIVATE and verification fields are optional. A FOUND create requires a question and answer. On edit send both verification fields empty (or the unchanged question with an empty answer) to retain the stored pair, or send a question and answer to replace it. Private notes are independent. The expected answer/hash is never returned to any client; the finder can replace it. Do not send multipart form data for photos.

Public report DTO fields: `id,type,visibility,title,description,category,subcategory,color,brand,eventDate,location,status,version,createdAt,updatedAt,hasPhoto`. A private item DTO is returned only from an authorized detail/dashboard/admin path. Owner/admin detail additionally includes `privateDetails` and its verification question, but no answer/hash/contact fields. Search never contains private DTOs. Session responses contain only the authenticated account's ID, username, name, and role.
