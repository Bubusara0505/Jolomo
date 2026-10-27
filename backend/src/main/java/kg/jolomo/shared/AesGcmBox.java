package kg.jolomo.shared;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM для небольших секретов в базе. Шифртекст привязан к aad (например, к id владельца). */
public class AesGcmBox {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmBox(String base64Key, String envName) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(envName + " не задан");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(envName + " должен быть в base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(envName + " должен быть 32 байта (openssl rand -base64 32)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] seal(byte[] plain, String aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось зашифровать", e);
        }
    }

    public byte[] open(byte[] sealed, String aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось расшифровать", e);
        }
    }
}
