package kg.jolomo.verification;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Проверка загружаемых документов: тип по содержимому (не по имени и не по заголовку клиента), размер, число файлов,
 * размеры картинки, активное содержимое PDF. Это не антивирус: файлы только хранятся и отдаются админу как вложение.
 */
@Component
class UploadValidator {

    static final int MAX_FILES = 3;
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final int MAX_SIDE = 12_000;
    static final long MAX_PIXELS = 50_000_000L;

    record Upload(String declaredType, long size, byte[] bytes) {}

    record Validated(String contentType, byte[] bytes, String sha256) {}

    private static final byte[] PNG_SIG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final Pattern ACTIVE_PDF = Pattern.compile(
            "/(JavaScript|JS|Launch|OpenAction|AA|EmbeddedFile|RichMedia|XFA)\\b");
    // Имена с #xx-экранированием прячут ключевые слова от проверки.
    private static final Pattern OBFUSCATED_NAME = Pattern.compile("/[A-Za-z0-9_]*#[0-9A-Fa-f]{2}");

    List<Validated> validateAll(List<Upload> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            throw reject("NO_FILES", HttpStatus.BAD_REQUEST);
        }
        if (uploads.size() > MAX_FILES) {
            throw reject("TOO_MANY_FILES", HttpStatus.BAD_REQUEST);
        }
        List<Validated> out = new ArrayList<>();
        for (Upload u : uploads) {
            out.add(validate(u));
        }
        return out;
    }

    private Validated validate(Upload u) {
        if (u.size() <= 0) {
            throw reject("EMPTY_FILE", HttpStatus.BAD_REQUEST);
        }
        if (u.size() > MAX_BYTES || u.bytes() == null || u.bytes().length > MAX_BYTES) {
            throw reject("TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        byte[] b = u.bytes();
        String sniffed = sniff(b);
        if (sniffed == null) {
            throw reject("TYPE_NOT_ALLOWED", HttpStatus.BAD_REQUEST);
        }
        if (!sniffed.equals(normalize(u.declaredType()))) {
            throw reject("TYPE_MISMATCH", HttpStatus.BAD_REQUEST);
        }
        if (sniffed.equals("application/pdf")) {
            checkPdf(b);
        } else {
            checkImage(b);
        }
        return new Validated(sniffed, b, sha256(b));
    }

    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && startsWith(b, PNG_SIG)) {
            return "image/png";
        }
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "application/pdf";
        }
        return null;
    }

    private static void checkImage(byte[] b) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(b))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw reject("INVALID_IMAGE", HttpStatus.BAD_REQUEST);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0) {
                    throw reject("INVALID_IMAGE", HttpStatus.BAD_REQUEST);
                }
                if (w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
                    throw reject("IMAGE_TOO_LARGE", HttpStatus.BAD_REQUEST);
                }
            } finally {
                reader.dispose();
            }
        } catch (UploadRejectedException e) {
            throw e;
        } catch (Exception e) {
            throw reject("INVALID_IMAGE", HttpStatus.BAD_REQUEST);
        }
    }

    private static void checkPdf(byte[] b) {
        String text = new String(b, StandardCharsets.ISO_8859_1);
        int tailStart = Math.max(0, text.length() - 2048);
        if (!text.substring(tailStart).contains("%%EOF")) {
            throw reject("INVALID_PDF", HttpStatus.BAD_REQUEST);
        }
        if (ACTIVE_PDF.matcher(text).find() || OBFUSCATED_NAME.matcher(text).find()) {
            throw reject("ACTIVE_CONTENT", HttpStatus.BAD_REQUEST);
        }
    }

    private static String normalize(String declared) {
        if (declared == null) {
            return "";
        }
        int semi = declared.indexOf(';');
        return (semi >= 0 ? declared.substring(0, semi) : declared).trim().toLowerCase();
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (b[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static UploadRejectedException reject(String reason, HttpStatus status) {
        return new UploadRejectedException(reason, status);
    }
}
