package dev.heiko.universe.sabletest;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Owns only calibration bodies/tickets; never reinitializes or manually steps a live scene. */
final class NativePhysicsFixture implements AutoCloseable {
    final ServerSubLevelContainer container;
    final ArrayList<ServerSubLevel> owned = new ArrayList<>();

    NativePhysicsFixture(ServerLevel level) {
        container = SubLevelContainer.getContainer(level);
        require(container != null && container.physicsSystem() != null, "Missing Sable physics");
        require(container.physicsSystem().getPipeline().getClass().getName().equals(
                "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline"), "Required native Rapier backend unavailable");
    }

    ServerSubLevel create(int layers, Quaterniond orientation, Vector3d worldPosition) {
        BlockPos worldCenter=BlockPos.containing(worldPosition.x,worldPosition.y,worldPosition.z);
        for(BlockPos p:BlockPos.betweenClosed(worldCenter.offset(-5,-5,-5),worldCenter.offset(5,5,5)))
            require(container.getLevel().getBlockState(p).isAir(),"Fixture world volume is obstructed");
        Pose3d pose = new Pose3d();
        pose.position().set(worldPosition); pose.orientation().set(orientation);
        ServerSubLevel body = (ServerSubLevel) container.allocateNewSubLevel(pose);
        require(body != null, "Allocation failed");
        owned.add(body);
        container.addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        var chunk = body.getPlot().getCenterChunk();
        body.getPlot().newEmptyChunk(chunk);
        BlockPos origin = new BlockPos(chunk.getMinBlockX()+3,64,chunk.getMinBlockZ()+3);
        for (int z=0; z<layers; z++) {
            for (BlockPos p : new BlockPos[]{origin.offset(0,0,z), origin.offset(1,0,z), origin.offset(0,1,z)})
                body.getLevel().setBlock(p,Blocks.STONE.defaultBlockState(),Block.UPDATE_ALL);
        }
        var center = body.getMassTracker().getCenterOfMass();
        require(center != null, "Missing assembly center of mass");
        body.logicalPose().rotationPoint().set(center);
        return body;
    }

    boolean ready() {
        for (var body : owned) {
            require(!body.isRemoved() && container.getSubLevel(body.getUniqueId()) == body, "Calibration body disappeared");
            double mass=body.getMassTracker().getMass();
            if (!RigidBodyHandle.of(body).isValid() || !Double.isFinite(mass) || mass<=0) return false;
        }
        return true;
    }

    void resetMotion(ServerSubLevel body) {
        var handle=RigidBodyHandle.of(body);
        require(handle.isValid(), "Invalid native body");
        handle.addLinearAndAngularVelocity(handle.getLinearVelocity(new Vector3d()).negate(),
                handle.getAngularVelocity(new Vector3d()).negate());
        require(handle.getLinearVelocity(new Vector3d()).length() < 1e-7
                && handle.getAngularVelocity(new Vector3d()).length() < 1e-7,
                "Native motion reset did not reach zero");
        container.physicsSystem().getPipeline().wakeUp(body);
    }

    @Override public void close() {
        RuntimeException failure=null;
        for (var body : new ArrayList<>(owned)) {
            boolean ticketRemoved = false;
            try {
                container.removeForceLoadTicket(body,SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);
                ticketRemoved = true;
            } catch (RuntimeException error) {
                if (failure==null) failure=error; else failure.addSuppressed(error);
            }
            try {
                if (container.getSubLevel(body.getUniqueId()) == body)
                    container.removeSubLevel(body,SubLevelRemovalReason.REMOVED);
                if (ticketRemoved && container.getSubLevel(body.getUniqueId()) != body) owned.remove(body);
            } catch (RuntimeException error) {
                if (failure==null) failure=error; else failure.addSuppressed(error);
            }
        }
        if (failure!=null) throw failure;
    }

    static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
