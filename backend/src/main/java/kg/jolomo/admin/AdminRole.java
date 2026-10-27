package kg.jolomo.admin;

import java.util.EnumSet;
import java.util.Set;

/** Роли с наименьшими правами. Что разрешено, задаётся здесь и проверяется на сервере. */
public enum AdminRole {
    /** Проверяет заявки водителей. */
    REVIEWER(EnumSet.of(AdminPermission.VERIFICATION_REVIEW)),
    /** Принимает инциденты. Не завершает доставку и не меняет вместимость. */
    SUPPORT(EnumSet.of(AdminPermission.INCIDENT_RECORD)),
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
