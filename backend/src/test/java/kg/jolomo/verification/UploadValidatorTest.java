package kg.jolomo.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kg.jolomo.testsupport.Samples;
import kg.jolomo.verification.UploadValidator.Upload;
import org.junit.jupiter.api.Test;

/** SP03-04: тип по содержимому, размер, число файлов, картинки-бомбы, активное содержимое PDF. */
class UploadValidatorTest {

    private final UploadValidator validator = new UploadValidator();

    private static Upload up(String type, byte[] bytes) {
        return new Upload(type, bytes.length, bytes);
    }

    private void rejected(List<Upload> uploads, String reason) {
        assertThatThrownBy(() -> validator.validateAll(uploads))
                .isInstanceOfSatisfying(UploadRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void acceptsJpegPngAndCleanPdf() {
        var ok = validator.validateAll(List.of(
                up("image/jpeg", Samples.jpeg(20, 20)),
                up("image/png", Samples.png(20, 20)),
                up("application/pdf; charset=binary", Samples.pdf())));

        assertThat(ok).extracting(UploadValidator.Validated::contentType)
                .containsExactly("image/jpeg", "image/png", "application/pdf");
        assertThat(ok.get(0).sha256()).hasSize(64);
    }

    @Test
    void executableDisguisedAsImageIsRejected() {
        rejected(List.of(up("image/png", Samples.fakeExe())), "TYPE_NOT_ALLOWED");
    }

    @Test
    void declaredTypeMustMatchContent() {
        rejected(List.of(up("application/pdf", Samples.png(10, 10))), "TYPE_MISMATCH");
        rejected(List.of(up(null, Samples.png(10, 10))), "TYPE_MISMATCH");
    }

    @Test
    void countAndSizeLimits() {
        byte[] png = Samples.png(10, 10);
        rejected(List.of(), "NO_FILES");
        rejected(List.of(up("image/png", png), up("image/png", png), up("image/png", png), up("image/png", png)), "TOO_MANY_FILES");
        rejected(List.of(new Upload("image/png", 0, new byte[0])), "EMPTY_FILE");
        rejected(List.of(new Upload("image/png", UploadValidator.MAX_BYTES + 1, null)), "TOO_LARGE");
        rejected(List.of(up("application/pdf", new byte[(int) UploadValidator.MAX_BYTES + 1])), "TOO_LARGE");
    }

    @Test
    void oversizedImageDimensionsAreRejectedWithoutDecoding() {
        rejected(List.of(up("image/png", Samples.pngClaiming(60_000, 60_000))), "IMAGE_TOO_LARGE");
    }

    @Test
    void brokenImageIsRejected() {
        byte[] truncated = new byte[12];
        System.arraycopy(Samples.png(10, 10), 0, truncated, 0, 12);
        rejected(List.of(up("image/png", truncated)), "INVALID_IMAGE");
    }

    @Test
    void pdfWithActiveContentIsRejected() {
        rejected(List.of(up("application/pdf", Samples.pdfWith("/OpenAction << /S /JavaScript /JS (app.alert(1)) >>"))),
                "ACTIVE_CONTENT");
        rejected(List.of(up("application/pdf", Samples.pdfWith("/Names << /EmbeddedFiles 2 0 R >> /Launch"))), "ACTIVE_CONTENT");
        // Имя, спрятанное через #-экранирование, тоже отклоняется.
        rejected(List.of(up("application/pdf", Samples.pdfWith("/J#61vaScript 3 0 R"))), "ACTIVE_CONTENT");
    }

    @Test
    void pdfWithoutEofIsRejected() {
        rejected(List.of(up("application/pdf", "%PDF-1.4 truncated".getBytes())), "INVALID_PDF");
    }
}
