package dev.heiko.universe.sabletest;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3d;
import org.joml.Quaterniond;
import java.util.function.Function;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A deliberately narrow probe of Sable 2.0.5 public plot.save/load APIs.
 * Velocity tests add a narrow identity-frame probe; passengers, constraint families, crash recovery and clients remain unvalidated.
 */
@GameTestHolder(SableTestMod.ID)
@PrefixGameTestTemplate(false)
public final class SableNativeTransferTests {
    private static final Set<Block> ALLOWED_BLOCKS = Set.of(
        Blocks.STONE, Blocks.IRON_BLOCK, Blocks.OAK_STAIRS, Blocks.CHEST);
    private static final int MAX_PHYSICS_WAIT_TICKS = 100;

    @GameTest(template = "empty", timeoutTicks = 400, batch = "sable_native_roundtrip")
    public static void blocksAndChestRoundTrip(GameTestHelper helper) {
        var session = new ProbeSession(helper, ProbeMode.ROUND_TRIP);
        session.start();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "sable_native_occupied")
    public static void occupiedDestinationPreservesSource(GameTestHelper helper) {
        var session = new ProbeSession(helper, ProbeMode.OCCUPIED_DESTINATION);
        session.start();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "sable_native_unsupported")
    public static void unsupportedBlockEntityPreservesSource(GameTestHelper helper) {
        var session = new ProbeSession(helper, ProbeMode.UNSUPPORTED_BE);
        session.start();
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = "sable_velocity_roundtrip", required = true)
    public static void nonzeroVelocityRoundTrip(GameTestHelper helper) {
        new ProbeSession(helper, ProbeMode.VELOCITY).start();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "sable_velocity_unavailable", required = true)
    public static void unavailableVelocityPreservesSource(GameTestHelper helper) {
        new ProbeSession(helper, ProbeMode.UNAVAILABLE_VELOCITY).start();
    }

    private enum ProbeMode { ROUND_TRIP, OCCUPIED_DESTINATION, UNSUPPORTED_BE, VELOCITY, UNAVAILABLE_VELOCITY }

    public static final class ProbeSession {
        private final GameTestHelper helper;
        private final ProbeMode mode;
        private final ArrayList<ServerSubLevel> owned = new ArrayList<>();
        private final Set<ServerSubLevel> releasedTickets = new LinkedHashSet<>();
        private final Map<BlockPos, BlockState> expected = new LinkedHashMap<>();
        private final UUID logicalId = UUID.randomUUID();
        private ServerLevel overworld;
        private ServerLevel end;
        private ServerSubLevel first;
        private ServerSubLevel occupant;
        private int sourceSlotX;
        private int sourceSlotZ;
        private BlockPos unsupportedPosition;
        private final Map<SubLevelPhysicsSystem, Boolean> originalPause = new LinkedHashMap<>();
        private boolean listening, observing, stopped;
        private ServerSubLevel movingSource, movingTarget;
        private Vector3d sourceStart, targetStart, capturedLinear, capturedAngular;
        private Quaterniond sourceRotation, targetRotation;
        private double sourceDt, targetDt;
        private int legs;
        private Runnable afterMotion;
        private String phase = "setup";
        // Fixed before root run; matches calibration tolerances, not widened for transfers.
        private static final double ABS = .01, REL = .05, INTERVAL = .1;

        private ProbeSession(GameTestHelper helper, ProbeMode mode) {
            this.helper = helper;
            this.mode = mode;
        }

        private void start() {
            guarded(() -> {
                var server = helper.getLevel().getServer();
                overworld = server.getLevel(Level.OVERWORLD);
                end = server.getLevel(Level.END);
                require(overworld != null && end != null, "Both Overworld and End must exist");
                var sourceContainer = container(overworld);
                var targetContainer = container(end);
                require(sourceContainer.getOrigin().equals(targetContainer.getOrigin()), "Different plot origins unsupported");
                require(sourceContainer.getLogPlotSize() == targetContainer.getLogPlotSize(), "Different plot sizes unsupported");
                first = (ServerSubLevel) sourceContainer.allocateNewSubLevel(new Pose3d());
                own(first);
                CompoundTag empty = first.getPlot().save();
                sourceSlotX = empty.getInt("plot_x");
                sourceSlotZ = empty.getInt("plot_z");
                require(targetContainer.getSubLevel(sourceSlotX, sourceSlotZ) == null, "Matching target plot slot occupied");
                populate(first);
                first.setName("Universe native transfer probe");
                var userData = new CompoundTag();
                userData.putUUID("universe_test_logical_id", logicalId);
                first.setUserDataTag(userData);
                first.logicalPose().rotationPoint().set(first.getPlot().getCenterBlock().getX() + 0.5,
                    64.5, first.getPlot().getCenterBlock().getZ() + 0.5);
                first.logicalPose().position().set(0.0, 160.0, 0.0);
                if (mode == ProbeMode.OCCUPIED_DESTINATION) {
                    Pose3d occupiedPose = new Pose3d();
                    occupiedPose.position().set(256.0, 160.0, 256.0);
                    occupant = (ServerSubLevel) targetContainer.allocateSubLevel(UUID.randomUUID(),
                        sourceSlotX, sourceSlotZ, occupiedPose);
                    own(occupant);
                    // Empty native plots have extreme bounds and Sable removes them on the next tick.
                    // A real occupied slot fixture must contain an actual loaded block and native body.
                    var occupantPlot = occupant.getPlot();
                    var occupantChunk = occupantPlot.getCenterChunk();
                    occupantPlot.newEmptyChunk(occupantChunk);
                    BlockPos occupantBlock = new BlockPos(occupantChunk.getMinBlockX() + 3,
                        64, occupantChunk.getMinBlockZ() + 3);
                    end.setBlock(occupantBlock, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    occupant.logicalPose().rotationPoint().set(occupantBlock.getX() + 0.5,
                        occupantBlock.getY() + 0.5, occupantBlock.getZ() + 0.5);
                } else if (mode == ProbeMode.UNSUPPORTED_BE) {
                    unsupportedPosition = expected.keySet().iterator().next().offset(1, 1, 1);
                    first.getLevel().setBlock(unsupportedPosition, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
                    var blockEntity = first.getLevel().getBlockEntity(unsupportedPosition);
                    require(blockEntity instanceof BarrelBlockEntity, "Unsupported BE fixture unavailable");
                    ((BarrelBlockEntity) blockEntity).setItem(2, new ItemStack(Items.COPPER_INGOT, 11));
                    blockEntity.setChanged();
                }
                waitForPhysics(first, 0, () -> {
                    if (mode == ProbeMode.VELOCITY || mode == ProbeMode.UNAVAILABLE_VELOCITY) startVelocity();
                    else if (mode == ProbeMode.ROUND_TRIP) transferFirstLeg();
                    else if (mode == ProbeMode.OCCUPIED_DESTINATION) {
                        waitForPhysics(occupant, 0, this::verifyRejectedTransfer);
                    }
                    else verifyRejectedTransfer();
                });
            });
        }

        private void verifyRejectedTransfer() {
            guarded(() -> {
                int sourceCount = container(overworld).getAllSubLevels().size();
                int targetCount = container(end).getAllSubLevels().size();
                int ownedCount = owned.size();
                String expectedReason = mode == ProbeMode.OCCUPIED_DESTINATION
                    ? "Matching target slot occupied" : "Unsupported block entity present";
                if (mode == ProbeMode.OCCUPIED_DESTINATION) {
                    require(!occupant.isRemoved() && container(end).getSubLevel(sourceSlotX, sourceSlotZ) == occupant,
                        "Occupied destination fixture disappeared before transfer attempt");
                }
                boolean rejected = false;
                try { stage(first, end); }
                catch (IllegalStateException expectedFailure) {
                    require(expectedReason.equals(expectedFailure.getMessage()),
                        "Transfer rejected for unexpected reason: " + expectedFailure.getMessage());
                    rejected = true;
                }
                require(rejected, "Unsafe transfer was accepted");
                require(!first.isRemoved() && container(overworld).getSubLevel(first.getUniqueId()) == first,
                    "Rejected transfer removed source");
                require(container(overworld).getAllSubLevels().size() == sourceCount,
                    "Rejected transfer changed source object count");
                require(container(end).getAllSubLevels().size() == targetCount && owned.size() == ownedCount,
                    "Rejected transfer allocated a destination");
                verifyContents(first);
                verifyIdentity(first);
                if (mode == ProbeMode.OCCUPIED_DESTINATION) {
                    require(!occupant.isRemoved() && container(end).getSubLevel(sourceSlotX, sourceSlotZ) == occupant,
                        "Rejected transfer replaced destination occupant");
                } else {
                    require(first.getLevel().getBlockState(unsupportedPosition).is(Blocks.BARREL),
                        "Rejected transfer changed unsupported source block");
                    var blockEntity = first.getLevel().getBlockEntity(unsupportedPosition);
                    require(blockEntity instanceof BarrelBlockEntity, "Rejected transfer removed unsupported BE");
                    var contents = ((BarrelBlockEntity) blockEntity).getItem(2);
                    require(contents.is(Items.COPPER_INGOT) && contents.getCount() == 11,
                        "Rejected transfer changed unsupported BE inventory");
                }
                cleanup();
                helper.succeed();
            });
        }

        private void populate(ServerSubLevel source) {
            var plot = source.getPlot();
            var globalChunk = plot.getCenterChunk();
            var localChunk = plot.toLocal(globalChunk);
            plot.newEmptyChunk(globalChunk);
            LevelChunk chunk = plot.getChunk(localChunk);
            require(chunk != null, "Native source plot chunk unavailable");
            BlockPos origin = new BlockPos(globalChunk.getMinBlockX() + 3, 64, globalChunk.getMinBlockZ() + 3);
            expected.put(origin, Blocks.STONE.defaultBlockState());
            expected.put(origin.offset(1, 0, 0), Blocks.IRON_BLOCK.defaultBlockState());
            expected.put(origin.offset(2, 0, 0), Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST).setValue(StairBlock.HALF, Half.TOP));
            expected.put(origin.offset(0, 1, 1), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
            for (var block : expected.entrySet()) {
                source.getLevel().setBlock(block.getKey(), block.getValue(), Block.UPDATE_ALL);
            }
            ChestBlockEntity chest = chest(source);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
            chest.setItem(8, new ItemStack(Items.IRON_INGOT, 19));
            chest.setChanged();
            verifyContents(source);
        }

        private void startVelocity() {
            guarded(() -> {
                helper.runAfterDelay(120, () -> {
                    if (!stopped) guarded(() -> { throw new IllegalStateException("Velocity transfer phase timeout"); });
                });
                for (ServerLevel level : new ServerLevel[]{overworld, end}) {
                    var system = container(level).physicsSystem();
                    require(system.getPipeline().getClass().getName().equals(
                        "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline"), "Native velocity backend unavailable");
                    require(system.getConfig().substepsPerTick == 2, "Velocity probe requires calibrated two substeps");
                    originalPause.put(system, system.getPaused());
                    system.setPaused(true);
                }
                // Pause is test-world-wide. Actor/ticket ticks still run; never call pipeline.init/step manually.
                require(new Vector3d(DimensionPhysicsData.getGravity(overworld)).distance(
                    DimensionPhysicsData.getGravity(end)) < 1e-10, "Control dimensions require equal gravity");
                require(Math.abs(DimensionPhysicsData.getUniversalDrag(overworld)
                    - DimensionPhysicsData.getUniversalDrag(end)) < 1e-10, "Control dimensions require equal drag");
                var h = captureHandle(first, RigidBodyHandle::of);
                h.teleport(new Vector3d(128, 240, -64), new Quaterniond());
                container(overworld).physicsSystem().updatePose(first);
                restore(first, new Vector3d(2, .4, -.7), new Vector3d(.15, .25, -.1));
                if (mode == ProbeMode.UNAVAILABLE_VELOCITY) rejectUnavailableVelocity();
                else velocityLeg(first, end);
            });
        }

        private RigidBodyHandle captureHandle(ServerSubLevel source,
                Function<ServerSubLevel, RigidBodyHandle> resolver) {
            require(!source.isRemoved() && container(source.getLevel()).getSubLevel(source.getUniqueId()) == source,
                "Velocity source unavailable");
            var h = resolver.apply(source);
            require(h != null && h.isValid(), "Native velocity capability unavailable");
            // isValid alone is insufficient: actually call native getters and demand finite values/mass.
            finite(h.getLinearVelocity(new Vector3d()));
            finite(h.getAngularVelocity(new Vector3d()));
            require(Double.isFinite(source.getMassTracker().getMass()) && source.getMassTracker().getMass() > 0,
                "Native velocity mass unavailable");
            return h;
        }

        private ServerSubLevel stageVelocity(ServerSubLevel source, ServerLevel target,
                Function<ServerSubLevel, RigidBodyHandle> resolver) {
            var h = captureHandle(source, resolver); // guard runs before allocating or deleting anything
            capturedLinear = new Vector3d(h.getLinearVelocity(new Vector3d()));
            capturedAngular = new Vector3d(h.getAngularVelocity(new Vector3d()));
            require(capturedLinear.length() > .1 && capturedAngular.length() > .05, "Nonzero motion required");
            return stage(source, target);
        }

        private void restore(ServerSubLevel body, Vector3d linear, Vector3d angular) {
            var h = captureHandle(body, RigidBodyHandle::of);
            h.addLinearAndAngularVelocity(new Vector3d(linear).sub(h.getLinearVelocity(new Vector3d())),
                new Vector3d(angular).sub(h.getAngularVelocity(new Vector3d())));
            near(h.getLinearVelocity(new Vector3d()), linear, "Immediate restored V");
            near(h.getAngularVelocity(new Vector3d()), angular, "Immediate restored omega");
            container(body.getLevel()).physicsSystem().getPipeline().wakeUp(body);
        }

        private void velocityLeg(ServerSubLevel source, ServerLevel targetLevel) {
            guarded(() -> {
                phase = "leg-" + legs + ":before-stage";
                diagnose(source, phase + ":source", null);
                movingSource = source;
                movingTarget = stageVelocity(source, targetLevel, RigidBodyHandle::of);
                // Allocation adds a native body synchronously, but load/mass/collider updates need ordinary ticks.
                // Keep source paused. Warm up only the destination before restoring the captured boundary.
                container(targetLevel).physicsSystem().setPaused(false);
                helper.runAfterDelay(2, () -> guarded(() -> {
                    phase = "leg-" + legs + ":after-warmup";
                    container(targetLevel).physicsSystem().setPaused(true);
                    diagnose(source, phase + ":source", null);
                    diagnose(movingTarget, phase + ":destination", null);
                    captureHandle(movingTarget, RigidBodyHandle::of);
                    verifyContents(source); verifyContents(movingTarget); verifyIdentity(movingTarget);
                    require(Math.abs(source.getMassTracker().getMass() - movingTarget.getMassTracker().getMass()) < .01,
                        "Velocity control mass mismatch");
                    near(captureHandle(source, RigidBodyHandle::of).getLinearVelocity(new Vector3d()),
                        capturedLinear, "Source V preserved through staging");
                    near(RigidBodyHandle.of(source).getAngularVelocity(new Vector3d()), capturedAngular,
                        "Source omega preserved through staging");
                    var sourcePose = container(source.getLevel()).physicsSystem().getPipeline().readPose(source, new Pose3d());
                    RigidBodyHandle.of(movingTarget).teleport(new Vector3d(160, 240, -64), sourcePose.orientation());
                    container(targetLevel).physicsSystem().updatePose(movingTarget);
                    restore(movingTarget, capturedLinear, capturedAngular);
                    sourceStart = new Vector3d(source.logicalPose().position());
                    targetStart = new Vector3d(movingTarget.logicalPose().position());
                    sourceRotation = new Quaterniond(source.logicalPose().orientation());
                    targetRotation = new Quaterniond(movingTarget.logicalPose().orientation());
                    sourceDt = targetDt = 0;
                    afterMotion = () -> finishVelocityLeg(source, movingTarget);
                    if (!listening) { NeoForge.EVENT_BUS.register(this); listening = true; }
                    observing = true;
                    container(source.getLevel()).physicsSystem().setPaused(false);
                    container(targetLevel).physicsSystem().setPaused(false);
                }));
            });
        }

        @SubscribeEvent
        public void velocityPost(ForgeSablePostPhysicsTickEvent event) {
            if (!observing || stopped) return;
            var sourceSystem = container(movingSource.getLevel()).physicsSystem();
            var targetSystem = container(movingTarget.getLevel()).physicsSystem();
            if (event.getPhysicsSystem() != sourceSystem && event.getPhysicsSystem() != targetSystem) return;
            try {
                double dt = event.getTimeStep();
                require(Double.isFinite(dt) && Math.abs(dt - .025) < 1e-10, "Unexpected calibrated transfer dt");
                if (event.getPhysicsSystem() == sourceSystem) {
                    sourceDt += dt;
                    if (sourceDt + 1e-10 >= INTERVAL) sourceSystem.setPaused(true);
                } else {
                    targetDt += dt;
                    if (targetDt + 1e-10 >= INTERVAL) targetSystem.setPaused(true);
                }
                if (sourceDt + 1e-10 >= INTERVAL && targetDt + 1e-10 >= INTERVAL) {
                    observing = false;
                    // No allocation/deletion/GameTest exception inside Sable's body iteration.
                    helper.runAfterDelay(1, () -> guarded(afterMotion));
                }
            } catch (Exception error) {
                observing = false;
                helper.runAfterDelay(1, () -> guarded(() -> { throw new IllegalStateException("Velocity post-step failed", error); }));
            }
        }

        private void finishVelocityLeg(ServerSubLevel source, ServerSubLevel destination) {
            phase = "leg-" + legs + ":after-activation-before-commit";
            diagnose(source, phase + ":source", null);
            diagnose(destination, phase + ":destination", null);
            require(Math.abs(sourceDt - INTERVAL) < 1e-10 && Math.abs(targetDt - INTERVAL) < 1e-10,
                "Control bodies did not receive exactly equal simulation intervals");
            require(!source.isRemoved() && container(source.getLevel()).getSubLevel(source.getUniqueId()) == source,
                "Source removed before velocity validation");
            require(container(destination.getLevel()).getSubLevel(destination.getUniqueId()) == destination,
                "Destination not registered before commit");
            var hs = captureHandle(source, RigidBodyHandle::of);
            var hd = captureHandle(destination, RigidBodyHandle::of);
            // The retained source is the no-transfer control: same mass/shape/frame and measured g/drag.
            near(hd.getLinearVelocity(new Vector3d()), hs.getLinearVelocity(new Vector3d()), "Post-step V control");
            near(hd.getAngularVelocity(new Vector3d()), hs.getAngularVelocity(new Vector3d()), "Post-step omega control");
            var sourceMotion = new Vector3d(source.logicalPose().position()).sub(sourceStart);
            var targetMotion = new Vector3d(destination.logicalPose().position()).sub(targetStart);
            near(new Vector3d(targetMotion).div(INTERVAL), new Vector3d(sourceMotion).div(INTERVAL), "Native pose motion control");
            require(targetMotion.length() > .01 && hd.getLinearVelocity(new Vector3d()).length() > .1,
                "Linear activation was a no-op");
            var sourceDelta = new Quaterniond(source.logicalPose().orientation()).mul(new Quaterniond(sourceRotation).invert());
            var targetDelta = new Quaterniond(destination.logicalPose().orientation()).mul(new Quaterniond(targetRotation).invert());
            require(targetDelta.angle() > .005 && hd.getAngularVelocity(new Vector3d()).length() > .05,
                "Angular activation was a no-op");
            require(new Quaterniond(targetDelta).mul(new Quaterniond(sourceDelta).invert()).angle() < .01,
                "Native angular pose control mismatch");
            verifyContents(source); verifyContents(destination); verifyIdentity(destination);
            System.out.println("SABLE_VELOCITY_TRANSFER leg=" + legs + " dt=" + targetDt
                + " capturedV=" + capturedLinear + " capturedOmega=" + capturedAngular
                + " postV=" + hd.getLinearVelocity(new Vector3d()) + " postOmega=" + hd.getAngularVelocity(new Vector3d())
                + " motion=" + targetMotion + " gravity=" + DimensionPhysicsData.getGravity(destination.getLevel())
                + " drag=" + DimensionPhysicsData.getUniversalDrag(destination.getLevel()));
            commitRemoval(source); // only after immediate readback AND real activation/control validation
            // Paused systems do not run solver removals; drain with the ordinary container lifecycle.
            container(source.getLevel()).processSubLevelRemovals();
            require(container(source.getLevel()).getSubLevel(source.getUniqueId()) == null, "Committed source still registered");
            phase = "leg-" + legs + ":after-source-removal";
            diagnose(destination, phase + ":survivor", null);
            verifyContents(destination); verifyIdentity(destination);
            if (++legs == 1) {
                // Match the existing passing probe: registration removal alone is not a completed
                // chunk/BE lifecycle barrier. Yield to the ordinary GameTest/level tick before slot reuse.
                waitForRemoval(source, 0, () -> guarded(() -> {
                    phase = "return:after-removal-lifecycle-barrier";
                    diagnose(destination, phase + ":source", null);
                    verifyContents(destination); verifyIdentity(destination);
                    velocityLeg(destination, overworld); // NEW capture, after real native motion
                }));
            } else { verifyContents(destination); verifyIdentity(destination); cleanup(); helper.succeed(); }
        }

        private void rejectUnavailableVelocity() {
            // Real removed native handle used as an unavailable resolver result for the actual capture guard.
            // The source itself stays registered/alive; this tests capability failure, not source deletion.
            Pose3d pose = new Pose3d(); pose.position().set(256, 240, -64);
            var fixture = (ServerSubLevel) container(end).allocateNewSubLevel(pose);
            own(fixture);
            var invalid = RigidBodyHandle.of(fixture);
            commitRemoval(fixture);
            container(end).processSubLevelRemovals();
            require(invalid != null && !invalid.isValid(), "Invalid native capability fixture did not invalidate");
            int sourceCount = container(overworld).getAllSubLevels().size();
            int targetCount = container(end).getAllSubLevels().size();
            int ownedCount = owned.size();
            var beforeV = RigidBodyHandle.of(first).getLinearVelocity(new Vector3d());
            var beforeW = RigidBodyHandle.of(first).getAngularVelocity(new Vector3d());
            boolean rejected = false;
            try { stageVelocity(first, end, ignored -> invalid); }
            catch (IllegalStateException error) {
                require("Native velocity capability unavailable".equals(error.getMessage()), "Unexpected capability rejection");
                rejected = true;
            }
            require(rejected, "Unavailable velocity capability accepted");
            require(!first.isRemoved() && container(overworld).getSubLevel(first.getUniqueId()) == first,
                "Unavailable capability removed source");
            require(sourceCount == container(overworld).getAllSubLevels().size()
                && targetCount == container(end).getAllSubLevels().size() && ownedCount == owned.size(),
                "Unavailable capability changed object counts");
            near(RigidBodyHandle.of(first).getLinearVelocity(new Vector3d()), beforeV, "Rejected source V");
            near(RigidBodyHandle.of(first).getAngularVelocity(new Vector3d()), beforeW, "Rejected source omega");
            verifyContents(first); verifyIdentity(first);
            cleanup();
            int cleanSourceCount = container(overworld).getAllSubLevels().size();
            int cleanTargetCount = container(end).getAllSubLevels().size();
            // Exercise the actual scheduled guard against mutation of the real native scene after stop.
            helper.runAfterDelay(1, () -> guarded(() -> {
                var unexpectedPose = new Pose3d(); unexpectedPose.position().set(256, 240, -64);
                var unexpected = (ServerSubLevel) container(end).allocateNewSubLevel(unexpectedPose);
                own(unexpected);
                var chunk = unexpected.getPlot().getCenterChunk();
                unexpected.getPlot().newEmptyChunk(chunk);
                end.setBlock(new BlockPos(chunk.getMinBlockX() + 3, 64, chunk.getMinBlockZ() + 3),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                NeoForge.EVENT_BUS.register(this); listening = true;
            }));
            helper.runAfterDelay(2, () -> {
                try {
                    require(container(overworld).getAllSubLevels().size() == cleanSourceCount
                        && container(end).getAllSubLevels().size() == cleanTargetCount,
                        "Delayed callback allocated a native body after cleanup");
                    require(container(overworld).getSubLevel(first.getUniqueId()) == null,
                        "Source fixture remained registered after cleanup");
                    require(!listening && owned.isEmpty(), "Delayed callback leaked native ownership/listener");
                    helper.succeed();
                } catch (Exception failure) {
                    try { cleanup(); } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                    helper.fail("Delayed stopped callback regression failed: " + failure);
                }
            });
        }

        private static Vector3d finite(Vector3d v) {
            require(Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z), "Non-finite native velocity");
            return v;
        }

        private static void near(Vector3d actual, Vector3d expected, String label) {
            finite(actual); finite(expected);
            require(actual.distance(expected) <= ABS + REL * expected.length(), label + " expected=" + expected + " actual=" + actual);
        }

        private void transferFirstLeg() {
            guarded(() -> {
                ServerSubLevel staging = stage(first, end);
                waitForPhysics(staging, 0, () -> guarded(() -> {
                    verifyContents(first);
                    verifyContents(staging);
                    require(!first.isRemoved(), "Source removed before destination verification");
                    commitRemoval(first);
                    waitForRemoval(first, 0, () -> transferReturnLeg(staging));
                }));
            });
        }

        private void transferReturnLeg(ServerSubLevel inEnd) {
            guarded(() -> {
                require(container(overworld).getSubLevel(first.getUniqueId()) == null, "Original source UUID still registered");
                ServerSubLevel returned = stage(inEnd, overworld);
                waitForPhysics(returned, 0, () -> guarded(() -> {
                    verifyContents(inEnd);
                    verifyContents(returned);
                    require(!inEnd.isRemoved(), "End source removed before return verification");
                    commitRemoval(inEnd);
                    waitForRemoval(inEnd, 0, () -> guarded(() -> {
                        verifyContents(returned);
                        verifyIdentity(returned);
                        validateSupportedSource(returned);
                        require(container(end).getSubLevel(inEnd.getUniqueId()) == null, "End source UUID still registered");
                        require(container(overworld).getSubLevel(returned.getUniqueId()) == returned, "Return object not registered");
                        helper.assertTrue(!returned.getUniqueId().equals(first.getUniqueId()), "Staging UUID must be distinct");
                        cleanup();
                        helper.succeed();
                    }));
                }));
            });
        }

        private ServerSubLevel stage(ServerSubLevel source, ServerLevel targetLevel) {
            boolean velocity = mode == ProbeMode.VELOCITY || mode == ProbeMode.UNAVAILABLE_VELOCITY;
            if (velocity) { phase = "leg-" + legs + ":stage-source-validation"; diagnose(source, phase, null); }
            validateSupportedSource(source);
            verifyContents(source);
            verifyIdentity(source);
            var targetContainer = container(targetLevel);
            require(targetContainer.getSubLevel(sourceSlotX, sourceSlotZ) == null, "Matching target slot occupied");
            CompoundTag snapshot = source.getPlot().save().copy();
            CompoundTag fullChest = null;
            if (velocity) {
                phase = "leg-" + legs + ":after-native-save";
                fullChest = chest(source).saveWithFullMetadata(source.getLevel().registryAccess());
                diagnose(source, phase, snapshot);
                requireNativeChestSnapshot(snapshot, fullChest);
            }
            require(snapshot.getInt("data_version") == 1, "Unsupported native plot data version");
            require(snapshot.getInt("plot_x") == sourceSlotX && snapshot.getInt("plot_z") == sourceSlotZ,
                "Native source slot unexpectedly changed");
            // The public serializer stores chunk section ARRAY INDICES, not world Y.
            // Only adapt its known envelope; block palette and chest NBT remain native data.
            remapSectionIndices(snapshot, source.getLevel(), targetLevel);
            Pose3d pose = new Pose3d(source.logicalPose());
            pose.position().set(16.0, 160.0, 16.0);
            ServerSubLevel staging = (ServerSubLevel) targetContainer.allocateSubLevel(UUID.randomUUID(),
                sourceSlotX, sourceSlotZ, pose);
            own(staging);
            if (velocity) {
                phase = "leg-" + legs + ":destination-before-load";
                diagnose(staging, phase, snapshot);
            }
            staging.getPlot().load(snapshot);
            if (velocity) {
                phase = "leg-" + legs + ":destination-after-load-before-level-lookup";
                diagnose(staging, phase, snapshot);
            }
            staging.setName(source.getName());
            staging.setUserDataTag(source.getUserDataTag().copy());
            require(!source.isRemoved() && container(source.getLevel()).getSubLevel(source.getUniqueId()) == source,
                "Source unavailable during stage");
            verifyContents(staging);
            if (velocity) {
                phase = "leg-" + legs + ":destination-after-level-lookup";
                diagnose(staging, phase, snapshot);
                require(fullChest.equals(chest(staging).saveWithFullMetadata(targetLevel.registryAccess())),
                    "Full chest NBT changed during native load at " + phase);
            }
            verifyIdentity(staging);
            require(source.getName().equals(staging.getName()), "Native name not copied");
            return staging;
        }

        private void validateSupportedSource(ServerSubLevel source) {
            require(source.getPlot().getContraptions().isEmpty(), "Contraptions unsupported by native probe");
            require(source.getPlot().getLoadedChunks().size() == 1, "Probe expects one fully loaded chunk");
            int solidCount = 0;
            int chestCount = 0;
            for (var holder : source.getPlot().getLoadedChunks()) {
                var chunk = holder.getChunk();
                require(chunk.getBlockEntities().values().stream().allMatch(ChestBlockEntity.class::isInstance),
                    "Unsupported block entity present");
                chestCount += chunk.getBlockEntities().size();
                for (var section : chunk.getSections()) {
                    if (section.hasOnlyAir()) continue;
                    for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!state.isAir()) {
                            require(ALLOWED_BLOCKS.contains(state.getBlock()), "Unsupported block present");
                            solidCount++;
                        }
                    }
                }
            }
            require(solidCount == expected.size() && chestCount == 1, "Unexpected plot content");
            AABB storage = new AABB(expected.keySet().iterator().next()).inflate(8.0);
            var bounds = source.boundingBox();
            AABB world = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ()).inflate(1.0);
            for (var entity : source.getLevel().getAllEntities()) {
                require(!storage.intersects(entity.getBoundingBox()) && !world.intersects(entity.getBoundingBox()),
                    "Entities/passengers unsupported by native probe");
            }
        }

        private void verifyContents(ServerSubLevel source) {
            for (var entry : expected.entrySet()) {
                helper.assertTrue(source.getLevel().getBlockState(entry.getKey()).equals(entry.getValue()),
                    "Block state mismatch at " + entry.getKey() + " in " + source.getLevel().dimension().location());
            }
            var chest = chest(source);
            String identity = " phase=" + phase + " dimension=" + source.getLevel().dimension().location()
                + " uuid=" + source.getUniqueId() + " chest=" + chest.getBlockPos()
                + " slot0=" + chest.getItem(0) + " slot8=" + chest.getItem(8);
            helper.assertTrue(chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 7, "Diamond inventory mismatch" + identity);
            helper.assertTrue(chest.getItem(8).is(Items.IRON_INGOT) && chest.getItem(8).getCount() == 19, "Iron inventory mismatch" + identity);
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (slot != 0 && slot != 8) helper.assertTrue(chest.getItem(slot).isEmpty(), "Unexpected inventory slot " + slot);
            }
        }

        private void requireNativeChestSnapshot(CompoundTag snapshot, CompoundTag liveChest) {
            int count = 0;
            CompoundTag serialized = null;
            var chunks = snapshot.getCompound("chunks");
            for (String key : chunks.getAllKeys()) {
                var entities = chunks.getCompound(key).getList("block_entities", 10);
                count += entities.size();
                for (int i = 0; i < entities.size(); i++) {
                    var tag = entities.getCompound(i);
                    if (tag.getString("id").equals("minecraft:chest")) serialized = tag.copy();
                }
            }
            require(count == 1 && serialized != null, "Native save lost/duplicated chest BE at " + phase);
            serialized.remove("keepPacked"); // chunk envelope flag, not a chest field
            require(serialized.equals(liveChest), "Native save differs from full live chest NBT at " + phase
                + " native=" + serialized + " live=" + liveChest);
        }

        private void diagnose(ServerSubLevel body, String label, CompoundTag snapshot) {
            BlockPos chestPos = expected.entrySet().stream().filter(e -> e.getValue().is(Blocks.CHEST))
                .map(Map.Entry::getKey).findFirst().orElse(null);
            int loadedCount = 0, pendingAndLoadedCount = 0;
            StringBuilder chunkState = new StringBuilder();
            for (var holder : body.getPlot().getLoadedChunks()) {
                var chunk = holder.getChunk();
                loadedCount += chunk.getBlockEntities().size();
                pendingAndLoadedCount += chunk.getBlockEntitiesPos().size();
                var direct = chestPos == null ? null : chunk.getBlockEntities().get(chestPos);
                chunkState.append(" chunk=").append(chunk.getPos()).append(" object=")
                    .append(System.identityHashCode(chunk)).append(" loadedBE=").append(chunk.getBlockEntities().size())
                    .append(" allBEPos=").append(chunk.getBlockEntitiesPos()).append(" directBE=");
                if (direct == null) chunkState.append("null");
                else chunkState.append(direct.getClass().getSimpleName()).append('@').append(System.identityHashCode(direct))
                    .append(" removed=").append(direct.isRemoved()).append(" nbt=")
                    .append(direct.saveWithFullMetadata(body.getLevel().registryAccess()));
            }
            StringBuilder payload = new StringBuilder();
            if (snapshot != null) {
                var chunks = snapshot.getCompound("chunks");
                int count = 0;
                for (String key : chunks.getAllKeys()) {
                    var tags = chunks.getCompound(key).getList("block_entities", 10);
                    count += tags.size(); payload.append(" key=").append(key).append(" be=").append(tags);
                }
                payload.insert(0, " serializedBECount=" + count + " chunkCount=" + chunks.getAllKeys().size());
            }
            System.out.println("SABLE_VELOCITY_BE phase=" + label + " dimension=" + body.getLevel().dimension().location()
                + " uuid=" + body.getUniqueId() + " removed=" + body.isRemoved()
                + " registered=" + (container(body.getLevel()).getSubLevel(body.getUniqueId()) == body)
                + " sourceCount=" + container(overworld).getAllSubLevels().size()
                + " targetCount=" + container(end).getAllSubLevels().size()
                + " ownedCount=" + owned.size() + " loadedBECount=" + loadedCount
                + " allBECount=" + pendingAndLoadedCount + chunkState + payload);
        }

        private void verifyIdentity(ServerSubLevel source) {
            helper.assertTrue(source.getUserDataTag().hasUUID("universe_test_logical_id"),
                "Logical test identity missing");
            helper.assertTrue(source.getUserDataTag().getUUID("universe_test_logical_id").equals(logicalId),
                "Logical test identity mismatch");
        }

        private ChestBlockEntity chest(ServerSubLevel source) {
            BlockPos position = expected.entrySet().stream().filter(e -> e.getValue().is(Blocks.CHEST))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
            var blockEntity = source.getLevel().getBlockEntity(position);
            if (mode == ProbeMode.VELOCITY || mode == ProbeMode.UNAVAILABLE_VELOCITY) {
                Object plotEntity = null;
                for (var holder : source.getPlot().getLoadedChunks()) {
                    var direct = holder.getChunk().getBlockEntities().get(position);
                    if (direct != null) plotEntity = direct;
                }
                System.out.println("SABLE_VELOCITY_BE_LOOKUP phase=" + phase
                    + " dimension=" + source.getLevel().dimension().location() + " uuid=" + source.getUniqueId()
                    + " pos=" + position + " levelChunk=" + System.identityHashCode(source.getLevel().getChunkAt(position))
                    + " levelBE=" + (blockEntity == null ? "null" : System.identityHashCode(blockEntity))
                    + " plotBE=" + (plotEntity == null ? "null" : System.identityHashCode(plotEntity))
                    + " sameBE=" + (blockEntity == plotEntity)
                    + " removed=" + (blockEntity != null && blockEntity.isRemoved())
                    + " fullNBT=" + (blockEntity == null ? "null" : blockEntity.saveWithFullMetadata(source.getLevel().registryAccess())));
            }
            require(blockEntity instanceof ChestBlockEntity, "Native chest block entity missing");
            return (ChestBlockEntity) blockEntity;
        }

        private void waitForPhysics(ServerSubLevel source, int waited, Runnable continuation) {
            helper.runAfterDelay(1, () -> guarded(() -> {
                require(!source.isRemoved(), "Native object removed while awaiting physics");
                if (RigidBodyHandle.of(source).isValid()) continuation.run();
                else {
                    require(waited < MAX_PHYSICS_WAIT_TICKS, "Native physics handle did not become valid");
                    waitForPhysics(source, waited + 1, continuation);
                }
            }));
        }

        private void waitForRemoval(ServerSubLevel source, int waited, Runnable continuation) {
            helper.runAfterDelay(1, () -> guarded(() -> {
                if (container(source.getLevel()).getSubLevel(source.getUniqueId()) == null) continuation.run();
                else {
                    require(waited < 20, "Native source removal did not complete");
                    waitForRemoval(source, waited + 1, continuation);
                }
            }));
        }

        private void own(ServerSubLevel source) {
            owned.add(source);
            container(source.getLevel()).addForceLoadTicket(source, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        }

        private void commitRemoval(ServerSubLevel source) {
            container(source.getLevel()).removeForceLoadTicket(source, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            releasedTickets.add(source);
            container(source.getLevel()).removeSubLevel(source, SubLevelRemovalReason.REMOVED);
        }

        private void cleanup() {
            stopped = true; observing = false;
            RuntimeException failure = null;
            var levels = new LinkedHashSet<ServerLevel>();
            try {
                // A ticket failure must not prevent body removal or cleanup of later owned objects.
                for (var source : new ArrayList<>(owned)) {
                    levels.add(source.getLevel());
                    if (!releasedTickets.contains(source)) {
                        try {
                            container(source.getLevel()).removeForceLoadTicket(source,
                                SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                            releasedTickets.add(source);
                        } catch (RuntimeException error) { failure = aggregate(failure, error); }
                    }
                    try {
                        var target = container(source.getLevel());
                        if (target.getSubLevel(source.getUniqueId()) == source)
                            target.removeSubLevel(source, SubLevelRemovalReason.REMOVED);
                    } catch (RuntimeException error) { failure = aggregate(failure, error); }
                }
                for (var level : levels) {
                    try { container(level).processSubLevelRemovals(); }
                    catch (RuntimeException error) { failure = aggregate(failure, error); }
                }
                for (var source : new ArrayList<>(owned)) {
                    try {
                        if (releasedTickets.contains(source)
                            && container(source.getLevel()).getSubLevel(source.getUniqueId()) != source) {
                            owned.remove(source);
                            releasedTickets.remove(source);
                        }
                    } catch (RuntimeException error) { failure = aggregate(failure, error); }
                }
            } finally {
                // Independent attempts: restore every dimension even if unregistration/restoration fails.
                if (listening) {
                    try { NeoForge.EVENT_BUS.unregister(this); listening = false; }
                    catch (RuntimeException error) { failure = aggregate(failure, error); }
                }
                for (var entry : originalPause.entrySet()) {
                    try { entry.getKey().setPaused(entry.getValue()); }
                    catch (RuntimeException error) { failure = aggregate(failure, error); }
                }
            }
            if (failure != null) throw failure;
        }

        private static RuntimeException aggregate(RuntimeException first, RuntimeException next) {
            if (first == null) return next;
            if (first != next) first.addSuppressed(next);
            return first;
        }

        private void guarded(Runnable action) {
            if (stopped) return;
            try { action.run(); }
            catch (Exception failure) {
                try { cleanup(); } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                helper.fail("Sable native probe failed: " + failure);
            }
        }
    }

    private static void remapSectionIndices(CompoundTag snapshot, ServerLevel source, ServerLevel target) {
        CompoundTag chunks = snapshot.getCompound("chunks");
        for (String chunkKey : chunks.getAllKeys()) {
            CompoundTag chunk = chunks.getCompound(chunkKey);
            CompoundTag oldSections = chunk.getCompound("sections");
            CompoundTag newSections = new CompoundTag();
            for (String index : oldSections.getAllKeys()) {
                int worldSectionY = source.getSectionYFromSectionIndex(Integer.parseInt(index));
                int destinationIndex = target.getSectionIndexFromSectionY(worldSectionY);
                require(destinationIndex >= 0 && destinationIndex < target.getSectionsCount(), "Section outside target height");
                newSections.put(Integer.toString(destinationIndex), oldSections.getCompound(index).copy());
            }
            chunk.put("sections", newSections);
            // Let native load prime heightmaps using the destination level's minY.
            chunk.remove("heightmaps");
        }
    }

    private static ServerSubLevelContainer container(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        require(container != null && container.physicsSystem() != null, "Native Sable container/physics unavailable");
        return container;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

