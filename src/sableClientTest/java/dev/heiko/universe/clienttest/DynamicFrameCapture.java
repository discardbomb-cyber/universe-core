package dev.heiko.universe.clienttest;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Root calls remember() at world-render event, capture() at the SAME RenderFrameEvent.Post. */
public final class DynamicFrameCapture {
    private Map<String, Object> observation;
    private String uuid;

    public void remember(RenderLevelStageEvent event, ClientSubLevel body, long frameId) {
        // Pinned renderer uses renderPose() with timer partial TRUE; do not mutate its cache with FALSE.
        var pose = body.renderPose();
        var p = pose.position(); var q = pose.orientation(); var r = pose.rotationPoint(); var scale = pose.scale();
        double norm=q.x()*q.x()+q.y()*q.y()+q.z()*q.z()+q.w()*q.w();
        if(!Double.isFinite(norm)||Math.abs(norm-1)>1e-5)throw new IllegalStateException("Nonunit render quaternion");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("frameId", frameId); data.put("uuid", body.getUniqueId().toString());
        data.put("position", new double[]{p.x(), p.y(), p.z()});
        data.put("orientation", new double[]{q.x(), q.y(), q.z(), q.w()});
        data.put("rotationPoint", new double[]{r.x(), r.y(), r.z()});
        data.put("scale", new double[]{scale.x(), scale.y(), scale.z()});
        for(double value:new double[]{p.x(),p.y(),p.z(),r.x(),r.y(),r.z(),scale.x(),scale.y(),scale.z()})
            if(!Double.isFinite(value))throw new IllegalStateException("Nonfinite render pose component");
        float[] modelView=event.getModelViewMatrix().get(new float[16]);
        float[] projection=event.getProjectionMatrix().get(new float[16]);
        for(float value:modelView)if(!Float.isFinite(value))throw new IllegalStateException("Nonfinite modelView");
        for(float value:projection)if(!Float.isFinite(value))throw new IllegalStateException("Nonfinite projection");
        data.put("modelView",modelView); data.put("projection",projection);
        double fov=Math.toDegrees(2*Math.atan(1.0/projection[5]));
        if(!Double.isFinite(fov)||fov<=0||fov>=179)throw new IllegalStateException("Invalid actual projection FOV");
        data.put("projectionVerticalFovDegrees",fov);
        var camera = event.getCamera().getPosition();
        data.put("camera", new double[]{camera.x, camera.y, camera.z});
        var cameraQ=event.getCamera().rotation();
        data.put("cameraQuaternion",new double[]{cameraQ.x(),cameraQ.y(),cameraQ.z(),cameraQ.w()});
        for(double value:new double[]{camera.x,camera.y,camera.z,cameraQ.x(),cameraQ.y(),cameraQ.z(),cameraQ.w()})
            if(!Double.isFinite(value))throw new IllegalStateException("Nonfinite actual camera");
        var origin=body.getPlot().getCenterChunk();
        data.put("markerOrigin",new int[]{origin.getMinBlockX()+3,64,origin.getMinBlockZ()+3});
        data.put("posePartialMode","ACTUAL_RENDERER_TIMER_TRUE");
        data.put("cameraPlayerPosition",new double[]{Minecraft.getInstance().player.getX(),Minecraft.getInstance().player.getY(),Minecraft.getInstance().player.getZ()});
        data.put("cameraPlayerYaw",Minecraft.getInstance().player.getYRot());
        data.put("cameraPlayerPitch",Minecraft.getInstance().player.getXRot());
        data.put("landmark",Map.of("position",new int[]{-4,83,-6},"block","minecraft:cyan_concrete",
                "actualBlockVerified",Minecraft.getInstance().level.getBlockState(new net.minecraft.core.BlockPos(-4,83,-6))
                        .is(net.minecraft.world.level.block.Blocks.CYAN_CONCRETE)));
        data.put("clientGameTime", body.getLevel().getGameTime());
        data.put("actualCommonAttemptUdp",dev.ryanhcode.sable.SableConfig.ATTEMPT_UDP_NETWORKING.getAsBoolean());
        data.put("actualClientAttemptUdp",dev.ryanhcode.sable.SableClientConfig.ATTEMPT_UDP_NETWORKING.getAsBoolean());
        var interpolation=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(body.getLevel()).getInterpolation();
        data.put("actualGlobalInterpolationStopped",interpolation.isStopped());
        double pointer=interpolation.getTickPointer();if(!Double.isFinite(pointer))throw new IllegalStateException("Nonfinite interpolation pointer");
        data.put("actualInterpolationTickPointer",pointer);
        var buffer=body.getInterpolator().buffer;int size;
        java.util.List<Map<String,Object>> samples=new java.util.ArrayList<>();
        synchronized(buffer){
        size=buffer.size();
        for(int i=Math.max(0,size-2);i<size;i++){
            var snapshot=buffer.get(i);var snapshotPose=snapshot.pose();var sp=snapshotPose.position();var sq=snapshotPose.orientation();
            samples.add(Map.of("gameTick",snapshot.gameTick(),"position",new double[]{sp.x(),sp.y(),sp.z()},
                    "orientation",new double[]{sq.x(),sq.y(),sq.z(),sq.w()}));
        }
        }
        data.put("actualInterpolationBufferSize",size);data.put("actualInterpolationLastTwo",samples);
        uuid = body.getUniqueId().toString(); observation = data;
    }

    /** Does NOT assess visual success or replace Sable geometry. File existence is not a PASS. */
    public Map<String, Object> capture(Path freshRoleDirectory, int stage, long expectedFrameId, com.google.gson.JsonObject serverState) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (observation == null || ((Number) observation.get("frameId")).longValue() != expectedFrameId
                || mc.level == null || mc.player == null || mc.screen != null || mc.getOverlay() != null || mc.isPaused()
                || !mc.player.isSpectator() || mc.player.distanceToSqr(0,94,-12)>1
                || Math.abs(mc.player.getYRot())>1 || Math.abs(mc.player.getXRot()-30)>1
                || mc.getSingleplayerServer() != null || mc.getConnection() == null
                || !(mc.getConnection().getConnection().getRemoteAddress() instanceof InetSocketAddress endpoint)
                || endpoint.isUnresolved() || !endpoint.getAddress().isLoopbackAddress() || endpoint.getPort() != 25575)
            throw new IllegalStateException("Capture requires current genuine loopback world frame: expectedFrame="+expectedFrameId
                    +", rememberedFrame="+(observation==null?"none":observation.get("frameId"))
                    +", screen="+(mc.screen==null?"none":mc.screen.getClass().getSimpleName())
                    +", overlay="+(mc.getOverlay()==null?"none":mc.getOverlay().getClass().getSimpleName())
                    +", paused="+mc.isPaused()+", player="+(mc.player==null?"none":
                    "spectator="+mc.player.isSpectator()+", position="+mc.player.position()+", yaw="+mc.player.getYRot()+", pitch="+mc.player.getXRot())
                    +", singleplayer="+(mc.getSingleplayerServer()!=null)+", endpoint="+(mc.getConnection()==null?"none":mc.getConnection().getConnection().getRemoteAddress()));
        Path frames = freshRoleDirectory.resolve("dynamic-frames"); Files.createDirectories(frames);
        String filename = "stage-" + stage + "-frame-" + expectedFrameId + ".png";
        Path target = frames.resolve(filename);
        if (Files.exists(target)) throw new IllegalStateException("Refusing old frame");
        Path temporary = Files.createTempFile(frames, ".dynamic-", ".tmp");
        int width, height;
        try {
            try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                width = image.getWidth(); height = image.getHeight(); image.writeToFile(temporary);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        Map<String, Object> data = new LinkedHashMap<>(observation);
        data.put("runId", ClientProbeMod.RUN_ID); data.put("sessionNonce", ClientProbeMod.SESSION_NONCE);
        data.put("dynamic",ClientProbeMod.DYNAMIC);
        data.put("runtimeNonce", ClientProbeMod.RUNTIME_NONCE); data.put("capturedAtMillis", System.currentTimeMillis());
        data.put("width", width); data.put("height", height); data.put("stage", stage); data.put("file", filename);
        data.put("uuid", uuid); data.put("visualAcceptance", "NOT_EVALUATED");
        data.put("writtenAtMillis", System.currentTimeMillis());
        data.put("stateEpoch", serverState.get("stateEpoch").getAsString());
        data.put("serverRuntimeNonce", serverState.get("runtimeNonce").getAsString());
        data.put("serverSimulationSeconds", serverState.get("simulationSeconds").getAsDouble());
        data.put("observedPhase", serverState.get("phase").getAsString());
        data.put("remoteEndpoint", mc.getConnection().getConnection().getRemoteAddress().toString());
        ClientProbeMod.atomicJson(frames.resolve(filename + ".json"), data, false);
        observation = null;
        return data;
    }
}








