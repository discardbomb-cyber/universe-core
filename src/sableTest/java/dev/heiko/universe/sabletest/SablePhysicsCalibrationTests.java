package dev.heiko.universe.sabletest;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.ArrayList;
import java.util.Locale;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Test-only milestone A. A successful compilation is not calibration evidence. */
@GameTestHolder(SableTestMod.ID)
@PrefixGameTestTemplate(false)
public final class SablePhysicsCalibrationTests {
    private static final Logger LOG=LoggerFactory.getLogger(SablePhysicsCalibrationTests.class);
    // Fixed before the confirming run. Velocity in blocks/meters per simulation second, omega rad/s.
    private static final double ABS=.01, REL=.05, INTERVAL=.1;
    private static final Vector3d ADD_LINEAR=new Vector3d(2,.4,-.7);
    private static final Vector3d ADD_ANGULAR=new Vector3d(.15,.25,-.1);
    // One common J for both masses, chosen by the fixed rule J = 2 * measured light mass.
    private static final double LIGHT_TARGET_SPEED=2;

    @GameTest(template="empty",timeoutTicks=240,batch="sable_calibration_impulse_identity",required=true)
    public static void impulseIdentityTwoMasses(GameTestHelper helper) { new Session(helper,false,0).start(); }
    @GameTest(template="empty",timeoutTicks=240,batch="sable_calibration_impulse_yaw90",required=true)
    public static void impulseYaw90TwoMasses(GameTestHelper helper) { new Session(helper,false,1).start(); }
    @GameTest(template="empty",timeoutTicks=240,batch="sable_calibration_velocity_world",required=true)
    public static void additiveWorldVelocitiesBothOrientations(GameTestHelper helper) { new Session(helper,true,2).start(); }

    private static final class Pair {
        final ServerSubLevel driven, control;
        final Vector3d expected;
        Vector3d beforeDriven, beforeControl, startDriven, startControl;
        Vector3d integrated=new Vector3d(), angularIntegral=new Vector3d(), angularMeasured=new Vector3d();
        Quaterniond previousOrientation;
        Vector3d stepDriven,stepControl;
        double mass;
        Pair(ServerSubLevel driven,ServerSubLevel control,Vector3d expected) {
            this.driven=driven;this.control=control;this.expected=expected;
        }
    }

    /** Public for event-bus reflective listener discovery; only this instance is registered. */
    public static final class Session {
        private final GameTestHelper helper;
        private final boolean additive;
        private final int variant;
        private final ArrayList<Pair> pairs=new ArrayList<>();
        private NativePhysicsFixture fixture;
        private boolean registered,finished,started;
        private double simulationTime,pendingDt;
        private int preSteps,postSteps;
        private long startGameTime;

        private Session(GameTestHelper helper,boolean additive,int variant) {
            this.helper=helper;this.additive=additive;this.variant=variant;
        }
        void start() {
            guarded(()->{
                fixture=new NativePhysicsFixture(helper.getLevel());
                for(int i=0;i<2;i++) {
                    Quaterniond rotation=new Quaterniond();
                    if ((additive && i==1) || (!additive && variant==1)) rotation.rotateY(Math.PI/2);
                    int layers=additive?1:i+1;
                    Vector3d base=fixturePosition(i,false);
                    var driven=fixture.create(layers,rotation,base);
                    var control=fixture.create(layers,rotation,fixturePosition(i,true));
                    Vector3d expected=additive?new Vector3d(ADD_LINEAR):rotation.transform(new Vector3d(1,0,0));
                    pairs.add(new Pair(driven,control,expected));
                }
                awaitReady(0);
                helper.runAfterDelay(210,()->{ if(!finished) fail(new IllegalStateException("Calibration hook/step timeout")); });
            });
        }
        private void awaitReady(int waited) {
            helper.runAfterDelay(1,()->guarded(()->{
                if(!fixture.ready()) {
                    require(waited<100,"Native bodies/masses never became ready"); awaitReady(waited+1);return;
                }
                // Reposition only fixture setup; all measured motion comes from the ordinary native steps.
                for(int i=0;i<pairs.size();i++) {
                    Pair p=pairs.get(i);
                    for(var body:new ServerSubLevel[]{p.driven,p.control}) {
                        Quaterniond q=new Quaterniond();
                        if((additive&&i==1)||(!additive&&variant==1))q.rotateY(Math.PI/2);
                        RigidBodyHandle.of(body).teleport(fixturePosition(i,body==p.control),q);
                        fixture.resetMotion(body);
                    }
                }
                // Let updatePose publish the setup teleport before measuring any finite differences.
                helper.runAfterDelay(2,()->guarded(()->{
                    require(fixture.ready(),"Native readiness lost after setup");
                    for(Pair p:pairs) {
                        fixture.resetMotion(p.driven);fixture.resetMotion(p.control);
                    }
                    LOG.info("SABLE_CALIBRATION backend={} configuredSubsteps={}",
                            fixture.container.physicsSystem().getPipeline().getClass().getName(),
                            fixture.container.physicsSystem().getConfig().substepsPerTick);
                    NeoForge.EVENT_BUS.register(this);registered=true;
                }));
            }));
        }
        @SubscribeEvent public void onPre(ForgeSablePrePhysicsTickEvent event) {
            if(finished || fixture==null || event.getPhysicsSystem()!=fixture.container.physicsSystem())return;
            guarded(()->{
                double dt=event.getTimeStep();
                int substeps=event.getPhysicsSystem().getConfig().substepsPerTick;
                require(Double.isFinite(dt)&&dt>0&&Math.abs(dt-.05/substeps)<1e-10,"Unexpected native dt");
                require(pendingDt==0,"Pre/post hook ordering mismatch");pendingDt=dt;preSteps++;
                for(Pair p:pairs) {
                    p.stepDriven=new Vector3d(p.driven.logicalPose().position());
                    p.stepControl=new Vector3d(p.control.logicalPose().position());
                    logStep("pre",p,dt);
                }
                if(started)return;
                started=true;startGameTime=helper.getLevel().getGameTime();
                double impulse=LIGHT_TARGET_SPEED*pairs.getFirst().driven.getMassTracker().getMass();
                require(Double.isFinite(impulse)&&impulse>0,"Invalid common impulse");
                for(Pair p:pairs) {
                    var h=RigidBodyHandle.of(p.driven);var c=RigidBodyHandle.of(p.control);
                    require(h.isValid()&&c.isValid(),"Invalid handle during calibration");
                    p.mass=p.driven.getMassTracker().getMass();
                    require(Double.isFinite(p.mass)&&p.mass>0,"Invalid mass");
                    nearScalar(p.mass,p.control.getMassTracker().getMass(),"Control mass");
                    p.beforeDriven=velocity(p.driven);p.beforeControl=velocity(p.control);
                    near(angular(p.driven),new Vector3d(),"Initial driven angular velocity");
                    near(angular(p.control),new Vector3d(),"Initial control angular velocity");
                    p.startDriven=new Vector3d(p.driven.logicalPose().position());
                    p.startControl=new Vector3d(p.control.logicalPose().position());
                    p.previousOrientation=new Quaterniond(p.driven.logicalPose().orientation());
                    if(additive) {
                        Vector3d oldAngular=angular(p.driven);
                        h.addLinearAndAngularVelocity(new Vector3d(ADD_LINEAR),new Vector3d(ADD_ANGULAR));
                        near(velocity(p.driven).sub(p.beforeDriven),ADD_LINEAR,"Immediate world linear addition");
                        near(angular(p.driven).sub(oldAngular),ADD_ANGULAR,"Immediate world angular addition");
                    } else {
                        h.applyLinearImpulse(new Vector3d(impulse,0,0));
                        p.expected.mul(impulse/p.mass);
                        near(velocity(p.driven).sub(p.beforeDriven),p.expected,"Immediate local impulse J/m");
                    }
                    LOG.info("SABLE_CALIBRATION start additive={} variant={} uuid={} mass={} q={} v0={} expected={} gravity={} drag={} substeps={} dt={} J={}",
                            additive,variant,p.driven.getUniqueId(),p.mass,p.previousOrientation,p.beforeDriven,p.expected,
                            DimensionPhysicsData.getGravity(helper.getLevel()),DimensionPhysicsData.getUniversalDrag(helper.getLevel()),substeps,dt,additive?0:impulse);
                    LOG.info("SABLE_CALIBRATION exact_start uuid={} drivenStart={} controlStart={} vDriven={} vControl={} omegaDriven={} omegaControl={} rotationPoint={} com={} solverIterations={}",
                            p.driven.getUniqueId(),exact(p.startDriven),exact(p.startControl),exact(p.beforeDriven),exact(p.beforeControl),
                            exact(angular(p.driven)),exact(angular(p.control)),exact(p.driven.logicalPose().rotationPoint()),
                            exact(new Vector3d(p.driven.getMassTracker().getCenterOfMass())),event.getPhysicsSystem().getConfig().solverIterations);
                }
                if(!additive)require(pairs.get(1).mass>pairs.get(0).mass*1.5,"Two distinct masses required");
            });
        }
        @SubscribeEvent public void onPost(ForgeSablePostPhysicsTickEvent event) {
            if(finished||!started||event.getPhysicsSystem()!=fixture.container.physicsSystem())return;
            guarded(()->{
                require(Math.abs(pendingDt-event.getTimeStep())<1e-10&&pendingDt>0,"Unpaired post hook");
                double dt=pendingDt;pendingDt=0;postSteps++;simulationTime+=dt;
                for(Pair p:pairs) {
                    require(!p.driven.isRemoved()&&!p.control.isRemoved(),"Body removed during motion");
                    logStep("post",p,dt);
                    Vector3d difference=velocity(p.driven).sub(velocity(p.control));
                    Vector3d initialDifference=new Vector3d(p.beforeDriven).sub(p.beforeControl);
                    difference.sub(initialDifference);p.integrated.fma(dt,difference);
                    if(additive) {
                        p.angularIntegral.fma(dt,angular(p.driven).sub(angular(p.control)));
                        Quaterniond current=new Quaterniond(p.driven.logicalPose().orientation());
                        Quaterniond delta=new Quaterniond(current).mul(new Quaterniond(p.previousOrientation).invert()).normalize();
                        if(delta.w<0)delta.set(-delta.x,-delta.y,-delta.z,-delta.w);
                        double s=Math.sqrt(delta.x*delta.x+delta.y*delta.y+delta.z*delta.z);
                        if(s>1e-12)p.angularMeasured.add(new Vector3d(delta.x,delta.y,delta.z).mul(2*Math.atan2(s,delta.w)/s));
                        p.previousOrientation=current;
                    }
                }
                if(simulationTime+1e-10>=INTERVAL)finish();
            });
        }
        private void finish() {
            require(postSteps==preSteps&&simulationTime>0,"Missing physics steps");
            require(helper.getLevel().getGameTime()-startGameTime<=4,"Unexpected game/simulation time relation");
            for(Pair p:pairs) {
                Vector3d deltaV=velocity(p.driven).sub(p.beforeDriven)
                        .sub(velocity(p.control).sub(p.beforeControl));
                near(deltaV,p.expected,"Post-step delta velocity (drag/control interval)");
                Vector3d motion=new Vector3d(p.driven.logicalPose().position()).sub(p.startDriven)
                        .sub(new Vector3d(p.control.logicalPose().position()).sub(p.startControl));
                motion.sub(new Vector3d(p.beforeDriven).sub(p.beforeControl).mul(simulationTime));
                require(motion.length()> .001 && deltaV.length()>.02,"No-op native motion must not pass");
                near(new Vector3d(motion).div(simulationTime),new Vector3d(p.integrated).div(simulationTime),"Pose finite difference / getter integral");
                near(new Vector3d(motion).div(simulationTime),p.expected,"Simulation-second displacement units");
                if(additive) {
                    near(new Vector3d(p.angularIntegral).div(simulationTime),ADD_ANGULAR,"Angular addition after native steps");
                    near(new Vector3d(p.angularMeasured).div(simulationTime),new Vector3d(p.angularIntegral).div(simulationTime),"Quaternion finite difference / angular getter");
                    require(p.angularMeasured.length()>.005,"Angular no-op must not pass");
                }
                LOG.info("SABLE_CALIBRATION measured additive={} variant={} mass={} steps={} sumDt={} deltaV={} deltaPosition={} integral={} angularIntegral={} angularMeasured={}",
                        additive,variant,p.mass,postSteps,simulationTime,deltaV,motion,p.integrated,p.angularIntegral,p.angularMeasured);
            }
            complete(null);
        }
        private Vector3d fixturePosition(int pair,boolean control) {
            // float32 native positions are repeatedly integrated at solver-internal dt/18.
            // Keep |X/Z| <= 320 instead of 8192+, without changing speed/tolerances.
            return new Vector3d(32+variant*128+pair*32,240,control?-32:-64);
        }
        private void logStep(String phase,Pair p,double dt) {
            Pose3d nativeDriven=fixture.container.physicsSystem().getPipeline().readPose(p.driven,new Pose3d());
            Pose3d nativeControl=fixture.container.physicsSystem().getPipeline().readPose(p.control,new Pose3d());
            LOG.info("SABLE_CALIBRATION step phase={} uuid={} ordinal={} dt={} sumDt={} drivenStart={} controlStart={} drivenNow={} controlNow={} nativeDriven={} nativeControl={} drivenStepDelta={} controlStepDelta={} vDriven={} vControl={} omegaDriven={} omegaControl={} qDriven={} qControl={}",
                    phase,p.driven.getUniqueId(),preSteps,dt,simulationTime,exact(p.stepDriven),exact(p.stepControl),
                    exact(p.driven.logicalPose().position()),exact(p.control.logicalPose().position()),
                    exact(nativeDriven.position()),exact(nativeControl.position()),
                    exact(new Vector3d(p.driven.logicalPose().position()).sub(p.stepDriven)),
                    exact(new Vector3d(p.control.logicalPose().position()).sub(p.stepControl)),
                    exact(velocity(p.driven)),exact(velocity(p.control)),exact(angular(p.driven)),exact(angular(p.control)),
                    exactQuaternion(p.driven.logicalPose().orientation()),exactQuaternion(p.control.logicalPose().orientation()));
        }
        private void cleanup() {
            if(registered){NeoForge.EVENT_BUS.unregister(this);registered=false;}
            if(fixture!=null)fixture.close();
        }
        private void guarded(Runnable action) {
            if(finished)return;
            try{action.run();}catch(Exception error){fail(error);}
        }
        private void fail(Exception error) {
            complete(error);
        }
        private void complete(Exception failure) {
            if(finished)return;
            // Immediately stop observations, but defer mutation and GameTest exceptions until
            // GameTest's own scheduled task phase, outside Sable's iteration/event callback.
            finished=true;
            helper.runAfterDelay(1,()->{
                Exception reported=failure;
                try{cleanup();}catch(Exception cleanupError){
                    if(reported==null)reported=cleanupError;else reported.addSuppressed(cleanupError);
                }
                if(reported!=null) {
                    LOG.error("SABLE_CALIBRATION failed in GameTest phase",reported);
                    helper.fail("Native calibration failed: "+reported);
                } else helper.succeed();
            });
        }
    }
    private static Vector3d velocity(ServerSubLevel body) { return finite(RigidBodyHandle.of(body).getLinearVelocity(new Vector3d())); }
    private static Vector3d angular(ServerSubLevel body) { return finite(RigidBodyHandle.of(body).getAngularVelocity(new Vector3d())); }
    private static Vector3d finite(Vector3d v) { require(Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z),"Non-finite native velocity");return v; }
    private static void near(Vector3d actual,Vector3d expected,String label) {
        finite(actual);finite(expected);
        require(actual.distance(expected)<=ABS+REL*expected.length(),label+": expected="+expected+" actual="+actual);
    }
    private static void nearScalar(double a,double b,String label) { require(Double.isFinite(a)&&Double.isFinite(b)&&Math.abs(a-b)<=ABS+REL*Math.abs(b),label); }
    private static String exact(Vector3d v) { return String.format(Locale.ROOT,"(%.12f, %.12f, %.12f)",v.x,v.y,v.z); }
    private static String exactQuaternion(org.joml.Quaterniondc q) { return String.format(Locale.ROOT,"(%.12f, %.12f, %.12f, %.12f)",q.x(),q.y(),q.z(),q.w()); }
    private static void require(boolean ok,String message) { NativePhysicsFixture.require(ok,message); }
}
