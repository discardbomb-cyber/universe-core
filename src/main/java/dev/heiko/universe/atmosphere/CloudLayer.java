package dev.heiko.universe.atmosphere;
import java.util.Objects;
/** Heights and shape size are local game blocks, not physical kilometers. */
public record CloudLayer(CloudTier tier, CloudType type, double bottom, double top,
        double coverage, double density, double shapeSize, Wind wind,
        Precipitation precipitation, ColorRgb color, Quality quality) {
    public CloudLayer {
        Objects.requireNonNull(tier); Objects.requireNonNull(type); Objects.requireNonNull(wind);
        Objects.requireNonNull(precipitation); Objects.requireNonNull(color); Objects.requireNonNull(quality);
        Checks.nonnegative(bottom, "bottom"); Checks.nonnegative(top, "top");
        if (top <= bottom) throw new IllegalArgumentException("top must exceed bottom");
        Checks.range(coverage, 0, 1, "coverage"); Checks.range(density, 0, 1, "density");
        Checks.range(shapeSize, Double.MIN_NORMAL, Double.MAX_VALUE, "shapeSize");
        boolean ground = type == CloudType.FOG || type == CloudType.HAZE;
        boolean storm = type == CloudType.CUMULONIMBUS;
        if (ground != (tier == CloudTier.GROUND) || storm != (tier == CloudTier.STORM))
            throw new IllegalArgumentException("type incompatible with tier");
    }
}
