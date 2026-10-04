package dev.heiko.universe.api.effect;
import java.util.Map;
import java.util.Objects;
/** Immutable content definition, independent of a live effect instance or renderer. */
public record EffectDefinition(String id, EffectCategory category, EffectScope scope,
        Map<EffectQuality,LodSpec> lods, int maxInstances, long maxLifetimeTicks) {
    public EffectDefinition {
        requireId(id); Objects.requireNonNull(category); Objects.requireNonNull(scope);
        lods=Map.copyOf(lods);
        if(lods.isEmpty() || maxInstances<=0 || maxLifetimeTicks<=0)
            throw new IllegalArgumentException("invalid definition limits");
    }
    static void requireId(String id) {
        if(id==null || id.length()>128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("namespaced effect id");
    }
}
