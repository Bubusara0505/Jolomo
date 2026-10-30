package kg.jolomo.bookings;

/**
 * Состояния брони. COMPLETED и NO_SHOW ставит только водитель после отправления (SP07-03). REVIEW_REQUIRED допустим схемой,
 * но ни один переход его пока не ставит: разбор спорных броней в задачах не описан. Переходы: docs/booking-lifecycle-and-metrics.md.
 */
public enum BookingStatus {
    REQUESTED, ACCEPTED, REJECTED, CANCELLED, COMPLETED, NO_SHOW, REVIEW_REQUIRED
}
