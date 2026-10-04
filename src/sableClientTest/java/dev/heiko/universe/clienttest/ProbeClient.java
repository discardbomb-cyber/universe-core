package dev.heiko.universe.clienttest;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Observes actual client plot data and captures the ordinary Sable renderer, never draws a proxy. */
@EventBusSubscriber(modid = ClientProbeMod.ID, value = Dist.CLIENT)
public final class ProbeClient {
    private static long started;
    private static int ticks, worldFrames, lastCapturedFrame;
    private static boolean finished, worldRendered;
    private static ClientSubLevel tracked;
    private static String phase = "WAIT_CONNECTION";
    private static final ArrayList<String> captures = new ArrayList<>();

    private ProbeClient() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ClientProbeMod.ENABLED || ClientProbeMod.DYNAMIC || finished) return;
        if (started == 0) started = System.nanoTime();
        try {
            ClientProbeMod.claim("client");
            if (System.nanoTime() - started > ClientProbeMod.TIMEOUT_NANOS)
                throw new IllegalStateException("Timeout at " + phase);
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            if (mc.getSingleplayerServer() != null)
                throw new IllegalStateException("Requires a genuine dedicated-server connection");
            requireLoopback(mc);
            ticks++;
            phase = "WAIT_CAMERA_AND_TRACKING";
            if (mc.player.distanceToSqr(0, 85, -12) > 1 || !mc.player.isSpectator()) return;
            var container = SubLevelContainer.getContainer(mc.level);
            if (container == null) return;
            tracked = null;
            for (var candidate : container.getAllSubLevels()) {
                if (ClientProbeMod.NAME.equals(candidate.getName())) {
                    if (tracked != null) throw new IllegalStateException("Duplicate fixture name");
                    tracked = candidate;
                }
            }
            if (!plotReady(tracked)) { phase = "WAIT_FINALIZED_PLOT"; return; }
            phase = "WAIT_RENDER_FRAMES";
        } catch (Exception error) { finish("FAILED", error.toString()); }
    }

    private static boolean plotReady(ClientSubLevel body) {
        if (body == null || body.isRemoved() || !body.isFinalized() || body.getRenderData() == null) return false;
        var chunk = body.getPlot().getCenterChunk();
        BlockPos origin = new BlockPos(chunk.getMinBlockX() + 3, 64, chunk.getMinBlockZ() + 3);
        var level = body.getLevel();
        return level.getBlockState(origin).is(Blocks.RED_CONCRETE)
                && level.getBlockState(origin.offset(2, 0, 0)).is(Blocks.BLUE_CONCRETE)
                && level.getBlockState(origin.offset(0, 0, 2)).is(Blocks.LIME_CONCRETE)
                && level.getBlockState(origin.offset(2, 0, 2)).is(Blocks.YELLOW_CONCRETE)
                && level.getBlockState(origin.offset(1, 1, 1)).is(Blocks.SEA_LANTERN);
    }

    @SubscribeEvent
    public static void worldFrame(RenderLevelStageEvent event) {
        if (!ClientProbeMod.ENABLED || ClientProbeMod.DYNAMIC || finished) return;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL
                && phase.equals("WAIT_RENDER_FRAMES") && plotReady(tracked)) {
            worldRendered = true;
            worldFrames++;
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (!ClientProbeMod.ENABLED || ClientProbeMod.DYNAMIC || finished) return;
        boolean rendered = worldRendered;
        worldRendered = false;
        Minecraft mc = Minecraft.getInstance();
        if (!rendered || mc.screen != null || mc.getOverlay() != null || mc.isPaused()
                || worldFrames < 40 || worldFrames - lastCapturedFrame < 20) return;
        try {
            requireLoopback(mc);
            Path directory = ClientProbeMod.claim("client").resolve("frames");
            Files.createDirectories(directory);
            String name = "static-" + captures.size() + ".png";
            Path target = directory.resolve(name);
            if (Files.exists(target)) throw new IllegalStateException("Refusing old PNG");
            Path temporary = Files.createTempFile(directory, ".frame-", ".tmp");
            try {
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(temporary); }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
            captures.add(name);
            lastCapturedFrame = worldFrames;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("runId", ClientProbeMod.RUN_ID);
            metadata.put("sessionNonce", ClientProbeMod.SESSION_NONCE);
            metadata.put("runtimeNonce", ClientProbeMod.RUNTIME_NONCE);
            metadata.put("capturedAtMillis", System.currentTimeMillis());
            metadata.put("remoteEndpoint", mc.getConnection().getConnection().getRemoteAddress().toString());
            metadata.put("frame", worldFrames);
            metadata.put("uuid", tracked.getUniqueId().toString());
            metadata.put("pose", tracked.renderPose().toString());
            metadata.put("camera", mc.gameRenderer.getMainCamera().getPosition().toString());
            ClientProbeMod.atomicJson(directory.resolve(name + ".json"), metadata, false);
            if (captures.size() == 3) finish("CAPTURED_PENDING_VISUAL_REVIEW", "");
        } catch (Exception error) { finish("FAILED", error.toString()); }
    }

    private static void requireLoopback(Minecraft mc) {
        if (mc.getConnection() == null || !(mc.getConnection().getConnection().getRemoteAddress()
                instanceof InetSocketAddress address) || address.isUnresolved()
                || !address.getAddress().isLoopbackAddress() || address.getPort() != 25575)
            throw new IllegalStateException("Actual connection must be loopback port 25575");
    }

    private static void finish(String status, String error) {
        if (finished) return;
        finished = true;
        try {
            ClientProbeMod.report("client", phase, status, Map.of("clientTicks", ticks,
                    "worldFrames", worldFrames, "captures", captures, "error", error,
                    "uuid", tracked == null ? "" : tracked.getUniqueId().toString(),
                    "trackingEvidence", tracked != null && plotReady(tracked)));
        } catch (Exception writeError) {
            org.slf4j.LoggerFactory.getLogger(ProbeClient.class).error("Cannot write probe report", writeError);
        } finally { Minecraft.getInstance().stop(); }
    }
}

