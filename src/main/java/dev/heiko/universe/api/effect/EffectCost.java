package dev.heiko.universe.api.effect;
/** Abstract admission units; not a promise of GPU milliseconds. */
public record EffectCost(int units, int particles) {
    public EffectCost {
        if(units<=0 || particles<0) throw new IllegalArgumentException("invalid cost");
    }
}
