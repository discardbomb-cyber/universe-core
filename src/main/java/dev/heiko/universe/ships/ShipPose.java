package dev.heiko.universe.ships;

import java.util.Objects;

/** Space-scene units; canonical interior coordinates never move. */
public record ShipPose(SpaceContext context, Vec3 position, Rotation rotation, Vec3 velocity) {
    public ShipPose {
        Objects.requireNonNull(context); Objects.requireNonNull(position);
        Objects.requireNonNull(rotation); Objects.requireNonNull(velocity);
    }
    public record SpaceContext(String realmId, String galaxyId, String systemId) {
        public SpaceContext {
            if (realmId == null || realmId.isBlank() || galaxyId == null || galaxyId.isBlank()
                    || systemId == null || systemId.isBlank()) throw new IllegalArgumentException("Missing context");
        }
    }
    public Vec3 localToSpace(Vec3 local) { return position.add(rotation.apply(local)); }
    public Vec3 spaceToLocal(Vec3 space) { return rotation.inverse().apply(space.subtract(position)); }
    public ShipPose follow(ShipPose relative) {
        if (!context.equals(relative.context)) throw new IllegalArgumentException("Different context");
        return new ShipPose(context, localToSpace(relative.position), rotation.multiply(relative.rotation), velocity);
    }
    public ShipPose relativeTo(ShipPose root) {
        if (!context.equals(root.context)) throw new IllegalArgumentException("Different context");
        return new ShipPose(context, root.spaceToLocal(position), root.rotation.inverse().multiply(rotation),
                root.rotation.inverse().apply(velocity.subtract(root.velocity)));
    }
    public Vec3 velocityAt(Vec3 localPoint, Vec3 angularVelocity) {
        return velocity.add(angularVelocity.cross(rotation.apply(localPoint)));
    }
}
