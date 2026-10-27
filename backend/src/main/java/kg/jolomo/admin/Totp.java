package kg.jolomo.admin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** TOTP по RFC 6238: HMAC-SHA1, 6 цифр, шаг 30 секунд, окно плюс-минус один шаг. */
final class Totp {

    static final int STEP_SECONDS = 30;
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

    private Totp() {}

    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0F;
            int bin = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16) | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 недоступен", e);
        }
    }

    static long stepAt(long epochSeconds) {
        return epochSeconds / STEP_SECONDS;
    }

    /**
     * @return шаг, которым подошёл код, либо -1. Шаг не больше lastStep отклоняется: один код нельзя использовать дважды.
     */
    static long verify(byte[] secret, String input, long epochSeconds, long lastStep) {
        if (input == null) {
            return -1;
        }
        String in = input.trim();
        if (!SIX_DIGITS.matcher(in).matches()) {
            return -1;
        }
        long current = stepAt(epochSeconds);
        long found = -1;
        for (long s = current - 1; s <= current + 1; s++) {
            boolean equal = MessageDigest.isEqual(
                    code(secret, s).getBytes(StandardCharsets.US_ASCII), in.getBytes(StandardCharsets.US_ASCII));
            if (equal && s > lastStep) {
                found = Math.max(found, s);
            }
        }
        return found;
    }
}
