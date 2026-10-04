package dev.heiko.universe.api.effect;
import java.util.Objects;
/** Client-local spawn request; seed belongs to this instance, not a global RNG. */
public record EffectExecution(String instanceId, String definitionId, EffectPosition position,
        int priority, long spawnTick, long lifetimeTicks, long seed) {
    public EffectExecution {
        if(instanceId==null || instanceId.isBlank() || instanceId.length()>128)
            throw new IllegalArgumentException("instance id");
        EffectDefinition.requireId(definitionId); Objects.requireNonNull(position);
        if(priority<0 || priority>1000 || spawnTick<0 || lifetimeTicks<=0)
            throw new IllegalArgumentException("execution limits");
    }
    public boolean aliveAt(long tick) {
        return tick>=spawnTick && tick-spawnTick<lifetimeTicks;
    }
}
