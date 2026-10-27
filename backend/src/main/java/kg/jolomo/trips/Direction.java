package kg.jolomo.trips;

/** Единственный коридор: Бишкек и Каракол. */
public enum Direction {
    BISHKEK_TO_KARAKOL("BISHKEK"),
    KARAKOL_TO_BISHKEK("KARAKOL");

    private final String originCity;

    Direction(String originCity) {
        this.originCity = originCity;
    }

    public String originCity() {
        return originCity;
    }
}
