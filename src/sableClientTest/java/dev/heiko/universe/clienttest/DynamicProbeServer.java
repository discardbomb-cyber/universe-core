package dev.heiko.universe.clienttest;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Complete orchestration candidate. Static ProbeServer MUST be excluded in dynamic mode by root. */
@EventBusSubscriber(modid=ClientProbeMod.ID)
public final class DynamicProbeServer {
    private static MinecraftServer server;
    private static ServerLevel level;
    private static ServerSubLevelContainer container;
    private static ServerSubLevel body;
    private static DynamicServerDriver driver;
    private static DynamicFixturePolicy policy;
    private static UUID uuid;
    private static Path directory;
    private static long startNanos,stageNanos,readyMillis;
    private static boolean armed,ticketOwned,listenerOwned,halting,completionPublished;
    private static long completionNanos;
    private static Throwable failure;
    private static int publishedStage=-1;
    private static String epoch;
    private static Map<String,Object> heldState;
    private static final Set<UUID> positioned=new HashSet<>();
    private static final Set<ChunkPos> forced=new HashSet<>();
    private static final Map<BlockPos,BlockState> changed=new LinkedHashMap<>();
    private DynamicProbeServer(){}

    @SubscribeEvent public static void start(ServerStartedEvent event){
        if(!DynamicProtocol.ENABLED||!event.getServer().isDedicatedServer())return;
        server=event.getServer();startNanos=System.nanoTime();
        try{
            if(!"127.0.0.1".equals(server.getLocalIp())||server.usesAuthentication())throw new IllegalStateException("Disposable offline loopback only");
            armed=true;directory=ClientProbeMod.claim("server");level=server.overworld();
            container=SubLevelContainer.getContainer(level);
            if(container==null||container.physicsSystem()==null||!container.getAllSubLevels().isEmpty()
                    ||!container.physicsSystem().getPipeline().getClass().getName().equals("dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline"))
                throw new IllegalStateException("Empty native Rapier scene required");
            policy=new DynamicFixturePolicy(container);
            for(int x=-1;x<=0;x++)for(int z=-1;z<=0;z++){
                ChunkPos chunk=new ChunkPos(x,z);
                if(!level.getForcedChunks().contains(chunk.toLong())){
                    forced.add(chunk); // Own BEFORE mutation, preserving pre-existing tickets.
                    level.setChunkForced(x,z,true);
                }
                level.getChunk(x,z);
            }
            // This is already inside the bounded forced chunk set; validate its z=-6 block separately.
            if(!level.getBlockState(new BlockPos(-4,83,-6)).isAir())throw new IllegalStateException("Landmark volume obstructed");
            for(BlockPos p:BlockPos.betweenClosed(-5,80,-5,5,92,5))if(!level.getBlockState(p).isAir())throw new IllegalStateException("Fixture obstructed");
            for(BlockPos p:BlockPos.betweenClosed(-5,80,-5,5,80,5))ordinary(p,Blocks.SMOOTH_STONE.defaultBlockState());
            for(BlockPos p:BlockPos.betweenClosed(2,84,-4,2,90,-4))ordinary(p,Blocks.MAGENTA_CONCRETE.defaultBlockState());
            ordinary(new BlockPos(-4,83,-6),Blocks.CYAN_CONCRETE.defaultBlockState());
            var manifest=DynamicProtocol.envelope(Map.of("scenario","dynamic","cameraPlayerPosition",new double[]{0,94,-12},
                    "cameraYaw",0,"cameraPitch",30,"fixturePolicy",policy.metadata(),"bodyBaselineAroundY",88,"wallMin",new int[]{2,84,-4},
                    "wallMax",new int[]{2,90,-4},"ordinaryFixtureVerified",verifyOrdinaryFixture(),"landmark",Map.of("position",new int[]{-4,83,-6},
                        "block","minecraft:cyan_concrete","actualBlockVerified",level.getBlockState(new BlockPos(-4,83,-6)).is(Blocks.CYAN_CONCRETE))));
            manifest.put("actualCommonAttemptUdp",dev.ryanhcode.sable.SableConfig.ATTEMPT_UDP_NETWORKING.getAsBoolean());
            ClientProbeMod.atomicJson(directory.resolve("dynamic-fixture-manifest.json"),manifest,false);
            Pose3d pose=new Pose3d();pose.position().set(0,88,0);
            body=(ServerSubLevel)container.allocateNewSubLevel(pose);if(body==null)throw new IllegalStateException("Allocation failed");
            uuid=body.getUniqueId();body.setName(ClientProbeMod.NAME+" dynamic");ticketOwned=true;
            container.addForceLoadTicket(body,SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);
            var chunk=body.getPlot().getCenterChunk();body.getPlot().newEmptyChunk(chunk);
            BlockPos origin=new BlockPos(chunk.getMinBlockX()+3,64,chunk.getMinBlockZ()+3);
            for(int x=0;x<3;x++)for(int z=0;z<3;z++)level.setBlock(origin.offset(x,0,z),Blocks.WHITE_CONCRETE.defaultBlockState(),Block.UPDATE_ALL);
            level.setBlock(origin,Blocks.RED_CONCRETE.defaultBlockState(),Block.UPDATE_ALL);
            level.setBlock(origin.offset(2,0,0),Blocks.BLUE_CONCRETE.defaultBlockState(),Block.UPDATE_ALL);
            level.setBlock(origin.offset(0,0,2),Blocks.LIME_CONCRETE.defaultBlockState(),Block.UPDATE_ALL);
            level.setBlock(origin.offset(2,0,2),Blocks.YELLOW_CONCRETE.defaultBlockState(),Block.UPDATE_ALL);
            level.setBlock(origin.offset(1,1,1),Blocks.SEA_LANTERN.defaultBlockState(),Block.UPDATE_ALL);
            body.logicalPose().rotationPoint().set(body.getMassTracker().getCenterOfMass());
            ClientProbeMod.report("server","WAIT_NATIVE","RUNNING",Map.of("uuid",uuid.toString(),"scenario","dynamic"));
        }catch(Throwable error){failure=error;}
    }
    private static void ordinary(BlockPos p,BlockState state){BlockPos key=p.immutable();changed.putIfAbsent(key,level.getBlockState(key));level.setBlock(key,state,Block.UPDATE_ALL);}
    private static boolean verifyOrdinaryFixture(){
        for(BlockPos p:BlockPos.betweenClosed(-5,80,-5,5,80,5))if(!level.getBlockState(p).is(Blocks.SMOOTH_STONE))throw new IllegalStateException("Actual floor changed");
        for(BlockPos p:BlockPos.betweenClosed(2,84,-4,2,90,-4))if(!level.getBlockState(p).is(Blocks.MAGENTA_CONCRETE))throw new IllegalStateException("Actual wall changed");
        if(!level.getBlockState(new BlockPos(-4,83,-6)).is(Blocks.CYAN_CONCRETE))throw new IllegalStateException("Actual landmark changed");
        return true;
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        if(event.getServer()!=server||!armed||halting)return;
        try{
            if(failure!=null)throw new IllegalStateException("Deferred test failure",failure);
            if(System.nanoTime()-startNanos>180_000_000_000L)throw new IllegalStateException("Server watchdog 180s");
            Path clientReportFile=directory.getParent().resolve("client/report.json");
            if(Files.exists(clientReportFile)){
                var report=DynamicProtocol.read(clientReportFile);DynamicProtocol.envelope(report,uuid.toString());
                if(report.get("status").getAsString().equals("FAILED"))
                    throw new IllegalStateException("Actual client failed: "+report.get("error").getAsString());
            }
            if(completionPublished){
                Path receipt=directory.getParent().resolve("client/dynamic-complete-ack.json");
                if(Files.exists(receipt)){
                    var ack=DynamicProtocol.read(receipt);DynamicProtocol.envelope(ack,uuid.toString());
                    if(!ack.get("status").getAsString().equals("DYNAMIC_CAPTURED_PENDING_VALIDATION")
                            ||ack.get("acknowledgedStage").getAsInt()!=3||ack.get("heldFrames").getAsInt()!=12
                            ||ack.get("liveFrames").getAsInt()<3||!epoch.equals(ack.get("stateEpoch").getAsString())
                            ||!ClientProbeMod.RUNTIME_NONCE.equals(ack.get("serverRuntimeNonce").getAsString())
                            ||ClientProbeMod.RUNTIME_NONCE.equals(ack.get("runtimeNonce").getAsString()))
                        throw new IllegalStateException("Completion receipt mismatch");
                    var report=DynamicProtocol.read(receipt.resolveSibling("report.json"));DynamicProtocol.envelope(report,uuid.toString());
                    if(!report.get("status").getAsString().equals("DYNAMIC_CAPTURED_PENDING_VALIDATION")
                            ||!report.get("runtimeNonce").equals(ack.get("runtimeNonce")))
                        throw new IllegalStateException("Client report missing before completion receipt");
                    halting=true;try{cleanup();}finally{server.halt(false);}return;
                }
                if(System.nanoTime()-completionNanos>20_000_000_000L)throw new IllegalStateException("Completion receipt timeout");
                return;
            }
            if(driver==null){
                var handle=RigidBodyHandle.of(body);
                if(handle==null||!handle.isValid()||body.getMassTracker().getMass()<=0)return;
                driver=new DynamicServerDriver(container,body);
                listenerOwned=true;NeoForge.EVENT_BUS.register(driver);
            }
            if(driver.failure()!=null)throw new IllegalStateException("Physics callback deferred failure",driver.failure());
            policy.verify();
            for(var player:server.getPlayerList().getPlayers())if(positioned.add(player.getUUID())){
                player.setGameMode(GameType.SPECTATOR);player.teleportTo(level,0,94,-12,0,30);
            }
            if(driver.holding()){
                if(publishedStage!=driver.stage())publishHeld();
                if(System.nanoTime()-stageNanos>120_000_000_000L)throw new IllegalStateException("Held ACK timeout 120s");
                Path ack=directory.getParent().resolve("client/dynamic-ack-"+publishedStage+".json");
                if(Files.exists(ack)){
                    validateAck(ack);
                    if(publishedStage==3){if(ackLiveCount(ack)<3)throw new IllegalStateException("Missing live moving PNG evidence");driver.verifyFinal();complete();}
                    else {driver.acknowledge(publishedStage);publishCurrent("MOVING");}
                }
            }else publishCurrent("MOVING");
        }catch(Throwable error){failure=error;failAndHalt();}
    }
    private static void publishHeld()throws Exception{
        publishedStage=driver.stage();epoch=UUID.randomUUID().toString();readyMillis=System.currentTimeMillis();stageNanos=System.nanoTime();
        heldState=DynamicProtocol.envelope(driver.snapshot());heldState.put("phase","HELD");heldState.put("stateEpoch",epoch);heldState.put("readyAtMillis",readyMillis);
        heldState.put("name",ClientProbeMod.NAME+" dynamic");heldState.put("fixturePolicy",policy.metadata());
        heldState.put("cameraPlayerPosition",new double[]{0,94,-12});heldState.put("cameraYaw",0);heldState.put("cameraPitch",30);
        heldState.put("ordinaryFixtureVerified",verifyOrdinaryFixture());
        heldState.put("actualCommonAttemptUdp",dev.ryanhcode.sable.SableConfig.ATTEMPT_UDP_NETWORKING.getAsBoolean());
        heldState.put("landmark",Map.of("position",new int[]{-4,83,-6},"block","minecraft:cyan_concrete",
                "actualBlockVerified",level.getBlockState(new BlockPos(-4,83,-6)).is(Blocks.CYAN_CONCRETE)));
        var chunk=body.getPlot().getCenterChunk();heldState.put("markerOrigin",new int[]{chunk.getMinBlockX()+3,64,chunk.getMinBlockZ()+3});
        ClientProbeMod.atomicJson(directory.resolve("dynamic-stage-"+publishedStage+".json"),heldState,false);
        ClientProbeMod.atomicJson(directory.resolve("dynamic-state.json"),heldState,true);
        flushTelemetry();
    }
    private static void publishCurrent(String phase)throws Exception{
        var row=DynamicProtocol.envelope(driver.snapshot());row.put("phase",phase);row.put("stateEpoch",epoch);row.put("readyAtMillis",readyMillis);
        ClientProbeMod.atomicJson(directory.resolve("dynamic-state.json"),row,true);
    }
    private static void flushTelemetry()throws Exception{
        ClientProbeMod.atomicJson(directory.resolve("dynamic-telemetry.json"),DynamicProtocol.envelope(Map.of("uuid",uuid.toString(),"samples",driver.telemetry())),true);
    }
    private static void validateAck(Path path)throws Exception{
        var ack=DynamicProtocol.read(path);DynamicProtocol.envelope(ack,uuid.toString());
        if(ack.get("stage").getAsInt()!=publishedStage||!epoch.equals(ack.get("stateEpoch").getAsString())
                ||ClientProbeMod.RUNTIME_NONCE.equals(ack.get("runtimeNonce").getAsString())
                ||ack.get("writtenAtMillis").getAsLong()<readyMillis)throw new IllegalStateException("ACK stage/time mismatch");
        var frames=ack.getAsJsonArray("frames");if(frames.size()!=3)throw new IllegalStateException("ACK requires 3 fresh PNG");
        long last=-1;
        var expected=com.google.gson.JsonParser.parseString(new com.google.gson.Gson().toJson(heldState)).getAsJsonObject();
        for(var entry:frames){
            var frame=entry.getAsJsonObject();String filename=frame.get("file").getAsString();
            if(!filename.matches("stage-"+publishedStage+"-frame-[0-9]+\\.png"))throw new IllegalStateException("Unsafe PNG path");
            Path png=path.getParent().resolve("dynamic-frames").resolve(filename);
            var data=DynamicProtocol.read(png.resolveSibling(filename+".json"));DynamicProtocol.envelope(data,uuid.toString());
            long frameId=data.get("frameId").getAsLong();
            if(frameId<=last||!epoch.equals(data.get("stateEpoch").getAsString())||data.get("stage").getAsInt()!=publishedStage
                    ||data.get("capturedAtMillis").getAsLong()<readyMillis||Files.getLastModifiedTime(png).toMillis()<readyMillis
                    ||!ack.get("runtimeNonce").getAsString().equals(data.get("runtimeNonce").getAsString())
                    ||!ClientProbeMod.RUNTIME_NONCE.equals(data.get("serverRuntimeNonce").getAsString())
                    ||frame.get("frameId").getAsLong()!=frameId||data.get("width").getAsInt()!=960||data.get("height").getAsInt()!=540
                    ||!frame.get("sha256").getAsString().equals(DynamicProtocol.sha(png)))throw new IllegalStateException("PNG metadata/hash mismatch");
            DynamicProtocol.pose(data,expected,.10,.03);last=frameId;
        }
    }
    private static int ackLiveCount(Path ackPath)throws Exception{
        var ack=DynamicProtocol.read(ackPath);var live=ack.getAsJsonArray("liveEvidence");
        if(live.size()>24||live.size()!=ack.get("liveFrames").getAsInt())throw new IllegalStateException("Live evidence cap/count mismatch");
        long previous=-1;
        for(var item:live){
            var row=item.getAsJsonObject();String file=row.get("file").getAsString();
            if(!file.matches("stage-(100|101|102)-frame-[0-9]+\\.png"))throw new IllegalStateException("Bad live PNG path");
            var png=ackPath.getParent().resolve("dynamic-frames").resolve(file);
            var metadata=DynamicProtocol.read(png.resolveSibling(file+".json"));DynamicProtocol.envelope(metadata,uuid.toString());
            long id=metadata.get("frameId").getAsLong();
            if(id<=previous||!"MOVING".equals(metadata.get("observedPhase").getAsString())
                    ||!ack.get("runtimeNonce").getAsString().equals(metadata.get("runtimeNonce").getAsString())
                    ||!ClientProbeMod.RUNTIME_NONCE.equals(metadata.get("serverRuntimeNonce").getAsString())
                    ||!row.get("sha256").getAsString().equals(DynamicProtocol.sha(png)))throw new IllegalStateException("Invalid live motion evidence");
            DynamicProtocol.array(metadata.getAsJsonArray("position"),3);DynamicProtocol.array(metadata.getAsJsonArray("orientation"),4);
            previous=id;
        }
        return live.size();
    }
    private static void complete()throws Exception{
        flushTelemetry();publishCurrent("EVIDENCE_COMPLETE");
        ClientProbeMod.report("server","DYNAMIC_STAGE_3","DYNAMIC_CAPTURED_PENDING_VALIDATION",Map.of("uuid",uuid.toString(),"numericMotionPassed",true,"visualAcceptance","NOT_EVALUATED"));
        completionPublished=true;completionNanos=System.nanoTime();
    }
    private static void failAndHalt(){
        try{ClientProbeMod.report("server","DYNAMIC_FAILED","FAILED",Map.of("uuid",uuid==null?"":uuid.toString(),"error",String.valueOf(failure)));
            if(directory!=null)ClientProbeMod.atomicJson(directory.resolve("dynamic-state.json"),DynamicProtocol.envelope(Map.of("uuid",uuid==null?"":uuid.toString(),"phase","FAILED","error",String.valueOf(failure))),true);
        }catch(Exception error){org.slf4j.LoggerFactory.getLogger(DynamicProbeServer.class).error("Failure report error",error);}
        halting=true;try{cleanup();}finally{server.halt(false);}
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){if(event.getServer()==server)cleanup();}
    private static void cleanup(){
        Throwable error=null;
        if(driver!=null){try{driver.close();}catch(Throwable e){error=e;}
            if(listenerOwned)try{NeoForge.EVENT_BUS.unregister(driver);listenerOwned=false;}catch(Throwable e){error=aggregate(error,e);}}
        if(body!=null&&container!=null){
            if(ticketOwned)try{container.removeForceLoadTicket(body,SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);ticketOwned=false;}catch(Throwable e){error=aggregate(error,e);}
            try{if(container.getSubLevel(body.getUniqueId())==body)container.removeSubLevel(body,SubLevelRemovalReason.REMOVED);}catch(Throwable e){error=aggregate(error,e);}
            try{container.processSubLevelRemovals();}catch(Throwable e){error=aggregate(error,e);}
        }
        if(policy!=null)try{policy.close();}catch(Throwable e){error=aggregate(error,e);}
        if(level!=null){
            for(var entry:new LinkedHashMap<>(changed).entrySet())try{level.setBlock(entry.getKey(),entry.getValue(),Block.UPDATE_ALL);changed.remove(entry.getKey());}catch(Throwable e){error=aggregate(error,e);}
            for(var chunk:new HashSet<>(forced))try{level.setChunkForced(chunk.x,chunk.z,false);forced.remove(chunk);}catch(Throwable e){error=aggregate(error,e);}
        }
        boolean absent=body==null;
        if(body!=null&&container!=null)try{absent=container.getSubLevel(body.getUniqueId())==null;}catch(Throwable e){error=aggregate(error,e);}
        try{if(directory!=null)ClientProbeMod.atomicJson(directory.resolve("dynamic-cleanup.json"),DynamicProtocol.envelope(Map.of("uuid",uuid==null?"":uuid.toString(),
                "ticketReleased",!ticketOwned,"nativeUnregistered",absent,"listenerUnregistered",!listenerOwned,
                "pauseRestored",driver==null||driver.pauseRestored(),"callbacksStopped",driver==null||driver.callbacksStopped(),"ordinaryBlocksRemaining",changed.size(),
                "forcedChunksRemaining",forced.size(),"fixturePolicyRestored",policy==null||policy.restored(),"error",error==null?"":error.toString())),true);}catch(Exception e){error=aggregate(error,e);}
        if(absent&&!ticketOwned)body=null;
        if(error!=null)org.slf4j.LoggerFactory.getLogger(DynamicProbeServer.class).error("Dynamic cleanup retained unresolved ownership",error);
    }
    private static Throwable aggregate(Throwable first,Throwable next){if(first==null)return next;first.addSuppressed(next);return first;}
}





