package kg.jolomo.push;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Общие помощники тестов Web Push. */
final class PushFixture {

    static final String FCM = "https://fcm.googleapis.com/fcm/send/";

    private static int phoneSeq = 0;

    private PushFixture() {}

    static PushSettings settings(boolean enabled) {
        return new PushSettings(enabled, "pub", "priv", "mailto:ops@jolomo.kg", List.of(), 90, 5);
    }

    static UUID user(JdbcClient jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, :p, 'ACTIVE', now())")
                .param("id", id)
                .param("p", String.format("+99655522%04d", ++phoneSeq))
                .update();
        return id;
    }

    static String endpoint(String name) {
        return FCM + name;
    }
}
