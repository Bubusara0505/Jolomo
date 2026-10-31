package kg.jolomo.push;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Подпись VAPID (RFC 8292): JWT с алгоритмом ES256 на ключе P-256. Только стандартная криптография JDK, без внешних библиотек.
 * Ключи в окружении: открытый (65 байт, несжатая точка) и закрытый (32 байта), оба в base64url без «=».
 */
public final class VapidSigner {

    private static final Pattern SUBJECT = Pattern.compile("^(mailto:|https://)[^\\s\"\\\\]{3,200}$");
    private static final Duration LIFETIME = Duration.ofHours(12);   // спецификация разрешает до 24 часов
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final PrivateKey privateKey;
    private final String publicKey;
    private final String subject;
    private final Clock clock;

    public VapidSigner(String publicKeyB64, String privateKeyB64, String subject, Clock clock) {
        byte[] pub = decode(publicKeyB64, "PUSH_VAPID_PUBLIC_KEY");
        if (pub.length != 65 || pub[0] != 0x04) {
            throw new IllegalStateException("PUSH_VAPID_PUBLIC_KEY должен быть несжатой точкой P-256 (65 байт) в base64url");
        }
        byte[] priv = decode(privateKeyB64, "PUSH_VAPID_PRIVATE_KEY");
        if (priv.length != 32) {
            throw new IllegalStateException("PUSH_VAPID_PRIVATE_KEY должен быть 32 байта в base64url");
        }
        if (subject == null || !SUBJECT.matcher(subject).matches()) {
            throw new IllegalStateException("PUSH_VAPID_SUBJECT должен быть адресом вида mailto:… или https://…");
        }
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
            this.privateKey = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, priv), spec));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось разобрать закрытый ключ VAPID", e);
        }
        this.publicKey = B64.encodeToString(pub);
        this.subject = subject;
        this.clock = clock;
    }

    private static byte[] decode(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " не задан");
        }
        try {
            return Base64.getUrlDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " не в формате base64url");
        }
    }

    public String publicKey() {
        return publicKey;
    }

    /** Значение заголовка Authorization для push-службы этого адреса: {@code vapid t=<jwt>, k=<открытый ключ>}. */
    public String authorization(URI endpoint) {
        String audience = "https://" + endpoint.getHost().toLowerCase(java.util.Locale.ROOT);
        long exp = clock.instant().plus(LIFETIME).getEpochSecond();
        String header = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = B64.encodeToString(
                ("{\"aud\":\"" + audience + "\",\"exp\":" + exp + ",\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
        String input = header + "." + claims;
        try {
            Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
            sig.initSign(privateKey);
            sig.update(input.getBytes(StandardCharsets.UTF_8));
            return "vapid t=" + input + "." + B64.encodeToString(sig.sign()) + ", k=" + publicKey;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось подписать VAPID", e);
        }
    }
}
