package dev.heiko.universe.api.effect;
import java.util.Objects;
/** Read-only renderer input. A dropped instance is stopped, not placed in an unbounded queue. */
public record ActiveEffect(EffectDefinition definition, EffectExecution execution,
        EffectQuality quality, LodSpec lod, double distance) {
    public ActiveEffect {
        Objects.requireNonNull(definition); Objects.requireNonNull(execution);
        Objects.requireNonNull(quality); Objects.requireNonNull(lod);
        if(!definition.id().equals(execution.definitionId()) || !lod.equals(definition.lods().get(quality)) ||
           !Double.isFinite(distance) || distance<0 || distance>lod.maxDistance())
            throw new IllegalArgumentException("inconsistent active effect");
    }
}
