package kg.jolomo.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-HMAC-SHA256 с солью. Формат: pbkdf2-sha256$итерации$соль$хеш. Итерации читаются из самой записи. */
public class PasswordHasher {

    private static final String PREFIX = "pbkdf2-sha256";

    private final int iterations;
    private final SecureRandom random = new SecureRandom();
    private final String dummy;

    public PasswordHasher(int iterations) {
        this.iterations = iterations;
        this.dummy = hash("dummy-password-for-timing");
    }

    public String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return PREFIX + "$" + iterations + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, iterations));
    }

    public boolean verify(String password, String stored) {
        String[] p = stored.split("\\$");
        if (p.length != 4 || !PREFIX.equals(p[0])) {
            return false;
        }
        try {
            byte[] expected = Base64.getDecoder().decode(p[3]);
            byte[] actual = derive(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Тратит столько же времени, сколько настоящая проверка: по времени ответа нельзя узнать, есть ли такой логин. */
    public void verifyDummy(String password) {
        verify(password, dummy);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 недоступен", e);
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
