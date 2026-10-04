package dev.heiko.universe.clienttest;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.bus.api.SubscribeEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Candidate: callback only collects bounded data/holds a boundary; failure is drained by ordinary tick. */
public final class DynamicServerDriver implements AutoCloseable {
    private final ServerSubLevelContainer container;
    private final ServerSubLevel body;
    private final boolean originalPause;
    private final Vector3d initialPosition, integral = new Vector3d();
    private Vector3d previousVelocity, lastPreviousVelocity, lastPostVelocity;
    private final Quaterniond initialOrientation;
    private final List<Map<String,Object>> telemetry = new ArrayList<>();
    private volatile Throwable deferredFailure;
    private double seconds;
    private int stage;
    private boolean holding = true, initialized, closed, restored;

    public DynamicServerDriver(ServerSubLevelContainer container, ServerSubLevel body) {
        this.container=container; this.body=body;
        thread();
        if(container.getAllSubLevels().size()!=1 || container.getSubLevel(body.getUniqueId())!=body)
            throw new IllegalStateException("Only owned single body permitted");
        var handle=handle();
        if(body.logicalPose().position().y()<87 || body.logicalPose().position().y()>89)
            throw new IllegalStateException("Prepare native airborne baseline around Y88");
        snapshot(); // Validate original live getters/pose BEFORE any mutation or normalization.
        originalPause=container.physicsSystem().getPaused();
        initialPosition=new Vector3d(body.logicalPose().position());
        initialOrientation=new Quaterniond(body.logicalPose().orientation()).normalize();
        try {
            container.physicsSystem().setPaused(true);
            handle.addLinearAndAngularVelocity(handle.getLinearVelocity(new Vector3d()).negate(),
                    handle.getAngularVelocity(new Vector3d()).negate());
            sample(0);
        } catch(RuntimeException|Error error) {
            try {container.physicsSystem().setPaused(originalPause);} catch(RuntimeException restore){error.addSuppressed(restore);}
            throw error;
        }
    }

    public void acknowledge(int expectedStage) {
        thread();
        if(closed || !holding || expectedStage!=stage || stage>=3) throw new IllegalStateException("Bad stage acknowledgement");
        var handle=handle();
        if(!initialized) {
            handle.addLinearAndAngularVelocity(new Vector3d(2,3,0).sub(handle.getLinearVelocity(new Vector3d())),
                    new Vector3d(0,.5,0).sub(handle.getAngularVelocity(new Vector3d())));
            if(handle.getLinearVelocity(new Vector3d()).distance(new Vector3d(2,3,0))>1e-6
                    || handle.getAngularVelocity(new Vector3d()).distance(new Vector3d(0,.5,0))>1e-6)
                throw new IllegalStateException("Immediate native getter readback failed");
            initialized=true;
        }
        var resumed=handle.getLinearVelocity(new Vector3d());
        if(!Double.isFinite(resumed.length()))throw new IllegalStateException("Nonfinite resumed velocity");
        if(previousVelocity!=null&&resumed.distance(previousVelocity)>1e-6)throw new IllegalStateException("Velocity changed during held boundary");
        previousVelocity=new Vector3d(resumed);
        lastPreviousVelocity=new Vector3d(resumed);lastPostVelocity=new Vector3d(resumed);sample(0);
        holding=false;
        container.physicsSystem().setPaused(false);
    }

    @SubscribeEvent public void post(ForgeSablePostPhysicsTickEvent event) {
        try {
            if(closed || deferredFailure!=null || holding || event.getPhysicsSystem()!=container.physicsSystem()) return;
            thread();
            double dt=event.getTimeStep();
            if(!Double.isFinite(dt)||Math.abs(dt-.025)>1e-10||container.physicsSystem().getConfig().substepsPerTick!=2) throw new IllegalStateException("Invalid physics timestep");
            seconds+=dt;
            var postVelocity=handle().getLinearVelocity(new Vector3d());
            if(previousVelocity==null||!Double.isFinite(previousVelocity.length())||!Double.isFinite(postVelocity.length()))
                throw new IllegalStateException("Nonfinite trapezoid endpoint");
            lastPreviousVelocity=new Vector3d(previousVelocity);lastPostVelocity=new Vector3d(postVelocity);
            integral.fma(.5*dt,new Vector3d(previousVelocity).add(postVelocity));
            previousVelocity.set(postVelocity);
            sample(dt);
            if(seconds+1e-8>=(stage+1)*.35) {
                stage++;
                container.physicsSystem().setPaused(true);
                holding=true;
            }
        } catch(Throwable error) {
            // NO throw, I/O, body deletion, listener removal or cleanup from native callback.
            deferredFailure=error;
        }
    }

    private void sample(double dt) {
        if(telemetry.size()>=512) throw new IllegalStateException("Telemetry cap 512 reached");
        if(!Double.isFinite(integral.length()))throw new IllegalStateException("Nonfinite integral");
        Map<String,Object> row=snapshot(); row.put("dt",dt); row.put("velocityIntegral",new double[]{integral.x,integral.y,integral.z});
        if(lastPreviousVelocity!=null)row.put("previousNativeV",new double[]{lastPreviousVelocity.x,lastPreviousVelocity.y,lastPreviousVelocity.z});
        if(lastPostVelocity!=null)row.put("postNativeV",new double[]{lastPostVelocity.x,lastPostVelocity.y,lastPostVelocity.z});
        telemetry.add(row);
    }
    public Map<String,Object> snapshot() {
        thread(); var p=body.logicalPose().position(); var q=body.logicalPose().orientation();
        var v=handle().getLinearVelocity(new Vector3d()); var w=handle().getAngularVelocity(new Vector3d());
        double norm=q.x()*q.x()+q.y()*q.y()+q.z()*q.z()+q.w()*q.w();
        if(!Double.isFinite(norm)||Math.abs(norm-1)>1e-5||!Double.isFinite(v.length())||!Double.isFinite(w.length())
                ||!Double.isFinite(p.x()+p.y()+p.z())) throw new IllegalStateException("Invalid live pose/getters");
        Map<String,Object> row=new LinkedHashMap<>(); row.put("uuid",body.getUniqueId().toString());
        row.put("stage",stage); row.put("simulationSeconds",seconds); row.put("gameTime",container.getLevel().getGameTime());
        var normalized=new Quaterniond(q).normalize();
        row.put("position",new double[]{p.x(),p.y(),p.z()}); row.put("orientation",new double[]{normalized.x,normalized.y,normalized.z,normalized.w});
        row.put("velocity",new double[]{v.x,v.y,v.z}); row.put("omega",new double[]{w.x,w.y,w.z});
        row.put("capturedAtMillis",System.currentTimeMillis());
        row.put("integralFormula","sum((previousNativeV+postNativeV)*0.5*dt)"); return row;
    }
    public List<Map<String,Object>> telemetry(){thread();return new ArrayList<>(telemetry);}
    public Throwable failure(){return deferredFailure;}
    public boolean callbacksStopped(){return closed;}
    public boolean pauseRestored(){return restored;}
    public int stage(){return stage;}
    public boolean holding(){return holding;}
    public void verifyFinal() {
        thread();
        var delta=new Vector3d(body.logicalPose().position()).sub(initialPosition);
        var q=new Quaterniond(body.logicalPose().orientation()).normalize().mul(new Quaterniond(initialOrientation).invert());
        double angle=2*Math.acos(Math.min(1,Math.abs(q.w)));
        snapshot();
        if(stage!=3 || !holding || !Double.isFinite(delta.length())||!Double.isFinite(integral.length())||delta.x<1.4 || !Double.isFinite(angle)||angle<.25
                ||delta.distance(integral)>.04 ||body.logicalPose().position().y()<83)
            throw new IllegalStateException("Final numeric movement gate failed");
    }
    private RigidBodyHandle handle(){var h=RigidBodyHandle.of(body);if(h==null||!h.isValid()||body.isRemoved())throw new IllegalStateException("Lost native body");return h;}
    private void thread(){if(!body.getLevel().getServer().isSameThread())throw new IllegalStateException("Wrong thread");}
    @Override public void close(){thread();closed=true;if(!restored){container.physicsSystem().setPaused(originalPause);restored=true;}}
}



