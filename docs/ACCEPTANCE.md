# Validation evidence and manual acceptance

This file separates executed checks from the browser/database acceptance work that still needs a runtime with the JDBC and WebP dependencies available. An acceptance step below is an expected result, not a claim that the step has already passed.

## Recorded automated checks

| Check | Recorded result | What it establishes |
| --- | --- | --- |
| Matching engine regression suite | 277 checks passed | Deterministic scores, eligibility gates, token normalization, missing attributes, bounds, and private-discovery evidence requirements |
| Photo service regression suite | 25 checks passed | JPEG/PNG decoding, MIME/content checks, metadata removal, transparency, truncated/spoofed files, byte limits, dimension/pixel limits, and bounded sanitized output |
| JSON/password/session-token boundary | 141 checks passed | Strict JSON parsing, large integer precision, malformed input rejection, salted hash verification, password policy, token shape |
| Java compilation and JAR packaging | Passed | All source classes and browser assets package successfully |
| JavaScript / shell / Python syntax and Maven XML | Passed | Static syntax/structure checks only |
| Rendered browser interaction with fixture API | Not executed; Chromium could not start in this environment | The reusable `scripts/ui_smoke.cjs` remains available for the team |
| Full HTTP → JDBC → browser flow | Not executed in the supplied environment | Requires the H2 dependency and a running application |
| Real WebP upload | Not executed in the supplied environment | Requires the configured TwelveMonkeys WebP provider and its dependencies |
| MySQL execution and migration | Not executed | Must be verified against the team's actual MySQL version and credentials |
| Concurrent database claim decisions / load testing | Not executed | Requires a running SQL database; source-level locking is not a substitute for a concurrency test |

The portable test entry points are `scripts/test.sh` and `scripts/test.cmd`. The package's README describes the normal build, dependency download, and Eclipse launch steps. Retain the command output when repeating these checks on the team's machine.

## Browser setup

1. Start the application with its default local H2 configuration and demo mode enabled. Open the URL printed by the launcher, normally `http://localhost:8080`.
2. Use separate browser profiles or private windows for independent sessions. Normal tabs in the same browser profile share the session cookie. Open browser Developer Tools → Network while checking authorization and validation responses.
3. Use these **local demo fixtures only**: `alice` / `AliceDemo1!`, `bob` / `BobDemo1!`, and `admin` / `AdminDemo1!`. Existing data is not reset on every start. Begin the walkthrough with a fresh demo database or account for earlier edits.

The seeded private found item is Bob's **Black canvas backpack** at Leavey Library. Alice owns a matching private lost report. Alice's seeded lost date is the day before the found date. Bob's verification answer is `small yellow moon`. The fixture is deliberately reproducible for a local demonstration.

## Public search and private-count boundary

| Action | Expected result |
| --- | --- |
| Search as a guest without filters | Active public cards appear; no private report card or private item description is returned |
| Search `backpack` as a guest | The private backpack does not appear; the private count is unavailable and the UI invites sign-in |
| Sign in and search `backpack` | A count of matching active private found reports appears; the search response contains no private report IDs, descriptions, photo URLs, verification question, or secret details |
| Search without a keyword, category, location, or date filter | The private count is withheld until a useful filter is supplied |
| Search with a query that matches no item | “No item matches this search” appears; unrelated public cards are not substituted |
| Change keyword capitalization or redundant spaces | Equivalent normalized searches produce equivalent relevant results |
| Combine category, location, report type, and inclusive date filters | Every returned public item satisfies all selected filter types; private counts obey the same filters |
| Open a known private report or its `/api/reports/{id}/photo` URL as a guest or an unrelated signed-in account | Both are denied with the same non-enumerating 404 used for an unavailable report |

For an unrelated account, use the local demo registration form or another account without a matching lost report. Demo registration explicitly does not send or verify email.

## Alice/Bob match, photo access, and revocation

Perform this before submitting a claim, so a pending review does not intentionally freeze the found report.

1. Sign in as **Bob**, open his private backpack from My reports, and attach an ordinary JPEG or PNG. Confirm that the photo survives a page reload. Record the report ID from the API request or the detail URL.
2. In Alice's independent session, open My reports. Confirm that the seeded lost backpack has a possible match. Open that found report: its details and photo should load. The UI must identify this as a possible match; it must not identify Alice as the confirmed owner.
3. Verify that neither Alice's search response nor her report detail response contains Bob's account/contact information, the expected answer, an answer hash, or Bob's administrator-only notes. Alice may see the verification question when eligible to claim.
4. Edit **Alice's lost report**, changing its primary category to Electronics and its subtype to Phone, with an unrelated valid description. Save and reload My reports. The private backpack should no longer appear as a match.
5. Request Bob's previously recorded private report URL and its photo URL again as Alice. Both must now return 404. Repeat after removing Alice's active lost report instead of editing it; removal must also revoke subsequent access.
6. Restore an active lost report with the original backpack description, Bags → Backpack, compatible dates, and Leavey Library. A qualifying match should again unlock the report. Repeated saves must not duplicate the same pair's initial notification.
7. Sign out and retry both URLs. Both must again be denied. A previously downloaded image is already known to the person who viewed it; revocation governs subsequent server requests.

## Report forms and photos

| Action | Expected result |
| --- | --- |
| Create a FOUND report with the private checkbox unchecked | It is PUBLIC and discoverable in general public search |
| Create a FOUND report with the private checkbox checked | Search reveals only the permitted aggregate count; details/photos require owner/admin access or an eligible active lost report |
| Create a LOST report | It is PRIVATE and participates in automatic matching |
| Omit the required title, description, category, date, location, or found verification question/answer | The server rejects the request with an actionable validation message; valid nonsensitive input is retained where applicable |
| Select a subcategory belonging to a different primary category by editing a request | The server rejects the request |
| Enter a future date or a date more than five years ago | The server rejects the report |
| Save without a photo | A deliberate placeholder appears |
| Upload an ordinary JPEG, PNG, and a real WebP | JPEG/PNG work; WebP works when its provider is installed; all served output is sanitized `image/png` |
| Rename HTML/SVG or arbitrary bytes to `.png`, or send a JPEG body with `image/png` | The server rejects the upload based on content/MIME validation |
| Upload a raw body over 10 MiB, even without a trustworthy Content-Length | The handler rejects it without retaining an unbounded body |
| Upload a tiny PNG whose header advertises over 10,000 pixels per side or over 32 million pixels | The service rejects dimensions before full pixel decoding |
| Upload a PNG containing a text metadata marker, or a JPEG containing EXIF/comment metadata | Downloaded sanitized bytes do not contain the source metadata marker |
| Replace an existing photo with an invalid upload | The previous valid photo remains intact |
| Save a report successfully, then fail its optional photo upload | The interface states that the report was saved and the photo failed, with a retry path |
| Replace/remove another person's photo by direct API request | The server denies the request even if the UI control was manually reproduced |
| Edit a stale version of a report in a second tab | The server rejects the stale edit rather than silently overwriting the newer version |
| Replace/remove a photo with an absent or stale `X-Report-Version` | The server rejects the change; a successful photo mutation increments the report version |
| Replace/remove a found item's photo while an ownership claim is pending | The server temporarily freezes the photo during review |
| Try to edit/remove a resolved or removed report | The server rejects the state transition |

## Matching behavior

Use a new lost report for each independent case, or restore the previous fixture before changing the next attribute.

| Comparison | Expected result |
| --- | --- |
| Bags → Backpack against Electronics → Phone | Hard veto, regardless of shared color/location words |
| Bags → Backpack against Bags → Purse | Reliable subtype contradiction veto |
| Found event date before lost event date | Hard veto |
| Same user's lost and found reports | No self-match |
| Only generic item/type words shared | Insufficient evidence to disclose a private report |
| Strong compatible category/subtype and descriptive overlap | Ranked possible match when the fixed score reaches the configured threshold |
| Different color or nearby/uncertain location | A softer score difference; no standalone hard veto |
| Missing optional color, brand, or subtype | No unsupported points; the available evidence is not renormalized to 100 |
| Capitalization and supported singular/plural variations | Do not independently prevent matching |
| Removed or resolved report | Does not unlock private discovery |

The weights and evidence rules are documented in `MATCHING.md`. Scores rank discovery candidates and never approve ownership.

## Accounts, claims, and administrator review

1. Confirm that an incorrect username/password produces a generic credential error and clears the password. A successful sign-in should update navigation, and sign-out should revoke authenticated API access. Cookies should be HttpOnly and SameSite; use the documented Secure-cookie setting when serving over HTTPS.
2. Attempt a report mutation without a session or with an absent/incorrect CSRF token. The server must deny it. A regular user must be denied `/api/admin` and administrator decision endpoints.
3. As Alice, submit a claim on Bob's matched backpack with the demo answer and supporting explanation. Only one pending claim for Alice/item may exist. Bob must not be able to claim his own item.
4. Submit an intentionally wrong answer in a separate test case. The correct answer must never be echoed to the claimant or included in a public response. Administrator review must have usable, server-verified answer evidence; independently salted hashes must not be compared as strings.
5. As the administrator, review and reject a claim with a reason. The claimant should see the decision/history and be able to retry within the three-attempts-per-item-per-24-hours limit. A fourth submitted attempt must be denied.
6. Submit eligible claims from two separate users, then approve one. Only one approval may succeed. The found report resolves, a linked eligible lost report resolves where applicable, competing pending claims close, an audit record is created, and the relevant notifications are recorded. Repeat concurrent approval requests as a separate database concurrency test.
7. Attempt to decide the same claim again, approve a claim against an inactive found report, or approve an administrator's own claim. Each must be denied. Keep decisions and reasons free of the expected verification answer because review notes can be visible to the claimant.

Email delivery, verification-code account activation, password recovery, timed three-day queue service, and broader account moderation require the team's corresponding services. Do not treat a local demo account or an in-app notification as evidence that an external email was delivered.

## Persistence and MySQL handoff

1. Create a distinctive report and attach a photo. Stop the Java process normally, restart it from the **same working directory with the same DB_URL**, sign in again, and verify the report/photo/history persist. Sessions are in memory, so sign-in again after restart is expected.
2. Confirm that sanitized photo bytes are stored under the report's foreign key and that changing report visibility does not create a publicly accessible file path.
3. Repeat report save/remove, match revocation, invalid-upload rollback, claims, and single-approval tests against a disposable MySQL database using the supplied MySQL schema/configuration. The H2 compatibility mode is a development convenience; it does not establish MySQL correctness.
4. Record database product/version, Java version, build command, test output, browser version, and any failing acceptance step. Run concurrency and representative-volume/load checks before deployment; no 100-user performance claim is made by this package's portable unit tests.
