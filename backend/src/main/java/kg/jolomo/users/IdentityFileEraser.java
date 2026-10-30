package kg.jolomo.users;

import java.util.UUID;

/** Порт модуля users: стереть зашифрованный файл личности. Реализует модуль verification, у которого есть каталог файлов. */
public interface IdentityFileEraser {
    void erase(UUID identityObjectId);
}
