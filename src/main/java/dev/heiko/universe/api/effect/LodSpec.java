package dev.heiko.universe.api.effect;
import java.util.Objects;
public record LodSpec(double maxDistance, EffectCost cost) {
    public LodSpec {
        if(!Double.isFinite(maxDistance) || maxDistance<0) throw new IllegalArgumentException("distance");
        Objects.requireNonNull(cost);
    }
}
