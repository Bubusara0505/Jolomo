package kg.jolomo.events;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Псевдоним вместо идентификатора: HMAC-SHA256 с ключом выгрузки, первые 12 байт в hex. Одна сущность даёт один и тот
 * же псевдоним во всех выгрузках (аналитик может склеивать события), но без ключа вернуть идентификатор нельзя.
 */
final class Pseudonymizer {

    private final SecretKeySpec key;

    Pseudonymizer(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    String of(UUID id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(("jolomo-export-v1:" + id).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
