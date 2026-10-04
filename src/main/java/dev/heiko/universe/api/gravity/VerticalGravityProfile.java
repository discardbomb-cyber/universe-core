package dev.heiko.universe.api.gravity;

import dev.heiko.universe.api.ApiIds;

/**
 * Target acceleration in game metres/second², directed along local -Y.
 * Constant below fadeStartY, smooth fade to zero by fadeEndY.
 * A zero magnitude defines zero-g at every height; the band still must be valid.
 */
public record VerticalGravityProfile(String fieldId, long revision,
                                    double accelerationMetersPerSecondSquared,
                                    double fadeStartY, double fadeEndY) {
    public static final double MAX_ACCELERATION = 128.0;

    public VerticalGravityProfile {
        fieldId = ApiIds.require(fieldId);
        if (revision < 0) throw new IllegalArgumentException("Negative gravity revision");
        if (!Double.isFinite(accelerationMetersPerSecondSquared)
                || accelerationMetersPerSecondSquared < 0
                || accelerationMetersPerSecondSquared > MAX_ACCELERATION) {
            throw new IllegalArgumentException("Gravity magnitude must be finite and within 0..128 m/s²");
        }
        double width = fadeEndY - fadeStartY;
        if (!Double.isFinite(fadeStartY) || !Double.isFinite(fadeEndY)
                || !Double.isFinite(width) || !(width > 0)) {
            throw new IllegalArgumentException("Gravity fade band must have a positive finite width");
        }
    }
}
