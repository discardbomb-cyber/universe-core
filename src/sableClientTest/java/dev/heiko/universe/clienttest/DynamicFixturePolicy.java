package dev.heiko.universe.clienttest;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.physics.config.PhysicsConfigData;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;

/** Fresh-world policy: ASSERT gravity/drag; only confirmed public substep field is saved/restored. */
final class DynamicFixturePolicy implements AutoCloseable {
    private final ServerLevel level;
    private final PhysicsConfigData config;
    private final int originalSubsteps;
    private boolean substepsOwned;
    DynamicFixturePolicy(ServerSubLevelContainer container) {
        level=container.getLevel();config=container.physicsSystem().getConfig();
        if(!level.getServer().isSameThread()||!container.getAllSubLevels().isEmpty())
            throw new IllegalStateException("Policy requires empty disposable server scene");
        originalSubsteps=config.substepsPerTick;
        requireTarget(DimensionPhysicsData.getGravity(level),DimensionPhysicsData.getUniversalDrag(level),originalSubsteps);
        try{
            substepsOwned=true;config.substepsPerTick=2;
            verify();
        }catch(RuntimeException|Error error){try{close();}catch(RuntimeException restore){error.addSuppressed(restore);}throw error;}
    }
    void verify(){requireTarget(DimensionPhysicsData.getGravity(level),DimensionPhysicsData.getUniversalDrag(level),config.substepsPerTick);}
    private static void requireTarget(org.joml.Vector3dc gravity,double drag,int substeps){
        if(!Double.isFinite(gravity.x())||!Double.isFinite(gravity.y())||!Double.isFinite(gravity.z())||gravity.distance(new Vector3d(0,-11,0))>1e-8
                ||!Double.isFinite(drag)||Math.abs(drag-.09)>1e-8||substeps!=2)
            throw new IllegalStateException("Fresh default g=(0,-11,0), float drag=.09, N=2 required; actual g="
                    +gravity+", drag="+drag+", N="+substeps+"; no g/drag setter used");
    }
    Map<String,Object> metadata(){
        verify();var g=DimensionPhysicsData.getGravity(level);
        return Map.of("gravity",new double[]{g.x(),g.y(),g.z()},"universalDrag",DimensionPhysicsData.getUniversalDrag(level),
                "substeps",config.substepsPerTick,"originalSubsteps",originalSubsteps,
                "configurationMode","ASSERT_ONLY_GRAVITY_DRAG_SAVE_RESTORE_SUBSTEPS",
                "integralFormula","sum((previousNativeV+postNativeV)*0.5*dt)");
    }
    boolean restored(){return !substepsOwned;}
    @Override public void close(){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Policy restore requires server thread");
        if(substepsOwned){
            config.substepsPerTick=originalSubsteps;
            if(config.substepsPerTick!=originalSubsteps)throw new IllegalStateException("Original substeps not restored");
            substepsOwned=false;
        }
    }
}
