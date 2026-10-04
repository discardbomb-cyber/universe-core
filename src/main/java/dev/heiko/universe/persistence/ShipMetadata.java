package dev.heiko.universe.persistence;

import dev.heiko.universe.ships.*;
import java.util.Map;
import java.util.Objects;

/** Metadata only. Blocks, inventories and meshes remain in their own authoritative stores. */
public record ShipMetadata(ShipId id, long revision, int generatorVersion, String interiorDimension,
                           ShipPose pose, Map<ShipSection, ShipStructure.Summary> sections,
                           Status status) {
    public enum Status { ACTIVE, BLOCKED }
    public ShipMetadata {
        Objects.requireNonNull(id); Objects.requireNonNull(pose); Objects.requireNonNull(status);
        ShipCatalogCodec.checkText(interiorDimension);
        ShipCatalogCodec.checkText(pose.context().realmId());
        ShipCatalogCodec.checkText(pose.context().galaxyId());
        ShipCatalogCodec.checkText(pose.context().systemId());
        if (revision < 0 || generatorVersion <= 0 || sections.size() > ShipCatalogCodec.MAX_SECTIONS_PER_SHIP)
            throw new IllegalArgumentException("Invalid ship metadata version/capacity");
        sections = Map.copyOf(sections);
        ShipStructure validation = new ShipStructure(id, ShipCatalogCodec.MAX_SECTIONS_PER_SHIP);
        sections.forEach(validation::update);
    }
    /** Recovery never resumes motion or interactions until the server validates the interior/context. */
    public ShipMetadata blocked() {
        return new ShipMetadata(id, revision, generatorVersion, interiorDimension,
                new ShipPose(pose.context(), pose.position(), pose.rotation(), Vec3.ZERO), sections, Status.BLOCKED);
    }
}
