package kg.jolomo.verification;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Допуск к публикации поездки. Решает только сервер: профиль должен быть в статусе из таблицы
 * eligibility_rules, и выключатель обязательств должен быть загружен. Иначе отказ (default deny).
 */
@Service
public class EligibilityService {

    public enum Reason { OK, NOT_A_DRIVER, NOT_APPROVED, OBLIGATIONS_NOT_LOADED }

    public record Decision(boolean allowed, Reason reason) {}

    private final JdbcClient jdbc;
    private final DriverProfileService profiles;
    private final ObligationConfigService obligations;

    public EligibilityService(JdbcClient jdbc, DriverProfileService profiles, ObligationConfigService obligations) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.obligations = obligations;
    }

    public Decision canPublishTrip(UUID userId) {
        var profile = profiles.find(userId);
        if (profile.isEmpty()) {
            return new Decision(false, Reason.NOT_A_DRIVER);
        }
        Boolean allowedStatus = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM eligibility_rules WHERE action = 'PUBLISH_TRIP' AND profile_status = :s)
                        """)
                .param("s", profile.get().status().name())
                .query(Boolean.class)
                .single();
        if (!allowedStatus) {
            return new Decision(false, Reason.NOT_APPROVED);
        }
        if (!obligations.isEnabled(ObligationConfigService.TRIP_PUBLISH)) {
            return new Decision(false, Reason.OBLIGATIONS_NOT_LOADED);
        }
        return new Decision(true, Reason.OK);
    }

    /** Принимать и отклонять запросы на места может водитель с профилем в статусе из правил MANAGE_BOOKINGS. */
    public Decision canManageBookings(UUID userId) {
        var profile = profiles.find(userId);
        if (profile.isEmpty()) {
            return new Decision(false, Reason.NOT_A_DRIVER);
        }
        Boolean allowedStatus = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM eligibility_rules WHERE action = 'MANAGE_BOOKINGS' AND profile_status = :s)
                        """)
                .param("s", profile.get().status().name())
                .query(Boolean.class)
                .single();
        return allowedStatus ? new Decision(true, Reason.OK) : new Decision(false, Reason.NOT_APPROVED);
    }

    /** Принимать и отклонять заявки на посылки может водитель с профилем в статусе из правил MANAGE_PARCELS. */
    public Decision canManageParcels(UUID userId) {
        var profile = profiles.find(userId);
        if (profile.isEmpty()) {
            return new Decision(false, Reason.NOT_A_DRIVER);
        }
        Boolean allowedStatus = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM eligibility_rules WHERE action = 'MANAGE_PARCELS' AND profile_status = :s)
                        """)
                .param("s", profile.get().status().name())
                .query(Boolean.class)
                .single();
        return allowedStatus ? new Decision(true, Reason.OK) : new Decision(false, Reason.NOT_APPROVED);
    }
}
