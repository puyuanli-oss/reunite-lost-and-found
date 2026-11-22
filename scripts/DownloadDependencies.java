import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** One-time dependency installer. Uses Java 17+; no shell package manager required. */
class DownloadDependencies {
    private static final String BASE = "https://repo.maven.apache.org/maven2/";
    private static final String VERSION = "3.15.3";
    private static final List<String> ARTIFACTS = List.of(
        "com/h2database/h2/2.3.232/h2-2.3.232.jar",
        "com/twelvemonkeys/imageio/imageio-webp/" + VERSION + "/imageio-webp-" + VERSION + ".jar",
        "com/twelvemonkeys/imageio/imageio-core/" + VERSION + "/imageio-core-" + VERSION + ".jar",
        "com/twelvemonkeys/imageio/imageio-metadata/" + VERSION + "/imageio-metadata-" + VERSION + ".jar",
        "com/twelvemonkeys/common/common-lang/" + VERSION + "/common-lang-" + VERSION + ".jar",
        "com/twelvemonkeys/common/common-io/" + VERSION + "/common-io-" + VERSION + ".jar",
        "com/twelvemonkeys/common/common-image/" + VERSION + "/common-image-" + VERSION + ".jar",
        "com/mysql/mysql-connector-j/9.2.0/mysql-connector-j-9.2.0.jar"
    );
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(25)).build();

    public static void main(String[] args) {
        try {
            Files.createDirectories(Path.of("lib"));
            for (String artifact : ARTIFACTS) install(artifact);
            System.out.println("Dependencies installed. Run run.sh (macOS/Linux) or run.cmd (Windows).");
        } catch (Exception e) {
            System.err.println("Dependency installation stopped: " + e.getMessage());
            System.err.println("Connect to the internet and retry. In Eclipse you can instead use Maven > Update Project,");
            System.err.println("then run edu.usc.lostfound.App. For an offline computer, copy the lib directory from a successful installation.");
            System.exit(1);
        }
    }

    private static void install(String artifact) throws Exception {
        String fileName = artifact.substring(artifact.lastIndexOf('/') + 1);
        Path target = Path.of("lib", fileName);
        Path stamp = Path.of(target + ".verified.sha256");
        if (Files.isRegularFile(target) && Files.isRegularFile(stamp)
                && digest(Files.readAllBytes(target), "SHA-256").equals(Files.readString(stamp).trim())) {
            System.out.println("Verified local " + fileName);
            return;
        }
        String expected = null;
        String algorithm = null;
        // Maven Central commonly publishes SHA-1 for older artifacts. Prefer SHA-256/512.
        for (String[] option : List.of(new String[]{".sha256", "SHA-256", "64"},
                                      new String[]{".sha512", "SHA-512", "128"},
                                      new String[]{".sha1", "SHA-1", "40"})) {
            HttpResponse<byte[]> checksum = get(BASE + artifact + option[0]);
            if (checksum.statusCode() == 404) continue;
            if (checksum.statusCode() != 200) throw new IOException("HTTP " + checksum.statusCode() + " retrieving checksum for " + fileName);
            if (checksum.body().length > 4096) throw new IOException("Invalid checksum response for " + fileName);
            String candidate = new String(checksum.body(), StandardCharsets.US_ASCII).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (!candidate.matches("[0-9a-f]{" + option[2] + "}")) throw new IOException("Invalid published checksum for " + fileName);
            expected = candidate;
            algorithm = option[1];
            break;
        }
        if (expected == null) throw new IOException("No published checksum for " + fileName);
        HttpResponse<byte[]> response = get(BASE + artifact);
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " downloading " + fileName);
        byte[] bytes = response.body();
        if (bytes.length < 4 || bytes.length > 100 * 1024 * 1024 || bytes[0] != 'P' || bytes[1] != 'K')
            throw new IOException("Invalid JAR response for " + fileName);
        if (!digest(bytes, algorithm).equals(expected)) throw new IOException("Checksum mismatch for " + fileName);
        Path temporary = Files.createTempFile(Path.of("lib"), ".dependency-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(stamp, digest(bytes, "SHA-256") + "\n", StandardCharsets.US_ASCII);
        } finally {
            Files.deleteIfExists(temporary);
        }
        System.out.println("Installed " + fileName + " (published " + algorithm + " verified)");
    }

    private static HttpResponse<byte[]> get(String uri) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(60))
            .header("User-Agent", "REUNITE-Dependency-Setup/1.0").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static String digest(byte[] bytes, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }
}
