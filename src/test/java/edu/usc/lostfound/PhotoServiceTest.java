package edu.usc.lostfound;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Dependency-free regression tests: execute this class with assertions enabled. */
public final class PhotoServiceTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        byte[] png = image("png", true, 16, 12);
        PhotoService.Sanitized sanitized = PhotoService.sanitize(png, "image/png");
        check("image/png".equals(sanitized.mimeType()), "canonical PNG response type");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(sanitized.bytes()));
        check(decoded.getWidth() == 16 && decoded.getHeight() == 12, "dimensions preserved");
        check((decoded.getRGB(0, 0) >>> 24) == 64, "transparent alpha retained");
        byte[] jpeg = image("jpeg", false, 16, 12);
        PhotoService.Sanitized converted = PhotoService.sanitize(jpeg, "image/jpeg");
        check("image/png".equals(converted.mimeType()), "JPEG converted to PNG");
        check(ImageIO.read(new ByteArrayInputStream(converted.bytes())) != null, "converted photo decodes");
        check(PhotoService.sanitize(png, "IMAGE/PNG; charset=binary").bytes().length > 0, "case-insensitive MIME parsing");

        reject(null, "image/png", "empty upload");
        reject(new byte[0], "image/png", "zero-byte upload");
        reject(png, "image/jpeg", "MIME mismatch");
        reject(png, null, "missing MIME");
        reject("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8), "image/png", "SVG spoof");
        reject("<html>not an image</html>".getBytes(StandardCharsets.UTF_8), "image/jpeg", "HTML spoof");
        reject(new byte[PhotoService.MAX_BYTES + 1], "image/png", "raw upload size limit");
        reject(Arrays.copyOf(png, 24), "image/png", "truncated PNG");
        reject(new byte[]{(byte)0xff, (byte)0xd8, (byte)0xff, 0, 0}, "image/jpeg", "JPEG signature alone is insufficient");

        byte[] metadata = pngWithText(png, "Location", "SECRET_GPS_34.0219_-118.4814");
        byte[] clean = PhotoService.sanitize(metadata, "image/png").bytes();
        check(!new String(clean, StandardCharsets.ISO_8859_1).contains("SECRET_GPS"), "PNG text metadata stripped");
        check(ImageIO.read(new ByteArrayInputStream(clean)) != null, "metadata-free output remains valid");

        byte[] comment = jpegWithComment(jpeg, "SECRET_LOCATION_AND_NAME");
        clean = PhotoService.sanitize(comment, "image/jpeg").bytes();
        check(!new String(clean, StandardCharsets.ISO_8859_1).contains("SECRET_LOCATION"), "JPEG comment metadata stripped");

        reject(pngWithDimensions(png, 10_001, 1), "image/png", "width bomb rejected before pixel decode");
        reject(pngWithDimensions(png, 1, 10_001), "image/png", "height bomb rejected before pixel decode");
        reject(pngWithDimensions(png, 8_000, 8_000), "image/png", "pixel-count bomb rejected before pixel decode");
        byte[] exactLimit = Arrays.copyOf(png, PhotoService.MAX_BYTES);
        check(PhotoService.sanitize(exactLimit, "image/png").bytes().length < 1024,
                "exact raw limit accepted and trailing bytes discarded");

        byte[] webpSignature = new byte[]{'R','I','F','F', 4,0,0,0, 'W','E','B','P'};
        reject(webpSignature, "image/webp", "WebP requires a real decoder and valid pixels");

        // Small JPEG uploads may expand beyond the storage bound when normalized.
        BufferedImage noise = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(175);
        int[] row = new int[noise.getWidth()];
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < row.length; x++) row[x] = random.nextInt(0x1000000);
            noise.setRGB(0, y, row.length, 1, row, 0, row.length);
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        ImageIO.write(noise, "jpeg", compressed);
        check(compressed.size() < PhotoService.MAX_BYTES, "large-photo input is under upload limit");
        // JPEG compression reduces entropy enough that the generated PNG may fit.
        // Either outcome must obey the stored-byte limit and remain a valid image.
        try {
            PhotoService.Sanitized photo = PhotoService.sanitize(compressed.toByteArray(), "image/jpeg");
            check(photo.bytes().length <= PhotoService.MAX_BYTES, "normalized output bounded");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("sanitized photo exceeds"), "expanded output rejected with actionable error");
        }
        noise.flush();
        System.out.println("PhotoServiceTest: " + checks + " checks passed.");
    }

    private static byte[] image(String format, boolean alpha, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, 0x404488cc);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        image.flush();
        return output.toByteArray();
    }

    private static byte[] pngWithText(byte[] png, String key, String value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(png, 0, 33); // signature and IHDR
        byte[] text = (key + '\0' + value).getBytes(StandardCharsets.ISO_8859_1);
        output.write(chunk("tEXt", text));
        output.write(png, 33, png.length - 33);
        return output.toByteArray();
    }

    private static byte[] pngWithDimensions(byte[] png, int width, int height) throws Exception {
        byte[] changed = Arrays.copyOf(png, png.length);
        byte[] ihdr = Arrays.copyOfRange(changed, 16, 29);
        for (int i = 0; i < 4; i++) {
            ihdr[i] = (byte) (width >>> (24 - i * 8));
            ihdr[4 + i] = (byte) (height >>> (24 - i * 8));
        }
        System.arraycopy(chunk("IHDR", ihdr), 0, changed, 8, 25);
        return changed;
    }

    private static byte[] chunk(String type, byte[] data) throws Exception {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(output);
        stream.writeInt(data.length);
        stream.write(name);
        stream.write(data);
        stream.writeInt((int) crc.getValue());
        return output.toByteArray();
    }

    private static byte[] jpegWithComment(byte[] jpeg, String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(output);
        stream.write(jpeg, 0, 2);
        stream.writeShort(0xfffe);
        stream.writeShort(text.length() + 2);
        stream.write(text.getBytes(StandardCharsets.US_ASCII));
        stream.write(jpeg, 2, jpeg.length - 2);
        return output.toByteArray();
    }

    private static void reject(byte[] bytes, String mime, String name) {
        try {
            PhotoService.sanitize(bytes, mime);
            throw new AssertionError("Expected rejection: " + name);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isBlank(), name);
        }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
}
