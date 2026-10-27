package kg.jolomo.bookings;

/** Состояния брони. COMPLETED, NO_SHOW и REVIEW_REQUIRED ставит SP07-03, здесь они только допустимы схемой. */
public enum BookingStatus {
    REQUESTED, ACCEPTED, REJECTED, CANCELLED, COMPLETED, NO_SHOW, REVIEW_REQUIRED
}
