package dev.heiko.universe.core;

/** Persistent identifiers; never derive saved addresses from enum ordinals. */
public enum Realm {
    MILKY_WAY("milky_way", "milky_way"), END("end", "silent_crown"), NETHER("nether", "crimson_forge");
    private final String id;
    private final String galaxyId;
    Realm(String id, String galaxyId) { this.id = id; this.galaxyId = galaxyId; }
    public String id() { return id; }
    public String galaxyId() { return galaxyId; }
}

