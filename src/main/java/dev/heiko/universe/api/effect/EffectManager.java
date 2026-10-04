package dev.heiko.universe.api.effect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Single-owner client admission manager. Call only for the current observation frame.
 * Work is bounded by current active count plus maxCandidatesPerUpdate. Definitions are never ticked.
 * Updates are atomic on invalid input; time is nonnegative and monotonic. No waiting/spawn queue.
 * Ranking: priority descending, distance ascending, stable instance id ascending.
 */
public final class EffectManager {
    private final EffectCatalog catalog;
    private final EffectBudget budget;
    private List<ActiveEffect> active=List.of();
    private long lastTick=-1;

    public EffectManager(EffectCatalog catalog, EffectBudget budget) {
        this.catalog=Objects.requireNonNull(catalog); this.budget=Objects.requireNonNull(budget);
    }
    public List<ActiveEffect> active() { return active; }

    public List<ActiveEffect> update(long tick, EffectPosition observer, EffectQuality desired,
                                     Collection<EffectExecution> requests) {
        Objects.requireNonNull(observer); Objects.requireNonNull(desired); Objects.requireNonNull(requests);
        if(tick<0 || tick<lastTick) throw new IllegalArgumentException("time must be monotonic");
        if(requests.size()>budget.maxCandidatesPerUpdate()) throw new IllegalArgumentException("candidate budget");
        var candidates=new TreeMap<String,EffectExecution>();
        for(var previous:active) if(previous.execution().aliveAt(tick))
            candidates.put(previous.execution().instanceId(),previous.execution());
        var seen=new HashSet<String>();
        for(var request:requests) {
            Objects.requireNonNull(request);
            if(!seen.add(request.instanceId())) throw new IllegalArgumentException("duplicate instance");
            var definition=catalog.require(request.definitionId());
            if(request.lifetimeTicks()>definition.maxLifetimeTicks()) throw new IllegalArgumentException("lifetime limit");
            var previous=candidates.get(request.instanceId());
            if(previous!=null && (!previous.definitionId().equals(request.definitionId()) ||
                previous.spawnTick()!=request.spawnTick() || previous.lifetimeTicks()!=request.lifetimeTicks() ||
                previous.seed()!=request.seed())) throw new IllegalArgumentException("live identity changed");
            // Future and expired requests are discarded, never retained for delayed spawning.
            if(request.aliveAt(tick)) candidates.put(request.instanceId(),request);
        }
        var ranked=new ArrayList<>(candidates.values());
        ranked.sort(Comparator.comparingInt(EffectExecution::priority).reversed()
            .thenComparingDouble(e->e.position().distanceTo(observer)).thenComparing(EffectExecution::instanceId));
        var admitted=new ArrayList<ActiveEffect>();
        var counts=new HashMap<String,Integer>();
        int usedCost=0,usedParticles=0;
        for(var execution:ranked) {
            if(admitted.size()==budget.maxActive()) break;
            var definition=catalog.require(execution.definitionId());
            if(counts.getOrDefault(definition.id(),0)>=definition.maxInstances()) continue;
            double distance=execution.position().distanceTo(observer);
            for(int level=desired.ordinal();level>=0;level--) {
                var quality=EffectQuality.values()[level];
                var lod=definition.lods().get(quality);
                if(lod==null || distance>lod.maxDistance() ||
                    lod.cost().units()>budget.maxCostUnits()-usedCost ||
                    lod.cost().particles()>budget.maxParticles()-usedParticles) continue;
                admitted.add(new ActiveEffect(definition,execution,quality,lod,distance));
                usedCost+=lod.cost().units(); usedParticles+=lod.cost().particles();
                counts.merge(definition.id(),1,Integer::sum);
                break;
            }
        }
        active=List.copyOf(admitted); lastTick=tick;
        return active;
    }
    /** Release the observation frame, for example when changing dimensions. Time remains monotonic. */
    public void clear() { active=List.of(); }
}
