package kg.jolomo.events;

import java.nio.file.Path;

/**
 * Настройки ночной выгрузки. Ключ псевдонимов только из окружения (EXPORT_PSEUDONYM_KEY) и не равен другим секретам.
 * Выгрузка по умолчанию выключена: включается явно и только вместе с ключом.
 */
public record ExportSettings(boolean enabled, Path dir, String pseudonymKey) {

    public ExportSettings {
        if (enabled && (pseudonymKey == null || pseudonymKey.length() < 16)) {
            throw new IllegalStateException("EXPORT_PSEUDONYM_KEY не задан или короче 16 символов");
        }
    }
}
