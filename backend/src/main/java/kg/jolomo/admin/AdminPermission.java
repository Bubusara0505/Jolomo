package kg.jolomo.admin;

public enum AdminPermission {
    VERIFICATION_REVIEW,
    INCIDENT_RECORD,
    /** Приостановка и снятие приостановки водителя. Допуск и вместимость это не меняет. */
    DRIVER_SUSPEND,
    /** Пометка подозрительной активности (только запись). */
    ACTIVITY_FLAG,
    /** Постановка и снятие legal hold: изъятие пользователя из заданий удаления. Только владелец. */
    LEGAL_HOLD,
    ADMIN_MANAGE
}
