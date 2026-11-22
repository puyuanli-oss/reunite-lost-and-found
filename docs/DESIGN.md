# Design decisions and integration contract

## Scope and precedence

The latest request is authoritative: implement report and photo pages with temporary SQL storage; add public/private choice to found reports; allow broad public search and authenticated aggregate private counts; reveal private found details only through an owned lost-report match; give reliable classifications more influence and consider vetoes. The supplied Technical Specifications and Detailed Design inform field names, categories, authorization, photos, and matching. Their inconsistent older examples (5 MB versus 10 MB, PNG typo, BCrypt versus Argon2id, contradictory claim states) are resolved explicitly here.

This module is Java 17-compatible source, vanilla HTML/CSS/JavaScript, JDK HttpServer, and prepared JDBC SQL. H2 supplies a temporary file-backed database; MySQL uses the same relational boundary and a dedicated schema. It deliberately avoids a frontend toolchain or servlet-container installation for Eclipse demonstrations. Matching is synchronous on save in this small prototype, whereas the larger design proposes background workers. Global rescore cost is O(lost × found); it is not a measured 10,000-report production solution.

## Report visibility and authorization

| Resource | Guest | Signed in, no qualifying lost report | Owner | Signed in, own qualifying active lost report | Administrator |
|---|---|---|---|---|---|
| Open public found card/detail/photo | Read | Read | Read/edit own open | Read | Read |
| Private found object in general search | No row or count | Aggregate count only | Aggregate count only | Aggregate count only | Aggregate count only; admin overview is separate |
| Private found detail/photo | 404 | 404 | Read/edit own open | Read while match remains eligible | Read |
| Private lost detail/photo | 404 | 404 | Read/edit own open | Other users' lost reports remain private | Read |
| Closed/resolved/removed report | 404 | 404 | History | No matched access | Read |
| Private notes | None | None | Own notes | None from the found report | Read via detail |
| Expected verification answer/hash | None | None | Never returned; answer can be replaced | None | Never returned; admin receives server-computed answer comparison on claims |

LOST reports are private in this module. FOUND reports default public; the single private checkbox is a true boolean with no contradictory two-checkbox state. One primary category and one optional subtype form a consistent hierarchy. This is deliberate: mutually exclusive types use single selection, while the visibility request is implemented as a checkbox.

Detail and photo access share the same policy. Private discovery requires an active FOUND, an authenticated viewer, an owned active LOST, different owners, and a fresh matcher decision. An old match row, report ID, saved URL, photo endpoint, client-side hidden card, or search filter never grants access. Editing/removing a lost report, making a found report incompatible, resolving the found report, or signing out changes the next server authorization decision. Previously disclosed bytes cannot be recalled from a user's memory/device.

## General search versus discovery

General search uses fewer optional fields: keyword, primary category, report type, location, and inclusive date range. Every filter type is combined; meaningful query tokens must all appear in title/description. Text tokens are normalized using the same case/plural cleanup as matching. Sorting is relevance, event date descending, ID descending. At most 12 public cards are returned per page.

Private counts count open private FOUND reports satisfying the same supplied filters. They are available only to authenticated users, and only once a keyword/category/location/date filter exists. The response contains no private record or identifier. LOST-only search therefore has zero private-found matches. Broad queries do not impose the stricter lost-report eligibility gates.

The server limits searches to 60 per minute per account (or remote address for guests) and report creation to 30 per hour per account. Limits are in-process and reset on restart; multiple server instances need shared storage. Exact counts still form an inference oracle through carefully varied queries. This follows the requested exact-count feature; a production deployment seeking stronger privacy should use buckets, minimum cohorts, query budgets, and/or verified affiliation, after an explicit product decision.

A user can fabricate a LOST that describes what they expect to find. Matching is an access condition for discovery, not a cryptographic ownership proof. Visible descriptors, known types, location, and dates may qualify. Sensitive verification evidence is deliberately excluded from scoring and is never shown to the matched user.

## Weights and vetoes

Yes, use vetoes for **clear contradictions**, paired with strong weights for reliable observations. The fixed evidence budget is:

| Feature | Maximum points | Missing or uncertain |
|---|---:|---|
| Same known primary category | 30 | 0 |
| Same known subtype | 20 | 0 |
| Meaningful title/description overlap | 25 | 0 |
| Location token similarity | 10 | 0 |
| Date proximity | 10 | 0 |
| Same color | 3 | 0 |
| Same brand | 2 | 0 |

Known contradictory category/subtype, found-before-lost date, wrong report types, inactive records, same owner, or no meaningful shared description veto the pair. `Other / unsure` is unknown rather than contradictory. Location, color, and brand disagreement do not veto: items move and people describe them differently. Optional missing evidence never increases the remaining weights.

Private discovery additionally needs score ≥65 and meaningful descriptive evidence beyond merely naming the object type. Exact coefficients and regression cases are in `MatchingEngine.java` and `MATCHING.md`. Scores are heuristic evidence points, not calibrated probabilities. These constants require labeled campus examples and false-positive/false-negative evaluation before claiming accuracy. Administrators can inspect all reports when a mistaken classification blocks an otherwise plausible connection; the app never silently overrides a veto.

## Report/photo transactions and lifecycle

Create/edit validates title, description, taxonomy, event date, location, lengths, and found verification fields on the server. Dates must be within the past five years and no later than the current America/Los_Angeles date. Type cannot change on edit. The owner can change only OPEN reports. Removed records are retained, hidden from search, and unavailable to matched viewers.

Report edits/removal use an optimistic `version`. Photos require `X-Report-Version`, lock the same report row, recheck owner/OPEN/version/pending-review state, and increment the report version. Invalid photo bytes are rejected before replacing an existing image. A pending claim freezes found-report and photo changes so evidence is stable during review. An unrelated lost edit can revoke access; approval rechecks linked-lost eligibility before resolving it.

Photo upload is a separate request after report save. If the optional image fails, the report exists and the UI identifies that partial outcome and lets the user retry through edit. Each image is checked by signature and declared MIME, bounded to 10 MiB and 32 million pixels, decoded, rendered into a fresh buffer, and normalized to PNG with metadata removed. Sanitized bytes and MIME live in SQL; no public filesystem path, object-store URL, browser localStorage, or embedded untrusted SVG is used. Image processing is not malware scanning or a guarantee against all parser vulnerabilities. Real WebP support requires the bundled dependency installer to obtain the declared reader.

## Matching records, notifications, and claim review

The pair `(lost_report_id, found_report_id)` is unique. Recompute marks obsolete suggestions withdrawn and updates eligible scores. Initial notification keys are unique per pair/recipient. Dashboard and resource authorization recalculate current evidence rather than trusting stored suggestions. Notifications carry generic wording and no private object details.

Claim submission locks the found row and rechecks current item state, private access and any linked lost report inside the transaction. At most one pending claim per claimant/item is accepted, and no more than three submitted claims per pair are allowed in 24 hours. The expected answer is a salted one-way hash; a submitted answer is normalized, verified transiently, then its independently salted hash and **server-computed comparison boolean** are stored. Correctness is exposed only to administrators; a claimant is never told the correct answer or the comparison result.

An administrator reviews the comparison and supporting explanation, records a reason, and approves/rejects. Administrators cannot decide their own ownership claims. Approval, found/lost resolution, competing-claim closure, notifications, and audit insertion share a transaction; a unique approved-report key is a second safeguard against multiple approvals. This prototype allows pending claims to coexist for administrative review. The earlier FIFO/three-day/email response queue is a separate service and is not simulated as though implemented. Physical collection is also a separate production workflow.

## Security and deployment boundary

Prepared statements bind all application values. Public DTOs use an explicit allowlist; public serializers do not reflect over whole objects. Session IDs and CSRF tokens use random 256-bit values; session identifiers rotate at authentication boundaries. Cookies are HttpOnly and SameSite=Strict; `COOKIE_SECURE=true` is available behind HTTPS. Authorization is always server-side. Logs expose an error reference/class, not passwords, private details, SQL text, or stack traces.

The server defaults to loopback. SQL credentials, the fixture identity service, in-memory session/rate state, full-table prototype search, and exact counts are development choices. Shared deployment needs the team's verified identity/email service, HTTPS, credential management, backups, dependency review, pooling, query indexes/full-text ranking, asynchronous matching, audit operations and realistic load/privacy testing. DEMO_MODE=false is a feature switch, not a production-hardening certification.

## References and traceability

| Source | Used for |
|---|---|
| Latest user request | Found visibility checkbox, count-only search, lost-report gate, reliable-attribute weights/vetoes, Java/SQL/Eclipse delivery |
| Technical Specifications (2).docx §§4–6 | Report fields, photos, matching, public search |
| Detailed Design (2).docx §§5–10 | Report/photo authorization, pair uniqueness, claims and evidence |
| Testing Plan and Code.docx | Guest/owner restrictions, invalid uploads, search, matches, claim integrity |
| [JDK HttpServer](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html) | Embedded Java server |
| [JDBC Connection](https://docs.oracle.com/en/java/javase/17/docs/api/java.sql/java/sql/Connection.html) | Transactions and connection boundary |
| [H2 features](https://h2database.com/html/features.html) | File/in-memory databases, MySQL compatibility limitations |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) | Runtime WebP reader |
