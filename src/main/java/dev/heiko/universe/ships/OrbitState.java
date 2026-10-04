package dev.heiko.universe.ships;

import java.util.Objects;

/** Circular analytic orbit around a stationary center. Seconds and scene units, not SI physics. */
public record OrbitState(ShipId shipId, String planetId, ShipPose.SpaceContext context,
                         Vec3 center, Vec3 axisU, Vec3 axisV, double radius, double phase,
                         double epochSeconds, double angularSpeed, Rotation orientation) {
    public OrbitState {
        Objects.requireNonNull(shipId); Objects.requireNonNull(context); Objects.requireNonNull(center);
        Objects.requireNonNull(orientation);
        if (planetId == null || planetId.isBlank() || !(radius > 0) || !Double.isFinite(radius)
                || !Double.isFinite(phase) || !Double.isFinite(epochSeconds) || !Double.isFinite(angularSpeed))
            throw new IllegalArgumentException("Invalid orbit");
        axisU = Objects.requireNonNull(axisU).normalized();
        axisV = Objects.requireNonNull(axisV).normalized();
        if (Math.abs(axisU.dot(axisV)) > 1e-10) throw new IllegalArgumentException("Orbit axes must be perpendicular");
    }
    public ShipPose at(double seconds) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Invalid time");
        double elapsed = seconds - epochSeconds;
        double advance = elapsed * angularSpeed;
        if (!Double.isFinite(advance)) throw new IllegalArgumentException("Time exceeds numeric range");
        double angle = Math.IEEEremainder(Math.IEEEremainder(phase, 2*Math.PI)
                + Math.IEEEremainder(advance, 2*Math.PI), 2*Math.PI);
        double c = Math.cos(angle), s = Math.sin(angle);
        Vec3 position = center.add(axisU.scale(radius*c)).add(axisV.scale(radius*s));
        Vec3 velocity = axisU.scale(-radius*angularSpeed*s).add(axisV.scale(radius*angularSpeed*c));
        return new ShipPose(context, position, orientation, velocity);
    }
}
