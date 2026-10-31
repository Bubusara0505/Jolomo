package kg.jolomo.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Устройства шлюза из настройки SMS_GATEWAY_DEVICES: «имя:sha256-токена», через запятую. На сервере лежит только хеш
 * токена, сам токен только на телефоне. Отзыв устройства значит убрать его из настройки и перезапустить сервер.
 */
public final class GatewayDevices {

    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final Map<String, byte[]> hashes = new LinkedHashMap<>();

    public GatewayDevices(String spec) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        for (String item : spec.split(",")) {
            String[] parts = item.trim().split(":");
            if (parts.length != 2 || !NAME.matcher(parts[0]).matches() || !HASH.matcher(parts[1]).matches()) {
                throw new IllegalStateException("SMS_GATEWAY_DEVICES: ожидается имя:sha256 через запятую, имя из a-z 0-9 -");
            }
            if (hashes.put(parts[0], HexFormat.of().parseHex(parts[1])) != null) {
                throw new IllegalStateException("SMS_GATEWAY_DEVICES: имя устройства повторяется: " + parts[0]);
            }
        }
    }

    public boolean isEmpty() {
        return hashes.isEmpty();
    }

    /** Имя устройства по токену или пусто. Сравнение за постоянное время по всем устройствам. */
    public Optional<String> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        byte[] presented = sha256(token);
        String found = null;
        for (Map.Entry<String, byte[]> e : hashes.entrySet()) {
            if (MessageDigest.isEqual(presented, e.getValue())) {
                found = e.getKey();
            }
        }
        return Optional.ofNullable(found);
    }

    /** SHA-256 токена в hex: это значение кладётся в SMS_GATEWAY_DEVICES. */
    public static String hashOf(String token) {
        return HexFormat.of().formatHex(sha256(token));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
