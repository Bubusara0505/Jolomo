package kg.jolomo.verification;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Загрузка документов проверки. Маршрута, который отдаёт байты, нет: смотреть файл сможет только админ
 * через короткую выдачу на 5 минут (SP04-02).
 */
@RestController
@RequestMapping("/api/v1/verification/cases")
class VerificationController {

    record SubmittedView(UUID caseId, String status, int files) {}

    private final SessionAuth auth;
    private final VerificationCaseService cases;

    VerificationController(SessionAuth auth, VerificationCaseService cases) {
        this.auth = auth;
        this.cases = cases;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SubmittedView submit(HttpServletRequest request, @RequestParam("files") List<MultipartFile> files) {
        UUID userId = auth.requireUserId(request);
        List<VerificationCaseService.FilePart> parts = new ArrayList<>();
        for (MultipartFile f : files) {
            try {
                // Слишком большой файл не читаем в память: сервис всё равно его отклонит.
                byte[] bytes = f.getSize() > UploadValidator.MAX_BYTES ? null : f.getBytes();
                parts.add(new VerificationCaseService.FilePart(f.getContentType(), f.getSize(), bytes));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        VerificationCaseService.Submitted s = cases.submit(userId, parts);
        return new SubmittedView(s.caseId(), "SUBMITTED", s.files());
    }

    @GetMapping("/current")
    VerificationCaseService.CaseView current(HttpServletRequest request) {
        UUID userId = auth.requireUserId(request);
        return cases.latest(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "error.case.notFound"));
    }
}
