package kg.jolomo.trips;

import java.util.UUID;

/**
 * Кого-то надо уведомить, когда водитель отменил поездку (модуль bookings снимает активные брони). Вызывается внутри
 * транзакции отмены, когда строка поездки уже заблокирована: порядок блокировок везде «поездка, потом бронь».
 */
public interface TripCancellationListener {

    void onTripCancelled(UUID tripId, UUID actorUserId);
}
