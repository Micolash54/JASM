package dev.micolash.jasm.station;

/** What the station's Bitling is up to, as the screen shows it. */
public enum StationStatus {
    EMPTY("empty"),
    ROAMING("roaming"),
    HEADING_HOME("heading_home"),
    RESTING("resting"),
    RECHARGING("recharging"),
    WAITING_FOR_POWER("waiting_for_power"),
    KNOCKED_OUT("knocked_out");

    private final String key;

    StationStatus(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static StationStatus of(int ordinal) {
        StationStatus[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : EMPTY;
    }
}
