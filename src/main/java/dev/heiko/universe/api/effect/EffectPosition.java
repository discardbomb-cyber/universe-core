package dev.heiko.universe.api.effect;
/** Coordinates must share one caller-selected local observation frame. */
public record EffectPosition(double x, double y, double z) {
    public EffectPosition {
        if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) ||
           Math.abs(x)>0x1.0p40 || Math.abs(y)>0x1.0p40 || Math.abs(z)>0x1.0p40)
            throw new IllegalArgumentException("position outside local frame");
    }
    public double distanceTo(EffectPosition other) {
        return StrictMath.hypot(StrictMath.hypot(x-other.x,y-other.y),z-other.z);
    }
}
