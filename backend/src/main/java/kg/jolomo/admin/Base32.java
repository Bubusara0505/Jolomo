package kg.jolomo.admin;

/** Base32 по RFC 4648 без заполнения: так секрет TOTP вводится в приложение-аутентификатор. */
final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {}

    static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] decode(String s) {
        String in = s.replace("=", "").replace(" ", "").toUpperCase();
        byte[] out = new byte[in.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int idx = 0;
        for (char c : in.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Не base32");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out;
    }
}
