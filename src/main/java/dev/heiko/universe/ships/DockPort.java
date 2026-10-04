package dev.heiko.universe.ships;

import java.util.Objects;
import java.util.UUID;

public record DockPort(UUID id, ShipId shipId, ShipPose.SpaceContext context, Vec3 localPosition,
                       Rotation localRotation, String connectorClass, int apertureSize,
                       long revision, boolean operational) {
    public DockPort {
        Objects.requireNonNull(id); Objects.requireNonNull(shipId); Objects.requireNonNull(context);
        Objects.requireNonNull(localPosition); Objects.requireNonNull(localRotation);
        if (connectorClass == null || connectorClass.isBlank() || apertureSize <= 0 || revision < 0)
            throw new IllegalArgumentException("Invalid port");
    }
    public boolean compatibleWith(DockPort other) {
        return operational && other.operational && !shipId.equals(other.shipId)
                && context.equals(other.context) && connectorClass.equals(other.connectorClass)
                && apertureSize == other.apertureSize;
    }
}
