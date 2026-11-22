package edu.usc.lostfound;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Validates uploads by their contents and produces a metadata-free PNG. */
public final class PhotoService {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_DIMENSION = 10_000;
    public static final long MAX_PIXELS = 32_000_000L;

    private PhotoService() {}

    public record Sanitized(byte[] bytes, String mimeType) {}

    /**
     * The caller must authorize the report and enforce the request-body limit
     * before reading the request into memory. This method repeats the byte limit
     * as defense in depth. Original bytes and metadata must never be stored.
     */
    public static Sanitized sanitize(byte[] input, String declaredMime) {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("Choose a JPEG, PNG, or WebP photo.");
        }
        if (input.length > MAX_BYTES) {
            throw new IllegalArgumentException("The photo must be no larger than 10 MiB.");
        }
        String detected = detectMime(input);
        String declared = declaredMime == null ? "" : declaredMime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!detected.equals(declared)) {
            throw new IllegalArgumentException("The photo content does not match its JPEG, PNG, or WebP content type.");
        }

        BufferedImage decoded = null;
        BufferedImage clean = null;
        ImageReader reader = null;
        try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                if ("image/webp".equals(detected)) {
                    throw new IllegalArgumentException("WebP decoding is unavailable on this server. Upload JPEG or PNG, or install the configured WebP ImageIO provider.");
                }
                throw new IllegalArgumentException("The photo could not be decoded. Choose a valid image.");
            }
            reader = readers.next();
            String format = reader.getFormatName().toLowerCase(Locale.ROOT);
            if (!("image/jpeg".equals(detected) && (format.equals("jpeg") || format.equals("jpg")))
                    && !("image/png".equals(detected) && format.equals("png"))
                    && !("image/webp".equals(detected) && format.equals("webp"))) {
                throw new IllegalArgumentException("The photo format is not supported.");
            }
            List<String> warnings = new ArrayList<>();
            reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
            reader.setInput(stream, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            validateDimensions(width, height);
            decoded = reader.read(0);
            if (decoded == null || !warnings.isEmpty()) {
                throw new IllegalArgumentException("The photo is damaged or incomplete. Upload a complete image.");
            }
            validateDimensions(decoded.getWidth(), decoded.getHeight());

            // Drawing copies pixels into a fresh color model. The writer receives
            // no input IIOMetadata, EXIF, XMP, comments, or original file bytes.
            clean = new BufferedImage(width, height,
                    decoded.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = clean.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.drawImage(decoded, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            return new Sanitized(encodePng(clean), "image/png");
        } catch (IOException e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof PhotoTooLarge limit) throw limit;
                cause = cause.getCause();
            }
            throw new IllegalArgumentException("The photo is damaged or could not be decoded.");
        } finally {
            if (reader != null) reader.dispose();
            if (decoded != null) decoded.flush();
            if (clean != null) clean.flush();
        }
    }

    private static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("The photo must be at most 10,000 pixels per side and 32 million pixels in total.");
        }
    }

    private static String detectMime(byte[] bytes) {
        if (bytes.length >= 8 && unsigned(bytes[0]) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && unsigned(bytes[4]) == 0x0d && unsigned(bytes[5]) == 0x0a
                && unsigned(bytes[6]) == 0x1a && unsigned(bytes[7]) == 0x0a) return "image/png";
        if (bytes.length >= 3 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8
                && unsigned(bytes[2]) == 0xff) return "image/jpeg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B'
                && bytes[11] == 'P') return "image/webp";
        throw new IllegalArgumentException("Only valid JPEG, PNG, and WebP images are accepted.");
    }

    private static int unsigned(byte value) { return value & 0xff; }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IllegalArgumentException("The server could not sanitize this photo.");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (LimitedImageOutput output = new LimitedImageOutput(bytes, MAX_BYTES)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), null);
            output.flush();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static final class PhotoTooLarge extends IllegalArgumentException {
        private PhotoTooLarge() {
            super("The sanitized photo exceeds 10 MiB. Reduce its dimensions and try again.");
        }
    }

    private static final class LimitedImageOutput extends MemoryCacheImageOutputStream {
        private final int limit;
        private LimitedImageOutput(ByteArrayOutputStream bytes, int limit) {
            super(bytes);
            this.limit = limit;
        }
        @Override public void write(int value) throws IOException {
            requireSpace(1);
            super.write(value);
        }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            requireSpace(length);
            super.write(data, offset, length);
        }
        private void requireSpace(int extra) throws IOException {
            if (extra > limit - getStreamPosition()) throw new PhotoTooLarge();
        }
    }
}
