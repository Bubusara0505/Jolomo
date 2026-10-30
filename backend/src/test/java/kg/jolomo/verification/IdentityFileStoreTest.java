package kg.jolomo.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import kg.jolomo.testsupport.Samples;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SP03-04: файлы личности лежат только зашифрованными и привязаны к своему id. */
class IdentityFileStoreTest {

    private static final String KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";

    @TempDir
    Path dir;

    private long files() throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void roundTripAndNothingReadableOnDisk() throws Exception {
        IdentityFileStore store = new IdentityFileStore(dir, KEY);
        UUID id = UUID.randomUUID();
        byte[] png = Samples.png(10, 10);

        store.write(id, png);

        byte[] onDisk = Files.readAllBytes(dir.resolve(IdentityFileStore.keyFor(id)));
        assertThat(onDisk).isNotEqualTo(png);
        // На диске нет ни сигнатуры PNG, ни служебного блока внутри файла (случайные байты шифртекста этого не дают).
        byte[] head = java.util.Arrays.copyOfRange(onDisk, 0, 8);
        assertThat(head).isNotEqualTo(java.util.Arrays.copyOfRange(png, 0, 8));
        assertThat(new String(onDisk, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("IHDR").doesNotContain("IDAT");
        assertThat(store.read(id)).isEqualTo(png);
        assertThat(files()).isEqualTo(1); // временного файла не осталось
    }

    @Test
    void fileCannotBeSwappedUnderAnotherId() throws Exception {
        IdentityFileStore store = new IdentityFileStore(dir, KEY);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        store.write(a, Samples.png(10, 10));
        store.write(b, Samples.png(12, 12));

        Files.copy(dir.resolve(IdentityFileStore.keyFor(a)), dir.resolve(IdentityFileStore.keyFor(b)),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThatThrownBy(() -> store.read(b)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedFileFailsAndDeleteRemovesIt() throws Exception {
        IdentityFileStore store = new IdentityFileStore(dir, KEY);
        UUID id = UUID.randomUUID();
        store.write(id, Samples.pdf());
        Path p = dir.resolve(IdentityFileStore.keyFor(id));
        byte[] bytes = Files.readAllBytes(p);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(p, bytes);

        assertThatThrownBy(() -> store.read(id)).isInstanceOf(IllegalStateException.class);
        store.deleteQuietly(id);
        assertThat(store.exists(id)).isFalse();
    }

    @Test
    void missingOrShortKeyRefusesToStart() {
        assertThatThrownBy(() -> new IdentityFileStore(dir, "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new IdentityFileStore(dir, "c2hvcnQ=")).isInstanceOf(IllegalStateException.class);
    }
}
