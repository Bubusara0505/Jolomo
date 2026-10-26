package kg.jolomo.verification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Зашифрованный каталог файлов личности на хосте приложения (AES-256-GCM, свой ключ IDENTITY_KEY).
 * Имя файла только из случайного UUID, имя от пользователя не используется. Публичного URL нет:
 * читать файл может только код сервера. Шифртекст привязан к id файла (AAD), подмена файлом другого id не пройдёт.
 */
public class IdentityFileStore {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path root;
    private final Path tmp;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public IdentityFileStore(Path root, String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("IDENTITY_KEY не задан");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("IDENTITY_KEY должен быть в base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("IDENTITY_KEY должен быть 32 байта (openssl rand -base64 32)");
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.root = root.toAbsolutePath().normalize();
        this.tmp = this.root.resolve("tmp");
        createPrivateDir(this.root);
        createPrivateDir(this.tmp);
    }

    /** Относительный ключ хранения, который записывается в базу. */
    public static String keyFor(UUID id) {
        String s = id.toString();
        return s.substring(0, 2) + "/" + s + ".enc";
    }

    /** Пишет зашифрованный файл: сначала во временный, затем атомарно на место. Временный файл не остаётся никогда. */
    public String write(UUID id, byte[] plain) {
        Path part = tmp.resolve(id + ".part");
        Path target = resolve(id);
        try {
            createPrivateDir(target.getParent());
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(id.toString().getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            Files.write(part, out);
            restrict(part);
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target);
            }
            return keyFor(id);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Не удалось сохранить файл личности", e);
        } finally {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // нечего делать: следующая запись всё равно использует другое имя
            }
        }
    }

    /** Только для кода сервера (выдача админу на 5 минут появится в SP04-02). Наружу напрямую не отдаётся. */
    public byte[] read(UUID id) {
        try {
            byte[] in = Files.readAllBytes(resolve(id));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            c.updateAAD(id.toString().getBytes(StandardCharsets.UTF_8));
            return c.doFinal(in, IV_BYTES, in.length - IV_BYTES);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Не удалось прочитать файл личности", e);
        }
    }

    public void deleteQuietly(UUID id) {
        try {
            Files.deleteIfExists(resolve(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(UUID id) {
        return Files.exists(resolve(id));
    }

    Path root() {
        return root;
    }

    private Path resolve(UUID id) {
        Path p = root.resolve(keyFor(id)).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalStateException("Путь вне каталога");
        }
        return p;
    }

    private static void createPrivateDir(Path dir) {
        try {
            try {
                Files.createDirectories(dir, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // не POSIX-система: права обеспечивает каталог
        }
    }
}
