package kg.jolomo.parcels;

import static kg.jolomo.parcels.ParcelFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import kg.jolomo.events.DomainEventStore.StoredEvent;
import kg.jolomo.shared.IdempotencyService;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP07-04: создание посылки. Default deny, проверка поездки, размера, категории и телефона получателя. */
class ParcelServiceTest extends PostgisTestBase {

    private static final String KEY = "parcel-key-001";

    private ParcelFixture f;
    private UUID driver;
    private UUID sender;
    private UUID trip;

    @BeforeEach
    void setUp() {
        f = new ParcelFixture(jdbc, tx);
        driver = f.driver();
        sender = f.user();
        trip = f.publishedTrip(driver, 2);
    }

    // ---------- запрет по умолчанию ----------

    @Test
    void withoutLoadedObligationsEvenAPerfectRequestIsRefusedAndNothingIsStored() {
        expectCode(() -> f.parcels.create(sender, f.valid(trip)), "OBLIGATIONS_NOT_LOADED");

        assertThat(f.parcelCount()).isZero();
        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(2);
    }

    @Test
    void theTripObligationsDoNotOpenParcels() {
        // В фикстуре TRIP_PUBLISH загружен (иначе поездку не опубликовать), а PARCEL_CREATE нет.
        assertThat(f.obligations.isEnabled("TRIP_PUBLISH")).isTrue();
        expectCode(() -> f.parcels.create(sender, f.valid(trip)), "OBLIGATIONS_NOT_LOADED");
    }

    @Test
    void disablingTheObligationsClosesCreationAgain() {
        f.loadParcelObligations();
        f.parcels.create(sender, f.valid(trip));

        f.obligations.disable("PARCEL_CREATE", ParcelFixture.ADMIN, "LEGAL_REVIEW");

        expectCode(() -> f.parcels.create(f.user(), f.valid(trip)), "OBLIGATIONS_NOT_LOADED");
        assertThat(f.parcelCount()).isEqualTo(1L);
    }

    // ---------- успешное создание ----------

    @Test
    void validRequestIsStoredAsRequestedWithTheObligationVersionAndTakesNoSlot() {
        f.loadParcelObligations();

        ParcelService.Parcel p = f.parcels.create(sender, f.valid(trip));

        assertThat(p.status()).isEqualTo(ParcelStatus.REQUESTED);
        assertThat(p.size()).isEqualTo("S");
        assertThat(p.category()).isEqualTo("DOCUMENTS");
        assertThat(p.senderId()).isEqualTo(sender);
        assertThat(p.tripId()).isEqualTo(trip);
        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT obligation_version FROM parcel_requests WHERE id = :id").param("id", p.id())
                        .query(String.class).single())
                .isEqualTo("leg-v1");
        assertThat(jdbc.sql("SELECT driver_user_id FROM parcel_requests WHERE id = :id").param("id", p.id())
                        .query(UUID.class).single())
                .isEqualTo(driver);
    }

    @Test
    void theRecipientPhoneIsStoredButNeverLeavesInViewsJsonOrEvents() {
        f.loadParcelObligations();

        ParcelService.Parcel p = f.parcels.create(sender, f.valid(trip));

        assertThat(jdbc.sql("SELECT recipient_phone FROM parcel_requests WHERE id = :id").param("id", p.id())
                        .query(String.class).single())
                .isEqualTo("+996700000001");
        assertThat(Arrays.stream(ParcelService.Parcel.class.getRecordComponents()).map(RecordComponent::getName))
                .noneMatch(n -> n.toLowerCase().contains("phone") || n.toLowerCase().contains("recipient"));
        assertThat(ParcelService.toJson(p)).doesNotContain("+996700000001").doesNotContain("phone");
        var events = f.events.forAggregate("PARCEL", p.id());
        assertThat(events).extracting(StoredEvent::eventType).containsExactly("PARCEL_REQUESTED");
        assertThat(events.toString()).doesNotContain("+996700000001").doesNotContain("recipient");
    }

    @Test
    void everyAllowedSizeIsAccepted() {
        f.loadParcelObligations();
        for (String size : new String[] {"S", "M", "L"}) {
            UUID other = f.user();
            var p = f.parcels.create(other, new ParcelService.NewParcel(trip, size, "SMALL_GOODS", "+996700000002"));
            assertThat(p.size()).isEqualTo(size);
        }
    }

    // ---------- проверка поездки ----------

    @Test
    void unknownDraftAndCancelledTripsAreNotFound() {
        f.loadParcelObligations();
        expectCode(() -> f.parcels.create(sender, f.valid(UUID.randomUUID())), "TRIP_NOT_FOUND");

        java.time.Instant from = f.clock.instant().plus(Duration.ofDays(1));
        UUID draft = f.trips.createDraft(driver, new kg.jolomo.trips.TripService.TripDraft(
                kg.jolomo.trips.Direction.BISHKEK_TO_KARAKOL, f.bishkekPoint, from, from.plus(Duration.ofHours(2)), 4, 700, 2)).id();
        expectCode(() -> f.parcels.create(sender, f.valid(draft)), "TRIP_NOT_FOUND");

        UUID cancelled = f.publishedTrip(driver, 2);
        f.trips.cancel(driver, cancelled);
        expectCode(() -> f.parcels.create(sender, f.valid(cancelled)), "TRIP_NOT_FOUND");
    }

    @Test
    void ownTripDepartedTripAndTripWithoutParcelSlotsAreRefused() {
        f.loadParcelObligations();
        UUID noSlots = f.publishedTrip(driver, 0);

        expectCode(() -> f.parcels.create(driver, f.valid(trip)), "OWN_TRIP");
        expectCode(() -> f.parcels.create(sender, f.valid(noSlots)), "NO_PARCEL_CAPACITY");
        f.clock.advance(Duration.ofDays(2));
        expectCode(() -> f.parcels.create(sender, f.valid(trip)), "TRIP_DEPARTED");
        assertThat(f.parcelCount()).isZero();
    }

    // ---------- проверка размера, категории, телефона ----------

    @Test
    void invalidSizeCategoryAndRecipientPhoneAreRejected() {
        f.loadParcelObligations();

        for (String size : new String[] {null, "", "XL", "s", "Small"}) {
            expectCode(() -> f.parcels.create(sender, new ParcelService.NewParcel(trip, size, "DOCUMENTS", "+996700000001")),
                    "INVALID_PARCEL_SIZE");
        }
        for (String cat : new String[] {null, "", "WEAPONS", "documents"}) {
            expectCode(() -> f.parcels.create(sender, new ParcelService.NewParcel(trip, "S", cat, "+996700000001")),
                    "INVALID_PARCEL_CATEGORY");
        }
        for (String phone : new String[] {null, "", "0700000001", "+99670000000", "+9967000000011", "+7 700 000 00 01", "+996 700000001", "996700000001"}) {
            expectCode(() -> f.parcels.create(sender, new ParcelService.NewParcel(trip, "S", "DOCUMENTS", phone)),
                    "INVALID_RECIPIENT_PHONE");
        }
        expectCode(() -> f.parcels.create(sender, new ParcelService.NewParcel(null, "S", "DOCUMENTS", "+996700000001")),
                "INVALID_TRIP");
        assertThat(f.parcelCount()).isZero();
    }

    @Test
    void inactiveCategoryIsRefusedAndTheCategoryListIsData() {
        f.loadParcelObligations();
        jdbc.sql("UPDATE parcel_categories SET active = false WHERE code = 'SMALL_GOODS'").update();

        expectCode(() -> f.parcels.create(sender, new ParcelService.NewParcel(trip, "S", "SMALL_GOODS", "+996700000001")),
                "INVALID_PARCEL_CATEGORY");

        jdbc.sql("INSERT INTO parcel_categories (code) VALUES ('LEGAL_OUTCOME_ITEM')").update();
        assertThat(f.parcels.create(sender, new ParcelService.NewParcel(trip, "S", "LEGAL_OUTCOME_ITEM", "+996700000001")).category())
                .isEqualTo("LEGAL_OUTCOME_ITEM");
    }

    @Test
    void recipientCannotBeTheSenderOrTheDriver() {
        f.loadParcelObligations();

        expectCode(() -> f.parcels.create(sender,
                new ParcelService.NewParcel(trip, "S", "DOCUMENTS", f.phoneOf(sender))), "RECIPIENT_IS_SENDER");
        expectCode(() -> f.parcels.create(sender,
                new ParcelService.NewParcel(trip, "S", "DOCUMENTS", f.phoneOf(driver))), "RECIPIENT_IS_DRIVER");
        assertThat(f.parcelCount()).isZero();
    }

    @Test
    void suspendedSenderCannotCreate() {
        f.loadParcelObligations();
        jdbc.sql("UPDATE users SET status = 'SUSPENDED' WHERE id = :id").param("id", sender).update();

        expectCode(() -> f.parcels.create(sender, f.valid(trip)), "USER_NOT_ACTIVE");
    }

    // ---------- база держит инварианты сама ----------

    @Test
    void databaseRefusesBadRowsEvenIfTheCodeIsBypassed() {
        f.loadParcelObligations();
        String insert = """
                INSERT INTO parcel_requests (id, sender_user_id, trip_id, driver_user_id, recipient_phone, size, category,
                                             status, obligation_version, created_at, updated_at)
                VALUES (gen_random_uuid(), :s, :t, :d, :phone, :size, :cat, :status, 'v', now(), now())
                """;
        String[][] bad = {
                {"+996700000001", "XL", "DOCUMENTS", "REQUESTED"},
                {"+996700000001", "S", "NO_SUCH_CATEGORY", "REQUESTED"},
                {"0700000001", "S", "DOCUMENTS", "REQUESTED"},
                {"+996700000001", "S", "DOCUMENTS", "SENT"},
        };
        for (String[] row : bad) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(insert)
                            .param("s", sender).param("t", trip).param("d", driver)
                            .param("phone", row[0]).param("size", row[1]).param("cat", row[2]).param("status", row[3])
                            .update())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(insert)
                        .param("s", driver).param("t", trip).param("d", driver)
                        .param("phone", "+996700000001").param("size", "S").param("cat", "DOCUMENTS").param("status", "REQUESTED")
                        .update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    // ---------- отмена поездки и видимость ----------

    @Test
    void cancellingTheTripCancelsOpenParcels() {
        f.loadParcelObligations();
        ParcelService.Parcel p = f.parcels.create(sender, f.valid(trip));

        f.trips.cancel(driver, trip);

        assertThat(f.parcels.find(p.id()).orElseThrow().status()).isEqualTo(ParcelStatus.CANCELLED);
        assertThat(f.events.forAggregate("PARCEL", p.id())).extracting(StoredEvent::eventType)
                .containsExactly("PARCEL_REQUESTED", "PARCEL_CANCELLED");
    }

    @Test
    void parcelsAreVisibleOnlyToTheSenderAndTheDriver() {
        f.loadParcelObligations();
        ParcelService.Parcel p = f.parcels.create(sender, f.valid(trip));
        UUID stranger = f.user();

        assertThat(f.parcels.findVisible(sender, p.id())).isPresent();
        assertThat(f.parcels.findVisible(driver, p.id())).isPresent();
        assertThat(f.parcels.findVisible(stranger, p.id())).isEmpty();
        assertThat(f.parcels.listMine(sender)).extracting(ParcelService.Parcel::id).containsExactly(p.id());
        assertThat(f.parcels.listMine(stranger)).isEmpty();
        assertThat(f.parcels.listForTrip(driver, trip)).extracting(ParcelService.Parcel::id).containsExactly(p.id());
        expectCode(() -> f.parcels.listForTrip(stranger, trip), "TRIP_NOT_FOUND");
    }

    // ---------- идемпотентность ----------

    @Test
    void sameKeyAndBodyReturnTheStoredResponseWithOneParcel() {
        f.loadParcelObligations();

        IdempotencyService.Result first = f.parcels.createWithKey(sender, f.valid(trip), KEY);
        IdempotencyService.Result again = f.parcels.createWithKey(sender, f.valid(trip), KEY);

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(first.body()).doesNotContain("+996700000001");
        assertThat(f.parcelCount()).isEqualTo(1L);
    }

    @Test
    void sameKeyWithADifferentBodyIsAConflict() {
        f.loadParcelObligations();
        f.parcels.createWithKey(sender, f.valid(trip), KEY);

        expectCode(() -> f.parcels.createWithKey(sender,
                new ParcelService.NewParcel(trip, "M", "DOCUMENTS", "+996700000001"), KEY), "IDEMPOTENCY_KEY_REUSED");
        expectCode(() -> f.parcels.createWithKey(sender,
                new ParcelService.NewParcel(trip, "S", "DOCUMENTS", "+996700000009"), KEY), "IDEMPOTENCY_KEY_REUSED");
        assertThat(f.parcelCount()).isEqualTo(1L);
    }

    @Test
    void keyIsRequiredAndARefusalIsNotStored() {
        expectCode(() -> f.parcels.createWithKey(sender, f.valid(trip), null), "IDEMPOTENCY_KEY_REQUIRED");

        expectCode(() -> f.parcels.createWithKey(sender, f.valid(trip), KEY), "OBLIGATIONS_NOT_LOADED");
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_records").query(Long.class).single()).isZero();

        f.loadParcelObligations();
        assertThat(f.parcels.createWithKey(sender, f.valid(trip), KEY).status()).isEqualTo(201);
    }
}
