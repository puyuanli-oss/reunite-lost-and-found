Runtime libraries are downloaded here on the first run, or by scripts/download-dependencies.sh (.cmd on Windows).
The application source compiles using only Java's standard library. Runtime SQL requires H2 (or the MySQL connector when DB_URL selects MySQL); WebP decoding requires TwelveMonkeys ImageIO.
Dependencies are declared in pom.xml. No dependency binaries were available in the restricted build environment.
