package dev.heiko.universe.api.gravity;

import dev.heiko.universe.api.ApiIds;
import dev.heiko.universe.ships.Vec3;
import java.util.Objects;

/** Stateless bounded sampler. The supplied Y belongs to the named local frame. */
public final class GravitySampler {
    private GravitySampler() {}

    public static GravitySample sample(VerticalGravityProfile profile, double localY, String frameId) {
        Objects.requireNonNull(profile, "profile");
        ApiIds.require(frameId);
        if (!Double.isFinite(localY)) throw new IllegalArgumentException("Gravity sample Y must be finite");
        double magnitude;
        // Branch before subtracting: very distant finite coordinates can overflow a difference.
        if (localY <= profile.fadeStartY()) {
            magnitude = profile.accelerationMetersPerSecondSquared();
        } else if (localY >= profile.fadeEndY()) {
            magnitude = 0;
        } else {
            double t = (localY - profile.fadeStartY()) / (profile.fadeEndY() - profile.fadeStartY());
            double fade = Math.max(0, Math.min(1, 1 - t * t * (3 - 2 * t)));
            magnitude = profile.accelerationMetersPerSecondSquared() * fade;
        }
        Vec3 acceleration = magnitude == 0 ? Vec3.ZERO : new Vec3(0, -magnitude, 0);
        return new GravitySample(profile.fieldId(), profile.revision(), frameId, acceleration);
    }
}
