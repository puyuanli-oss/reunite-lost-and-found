# REUNITE — reports, photos & privacy-aware discovery

A complete **report/photo module** for the team's Lost and Found project: browser UI, Java HTTP API, relational temporary database, deterministic matching, and a local claim-review demonstration. This implements the latest request on top of the supplied Technical Specifications, Detailed Design, and Testing Plan. Read `docs/DESIGN.md` for decisions that resolve conflicting earlier drafts.

**Start here:** install a full **JDK 17+** (JDK 21 recommended), extract this folder, then run:

| Windows | macOS / Linux |
|---|---|
| `run.cmd` | `bash run.sh` |

Open **http://localhost:8080**. The precompiled application JAR is included. The **first run needs internet** to download H2 and image-reader libraries into `lib/`; later runs can work offline. Those third-party JARs could not be bundled because downloads were blocked in the authoring environment. Source compilation and 443 automated checks passed; full JDBC, real WebP, and MySQL execution have **not** been verified here. See `TEST_RESULTS.md`.

## What is included

- Responsive HTML/CSS/JavaScript search, detail, create/edit, photo-preview, dashboard, authentication, and administrator screens; no frontend build or CDN.
- A **Private found report** checkbox: unchecked means public. Lost reports are private so detailed ownership descriptions do not become public evidence.
- Public browsing, combined filters, pagination, relevance ordering, and authenticated **aggregate-only** private-found counts. A general search never returns a private report's ID, description, photo, question, or URL.
- Private detail **and photo** access only for the finder, administrator, or an authenticated user with their own active qualifying lost report. Every read recomputes eligibility; editing/removing a lost report revokes later access.
- Strong category/subtype weights and hard contradiction gates; uncertain values do not veto. A score is a discovery lead, never proof of ownership.
- Owner-only report changes, version conflicts, soft removal, normalized and metadata-stripped photos, 10 MiB upload limits, and SQL BLOB photo storage.
- Prepared JDBC statements, transactional claim decisions, one-approved-claim database constraint, answer verification recorded for administrators, deduplicated match notifications, and audit entries.
- H2 file database for development; a MySQL schema and JDBC configuration for the team's next stage.

## Local demonstration

These are deliberately public **demo accounts**, unrelated to real people or email addresses.

| Account | Password | Role / example |
|---|---|---|
| `alice` | `AliceDemo1!` | Has a lost black canvas backpack that matches Bob's private report |
| `bob` | `BobDemo1!` | Owns public and private found reports |
| `admin` | `AdminDemo1!` | Can inspect reports and review ownership claims |

1. Browse as a guest: only public reports appear.
2. Sign in as Alice; search **backpack**. General search shows a private count, without private cards.
3. Open **My reports → Potential matches** to open Bob's matching private backpack.
4. To demonstrate the full creation gate from scratch, register a new local demo account, search backpack, then file a lost report describing a **black canvas backpack, silver zipper, red front loop, library study desk**, Bags → Backpack, Leavey Library, and a date no later than the found date.
5. A claim additionally asks the finder's question. In the seed fixture, its answer is **small yellow moon**. Only an administrator sees whether the submitted answer matched. A matching answer does not approve the claim automatically.
6. For a photo demo, sign in as Bob, edit his private backpack, and attach a JPEG/PNG/WebP. Repeat the guest/Alice detail and direct-photo access checks in `docs/ACCEPTANCE.md`.

Seed data is created only when the user table is empty. Dates are relative to the campus date when first seeded. The application does not overwrite an existing database on restart.

## Eclipse

1. **File → Import → Maven → Existing Maven Projects**; select the folder containing `pom.xml`.
2. Configure a full JDK 17+ and allow Maven dependency resolution.
3. Open `src/main/java/edu/usc/lostfound/App.java` → **Run As → Java Application**.
4. Set the run configuration's working directory to this extracted folder.
5. Open the printed localhost URL in a browser. No Tomcat installation is required.

`docs/BUILD.md` covers Maven, Windows, classpaths, offline operation, and compiler requirements. To rebuild after editing source, use `build.cmd` / `bash build.sh`; the launcher uses the included JAR until it is rebuilt. Run Java checks with `scripts\test.cmd` / `bash scripts/test.sh`. Maven's default test phase does not execute these plain-Java test entry points.

## Temporary SQL database

Default location: **`data/lostfound.mv.db`**, created on first launch. It stores reports, sanitized photos, users, matches, claim records, and notifications across restarts. Stop the server before backing up or moving it. This is a development database, not a browser-local mock or a JSON file.

For an ephemeral database, set `DB_URL=jdbc:h2:mem:lostfound;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1` for the Java process; it disappears at process exit. For a fresh demo, stop the server and move the whole `data` directory aside before restarting. Keep any reports/photos you want before resetting.

### MySQL

Create a database with `utf8mb4`, then set these variables in Eclipse's **Run Configurations → Environment** or your shell:

```text
DB_URL=jdbc:mysql://localhost:3306/lostfound?sslMode=REQUIRED&serverTimezone=UTC
DB_USER=your_application_user
DB_PASSWORD=your_local_database_password
DEMO_MODE=false
```

Do not put real database passwords in source control. The application initializes its portable schema; `sql/mysql-schema.sql` is also supplied for review/manual setup. Use a dedicated application database user. MySQL support is supplied as an integration path and has not been run here. Existing external user/session services should replace local demo authentication before shared deployment.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `BIND_ADDRESS` | `127.0.0.1` | Local-only demo binding |
| `DB_URL` | H2 file URL | SQL database connection |
| `DB_USER` | `sa` | H2 local development username |
| `DB_PASSWORD` | empty | H2 local development password |
| `DEMO_MODE` | `true` | Seed demo accounts and enable explicitly unverified local registration |
| `COOKIE_SECURE` | `false` | Set `true` when running behind HTTPS |

## Scope and integration boundary

This deliverable completes the requested reports/photos/UI/temporary-SQL module and supplies a working-source demonstration of authentication, notifications, and claims around it. The earlier documents also describe a larger platform. **Live email verification/recovery, a timed three-day claim queue, email delivery/retry, account-removal/fraud workflows, physical collection, administrator reopening, and production deployment are not implemented in this module.** They are identified in `docs/DESIGN.md` so the team does not mistake a demo button for a verified external service.

Local authentication uses salted PBKDF2-HMAC-SHA256, server-side sessions, 30-minute idle expiry, CSRF tokens, and login limits. Earlier drafts alternately name BCrypt and Argon2id; production account integration should choose and audit one policy. With demo mode disabled, demo registration and new fixture creation are disabled; existing fixtures are not silently deleted. This switch alone does not turn the module into a production identity system.

Exact private counts inherently reveal aggregate existence, and a dishonest user can invent a plausible lost report. Rate limits and stricter matching reduce probing; neither proves ownership or prevents all inference. Finder questions and administrator review remain separate. See `docs/DESIGN.md` and `docs/MATCHING.md` for the precise privacy contract and limitations.

## Files to review

| File / directory | Contents |
|---|---|
| `src/main/resources/web/` | Actual HTML, CSS, JavaScript UI |
| `src/main/java/edu/usc/lostfound/App.java` | Routes, access checks, search, reports, claims |
| `MatchingEngine.java` | Weighted matcher and hard gates |
| `PhotoService.java` | Image validation and normalization |
| `Database.java` / `src/main/resources/schema.sql` | JDBC, fixtures, SQL initialization |
| `sql/` | H2 and MySQL schemas |
| `docs/DESIGN.md` / `docs/API.md` | Architecture, decisions, integration contract |
| `docs/ACCEPTANCE.md` | Browser/security walkthrough |
| `scripts/integration_test.py` | Real HTTP smoke test to run after starting the app |
| `TEST_RESULTS.md` | Actual validation evidence and outstanding checks |
| `dist/lostfound.jar` | Precompiled application, excluding third-party dependencies |
