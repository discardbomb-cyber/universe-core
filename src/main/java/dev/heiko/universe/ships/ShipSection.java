package dev.heiko.universe.ships;

/** Addressing only: no block storage, scanning or ticking. */
public record ShipSection(ShipId shipId, int x, int y, int z) {
    public static final int SIZE = 16;
    public static final int VOLUME = SIZE * SIZE * SIZE;
    public ShipSection { java.util.Objects.requireNonNull(shipId); }
    public static ShipSection containing(ShipId ship, int blockX, int blockY, int blockZ) {
        return new ShipSection(ship, Math.floorDiv(blockX,SIZE), Math.floorDiv(blockY,SIZE), Math.floorDiv(blockZ,SIZE));
    }
    /** X fastest, then Z, then Y. */
    public static int index(int blockX, int blockY, int blockZ) {
        return Math.floorMod(blockX,SIZE) | Math.floorMod(blockZ,SIZE)<<4 | Math.floorMod(blockY,SIZE)<<8;
    }
    public static int localX(int index) { check(index); return index & 15; }
    public static int localY(int index) { check(index); return index >>> 8; }
    public static int localZ(int index) { check(index); return index >>> 4 & 15; }
    private static void check(int index) {
        if (index < 0 || index >= VOLUME) throw new IllegalArgumentException("Section index");
    }
}
