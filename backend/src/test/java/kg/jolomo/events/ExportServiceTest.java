package kg.jolomo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import kg.jolomo.bookings.BookingService;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SP12-01: ночная выгрузка без сырых секретов, с псевдонимами и только зарегистрированными событиями. */
class ExportServiceTest extends PostgisTestBase {

    private static final String KEY = "test-pseudonym-key-0123456789";
    private static final Instant FOREVER = Instant.parse("2100-01-01T00:00:00Z");

    @TempDir
    Path dir;

    private PilotFixture f;
    private ExportService export;

    @BeforeEach
    void setUp() {
        f = new PilotFixture(jdbc, tx);
        export = new ExportService(jdbc, f.clock, new MetricsService(jdbc), new ExportSettings(true, dir, KEY));
    }

    @Test
    void exportHasMetricsAndPseudonymsInsteadOfIdentifiersAndNoPhones() {
        UUID driver = f.driver();
        UUID passenger = f.user();
        UUID trip = f.publishedTrip(driver, 3, 2);
        BookingService.Booking b = f.bookings.request(passenger, trip, 1);
        f.bookings.accept(driver, b.id());
        f.parcels.create(f.user(), f.parcelFor(trip));

        String json = export.buildDocument(Instant.EPOCH, FOREVER);

        assertThat(json).contains("\"schemaVersion\":1").contains("\"metrics\":[").contains("\"code\":\"BOOKING_ACCEPTANCE_RATE\"");
        assertThat(json).contains("\"type\":\"BOOKING_ACCEPTED\"").contains("\"type\":\"PARCEL_REQUESTED\"");
        Pseudonymizer p = new Pseudonymizer(KEY);
        assertThat(json).contains(p.of(driver)).contains(p.of(passenger)).contains(p.of(b.id())).contains(p.of(trip));
        for (UUID raw : new UUID[] {driver, passenger, trip, b.id()}) {
            assertThat(json).doesNotContain(raw.toString());
        }
        assertThat(json).doesNotContain("+996").doesNotContain("phone").doesNotContain("recipient");
        assertThat(json).doesNotContain(f.phoneOf(driver)).doesNotContain(f.phoneOf(passenger));
    }

    @Test
    void onlyAllowListedPayloadFieldsLeaveAndPhoneLikeValuesAreDroppedEvenInAllowedFields() {
        UUID booking = UUID.randomUUID();
        Map<String, Object> canary = new LinkedHashMap<>();
        canary.put("phone", "+996555000099");
        canary.put("otp", "424242");
        canary.put("code", "424242");
        canary.put("note", "секретный текст");
        canary.put("recipientPhone", "+996700000077");
        canary.put("from", "+996555000098");          // разрешённое поле, но значение похоже на телефон
        canary.put("to", "ACCEPTED");                 // разрешённое поле с безопасным значением
        canary.put("seats", 2);
        canary.put("tripId", UUID.randomUUID());
        canary.put("passengerId", "not-a-uuid");      // не идентификатор: не выгружаем вовсе
        f.events.append("BOOKING", booking, "BOOKING_REQUESTED", null, canary);

        String json = export.buildDocument(Instant.EPOCH, FOREVER);

        for (String secret : new String[] {"+996555000099", "424242", "секретный текст", "+996700000077", "+996555000098",
                "not-a-uuid", "\"otp\"", "\"note\"", "\"phone\""}) {
            assertThat(json).as(secret).doesNotContain(secret);
        }
        assertThat(json).contains("\"to\":\"ACCEPTED\"").contains("\"seats\":2");
    }

    @Test
    void unregisteredEventTypesAreNotExportedAndOnlyCounted() {
        f.events.append("BOOKING", UUID.randomUUID(), "SECRET_NEW_THING", null, Map.of("to", "X"));

        String json = export.buildDocument(Instant.EPOCH, FOREVER);

        assertThat(json).doesNotContain("SECRET_NEW_THING").contains("\"skippedUnregistered\":1");
    }

    @Test
    void theWindowDecidesWhichEventsAreIncludedButMetricsCoverTheWholeCohort() {
        UUID driver = f.driver();
        UUID trip = f.publishedTrip(driver, 2, 0);
        Instant t0 = f.clock.instant();
        f.bookings.accept(driver, f.bookings.request(f.user(), trip, 1).id());

        String laterWindow = export.buildDocument(t0.plus(Duration.ofDays(1)), t0.plus(Duration.ofDays(2)));
        String earlierWindow = export.buildDocument(t0.minus(Duration.ofDays(2)), t0.minus(Duration.ofDays(1)));
        String full = export.buildDocument(t0.minusSeconds(10), t0.plusSeconds(10));

        assertThat(laterWindow).contains("\"events\":[]");
        assertThat(full).contains("BOOKING_ACCEPTED");
        // Метрики считаются по когорте «все запросы до конца окна»: в позднем окне запрос уже в когорте, в раннем его ещё нет.
        assertThat(laterWindow).contains("\"code\":\"BOOKING_ACCEPTANCE_RATE\",\"numerator\":1,\"denominator\":1,\"value\":1.0");
        assertThat(earlierWindow).contains("\"events\":[]")
                .contains("\"code\":\"BOOKING_ACCEPTANCE_RATE\",\"numerator\":0,\"denominator\":0,\"value\":null");
    }

    @Test
    void pseudonymsAreStableForTheSamePersonAndChangeWithTheKey() {
        UUID id = UUID.randomUUID();
        assertThat(new Pseudonymizer(KEY).of(id)).isEqualTo(new Pseudonymizer(KEY).of(id)).hasSize(24).matches("[0-9a-f]{24}");
        assertThat(new Pseudonymizer(KEY).of(id)).isNotEqualTo(new Pseudonymizer("another-key-0123456789ab").of(id));
        assertThat(new Pseudonymizer(KEY).of(id)).isNotEqualTo(new Pseudonymizer(KEY).of(UUID.randomUUID()));
        assertThat(new Pseudonymizer(KEY).of(id)).doesNotContain(id.toString().substring(0, 8));
    }

    @Test
    void withoutAKeyNothingIsExported() {
        ExportService noKey = new ExportService(jdbc, f.clock, new MetricsService(jdbc), new ExportSettings(false, dir, ""));

        assertThatThrownBy(() -> noKey.buildDocument(Instant.EPOCH, FOREVER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enablingTheExportWithoutAKeyStopsTheStartup() {
        assertThatThrownBy(() -> new ExportSettings(true, dir, "short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ExportSettings(true, dir, null)).isInstanceOf(IllegalStateException.class);
        assertThat(new ExportSettings(false, dir, "").enabled()).isFalse();
    }

    @Test
    void theNightlyFileIsNamedByTheLocalDayAndWrittenOnceWithoutLeftovers() throws IOException {
        UUID driver = f.driver();
        UUID trip = f.publishedTrip(driver, 2, 0);
        f.bookings.accept(driver, f.bookings.request(f.user(), trip, 1).id());
        LocalDate day = f.clock.instant().atZone(ExportService.ZONE).toLocalDate();

        Path file = export.writeFor(day);
        Path again = export.writeFor(day);

        assertThat(file).isEqualTo(again).hasFileName("jolomo-export-" + day + ".json");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("BOOKING_ACCEPTED").contains("\"schemaVersion\":1");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("jolomo-export-" + day + ".json");
        }
    }

    @Test
    void everyEventTypeTheServicesActuallyWriteIsRegistered() {
        UUID driver = f.driver();
        UUID passenger = f.user();
        UUID trip = f.publishedTrip(driver, 4, 3);
        BookingService.Booking b = f.bookings.request(passenger, trip, 1);
        f.bookings.accept(driver, b.id());
        var parcel = f.parcels.create(f.user(), f.parcelFor(trip));
        f.parcels.accept(driver, parcel.id());
        f.parcels.confirmPickup(driver, parcel.id());
        f.parcels.dispatch(driver, parcel.id());
        f.parcels.arrive(driver, parcel.id());
        UUID other = f.publishedTrip(driver, 1, 0);
        f.trips.cancel(driver, other);
        f.clock.advance(Duration.ofDays(2));
        f.bookings.complete(driver, b.id());

        var types = jdbc.sql("SELECT DISTINCT event_type FROM domain_events").query(String.class).list();

        assertThat(types).isNotEmpty();
        assertThat(EventRegistry.types()).containsAll(types);
    }
}
