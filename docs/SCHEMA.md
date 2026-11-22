# Database implementation

REUNITE uses a real relational SQL database through JDBC. The default H2 database is a file at `data/lostfound.mv.db`; it survives application restarts. This is demonstration storage, not a production database deployment. A MySQL 8+ JDBC URL selects MySQL instead. The supplied MySQL SQL file is a one-time fresh-schema import; normal application startup also creates missing tables and indexes.

| Table | Purpose | Important constraint |
| --- | --- | --- |
| `users` | Normalized login identity and salted password hash | Unique username and email |
| `reports` | Searchable report attributes and lifecycle | Owner foreign key; type, visibility and status checks; optimistic `version` |
| `report_secrets` | Private evidence and verification question/hash | One row per report, separate from public projection |
| `photos` | Sanitized binary image plus MIME type and size | One image per report; 10 MiB maximum; report foreign key |
| `matches` | Candidate lost–found pairs | One row per pair; score 0–100; active or withdrawn |
| `notifications` | In-app messages | Optional unique event key suppresses duplicate notifications |
| `claims` | Ownership requests, private verification result and administrator decisions | Nullable unique `approved_report_id` permits one approved claim per found report |
| `audit` | Administrative action, actor, reason and timestamp | Actor/report foreign keys |

`photos` uses H2 `BLOB` and MySQL `LONGBLOB`; the MySQL `BLOB` type alone would be too small for the allowed upload size. Serving a photo always requires the same application authorization as serving its report. Database storage does not make a photo publicly addressable.

Prepared statements bind application data. Report creation and private evidence writes belong in one transaction; approving a claim, resolving reports and closing competing claims likewise belong in one transaction. The application locks the relevant report while changing claim state. Database foreign keys and the single-approval unique constraint provide a second layer of protection.

The schema intentionally permits multiple non-approved claims and multiple candidate matches per report. A candidate is never a uniqueness assertion or proof of ownership. When a report changes, existing match rows can be withdrawn and later reactivated without creating another pair record.

Claim submission compares the normalized answer with the protected expected answer while the supplied answer is in memory. Only the resulting `answer_matches` boolean is exposed to the administrator review interface. Independently salted answer hashes are not directly comparable.

Only the public projection of a report should be returned by general search. `owner_id`, private evidence, verification hashes, contact details and photo binary are excluded. The server decides whether an authenticated user has an eligible active lost report before returning a private found report or its image. A historical match row alone is insufficient: its current eligibility must be checked again.

## Connection settings

- Default: `jdbc:h2:file:./data/lostfound;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE`
- `DB_URL`: H2 or MySQL JDBC URL.
- `DB_USER`: default `sa` for the local H2 demonstration; set a dedicated database user for MySQL.
- `DB_PASSWORD`: default empty for the local H2 demonstration; supply a database password for MySQL.

H2 MySQL compatibility mode does not establish full MySQL equivalence. Run the application integration tests against the actual target MySQL server before deployment. Back up the database while the application is stopped, or use the database engine's supported backup method.

## Demonstration data

`Database.seed()` is explicitly invoked by demo mode only. It inserts fixtures only when `users` is empty. It creates Alice, Bob and an administrator, five public found reports, two private found reports and Alice's private lost backpack report. Alice's backpack and Bob's private backpack demonstrate the match-gated reveal. All fixture dates are relative to the current date in `America/Los_Angeles`. Do not use demonstration accounts or data in a deployed service.
