package dev.heiko.universe.persistence;

import dev.heiko.universe.ships.DockConnection;
import dev.heiko.universe.ships.ShipId;
import java.util.*;
import java.nio.file.Files;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Server-owned metadata, persisted separately from UniverseManifest. Use only on the server thread. */
public final class ShipCatalog extends SavedData {
    public static final String DATA_NAME = "universe_ships";
    private final Map<ShipId, ShipMetadata> ships = new HashMap<>();
    private final Map<UUID, ShipCatalogCodec.Link> links = new HashMap<>();

    private ShipCatalog() {}

    public static ShipCatalog get(MinecraftServer server) {
        Objects.requireNonNull(server);
        if (!server.isSameThread()) throw new IllegalStateException("Ship catalog requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(() -> {
            // DimensionDataStorage may treat a failed load as absent. Never replace an existing damaged file.
            var path = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
            if (!Files.notExists(path))
                throw new IllegalStateException("Existing Universe ship catalog could not load; preserve it and restore backup");
            var catalog = new ShipCatalog(); catalog.setDirty(); return catalog;
        }, ShipCatalog::load), DATA_NAME);
    }

    private static ShipCatalog load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("schema", Tag.TAG_INT) || tag.getInt("schema") != ShipCatalogCodec.SCHEMA
                || !tag.contains("metadata", Tag.TAG_BYTE_ARRAY))
            throw new IllegalStateException("Unknown/damaged Universe ship schema; preserve save and restore backup");
        var decoded = ShipCatalogCodec.decode(tag.getByteArray("metadata"));
        var catalog = new ShipCatalog();
        decoded.ships().forEach((id, metadata) -> catalog.ships.put(id, metadata.blocked()));
        catalog.links.putAll(decoded.links());
        // Recovery changes are durable; no persisted ACTIVE ship may silently resume on the next restart.
        catalog.setDirty();
        return catalog;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema", ShipCatalogCodec.SCHEMA);
        tag.putByteArray("metadata", ShipCatalogCodec.encode(snapshot()));
        return tag;
    }

    public Optional<ShipMetadata> ship(ShipId id) { return Optional.ofNullable(ships.get(id)); }
    public ShipCatalogCodec.Snapshot snapshot() { return new ShipCatalogCodec.Snapshot(ships, links); }

    /** Identity is never reused or removed; sections include deletion tombstones. */
    public boolean putShip(ShipMetadata metadata) {
        Objects.requireNonNull(metadata);
        ShipMetadata previous = ships.get(metadata.id());
        if (metadata.equals(previous)) return false;
        if (previous != null) {
            if (metadata.revision() <= previous.revision() || metadata.generatorVersion() != previous.generatorVersion()
                    || !metadata.interiorDimension().equals(previous.interiorDimension()))
                throw new IllegalArgumentException("Ship identity or revision changed");
            for (var entry : previous.sections().entrySet()) {
                var next = metadata.sections().get(entry.getKey());
                if (next == null || (!next.equals(entry.getValue()) && next.revision() <= entry.getValue().revision()))
                    throw new IllegalArgumentException("Lost section tombstone or stale section revision");
            }
        }
        var candidate = new HashMap<>(ships); candidate.put(metadata.id(), metadata);
        new ShipCatalogCodec.Snapshot(candidate, links);
        ships.put(metadata.id(), metadata); setDirty(); return true;
    }

    /** Retains unknown ship/port references for diagnosis, with closed recovery state. */
    public boolean putLink(ShipCatalogCodec.Link link) {
        Objects.requireNonNull(link);
        var previous = links.get(link.id());
        if (link.equals(previous)) return false;
        if (previous != null && (link.revision() <= previous.revision()
                || !link.first().equals(previous.first()) || !link.second().equals(previous.second())
                || !link.firstPort().equals(previous.firstPort()) || !link.secondPort().equals(previous.secondPort())
                || !link.root().equals(previous.root())))
            throw new IllegalArgumentException("Link identity or revision changed");
        var candidate = new HashMap<>(links); candidate.put(link.id(),link);
        new ShipCatalogCodec.Snapshot(ships,candidate);
        links.put(link.id(),link); setDirty(); return true;
    }
    public boolean putConnection(DockConnection connection) { return putLink(ShipCatalogCodec.Link.from(connection)); }

    /** This is diagnostic only: resolving ship IDs does not validate ports, hulls or doors. */
    public boolean hasKnownShips(UUID connection) {
        var link = links.get(connection);
        return link != null && ships.containsKey(link.first()) && ships.containsKey(link.second());
    }
}
