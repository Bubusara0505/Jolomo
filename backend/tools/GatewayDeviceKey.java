import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Генератор токена устройства SMS-шлюза. Запуск из папки backend: java tools/GatewayDeviceKey.java pixel-1
 * Токен вводится в приложение на телефоне и нигде больше не хранится. На сервер идёт только хеш:
 * export SMS_GATEWAY_DEVICES="pixel-1:<хеш>"
 */
public class GatewayDeviceKey {

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "gateway-1";
        if (!name.matches("[a-z0-9-]{1,32}")) {
            System.err.println("Имя устройства: a-z, 0-9 и дефис, до 32 символов");
            System.exit(1);
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        System.out.println("Токен для телефона (показывается один раз, на сервере его нет):");
        System.out.println("  " + token);
        System.out.println();
        System.out.println("Строка для сервера (добавьте через запятую к существующим устройствам):");
        System.out.println("  export SMS_GATEWAY_DEVICES=\"" + name + ":" + hash + "\"");
    }
}
