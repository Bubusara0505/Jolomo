package kg.jolomo.testsupport;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Синтетические «документы» для тестов. Настоящих сканов в тестах не бывает. */
public final class Samples {

    private Samples() {}

    public static byte[] png(int w, int h) {
        return image(w, h, "png");
    }

    public static byte[] jpeg(int w, int h) {
        return image(w, h, "jpg");
    }

    private static byte[] image(int w, int h, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] pdf() {
        return pdfWith("");
    }

    /** Минимальный PDF; extra подставляется в словарь каталога. */
    public static byte[] pdfWith(String extra) {
        String s = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog " + extra + " >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n";
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Настоящий PNG, у которого в заголовке заявлены огромные размеры (с верной CRC): проверка «бомбы» из пикселей. */
    public static byte[] pngClaiming(int w, int h) {
        byte[] b = png(1, 1);
        ByteBuffer.wrap(b).putInt(16, w).putInt(20, h);
        CRC32 crc = new CRC32();
        crc.update(b, 12, 17); // тип IHDR + 13 байт данных
        ByteBuffer.wrap(b).putInt(29, (int) crc.getValue());
        return b;
    }

    /** Исполняемый файл, выдающий себя за картинку. */
    public static byte[] fakeExe() {
        byte[] b = new byte[256];
        b[0] = 'M';
        b[1] = 'Z';
        return b;
    }
}
