package dev.heiko.universe.clienttest;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.util.HashSet;
import java.nio.file.Files;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;

/** Dedicated-only, one native body; do not load into a valuable world. */
@EventBusSubscriber(modid = ClientProbeMod.ID)
public final class ProbeServer {
    private static ServerSubLevelContainer container;
    private static ServerSubLevel body;
    private static ServerLevel level;
    private static long started;
    private static long startedAtMillis;
    private static UUID fixtureUuid;
    private static boolean disposable, sessionClaimed, haltRequested;
    private static int supervisorTicks;
    private static int stable;
    private static boolean ready, failed, ticketOwned;
    private static int cleanupAttempts;
    private static final Set<UUID> positioned = new HashSet<>();

    private ProbeServer() {}

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (!ClientProbeMod.ENABLED || ClientProbeMod.DYNAMIC || !event.getServer().isDedicatedServer()) return;
        started = System.nanoTime();
        startedAtMillis = System.currentTimeMillis();
        try {
            require("127.0.0.1".equals(event.getServer().getLocalIp()) && !event.getServer().usesAuthentication(),
                    "Probe requires explicit loopback-only offline disposable server");
            disposable = true;
            ClientProbeMod.claim("server");
            sessionClaimed = true;
            level = event.getServer().overworld();
            container = SubLevelContainer.getContainer(level);
            require(container != null && container.physicsSystem() != null, "Missing native physics container");
            require(container.physicsSystem().getPipeline().getClass().getName().equals(
                    "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline"), "Native Rapier backend required");
            require(container.getAllSubLevels().isEmpty(), "Disposable fixture world must have no existing sublevels");
            // Bound ordinary chunks and validate the entire fixture volume before placing anything.
            for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) level.getChunk(x, z);
            for (BlockPos pos : BlockPos.betweenClosed(-5, 80, -5, 5, 88, 5))
                require(level.getBlockState(pos).isAir(), "Fixture volume is obstructed; use a fresh void/flat world");
            for (BlockPos pos : BlockPos.betweenClosed(-5, 80, -5, 5, 80, 5))
                level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
            Pose3d pose = new Pose3d();
            pose.position().set(0, 84, 0);
            body = (ServerSubLevel) container.allocateNewSubLevel(pose);
            require(body != null, "Sublevel allocation failed");
            fixtureUuid = body.getUniqueId();
            body.setName(ClientProbeMod.NAME);
            ticketOwned = true; // Retain ownership even if add throws after partial registration.
            container.addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            var chunk = body.getPlot().getCenterChunk();
            body.getPlot().newEmptyChunk(chunk);
            BlockPos origin = new BlockPos(chunk.getMinBlockX() + 3, 64, chunk.getMinBlockZ() + 3);
            for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++)
                level.setBlock(origin.offset(x, 0, z), Blocks.WHITE_CONCRETE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(origin, Blocks.RED_CONCRETE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(origin.offset(2, 0, 0), Blocks.BLUE_CONCRETE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(origin.offset(0, 0, 2), Blocks.LIME_CONCRETE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(origin.offset(2, 0, 2), Blocks.YELLOW_CONCRETE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(origin.offset(1, 1, 1), Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_ALL);
            body.logicalPose().rotationPoint().set(body.getMassTracker().getCenterOfMass());
            ClientProbeMod.report("server", "WAIT_NATIVE_SETTLE", "RUNNING", Map.of("uuid", body.getUniqueId().toString()));
        } catch (Exception error) { fail(error); }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (started == 0) return;
        supervise(event.getServer());
        if (haltRequested || level == null) return;
        if (failed) {
            if (cleanupAttempts < 3) cleanup();
            return;
        }
        try {
            require(body != null && !body.isRemoved(), "Fixture disappeared");
            if (!ready) {
                require(System.nanoTime() - started < ClientProbeMod.TIMEOUT_NANOS, "Native settling timeout");
                var handle = RigidBodyHandle.of(body);
                boolean settled = handle != null && handle.isValid() && body.getMassTracker().getMass() > 0
                        && handle.getLinearVelocity(new Vector3d()).length() < .02
                        && handle.getAngularVelocity(new Vector3d()).length() < .02;
                stable = settled ? stable + 1 : 0;
                if (stable < 20) return;
                ready = true;
                ClientProbeMod.report("server", "FIXTURE_READY", "READY", Map.of("uuid", body.getUniqueId().toString(),
                        "name", ClientProbeMod.NAME, "stableTicks", stable, "position", body.logicalPose().position().toString()));
            }
            for (var player : event.getServer().getPlayerList().getPlayers()) {
                if (positioned.add(player.getUUID())) {
                    player.setGameMode(GameType.SPECTATOR);
                    player.teleportTo(level, 0, 85, -12, 0, 16);
                }
            }
        } catch (Exception error) { fail(error); }
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        // Never forget unresolved ownership: failed tick retries and this final shutdown retry
        // must still have the original body/container and independent ticket state.
        if (!cleanup()) return;
        container = null; level = null; started = 0;
        stable = 0; ready = false; failed = false; cleanupAttempts = 0; positioned.clear();
    }

    private static void supervise(net.minecraft.server.MinecraftServer server) {
        if (!disposable || haltRequested) return;
        supervisorTicks++;
        if (System.nanoTime() - started > 180_000_000_000L) {
            requestHalt(server, "SERVER_WATCHDOG", "No accepted client completion within 180 seconds");
            return;
        }
        if (!sessionClaimed || fixtureUuid == null || supervisorTicks % 20 != 0) return;
        try {
            var path = ClientProbeMod.claim("server").getParent().resolve("client/report.json");
            if (!Files.isRegularFile(path) || Files.size(path) > 65536) return;
            var report = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            String status = report.get("status").getAsString();
            if (!(status.equals("CAPTURED_PENDING_VISUAL_REVIEW") || status.equals("FAILED"))) return;
            long now = System.currentTimeMillis();
            long written = report.get("writtenAtMillis").getAsLong();
            long clientStarted = report.get("runtimeStartedAtMillis").getAsLong();
            if (!ClientProbeMod.RUN_ID.equals(report.get("runId").getAsString())
                    || !ClientProbeMod.SESSION_NONCE.equals(report.get("sessionNonce").getAsString())
                    || !fixtureUuid.toString().equals(report.get("uuid").getAsString())
                    || written < startedAtMillis || written > now || now - written > 120_000
                    || clientStarted < startedAtMillis || clientStarted > written
                    || ClientProbeMod.RUNTIME_NONCE.equals(report.get("runtimeNonce").getAsString())) return;
            UUID.fromString(report.get("runtimeNonce").getAsString());
            if (status.equals("CAPTURED_PENDING_VISUAL_REVIEW")) {
                var captures = report.getAsJsonArray("captures");
                if (captures.size() != 3) return;
                var directory = path.getParent().resolve("frames");
                for (int i = 0; i < 3; i++) {
                    String name = "static-" + i + ".png";
                    if (!name.equals(captures.get(i).getAsString())) return;
                    var png = directory.resolve(name);
                    if (!Files.isRegularFile(png) || Files.size(png) == 0
                            || Files.getLastModifiedTime(png).toMillis() < clientStarted) return;
                    var metadata = JsonParser.parseString(Files.readString(directory.resolve(name + ".json"))).getAsJsonObject();
                    if (!ClientProbeMod.SESSION_NONCE.equals(metadata.get("sessionNonce").getAsString())
                            || !fixtureUuid.toString().equals(metadata.get("uuid").getAsString())
                            || !report.get("runtimeNonce").getAsString().equals(metadata.get("runtimeNonce").getAsString())) return;
                }
            }
            requestHalt(server, "CLIENT_TERMINAL_REPORT", status);
        } catch (Exception error) {
            // Invalid evidence cannot request shutdown; the bounded watchdog remains active.
            org.slf4j.LoggerFactory.getLogger(ProbeServer.class).warn("Ignoring unaccepted client report", error);
        }
    }

    private static void requestHalt(net.minecraft.server.MinecraftServer server, String reason, String detail) {
        haltRequested = true;
        try {
            ClientProbeMod.atomicJson(ClientProbeMod.claim("server").resolve("shutdown-request.json"),
                    Map.of("runId", ClientProbeMod.RUN_ID, "sessionNonce", ClientProbeMod.SESSION_NONCE,
                            "runtimeNonce", ClientProbeMod.RUNTIME_NONCE, "requestedAtMillis", System.currentTimeMillis(),
                            "reason", reason, "detail", detail, "uuid", fixtureUuid == null ? "" : fixtureUuid.toString()), false);
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(ProbeServer.class).error("Cannot write shutdown request", error);
        } finally {
            server.halt(false); // Ordinary run-loop shutdown, never process kill or synthetic events.
        }
    }

    private static boolean cleanup() {
        cleanupAttempts++;
        if (body == null) return true;
        if (container == null) return false;
        Exception failure = null;
        if (ticketOwned) {
            try {
                container.removeForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                ticketOwned = false;
            } catch (Exception error) { failure = error; }
        }
        boolean removed = false;
        try {
            if (container.getSubLevel(body.getUniqueId()) == body)
                container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            container.processSubLevelRemovals();
            removed = container.getSubLevel(body.getUniqueId()) == null;
        } catch (Exception error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null)
            org.slf4j.LoggerFactory.getLogger(ProbeServer.class).error("Fixture cleanup failed; ownership retained", failure);
        if (!ticketOwned && removed) {
            try {
                ClientProbeMod.atomicJson(ClientProbeMod.claim("server").resolve("native-cleanup.json"),
                        Map.of("runId", ClientProbeMod.RUN_ID, "sessionNonce", ClientProbeMod.SESSION_NONCE,
                                "runtimeNonce", ClientProbeMod.RUNTIME_NONCE, "writtenAtMillis", System.currentTimeMillis(),
                                "uuid", body.getUniqueId().toString(), "ticketReleased", true,
                                "nativeUnregistered", true, "remainingSublevels", container.getAllSubLevels().size()), true);
            } catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger(ProbeServer.class).error("Cannot write native cleanup evidence", error);
            }
            body = null;
            return true;
        }
        return false;
    }

    private static void fail(Exception error) {
        failed = true;
        try { ClientProbeMod.report("server", "FIXTURE_FAILED", "FAILED", Map.of("error", error.toString())); }
        catch (Exception writeError) { error.addSuppressed(writeError); }
        org.slf4j.LoggerFactory.getLogger(ProbeServer.class).error("Client fixture failed", error);
        cleanup();
    }
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}



