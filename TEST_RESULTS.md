# Recorded validation — 21 November 2026

This records actual execution for this package. It does not certify the unexecuted integration paths.

| Check | Result |
|---|---|
| Compile all Java application classes and package executable JAR with web/schema resources | **Passed** |
| MatchingEngineTest | **277 checks passed** |
| PhotoServiceTest | **25 checks passed** |
| CoreTest (JSON, password hashes/policy, answer normalization, random-token shape) | **141 checks passed** |
| Total executable Java checks | **443 passed** |
| JavaScript syntax (`node --check`) | **Passed** |
| Shell script syntax (`bash -n`) | **Passed** |
| Python HTTP test syntax compilation | **Passed** |
| Maven POM XML parse | **Passed** |
| JAR contains main/database classes, schema, HTML, CSS, JavaScript; no database files | **Passed** |
| Rendered browser/UI fixture smoke | **Not executed**; Chromium failed before opening a page due to the environment's socket restrictions |
| Actual HTTP/JDBC/H2 persistence and privacy integration | **Not executed**; JDBC dependency download blocked here |
| Actual WebP decoding | **Not executed**; provider dependency download blocked here |
| MySQL execution, concurrent decisions, realistic load | **Not executed** |

Executed command:

```sh
JAVA_RELEASE_FALLBACK=1 ./scripts/test.sh
```

Recorded suite output:

```text
MatchingEngineTest: 277 checks passed.
PhotoServiceTest: 25 checks passed.
CoreTest: 141 checks passed.
```

The available Java 21 runtime includes the compiler module but omits `ct.sym`; therefore this verification used `-source 17 -target 17`. All classes are Java17 bytecode. A full JDK17+ should run the default `--release 17` build before handoff, which also checks the target platform API boundary.

The prebuilt JAR contains the application and assets. H2, MySQL JDBC, and TwelveMonkeys reader JARs are downloaded on the first normal-network launch; they are not inside this ZIP. No actual database file, photo, real personal data, or private credential is included. Documented seed accounts are intentional local demo fixtures.

## Complete the integration checks on your machine

1. Run `run.cmd` or `bash run.sh`, allow dependency setup, and keep the application running.
2. In a second terminal, run `python scripts/integration_test.py` (or `python3` on macOS/Linux). This test creates fixtures in the local demo database; resolved claim history remains. It covers public/private search, direct resource authorization, CSRF, report creation/editing, photo sanitation, access revocation, optimistic versions, answer-comparison privacy, and approval consistency.
3. Follow `docs/ACCEPTANCE.md` in real independent browser sessions.
4. Optionally run `node scripts/ui_smoke.cjs` after installing Playwright and Chromium. This self-hosted **fixture API** test checks frontend behavior only; it does not replace the real Java/JDBC test.
5. Repeat the HTTP checks against the selected MySQL configuration before calling the MySQL path verified. Run a separate measured load/concurrency test before claiming the original documents' performance targets.

No test in steps1–5 is marked passed until it actually executes successfully in that environment.
