package kg.jolomo.verification;

import java.util.EnumSet;
import java.util.Set;

/** Состояния профиля водителя. Проверка вручную, без OCR: SUBMITTED, IN_REVIEW, APPROVED или REJECTED, затем EXPIRED. */
public enum DriverProfileStatus {
    UNVERIFIED,
    SUBMITTED,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    EXPIRED,
    SUSPENDED;

    /** Обычные переходы. Приостановка и снятие приостановки идут отдельными методами сервиса. */
    public Set<DriverProfileStatus> next() {
        return switch (this) {
            case UNVERIFIED, REJECTED, EXPIRED -> EnumSet.of(SUBMITTED);
            case SUBMITTED -> EnumSet.of(IN_REVIEW);
            case IN_REVIEW -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED -> EnumSet.of(EXPIRED);
            case SUSPENDED -> EnumSet.noneOf(DriverProfileStatus.class);
        };
    }
}
