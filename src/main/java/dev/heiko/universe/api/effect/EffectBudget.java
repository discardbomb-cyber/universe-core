package dev.heiko.universe.api.effect;
public record EffectBudget(int maxActive, int maxCostUnits, int maxParticles, int maxCandidatesPerUpdate) {
    public EffectBudget {
        if(maxActive<=0 || maxCostUnits<=0 || maxParticles<0 || maxCandidatesPerUpdate<=0)
            throw new IllegalArgumentException("budget");
    }
}
