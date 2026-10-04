package dev.heiko.universe.core;

import java.util.Objects;

public record RegionKey(BodyKey body, int chartId, int x, int z) {
    public static final int CHUNKS_PER_SIDE = 32;
    public static final int BLOCKS_PER_SIDE = 512;
    public RegionKey {
        Objects.requireNonNull(body, "body");
        if (body.kind() != BodyKey.Kind.PLANET && body.kind() != BodyKey.Kind.MOON)
            throw new IllegalArgumentException("Only planets and moons have surfaces");
        if (chartId != 0) throw new IllegalArgumentException("Only chart 0 is supported");
    }
    public static RegionKey fromChunk(BodyKey body, int chartId, int chunkX, int chunkZ) {
        return new RegionKey(body, chartId, Math.floorDiv(chunkX, CHUNKS_PER_SIDE), Math.floorDiv(chunkZ, CHUNKS_PER_SIDE));
    }
    public static int localChunk(int chunkCoordinate) { return Math.floorMod(chunkCoordinate, CHUNKS_PER_SIDE); }
}

