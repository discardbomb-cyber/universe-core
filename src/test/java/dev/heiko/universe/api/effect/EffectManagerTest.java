package dev.heiko.universe.api.effect;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EffectManagerTest {
    private static final EffectPosition ORIGIN=new EffectPosition(0,0,0);
    private static EffectDefinition definition(String id,int instances) {
        return new EffectDefinition(id,EffectCategory.NEBULA,EffectScope.SYSTEM,
            Map.of(EffectQuality.LOW,new LodSpec(100,new EffectCost(2,3)),
                EffectQuality.HIGH,new LodSpec(20,new EffectCost(8,10))),instances,100);
    }
    private static EffectExecution request(String id,String definition,int priority,double distance,long spawn,long lifetime) {
        return new EffectExecution(id,definition,new EffectPosition(distance,0,0),priority,spawn,lifetime,42);
    }
    private static EffectManager manager(EffectBudget budget,EffectDefinition... definitions) {
        return new EffectManager(new EffectCatalog(List.of(definitions),10000),budget);
    }
    @Test void globalCostParticleAndActiveBudgetsAreEnforced() {
        var m=manager(new EffectBudget(2,4,6,10),definition("universe:dust",10));
        var result=m.update(0,ORIGIN,EffectQuality.HIGH,List.of(
            request("a","universe:dust",1,0,0,10),request("b","universe:dust",1,1,0,10),
            request("c","universe:dust",1,2,0,10)));
        assertEquals(2,result.size());
        assertEquals(4,result.stream().mapToInt(a->a.lod().cost().units()).sum());
        assertEquals(6,result.stream().mapToInt(a->a.lod().cost().particles()).sum());
        assertTrue(result.stream().allMatch(a->a.quality()==EffectQuality.LOW));
        var noParticles=manager(new EffectBudget(2,100,0,10),definition("universe:dust",10));
        assertTrue(noParticles.update(0,ORIGIN,EffectQuality.LOW,
            List.of(request("a","universe:dust",0,0,0,1))).isEmpty());
    }
    @Test void definitionLimitAndPriorityDisplaceEarlierAdmission() {
        var m=manager(new EffectBudget(5,100,100,10),definition("universe:dust",1));
        m.update(0,ORIGIN,EffectQuality.LOW,List.of(request("old","universe:dust",1,0,0,10)));
        var result=m.update(1,ORIGIN,EffectQuality.LOW,List.of(request("new","universe:dust",2,50,1,10)));
        assertEquals(List.of("new"),result.stream().map(a->a.execution().instanceId()).toList());
    }
    @Test void expirationIsExclusiveAndFutureRequestsAreNotQueued() {
        var m=manager(new EffectBudget(2,100,100,10),definition("universe:dust",10));
        assertEquals(1,m.update(5,ORIGIN,EffectQuality.LOW,List.of(
            request("now","universe:dust",0,0,5,10),request("future","universe:dust",0,0,20,10))).size());
        assertEquals(1,m.update(14,ORIGIN,EffectQuality.LOW,List.of()).size());
        assertTrue(m.update(15,ORIGIN,EffectQuality.LOW,List.of()).isEmpty());
        assertTrue(m.update(20,ORIGIN,EffectQuality.LOW,List.of()).isEmpty());
        assertTrue(m.update(Long.MAX_VALUE,ORIGIN,EffectQuality.LOW,List.of()).isEmpty());
    }
    @Test void lodUsesDistanceQualityAndBudgetAndDropsOutOfRange() {
        var m=manager(new EffectBudget(5,100,100,10),definition("universe:dust",10));
        var result=m.update(0,ORIGIN,EffectQuality.HIGH,List.of(
            request("near","universe:dust",0,10,0,10),request("mid","universe:dust",0,30,0,10),
            request("far","universe:dust",0,101,0,10)));
        assertEquals(List.of(EffectQuality.HIGH,EffectQuality.LOW),result.stream().map(ActiveEffect::quality).toList());
        result=m.update(1,ORIGIN,EffectQuality.MEDIUM,List.of());
        assertTrue(result.stream().allMatch(a->a.quality()==EffectQuality.LOW));
        assertTrue(m.update(2,new EffectPosition(1000,0,0),EffectQuality.HIGH,List.of()).isEmpty());
    }
    @Test void requestOrderDoesNotAffectSelectionAndSeedIsPreserved() {
        var definitions=definition("universe:dust",10);
        var input=new ArrayList<>(List.of(request("c","universe:dust",3,5,0,10),
            request("a","universe:dust",3,5,0,10),request("b","universe:dust",3,5,0,10)));
        var a=manager(new EffectBudget(2,100,100,10),definitions);
        var b=manager(new EffectBudget(2,100,100,10),definitions);
        var expected=a.update(0,ORIGIN,EffectQuality.LOW,input);
        Collections.reverse(input);
        assertEquals(expected,b.update(0,ORIGIN,EffectQuality.LOW,input));
        assertEquals(List.of("a","b"),expected.stream().map(e->e.execution().instanceId()).toList());
        assertTrue(expected.stream().allMatch(e->e.execution().seed()==42));
        assertThrows(UnsupportedOperationException.class,()->expected.clear());
    }
    @Test void fiveThousandDefinitionsDoNotCreateActiveInstances() {
        var definitions=new ArrayList<EffectDefinition>();
        for(int i=0;i<5000;i++) definitions.add(definition("universe:effect_"+i,2));
        var catalog=new EffectCatalog(definitions,5000);
        var m=new EffectManager(catalog,new EffectBudget(4,100,100,8));
        assertEquals(5000,catalog.size()); assertTrue(m.active().isEmpty());
        assertTrue(m.update(0,ORIGIN,EffectQuality.HIGH,List.of()).isEmpty());
    }
    @Test void invalidInputIsRejectedAtomically() {
        var d=definition("universe:dust",1);
        var m=manager(new EffectBudget(1,100,100,2),d);
        var spawn=request("a","universe:dust",1,0,0,10);
        var snapshot=m.update(0,ORIGIN,EffectQuality.LOW,List.of(spawn));
        assertThrows(IllegalArgumentException.class,()->m.update(1,ORIGIN,EffectQuality.LOW,List.of(spawn,spawn)));
        assertEquals(snapshot,m.active());
        assertThrows(IllegalArgumentException.class,()->m.update(1,ORIGIN,EffectQuality.LOW,List.of(
            request("a","universe:dust",1,0,1,10))));
        assertThrows(IllegalArgumentException.class,()->m.update(1,ORIGIN,EffectQuality.LOW,List.of(
            request("b","universe:dust",1,0,0,101))));
        assertThrows(IllegalArgumentException.class,()->m.update(1,ORIGIN,EffectQuality.LOW,List.of(
            request("b","universe:missing",1,0,0,10))));
        assertThrows(IllegalArgumentException.class,()->m.update(1,ORIGIN,EffectQuality.LOW,List.of(spawn,spawn,spawn)));
        assertEquals(snapshot,m.active());
        m.update(2,ORIGIN,EffectQuality.LOW,List.of());
        assertThrows(IllegalArgumentException.class,()->m.update(1,ORIGIN,EffectQuality.LOW,List.of()));
        assertThrows(IllegalArgumentException.class,()->new EffectCost(0,0));
        assertThrows(IllegalArgumentException.class,()->new LodSpec(Double.NaN,new EffectCost(1,0)));
        assertThrows(IllegalArgumentException.class,()->new EffectPosition(Double.POSITIVE_INFINITY,0,0));
        assertThrows(IllegalArgumentException.class,()->new EffectCatalog(List.of(d,d),2));
        assertThrows(IllegalArgumentException.class,()->new EffectCatalog(List.of(d),0));
    }
}
