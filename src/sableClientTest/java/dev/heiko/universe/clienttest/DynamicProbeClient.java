package dev.heiko.universe.clienttest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Candidate: actual live client state and PNGs; ACK is not pixel/visual PASS. */
@EventBusSubscriber(modid=ClientProbeMod.ID,value=Dist.CLIENT)
public final class DynamicProbeClient {
    private static final DynamicFrameCapture capture=new DynamicFrameCapture();
    private static final List<Map<String,Object>> stageFrames=new ArrayList<>();
    private static final List<Map<String,Object>> allFrames=new ArrayList<>();
    private static final List<Map<String,Object>> liveEvidence=new ArrayList<>();
    private static long started,frameId,rememberedFrame=-1;
    private static int stage=-1,warmFrames,acknowledged=-1,liveFrames;
    private static String epoch,uuid="",serverRuntime;
    private static Path directory;
    private static JsonObject state;
    private static ClientSubLevel body;
    private static boolean worldRendered,finished;
    private static OptionInstance<Double> ownedFovEffect;
    private static Double originalFovEffect,persistedFovEffect;
    private static boolean cameraOptionClaimed,cameraOptionRestored;
    private static String cameraCleanupError="";
    private static long cameraSettlingStarted;
    private static int cameraSettlingFrames;
    private static boolean cameraMatrixReady;
    private DynamicProbeClient(){}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!DynamicProtocol.ENABLED||finished)return;
        if(started==0)started=System.nanoTime();
        try{
            directory=ClientProbeMod.claim("client");
            claimCameraOption(Minecraft.getInstance());
            if(System.nanoTime()-started>120_000_000_000L)throw new IllegalStateException("Client timeout 120s");
            Path file=directory.getParent().resolve("server/dynamic-state.json");if(!Files.exists(file))return;
            state=DynamicProtocol.read(file);uuid=state.get("uuid").getAsString();DynamicProtocol.envelope(state,uuid);
            if(serverRuntime==null)serverRuntime=DynamicProtocol.read(directory.getParent().resolve("session.json")).get("serverRuntimeNonce").getAsString();
            if(!serverRuntime.equals(state.get("runtimeNonce").getAsString()))throw new IllegalStateException("Server runtime identity mismatch");
            String phase=state.get("phase").getAsString();
            if(phase.equals("FAILED"))throw new IllegalStateException("Real server scenario failed: "+state.get("error"));
            if(phase.equals("EVIDENCE_COMPLETE")){
                if(acknowledged!=3||allFrames.size()<12||liveFrames<3)throw new IllegalStateException("Incomplete actual client evidence");
                finish("DYNAMIC_CAPTURED_PENDING_VALIDATION","");return;
            }
            if(!(phase.equals("HELD")||phase.equals("MOVING")))throw new IllegalStateException("Unknown phase");
            Minecraft mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;endpoint(mc);
            body=null;
            if(!mc.player.isSpectator()||mc.player.distanceToSqr(0,94,-12)>1
                    ||Math.abs(mc.player.getYRot())>1||Math.abs(mc.player.getXRot()-30)>1)
                return; // Allow ordinary setup teleport to arrive; never move the client to make a test pass.
            if(!mc.level.getBlockState(new BlockPos(-4,83,-6)).is(Blocks.CYAN_CONCRETE))return;
            var container=SubLevelContainer.getContainer(mc.level);if(container==null)return;
            for(var candidate:container.getAllSubLevels())if(candidate.getUniqueId().toString().equals(uuid)){
                if(body!=null)throw new IllegalStateException("Duplicate tracking UUID");body=candidate;
            }
            if(!ready(body))return;
            if(phase.equals("HELD")){
                int incoming=state.get("stage").getAsInt();String incomingEpoch=state.get("stateEpoch").getAsString();
                if(incoming>acknowledged+1||incoming<acknowledged)throw new IllegalStateException("Stage order violated");
                if(incoming!=stage){
                    stage=incoming;epoch=incomingEpoch;warmFrames=0;stageFrames.clear();
                }else if(!incomingEpoch.equals(epoch))throw new IllegalStateException("Held stage epoch changed");
            }
        }catch(Throwable error){finish("FAILED",error.toString());}
    }
    private static boolean ready(ClientSubLevel b){
        if(b==null||b.isRemoved()||!b.isFinalized()||b.getRenderData()==null)return false;
        var chunk=b.getPlot().getCenterChunk();BlockPos o=new BlockPos(chunk.getMinBlockX()+3,64,chunk.getMinBlockZ()+3);
        return b.getLevel().getBlockState(o).is(Blocks.RED_CONCRETE)&&b.getLevel().getBlockState(o.offset(2,0,0)).is(Blocks.BLUE_CONCRETE)
                &&b.getLevel().getBlockState(o.offset(0,0,2)).is(Blocks.LIME_CONCRETE)&&b.getLevel().getBlockState(o.offset(2,0,2)).is(Blocks.YELLOW_CONCRETE)
                &&b.getLevel().getBlockState(o.offset(1,1,1)).is(Blocks.SEA_LANTERN);
    }
    @SubscribeEvent public static void world(RenderLevelStageEvent event){
        if(!DynamicProtocol.ENABLED||finished||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
        try{
            if(state==null||!ready(body))return;
            Minecraft mc=Minecraft.getInstance();endpoint(mc);
            if(mc.screen!=null||mc.getOverlay()!=null||mc.isPaused())return;
            claimCameraOption(mc);
            double verticalScale=event.getProjectionMatrix().m11();
            double actualFov=Math.toDegrees(2*Math.atan(1.0/verticalScale));
            if(!Double.isFinite(verticalScale)||verticalScale<=0||!Double.isFinite(actualFov))
                throw new IllegalStateException("Invalid actual camera projection during settling");
            if(Math.abs(actualFov-70)>.1){
                if(cameraMatrixReady)throw new IllegalStateException("Fixed camera projection changed after settling: "+actualFov);
                if(cameraSettlingStarted==0)cameraSettlingStarted=System.nanoTime();
                if(++cameraSettlingFrames>300||System.nanoTime()-cameraSettlingStarted>5_000_000_000L)
                    throw new IllegalStateException("Actual camera did not settle to FOV70 within bounded warmup");
                return; // Wait for vanilla smoothing; no remembered frame, PNG, warm-frame count or cache mutation.
            }
            cameraMatrixReady=true;
            frameId++;rememberedFrame=frameId;worldRendered=true;
            capture.remember(event,body,frameId);
        }catch(Throwable error){finish("FAILED",error.toString());}
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event){
        if(!DynamicProtocol.ENABLED||finished)return;
        boolean rendered=worldRendered;worldRendered=false;if(!rendered)return;
        try{
            String phase=state.get("phase").getAsString();
            if(phase.equals("HELD")){
                if(stage!=state.get("stage").getAsInt()||stage<=acknowledged)return;
                warmFrames++;if(warmFrames<(stage==0?40:20))return;
                var metadata=capture.capture(directory,stage,rememberedFrame,state);
                var json=new Gson().toJsonTree(metadata).getAsJsonObject();
                DynamicProtocol.pose(json,state,.10,.03);
                add(metadata);stageFrames.add(summary(metadata));
                if(stageFrames.size()==3){
                    var ack=DynamicProtocol.envelope(Map.of("uuid",uuid,"stage",stage,"stateEpoch",epoch,"frames",new ArrayList<>(stageFrames),
                            "serverRuntimeNonce",serverRuntime,"liveFrames",liveFrames,"liveEvidence",new ArrayList<>(liveEvidence),"visualAcceptance","NOT_EVALUATED"));
                    ClientProbeMod.atomicJson(directory.resolve("dynamic-ack-"+stage+".json"),ack,false);
                    acknowledged=stage;
                }
            }else if(phase.equals("MOVING")&&frameId%4==0&&liveFrames<24){
                var metadata=capture.capture(directory,100+state.get("stage").getAsInt(),rememberedFrame,state);
                add(metadata);liveEvidence.add(summary(metadata));liveFrames++;
            }
        }catch(Throwable error){finish("FAILED",error.toString());}
    }
    private static void add(Map<String,Object> metadata){if(allFrames.size()>=128)throw new IllegalStateException("Client capture bound");allFrames.add(metadata);}
    private static Map<String,Object> summary(Map<String,Object> metadata)throws Exception{
        String file=(String)metadata.get("file");Map<String,Object> row=new LinkedHashMap<>();row.put("file",file);
        row.put("frameId",metadata.get("frameId"));row.put("sha256",DynamicProtocol.sha(directory.resolve("dynamic-frames").resolve(file)));return row;
    }
    private static void endpoint(Minecraft mc){
        if(mc.getSingleplayerServer()!=null||mc.getConnection()==null||!(mc.getConnection().getConnection().getRemoteAddress() instanceof InetSocketAddress address)
                ||address.isUnresolved()||!address.getAddress().isLoopbackAddress()||address.getPort()!=25575)throw new IllegalStateException("Real loopback endpoint required");
    }
    private static void claimCameraOption(Minecraft mc){
        if(!mc.isSameThread())throw new IllegalStateException("Camera option requires client thread");
        if(mc.options.fov().get()!=70)throw new IllegalStateException("Fixture base FOV must remain 70");
        var option=mc.options.fovEffectScale();
        if(!cameraOptionClaimed){
            double previous=option.get();
            if(!Double.isFinite(previous)||previous<0||previous>1)throw new IllegalStateException("Invalid original FOV effect scale");
            ownedFovEffect=option;originalFovEffect=previous;cameraOptionClaimed=true;
            // Disable the vanilla/NeoForge flying multiplier in this disposable client, without changing projection data.
            option.set(0.0);
        }
        if(option!=ownedFovEffect||Double.compare(option.get(),0.0)!=0)
            throw new IllegalStateException("Owned FOV effect option changed during fixture");
    }
    private static void restoreCameraOption()throws Exception{
        if(ownedFovEffect==null)return;
        Minecraft mc=Minecraft.getInstance();
        if(!mc.isSameThread()||mc.options.fovEffectScale()!=ownedFovEffect)
            throw new IllegalStateException("Lost camera option identity/thread ownership");
        double current=ownedFovEffect.get();
        if(Double.compare(current,0.0)!=0&&Double.compare(current,originalFovEffect)!=0)
            throw new IllegalStateException("Foreign FOV effect change; refusing to overwrite it");
        ownedFovEffect.set(originalFovEffect);
        if(Double.compare(ownedFovEffect.get(),originalFovEffect)!=0)throw new IllegalStateException("FOV effect restore failed");
        // Minecraft may autosave options while the world is open. Restore the owned value on disk as well.
        mc.options.save();
        Path file=mc.gameDirectory.toPath().resolve("options.txt");
        if(Files.size(file)>131_072)throw new IllegalStateException("Options cleanup byte cap");
        Double saved=null;
        for(String line:Files.readAllLines(file))if(line.startsWith("fovEffectScale:")){
            if(saved!=null)throw new IllegalStateException("Duplicate persisted FOV effect option");
            saved=Double.parseDouble(line.substring("fovEffectScale:".length()));
        }
        if(saved==null||!Double.isFinite(saved)||Double.compare(saved,originalFovEffect)!=0)
            throw new IllegalStateException("Persisted FOV effect restore failed");
        persistedFovEffect=saved;cameraOptionRestored=true;ownedFovEffect=null;
    }
    private static Map<String,Object> cameraOptionsReport(){
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("claimed",cameraOptionClaimed);report.put("baseFov",Minecraft.getInstance().options.fov().get());
        report.put("fixtureFovEffectScale",0.0);report.put("originalFovEffectScale",originalFovEffect);
        report.put("restoredFovEffectScale",Minecraft.getInstance().options.fovEffectScale().get());
        report.put("persistedFovEffectScale",persistedFovEffect);report.put("restored",cameraOptionRestored);
        report.put("settlingSkippedWorldFrames",cameraSettlingFrames);report.put("matrixReady",cameraMatrixReady);
        report.put("error",cameraCleanupError);return report;
    }
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event){
        if(!DynamicProtocol.ENABLED)return;
        if(!finished){finish("FAILED","Client shutdown before terminal receipt",false);return;}
        try{restoreCameraOption();}
        catch(Throwable error){org.slf4j.LoggerFactory.getLogger(DynamicProbeClient.class).error("Camera option shutdown cleanup failed",error);}
    }
    private static void finish(String status,String error){
        finish(status,error,true);
    }
    private static void finish(String status,String error,boolean requestStop){
        if(finished)return;finished=true;
        try{restoreCameraOption();}
        catch(Throwable cleanup){cameraCleanupError=cleanup.toString();status="FAILED";error=error.isEmpty()?cameraCleanupError:error+"; camera cleanup: "+cameraCleanupError;}
        try{ClientProbeMod.report("client","DYNAMIC_STAGE_"+stage,status,Map.of("uuid",uuid,"acknowledgedStage",acknowledged,
                "frames",allFrames,"liveFrames",liveFrames,"error",error,"visualAcceptance","NOT_EVALUATED","cameraOptions",cameraOptionsReport()));
            if(status.equals("DYNAMIC_CAPTURED_PENDING_VALIDATION"))
                ClientProbeMod.atomicJson(directory.resolve("dynamic-complete-ack.json"),DynamicProtocol.envelope(Map.of("uuid",uuid,
                        "status",status,"acknowledgedStage",acknowledged,"heldFrames",allFrames.size()-liveFrames,
                        "liveFrames",liveFrames,"stateEpoch",epoch,"serverRuntimeNonce",serverRuntime)),false);
        }
        catch(Exception failure){org.slf4j.LoggerFactory.getLogger(DynamicProbeClient.class).error("Dynamic client report failure",failure);}
        finally{if(requestStop)Minecraft.getInstance().stop();}
    }
}


