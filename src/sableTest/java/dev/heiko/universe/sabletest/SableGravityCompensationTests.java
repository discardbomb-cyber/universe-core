package dev.heiko.universe.sabletest;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
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

/** Disposable native test server only. Temporary scene configuration is not a production adapter. */
@GameTestHolder(SableTestMod.ID)
@PrefixGameTestTemplate(false)
public final class SableGravityCompensationTests {
    private static final Logger LOG=LoggerFactory.getLogger(SableGravityCompensationTests.class);
    private static final double TIME=.2, ABS=.01, REL=.05;
    private static final int SOLVER_ITERATIONS=18;
    private static final Vector3d[] TARGETS={new Vector3d(),new Vector3d(3,-4,2)};
    // Lock only this suite's mutations. Other suites must remain one-test-per-batch on the isolated runner.
    private static final Map<SubLevelPhysicsSystem,Session> CONFIG_OWNERS=new IdentityHashMap<>();
    // Values contain only measurements, so pending results do not strongly retain the scene key.
    private static final Map<SubLevelPhysicsSystem,Map<Integer,List<Observation>>> COMPLETED=new WeakHashMap<>();
    private record Observation(Vector3d velocity,Vector3d correctedMotion,double velocityDrift,double poseDrift) {}

    @GameTest(template="empty",timeoutTicks=260,batch="sable_gravity_compensation_one_step",required=true)
    public static void compensateGravityOneSubstep(GameTestHelper helper) { new Session(helper,1).start(); }
    @GameTest(template="empty",timeoutTicks=260,batch="sable_gravity_compensation_four_steps",required=true)
    public static void compensateGravityFourSubsteps(GameTestHelper helper) { new Session(helper,4).start(); }

    private static final class Pair {
        final ServerSubLevel driven,control;
        final Quaterniond orientation;
        Vector3d startDriven,startControl,vDrivenBefore,vControlBefore,vDrivenAfter;
        final Vector3d drivenIntegral=new Vector3d(),controlIntegral=new Vector3d(),worldImpulseSum=new Vector3d();
        double mass;
        int impulses;
        Pair(ServerSubLevel driven,ServerSubLevel control,Quaterniond orientation) {
            this.driven=driven;this.control=control;this.orientation=orientation;
        }
        void resetMeasurement() { drivenIntegral.zero();controlIntegral.zero();worldImpulseSum.zero();impulses=0; }
    }

    public static final class Session {
        private final GameTestHelper helper;
        private final int requested;
        private final ArrayList<Pair> pairs=new ArrayList<>();
        private final ArrayList<Observation> observations=new ArrayList<>();
        private NativePhysicsFixture fixture;
        private SubLevelPhysicsSystem system;
        private Vector3d gravity;
        private double drag,time,pendingDt,dtSquaredSum;
        private int originalSubsteps,phase,preSteps,postSteps;
        private long startGameTime;
        private boolean configOwned,registered,measuring,phaseStarted,finished;

        private Session(GameTestHelper helper,int requested) { this.helper=helper;this.requested=requested; }
        void start() {
            guarded(()->{
                fixture=new NativePhysicsFixture(helper.getLevel());system=fixture.container.physicsSystem();
                synchronized(CONFIG_OWNERS) {
                    require(!CONFIG_OWNERS.containsKey(system),"Another compensation test owns this scene config");
                    CONFIG_OWNERS.put(system,this);configOwned=true;
                }
                // getConfig returns the live mutable object; tickPipelinePhysics reads this field directly.
                originalSubsteps=system.getConfig().substepsPerTick;
                require(originalSubsteps>=1&&originalSubsteps<=10,"Original substep count outside pinned range");
                require(system.getConfig().solverIterations==SOLVER_ITERATIONS,"Uncalibrated internal solver count");
                system.getConfig().substepsPerTick=requested;
                require(system.getConfig().substepsPerTick==requested,"Live config mutation not retained");
                gravity=finite(DimensionPhysicsData.getGravity(helper.getLevel()));
                drag=DimensionPhysicsData.getUniversalDrag(helper.getLevel());
                require(gravity.length()>1&&gravity.length()<32,"Nonzero bounded native control gravity required");
                require(Double.isFinite(drag)&&drag>=0&&drag<=.1,"Uncalibrated damping regime");
                for(int i=0;i<4;i++) {
                    Quaterniond q=new Quaterniond();
                    // Rotate gravity's Y axis too: yaw alone would not exercise inverse-frame compensation.
                    if(i>=2)q.rotateZ(Math.PI/2);
                    int layers=i%2+1;
                    pairs.add(new Pair(fixture.create(layers,q,position(i,false)),
                            fixture.create(layers,q,position(i,true)),q));
                }
                awaitReady(0);
                helper.runAfterDelay(230,()->{if(!finished)complete(new IllegalStateException("Compensation watchdog"));});
            });
        }
        private Vector3d position(int i,boolean control) {
            return new Vector3d(-192+(requested==4?256:0)+i*32,160,control?192:160);
        }
        private void awaitReady(int waited) {
            helper.runAfterDelay(1,()->guarded(()->{
                if(!fixture.ready()) { require(waited<100,"Native compensation fixtures not ready");awaitReady(waited+1);return; }
                for(Pair p:pairs) {
                    p.mass=p.driven.getMassTracker().getMass();
                    require(Math.abs(p.mass-p.control.getMassTracker().getMass())<1e-6,"Control fixture mass mismatch");
                }
                require(pairs.get(1).mass>pairs.get(0).mass*1.5,"Two distinct native masses required");
                require(Math.abs(pairs.get(0).mass-pairs.get(2).mass)<1e-6
                        &&Math.abs(pairs.get(1).mass-pairs.get(3).mass)<1e-6,"Orientation changed fixture masses");
                NeoForge.EVENT_BUS.register(this);registered=true;
                preparePhase();
            }));
        }
        private void preparePhase() {
            measuring=false;phaseStarted=false;time=0;pendingDt=0;dtSquaredSum=0;preSteps=0;postSteps=0;
            for(int i=0;i<pairs.size();i++) {
                Pair p=pairs.get(i);p.resetMeasurement();
                RigidBodyHandle.of(p.driven).teleport(position(i,false),p.orientation);
                RigidBodyHandle.of(p.control).teleport(position(i,true),p.orientation);
                fixture.resetMotion(p.driven);fixture.resetMotion(p.control);
            }
            // Publish setup pose through ordinary updatePose before sampling. No measured teleport.
            helper.runAfterDelay(2,()->guarded(()->{require(fixture.ready(),"Readiness lost after setup");measuring=true;}));
        }
        @SubscribeEvent public void pre(ForgeSablePrePhysicsTickEvent event) {
            if(finished||!measuring||event.getPhysicsSystem()!=system)return;
            guarded(()->{
                require(system.getConfig().substepsPerTick==requested,"Substep config changed during ownership");
                double dt=event.getTimeStep();
                require(Double.isFinite(dt)&&Math.abs(dt-.05/requested)<1e-10,"Runtime did not use requested substeps");
                require(pendingDt==0,"Duplicate pre hook/unpaired step");pendingDt=dt;preSteps++;
                if(!phaseStarted) {
                    phaseStarted=true;startGameTime=helper.getLevel().getGameTime();
                    for(Pair p:pairs) {
                        fixture.resetMotion(p.driven);fixture.resetMotion(p.control);
                        p.startDriven=new Vector3d(p.driven.logicalPose().position());
                        p.startControl=new Vector3d(p.control.logicalPose().position());
                        LOG.info("SABLE_GRAVITY start N={} phase={} mass={} driven={} control={} q={} gravity={} target={} drag={} startDriven={} startControl={}",
                                requested,phase,p.mass,p.driven.getUniqueId(),p.control.getUniqueId(),p.orientation,
                                exact(gravity),exact(TARGETS[phase]),drag,exact(p.startDriven),exact(p.startControl));
                    }
                }
                require(helper.getLevel().getGameTime()-startGameTime==(preSteps-1)/requested,
                        "Pre substeps did not follow ordinary game tick boundaries");
                Vector3d deltaA=new Vector3d(TARGETS[phase]).sub(gravity);
                for(Pair p:pairs) {
                    require(RigidBodyHandle.of(p.driven).isValid()&&RigidBodyHandle.of(p.control).isValid(),"Invalid compensation handle");
                    require(Math.abs(p.mass-p.driven.getMassTracker().getMass())<1e-6,"Mass changed during experiment");
                    p.vDrivenBefore=velocity(p.driven);p.vControlBefore=velocity(p.control);
                    Vector3d worldImpulse=new Vector3d(deltaA).mul(p.mass*dt);
                    Vector3d localImpulse=new Quaterniond(p.driven.logicalPose().orientation()).invert().transform(new Vector3d(worldImpulse));
                    // Exactly one local impulse for this body on this public native pre-step.
                    RigidBodyHandle.of(p.driven).applyLinearImpulse(localImpulse);p.impulses++;
                    p.worldImpulseSum.add(worldImpulse);p.vDrivenAfter=velocity(p.driven);
                    near(new Vector3d(p.vDrivenAfter).sub(p.vDrivenBefore),new Vector3d(deltaA).mul(dt),0,"Immediate world compensation increment");
                    LOG.info("SABLE_GRAVITY impulse N={} phase={} ordinal={} dt={} mass={} worldJ={} localJ={} q={} before={} after={}",
                            requested,phase,preSteps,dt,p.mass,exact(worldImpulse),exact(localImpulse),p.driven.logicalPose().orientation(),
                            exact(p.vDrivenBefore),exact(p.vDrivenAfter));
                }
            });
        }
        @SubscribeEvent public void post(ForgeSablePostPhysicsTickEvent event) {
            if(finished||!measuring||!phaseStarted||event.getPhysicsSystem()!=system)return;
            guarded(()->{
                require(pendingDt>0&&Math.abs(event.getTimeStep()-pendingDt)<1e-10,"Unpaired compensation post hook");
                double dt=pendingDt;pendingDt=0;postSteps++;time+=dt;dtSquaredSum+=dt*dt;
                require(helper.getLevel().getGameTime()-startGameTime==(postSteps-1)/requested,
                        "Post substeps did not follow ordinary game tick boundaries");
                for(Pair p:pairs) {
                    require(!p.driven.isRemoved()&&!p.control.isRemoved(),"Compensation fixture disappeared");
                    Vector3d v=velocity(p.driven),c=velocity(p.control);
                    // Integrate measured native velocity, including the actual impulse jump at pre.
                    p.drivenIntegral.fma(dt*.5,new Vector3d(p.vDrivenAfter).add(v));
                    p.controlIntegral.fma(dt*.5,new Vector3d(p.vControlBefore).add(c));
                    near(angular(p.driven),new Vector3d(),0,"COM impulse unexpectedly added angular velocity");
                    LOG.info("SABLE_GRAVITY step N={} phase={} ordinal={} sumDt={} drivenPosition={} controlPosition={} drivenV={} controlV={} omega={}",
                            requested,phase,postSteps,time,exact(p.driven.logicalPose().position()),exact(p.control.logicalPose().position()),
                            exact(v),exact(c),exact(angular(p.driven)));
                }
                if(time+1e-10>=TIME)verifyPhase();
            });
        }
        private void verifyPhase() {
            require(preSteps==postSteps&&postSteps==4*requested,"Not exactly four ordinary ticks of native substeps");
            require(helper.getLevel().getGameTime()-startGameTime==3,"Measurement did not span four ordinary game ticks");
            require(Math.abs(time-TIME)<1e-10,"Mismatched simulation duration");
            Vector3d target=TARGETS[phase],deltaA=new Vector3d(target).sub(gravity);
            for(Pair p:pairs) {
                require(p.impulses==postSteps,"Impulse was not applied exactly once per body/substep");
                near(new Vector3d(p.worldImpulseSum).div(p.mass),new Vector3d(deltaA).mul(TIME),0,"Total impulse/dt accumulation");
                Vector3d v=velocity(p.driven),c=velocity(p.control);
                Vector3d motion=new Vector3d(p.driven.logicalPose().position()).sub(p.startDriven);
                Vector3d controlMotion=new Vector3d(p.control.logicalPose().position()).sub(p.startControl);
                // Fixed analytic damping bound: speed during a step is at most |target|*T+|deltaA|*dt.
                double drivenDrift=drag*TIME*(target.length()*TIME+deltaA.length()*.05/requested);
                double controlDrift=drag*gravity.length()*TIME*TIME;
                near(v,new Vector3d(target).mul(TIME),drivenDrift,"Compensated target endpoint velocity");
                near(c,new Vector3d(gravity).mul(TIME),controlDrift,"Unmodified native gravity control");
                require(c.length()>.1,"Native gravity control was a no-op");
                near(new Vector3d(v).sub(c),new Vector3d(deltaA).mul(TIME),drivenDrift+controlDrift,"Differential acceleration/no double counting");
                // Pre-step impulses have a known dt-dependent trajectory bias, even for target zero.
                Vector3d bias=new Vector3d(deltaA).mul(.5*dtSquaredSum);
                Vector3d nominal=new Vector3d(target).mul(.5*TIME*TIME).add(bias);
                double solverPositionBound=(gravity.length()+deltaA.length())*dtSquaredSum/(2*SOLVER_ITERATIONS);
                double drivenPositionDrift=drivenDrift*TIME;
                double controlPositionDrift=controlDrift*TIME;
                near(new Vector3d(motion).div(TIME),new Vector3d(nominal).div(TIME),
                        (solverPositionBound+drivenPositionDrift)/TIME,"Target trajectory including pre-impulse timing bias");
                near(new Vector3d(controlMotion).div(TIME),new Vector3d(gravity).mul(.5*TIME),
                        (gravity.length()*dtSquaredSum/(2*SOLVER_ITERATIONS)+controlPositionDrift)/TIME,"Control trajectory");
                near(new Vector3d(motion).div(TIME),new Vector3d(p.drivenIntegral).div(TIME),
                        gravity.length()*dtSquaredSum/(2*SOLVER_ITERATIONS*TIME),"Driven pose/native getter integral");
                near(new Vector3d(controlMotion).div(TIME),new Vector3d(p.controlIntegral).div(TIME),
                        gravity.length()*dtSquaredSum/(2*SOLVER_ITERATIONS*TIME),"Control pose/native getter integral");
                Vector3d corrected=new Vector3d(motion).sub(bias).div(TIME);
                observations.add(new Observation(new Vector3d(v),corrected,drivenDrift,
                        (solverPositionBound+drivenPositionDrift)/TIME));
                LOG.info("SABLE_GRAVITY measured N={} phase={} mass={} steps={} sumDt={} sumDtSquared={} impulses={} worldJ={} target={} velocity={} controlVelocity={} motion={} controlMotion={} preImpulseBias={} correctedMotionPerSecond={} getterIntegral={} velocityDriftBound={} solverPositionBound={}",
                        requested,phase,p.mass,postSteps,time,dtSquaredSum,p.impulses,exact(p.worldImpulseSum),exact(target),exact(v),exact(c),
                        exact(motion),exact(controlMotion),exact(bias),exact(corrected),exact(p.drivenIntegral),drivenDrift,solverPositionBound);
            }
            measuring=false;
            if(phase==0)helper.runAfterDelay(1,()->guarded(()->{phase=1;preparePhase();}));
            else complete(null);
        }
        private void compareOtherRun() {
            synchronized(COMPLETED) {
                var pending=COMPLETED.computeIfAbsent(system,key->new HashMap<>());
                var other=pending.get(requested==1?4:1);
                if(other!=null) {
                    require(other.size()==observations.size(),"Cross-substeps case count mismatch");
                    for(int i=0;i<other.size();i++) {
                        Observation a=observations.get(i),b=other.get(i);
                        near(a.velocity,b.velocity,a.velocityDrift+b.velocityDrift,"N=1 versus N=4 endpoint");
                        near(a.correctedMotion,b.correctedMotion,a.poseDrift+b.poseDrift,"N=1 versus N=4 corrected trajectory");
                    }
                    LOG.info("SABLE_GRAVITY cross_substeps verified cases={} duration={} N=1/N=4",observations.size(),TIME);
                    COMPLETED.remove(system);
                } else {
                    // A repeat of the same N replaces its pending sample; a completed pair is consumed.
                    pending.put(requested,List.copyOf(observations));
                }
            }
        }
        private void cleanup() {
            RuntimeException error=null;
            try{if(registered){NeoForge.EVENT_BUS.unregister(this);registered=false;}}
            catch(RuntimeException e){error=e;}
            try{if(fixture!=null)fixture.close();}
            catch(RuntimeException e){if(error==null)error=e;else error.addSuppressed(e);}
            if(configOwned) {
                synchronized(CONFIG_OWNERS) {
                    if(CONFIG_OWNERS.get(system)==this) {
                        system.getConfig().substepsPerTick=originalSubsteps;
                        CONFIG_OWNERS.remove(system);configOwned=false;
                        LOG.info("SABLE_GRAVITY restored originalSubsteps={} actual={}",originalSubsteps,system.getConfig().substepsPerTick);
                    }
                }
            }
            if(error!=null)throw error;
        }
        private void complete(Exception failure) {
            if(finished)return;finished=true;measuring=false;
            helper.runAfterDelay(1,()->{
                Exception reported=failure;
                try{cleanup();}catch(Exception e){if(reported==null)reported=e;else reported.addSuppressed(e);}
                if(reported==null)try{compareOtherRun();}catch(Exception e){reported=e;}
                if(reported!=null){LOG.error("SABLE_GRAVITY failed in GameTest phase",reported);helper.fail("Native compensation failed: "+reported);}
                else helper.succeed();
            });
        }
        private void guarded(Runnable action) { if(finished)return;try{action.run();}catch(Exception e){complete(e);} }
    }
    private static Vector3d velocity(ServerSubLevel b){return finite(RigidBodyHandle.of(b).getLinearVelocity(new Vector3d()));}
    private static Vector3d angular(ServerSubLevel b){return finite(RigidBodyHandle.of(b).getAngularVelocity(new Vector3d()));}
    private static Vector3d finite(Vector3d v){require(v.isFinite(),"Non-finite native vector");return v;}
    private static void near(Vector3d actual,Vector3d expected,double knownBound,String label) {
        finite(actual);finite(expected);require(Double.isFinite(knownBound)&&knownBound>=0,"Invalid physical bound");
        require(actual.distance(expected)<=ABS+REL*expected.length()+knownBound,
                label+": expected="+exact(expected)+" actual="+exact(actual)+" knownBound="+knownBound);
    }
    private static String exact(Vector3d v){return String.format(Locale.ROOT,"(%.12f, %.12f, %.12f)",v.x,v.y,v.z);}
    private static void require(boolean ok,String message){NativePhysicsFixture.require(ok,message);}
}
