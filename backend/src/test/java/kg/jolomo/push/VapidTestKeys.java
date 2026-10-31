package kg.jolomo.push;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/** Пара ключей VAPID для тестов, в том же формате, что печатает tools/VapidKeygen.java. */
record VapidTestKeys(String publicKey, String privateKey, ECPublicKey ecPublic) {

    static VapidTestKeys generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = gen.generateKeyPair();
            ECPublicKey pub = (ECPublicKey) pair.getPublic();
            byte[] point = new byte[65];
            point[0] = 0x04;
            System.arraycopy(fixed(pub.getW().getAffineX()), 0, point, 1, 32);
            System.arraycopy(fixed(pub.getW().getAffineY()), 0, point, 33, 32);
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            return new VapidTestKeys(b64.encodeToString(point), b64.encodeToString(fixed(((ECPrivateKey) pair.getPrivate()).getS())), pub);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] fixed(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length > 32) {
            return Arrays.copyOfRange(raw, raw.length - 32, raw.length);
        }
        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }
}
