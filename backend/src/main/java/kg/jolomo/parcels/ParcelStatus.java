package kg.jolomo.parcels;

/** Состояния посылки по ТЗ 1.7. Здесь создаётся только REQUESTED; остальные ставят SP08 и SP09. */
public enum ParcelStatus {
    REQUESTED, ACCEPTED, PICKUP_CONFIRMED, IN_TRANSIT, DELIVERY_PENDING, DELIVERED,
    REJECTED, CANCELLED, DELIVERY_FAILED, VERIFICATION_LOCKED, DISPUTED
}
