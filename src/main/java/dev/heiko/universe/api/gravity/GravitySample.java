package dev.heiko.universe.api.gravity;

import dev.heiko.universe.api.ApiIds;
import dev.heiko.universe.ships.Vec3;
import java.util.Objects;

/** Target field, not an additional force on top of a physics engine's base gravity. */
public record GravitySample(String fieldId, long revision, String frameId,
                            Vec3 accelerationMetersPerSecondSquared) {
    public static final int TICKS_PER_SECOND = 20;

    public GravitySample {
        fieldId = ApiIds.require(fieldId);
        frameId = ApiIds.require(frameId);
        Objects.requireNonNull(accelerationMetersPerSecondSquared, "acceleration");
        if (revision < 0) throw new IllegalArgumentException("Negative gravity revision");
        if (accelerationMetersPerSecondSquared.length() > VerticalGravityProfile.MAX_ACCELERATION) {
            throw new IllegalArgumentException("Gravity vector magnitude exceeds 128 m/s²");
        }
    }

    /** One block per game metre at 20 ticks/s. This conversion is for acceleration only. */
    public Vec3 accelerationBlocksPerTickSquared() {
        return accelerationMetersPerSecondSquared.scale(1.0 / (TICKS_PER_SECOND * TICKS_PER_SECOND));
    }
}
