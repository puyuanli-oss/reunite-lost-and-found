# Build and run

Install a full Java Development Kit (JDK) **17 or newer**. Java 21 is recommended. The browser application serves its own HTTP endpoint; Tomcat, Node.js, and a separate frontend build are unnecessary.

From the project directory:

| Task | macOS / Linux | Windows |
|---|---|---|
| Build | `./build.sh` | `build.cmd` |
| Run | `./run.sh` | `run.cmd` |
| Download runtime libraries separately | `./scripts/download-dependencies.sh` | `scripts\download-dependencies.cmd` |
| Run executable Java checks | `./scripts/test.sh` | `scripts\test.cmd` |

Open the address printed by the server. First run downloads runtime SQL and image libraries over HTTPS. The script verifies Maven Central's published checksums, preferring SHA-256 or SHA-512 and falling back to SHA-1 when those are unavailable. It also saves a SHA-256 receipt for subsequent offline checks. An unavailable network or a failed checksum stops setup with an actionable error; no partial artifact is installed.

`build.sh` and `build.cmd` use standard JDK compiler and archive modules and produce `dist/lostfound.jar`. Java source compilation does not require third-party libraries. Runtime SQL needs H2, or the optional MySQL driver; WebP needs the TwelveMonkeys ImageIO service providers. Keep **all** downloaded TwelveMonkeys JARs together in `lib`. The application reads dependencies from `lib/*`, allowing offline subsequent runs.

## Eclipse

1. Install Eclipse IDE for Java Developers with a configured JDK 17+.
2. Choose **File → Import → Maven → Existing Maven Projects**.
3. Select the extracted project directory containing `pom.xml`; finish the import.
4. Allow Maven to download dependencies. If necessary use **Maven → Update Project**.
5. Open `src/main/java/edu/usc/lostfound/App.java`; choose **Run As → Java Application**.
6. Set the run configuration's working directory to the project directory, so the SQL database is stored under that project.

Eclipse's normal Maven classpath includes the runtime image service providers. For a plain non-Maven Java project, add **every** JAR in `lib` to the build path and mark `src/main/resources` as a source folder.

## Maven alternative

With Maven 3.6.3+ and JDK 17+:

```sh
mvn package
java -cp 'target/lostfound.jar:lib/*' edu.usc.lostfound.App
```

On Windows replace the classpath separator `:` with `;`. The `package` phase copies runtime dependencies into `lib`. The project's executable checks use plain Java main methods; run `scripts/test.sh` or `scripts\test.cmd` to execute them. Maven's default `test` lifecycle alone does not run these checks.

## Database modes

The default uses a file-backed H2 SQL database with MySQL compatibility enabled. This is a temporary development database that persists across restarts, avoiding the loss of photos and reports when the server exits. Do not run two server processes against the same database file. MySQL compatibility is not a claim that H2 implements every MySQL feature.

The application accepts `DB_URL`, `DB_USER`, and `DB_PASSWORD`. For MySQL, create a database and use a JDBC URL such as `jdbc:mysql://localhost:3306/lostfound?useSSL=true&serverTimezone=UTC`; use the dedicated MySQL schema in `sql`. Consult the root README for configuration and security settings.

## Build environment verification boundary

The supplied source can be compiled and the Java algorithm/image tests can run without external dependencies. The authoring environment blocked Maven Central and the npm registry and did not contain H2 or WebP readers. A JDBC or WebP integration test requires the dependencies to be installed in the target environment. A successful source compilation does not imply either integration test has passed. See `TEST_RESULTS.md` for the actual recorded checks.

The authoring environment's Java runtime also omitted the JDK's `ct.sym` platform signatures. Its verification build therefore used `JAVA_RELEASE_FALLBACK=1 ./scripts/test.sh`, which selects `-source 17 -target 17`. This validates Java 17 syntax and bytecode, but does not enforce the Java 17 standard-library API boundary. A full JDK uses the default `--release 17` check. Prefer installing the full JDK to enabling this fallback on your computer.

## Optional browser fixture smoke

`node scripts/ui_smoke.cjs` hosts the static assets itself and intercepts `/api` with deterministic test fixtures. It requires the separately installed Playwright package and Chromium (`npm install --no-save playwright` and `npx playwright install chromium`). It checks the frontend's interactions and layouts only. It does not start or validate the Java/JDBC backend. See `TEST_RESULTS.md` for its unexecuted status in the authoring environment.
