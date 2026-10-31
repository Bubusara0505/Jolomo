import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Генератор ключей VAPID для Web Push (SP11-02). Запуск из папки backend, без сборки и без внешних библиотек:
 *   java tools/VapidKeygen.java
 * Печатает две строки для окружения сервера. Закрытый ключ секретный: в репозиторий, чат и журналы не попадает.
 * Открытый ключ потом отдаёт браузеру маршрут GET /api/v1/push/public-key. Смена ключей отключит все текущие подписки.
 */
public class VapidKeygen {

    public static void main(String[] args) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = gen.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        ECPrivateKey priv = (ECPrivateKey) pair.getPrivate();

        byte[] point = new byte[65];
        point[0] = 0x04;
        System.arraycopy(fixed(pub.getW().getAffineX()), 0, point, 1, 32);
        System.arraycopy(fixed(pub.getW().getAffineY()), 0, point, 33, 32);

        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        System.out.println("PUSH_VAPID_PUBLIC_KEY=" + b64.encodeToString(point));
        System.out.println("PUSH_VAPID_PRIVATE_KEY=" + b64.encodeToString(fixed(priv.getS())));
    }

    /** Ровно 32 байта, со знаковым нулём спереди убранным и с нулями дополненным. */
    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        if (raw.length > 32) {
            return Arrays.copyOfRange(raw, raw.length - 32, raw.length);
        }
        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
