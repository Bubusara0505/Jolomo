package kg.jolomo.admin;

import java.util.EnumSet;
import java.util.Set;

/** Роли с наименьшими правами. Что разрешено, задаётся здесь и проверяется на сервере. */
public enum AdminRole {
    /** Проверяет заявки водителей. */
    REVIEWER(EnumSet.of(AdminPermission.VERIFICATION_REVIEW)),
    /**
     * Поддержка: принимает инциденты, приостанавливает водителей, отмечает подозрительную активность, снимает блокировку проверки.
     * Не завершает доставку, не меняет вместимость и не принимает решение по документам.
     */
    SUPPORT(EnumSet.of(AdminPermission.INCIDENT_RECORD, AdminPermission.DRIVER_SUSPEND, AdminPermission.ACTIVITY_FLAG)),
    /** Владелец: основной администратор и поддержка, единственный, кто управляет учётными записями. */
    OWNER(EnumSet.allOf(AdminPermission.class));

    private final Set<AdminPermission> permissions;

    AdminRole(Set<AdminPermission> permissions) {
        this.permissions = permissions;
    }

    public boolean allows(AdminPermission p) {
        return permissions.contains(p);
    }
}
