package dev.heiko.universe;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Small authoritative identity record; never stores blocks or meshes. */
public final class UniverseManifest extends SavedData {
    private static final int SCHEMA = 1;
    private final UUID worldId;
    private final long seed;

    private UniverseManifest(UUID worldId, long seed) {
        this.worldId = worldId;
        this.seed = seed;
    }

    public static UniverseManifest get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(
            () -> {
                var data = new UniverseManifest(UUID.randomUUID(), server.overworld().getSeed());
                data.setDirty();
                return data;
            }, UniverseManifest::load), "universe_manifest");
    }

    private static UniverseManifest load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("schema") != SCHEMA || tag.getInt("generatorVersion") != 1
            || !tag.hasUUID("worldId") || !tag.contains("seed")) {
            throw new IllegalStateException("Unsupported or damaged Universe manifest; preserve the world and restore a backup");
        }
        return new UniverseManifest(tag.getUUID("worldId"), tag.getLong("seed"));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema", SCHEMA);
        tag.putInt("generatorVersion", 1);
        tag.putUUID("worldId", worldId);
        tag.putLong("seed", seed);
        return tag;
    }

    public UUID worldId() { return worldId; }
    public long seed() { return seed; }
}
