package kg.jolomo.parcels;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.sms.SmsBodyCipher;
import kg.jolomo.sms.SmsOutboxService;
import kg.jolomo.verification.EligibilityService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** Заготовка тестов кода доставки: посылка на нужном шаге custody, SMS в outbox, код читается из зашифрованного outbox. */
final class DeliveryFixture {

    static final String PEPPER = "test-delivery-pepper-0123456789";
    static final String RECIPIENT_PHONE = "+996700000001";

    final ParcelFixture f;
    final JdbcClient jdbc;
    final SmsBodyCipher cipher;
    final DeliverySettings settings =
            new DeliverySettings(PEPPER, Duration.ofMinutes(120), Duration.ofSeconds(60), 5, "https://jolomo.test/r");
    final DeliveryService delivery;

    record Pending(UUID sender, UUID driver, UUID trip, UUID parcel) {}

    DeliveryFixture(JdbcClient jdbc, TransactionOperations tx, String smsKey) {
        this.jdbc = jdbc;
        this.f = new ParcelFixture(jdbc, tx);
        f.loadParcelObligations();
        this.cipher = new SmsBodyCipher(smsKey);
        AuditLog audit = new AuditLog(jdbc, f.clock);
        EligibilityService eligibility = new EligibilityService(jdbc, f.profiles, f.obligations);
        SmsOutboxService sms = new SmsOutboxService(jdbc, tx, (c, p, b) -> true, cipher, f.clock);
        this.delivery = new DeliveryService(jdbc, tx, f.clock, f.parcels, eligibility, audit, sms, settings);
    }

    /** Посылка у водителя, который доехал и ждёт получателя (DELIVERY_PENDING). */
    Pending pending() {
        return build(true);
    }

    /** Посылка в пути (IN_TRANSIT): водитель ещё не на месте. */
    Pending inTransit() {
        return build(false);
    }

    private Pending build(boolean arrived) {
        UUID sender = f.user();
        UUID driver = f.driver();
        UUID trip = f.publishedTrip(driver, 2);
        UUID parcel = f.parcels.create(sender, f.valid(trip)).id();
        f.parcels.accept(driver, parcel);
        f.parcels.confirmPickup(driver, parcel);
        f.parcels.dispatch(driver, parcel);
        if (arrived) {
            f.parcels.arrive(driver, parcel);
        }
        return new Pending(sender, driver, trip, parcel);
    }

    UUID activeChallenge(UUID parcel) {
        return jdbc.sql("SELECT id FROM delivery_challenges WHERE parcel_id = :p AND status = 'ACTIVE'")
                .param("p", parcel)
                .query(UUID.class)
                .single();
    }

    /** Код из SMS в outbox (расшифровывается тестовым ключом). */
    String codeOf(UUID challengeId) {
        String commandId = "delivery:" + challengeId;
        String body = jdbc.sql("SELECT body FROM sms_messages WHERE command_id = :c").param("c", commandId).query(String.class).single();
        Matcher m = Pattern.compile("(\\d{6})").matcher(cipher.decrypt(body, commandId));
        if (!m.find()) {
            throw new IllegalStateException("В SMS нет кода");
        }
        return m.group(1);
    }

    /** Выдаёт код и возвращает id проверки и сам код (так видит только тест). */
    String[] issue(Pending p) {
        delivery.requestChallenge(p.driver(), p.parcel());
        UUID id = activeChallenge(p.parcel());
        return new String[] {id.toString(), codeOf(id)};
    }

    static String wrongThan(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    String parcelStatus(UUID parcel) {
        return jdbc.sql("SELECT status FROM parcel_requests WHERE id = :id").param("id", parcel).query(String.class).single();
    }

    int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
