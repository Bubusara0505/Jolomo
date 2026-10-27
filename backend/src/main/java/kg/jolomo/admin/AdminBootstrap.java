package kg.jolomo.admin;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Первый администратор. Работает только если задан ADMIN_BOOTSTRAP_LOGIN и в базе ещё нет ни одного администратора.
 * Секреты печатаются в консоль один раз (не в журнал) и нигде не сохраняются.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminService admins;
    private final String login;

    AdminBootstrap(AdminService admins, @Value("${jolomo.admin.bootstrap-login:}") String login) {
        this.admins = admins;
        this.login = login;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (login == null || login.isBlank()) {
            return;
        }
        if (admins.anyAdminExists()) {
            log.info("Администратор уже есть, ADMIN_BOOTSTRAP_LOGIN игнорируется");
            return;
        }
        AdminService.Enrollment e = admins.create(login.trim(), "Owner", AdminRole.OWNER, (UUID) null, "BOOTSTRAP");
        System.out.println("""

                ================ ПЕРВЫЙ АДМИНИСТРАТОР (показывается ОДИН раз) ================
                login:    %s
                password: %s
                TOTP key: %s   (в приложении-аутентификаторе: «ввести ключ вручную», тип «по времени»)
                otpauth:  %s
                recovery: %s
                Сохраните это в менеджере паролей и уберите ADMIN_BOOTSTRAP_LOGIN из окружения.
                =============================================================================
                """.formatted(e.login(), e.password(), e.totpSecret(), e.otpauthUri(), String.join("  ", e.recoveryCodes())));
    }
}
