package kg.jolomo.bookings;

import java.util.UUID;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Контакты по брони (SP07-01, RQ-07). Телефон отдаёт только этот сервис и только отдельным маршрутом: обычные
 * ответы по броням (BookingService.Booking) контактов не содержат вообще. Правило такое:
 * <ul>
 *   <li>не участник брони: 404 BOOKING_NOT_FOUND (чужую бронь не отличить от несуществующей);</li>
 *   <li>участник, но бронь не ACCEPTED (запрос, отказ, отмена): 403 CONTACT_NOT_AVAILABLE, ничьих данных в ответе нет;</li>
 *   <li>ACCEPTED: пассажиру телефон водителя, водителю телефон пассажира, и больше ничего.</li>
 * </ul>
 * Телефон другой стороны читается из базы только после проверки состояния. После отмены контакт снова закрыт.
 * После COMPLETED и NO_SHOW контакты тоже закрыты (SP07-03): открыты они только у ACCEPTED, лишние персональные данные не раздаём.
 */
@Service
public class BookingContactService {

    /** Чей это телефон: DRIVER (водителя поездки) или PASSENGER (пассажира брони). */
    public record Contact(UUID bookingId, String role, String phone) {}

    private record Parties(String status, UUID passengerId, UUID driverId) {}

    private final JdbcClient jdbc;

    public BookingContactService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Contact contactFor(UUID viewerId, UUID bookingId) {
        Parties p = jdbc.sql("""
                        SELECT b.status, b.passenger_user_id, t.driver_user_id
                        FROM bookings b JOIN trips t ON t.id = b.trip_id WHERE b.id = :id
                        """)
                .param("id", bookingId)
                .query((rs, i) -> new Parties(rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)))
                .optional()
                .orElseThrow(BookingContactService::notFound);
        boolean passenger = p.passengerId().equals(viewerId);
        boolean driver = p.driverId().equals(viewerId);
        if (!passenger && !driver) {
            throw notFound();
        }
        if (!BookingStatus.ACCEPTED.name().equals(p.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CONTACT_NOT_AVAILABLE", "error.booking.contact_not_available");
        }
        UUID otherSide = passenger ? p.driverId() : p.passengerId();
        String phone = jdbc.sql("SELECT phone FROM users WHERE id = :id")
                .param("id", otherSide)
                .query(String.class)
                .single();
        return new Contact(bookingId, passenger ? "DRIVER" : "PASSENGER", phone);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "error.booking.not_found");
    }
}
