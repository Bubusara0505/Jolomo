package kg.jolomo.admin;

public enum AdminPermission {
    VERIFICATION_REVIEW,
    INCIDENT_RECORD,
    /** Приостановка и снятие приостановки водителя. Допуск и вместимость это не меняет. */
    DRIVER_SUSPEND,
    /** Пометка подозрительной активности (только запись). */
    ACTIVITY_FLAG,
    ADMIN_MANAGE
}
