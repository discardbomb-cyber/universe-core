package dev.heiko.universe.gravityruntime;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.heiko.universe.api.gravity.GravitySample;
import dev.heiko.universe.integration.gravity.LivingGravityAdapter;
import dev.heiko.universe.integration.gravity.LivingGravityDomain;
import dev.heiko.universe.ships.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;

/** Dedicated disposable-world fixture only. No GameTest, client, Sable or synthetic lifecycle events. */
@Mod(GravityRuntimeProbe.ID)
@EventBusSubscriber(modid=GravityRuntimeProbe.ID)
public final class GravityRuntimeProbe {
    public static final String ID="universe_gravity_runtime_test";
    private static final boolean ENABLED=Boolean.getBoolean("universe.gravityRuntime.enabled");
    private static final String SCENARIO=System.getProperty("universe.gravityRuntime.scenario","");
    private static final String RUN=System.getProperty("universe.gravityRuntime.runId","");
    private static final String NONCE=System.getProperty("universe.gravityRuntime.nonce","");
    private static final String RUNTIME=UUID.randomUUID().toString();
    private static final long PID=ProcessHandle.current().pid();
    private static final int CX=1024,CZ=1024,TIMEOUT=2400;
    private static final String FRAME="universe_gravity_runtime_test:world_y";
    private static final ResourceLocation FOREIGN=ResourceLocation.fromNamespaceAndPath(ID,"foreign");
    private static final Map<String,Object> REPORT=new LinkedHashMap<>();
    private static MinecraftServer server;
    private static ServerLevel level;
    private static Pig original,reloaded;
    private static UUID expectedUuid;
    private static LivingGravityDomain domain;
    private static LivingGravityDomain.Registration token;
    private static AttributeModifier ownedModifier;
    private static LevelChunk originalChunk;
    private static boolean authorized,forceOwned,failed,finished,observedUnload,stopChecks,writerBeforeStop;
    private static boolean observedChunkUnload;
    private static boolean observedLeave;
    private static int ticks,phaseTick;
    private static String phase="START";
    private static Path artifacts,marker;
    private static JsonObject expected;
    private static final long[] tickNanos=new long[TIMEOUT+1];
    private static int timingCount;
    private static long tickStart;

    public GravityRuntimeProbe() {
        if (!ENABLED) return;
        require(SCENARIO.equals("unload") || SCENARIO.equals("restart-write") || SCENARIO.equals("restart-read"),"Explicit scenario required");
        require(RUN.matches("[A-Za-z0-9_-]{1,64}") && NONCE.matches("[A-Za-z0-9_-]{16,80}"),"Fresh runId/session nonce required");
        require(!Boolean.getBoolean("universe.clientProbe.enabled"),"Static/client fixture must be disabled");
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void start(ServerStartedEvent event) {
        if (!ENABLED) return;
        server=event.getServer();level=server.overworld();
        try {
            require(server.isDedicatedServer() && !server.getClass().getName().contains("GameTest"),"Ordinary dedicated server required");
            require("127.0.0.1".equals(server.getLocalIp()) && !server.usesAuthentication(),"Offline loopback disposable server required");
            require(server.getPlayerList().getPlayerCount()==0,"No player tickets permitted");
            require(LivingGravityDomain.activeCount(server)==0,"Exclusive gravity runtime fixture required");
            Path world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            // Supervisor creates this fence in the disposable world before launch and copies it with the world.
            require(Files.readString(world.resolve("gravity-runtime-optin.txt")).trim().equals(NONCE),"Missing matching disposable-world fence");
            authorized=true;
            Path parent=Path.of(System.getProperty("universe.gravityRuntime.artifacts","")).toAbsolutePath().normalize();
            require(!System.getProperty("universe.gravityRuntime.artifacts","").isBlank(),"Artifact parent required");
            Files.createDirectories(parent);Path claimed=parent.resolve(RUN);Files.createDirectory(claimed);artifacts=claimed;
            marker=world.resolve("gravity-runtime-expected.json");
            REPORT.put("runId",RUN);REPORT.put("nonce",NONCE);REPORT.put("runtimeNonce",RUNTIME);REPORT.put("pid",PID);
            REPORT.put("scenario",SCENARIO);REPORT.put("worldPath",world.toString());REPORT.put("status","RUNNING");
            REPORT.put("trajectoryAcceptance","NOT_EVALUATED_NOAI_FIXTURE");
            BlockPos spawn=level.getSharedSpawnPos();
            require(Math.abs((spawn.getX()>>4)-CX)>128 || Math.abs((spawn.getZ()>>4)-CZ)>128,"Fixture too close to spawn tickets");
            require(!level.getForcedChunks().contains(ChunkPos.asLong(CX,CZ)),"Chunk force ticket already owned externally");
            if (SCENARIO.equals("restart-read")) {
                require(Files.isRegularFile(marker) && Files.size(marker)<=16384,"Writer world marker required");
                expected=JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
                require(expected.get("schema").getAsInt()==1 && expected.get("status").getAsString().equals("PASSED_WRITE_STOP"),"Writer did not complete real stop");
                require(expected.get("nonce").getAsString().equals(NONCE),"Writer session mismatch");
                require(expected.get("pid").getAsLong()!=PID && !expected.get("runtimeNonce").getAsString().equals(RUNTIME),"New server JVM required");
                require(!expected.get("runId").getAsString().equals(RUN),"Reader needs fresh run directory");
                require(expected.get("chunkX").getAsInt()==CX && expected.get("chunkZ").getAsInt()==CZ,"Unexpected expected chunk");
                expectedUuid=UUID.fromString(expected.get("pigUuid").getAsString());
                REPORT.put("writerPid",expected.get("pid").getAsLong());REPORT.put("writerRunId",expected.get("runId").getAsString());
                force();level.getChunk(CX,CZ);phase="WAIT_RESTART_ENTITY";
                // No EntityType.create, Pig.load or addFreshEntity in this branch.
            } else {
                require(!Files.exists(marker),"Fresh writer/unload world required, not another scenario's world");
                force();originalChunk=level.getChunk(CX,CZ);
                int x=CX*16+8,z=CZ*16+8;
                level.setBlock(new BlockPos(x,128,z),Blocks.STONE.defaultBlockState(),3);
                level.setBlock(new BlockPos(x,129,z),Blocks.AIR.defaultBlockState(),3);
                level.setBlock(new BlockPos(x,130,z),Blocks.AIR.defaultBlockState(),3);
                original=EntityType.PIG.create(level);require(original!=null,"Cannot create fixture Pig");
                original.moveTo(x+0.5,129,z+0.5,0,0);original.setNoAi(true);original.setPersistenceRequired();original.setInvulnerable(true);
                original.getAttribute(Attributes.GRAVITY).addPermanentModifier(new AttributeModifier(FOREIGN,0.02,AttributeModifier.Operation.ADD_VALUE));
                require(level.addFreshEntity(original),"Cannot admit real fixture Pig");expectedUuid=original.getUUID();
                domain=LivingGravityDomain.open(server,ID+":host_"+RUNTIME,1);
                var attempt=domain.register(original,new GravitySample(ID+":field",0,FRAME,new Vec3(0,-16,0)),FRAME);
                token=attempt.registration();require(attempt.applied() && token!=null && token.isActive(),"Registration not applied");
                ownedModifier=original.getAttribute(Attributes.GRAVITY).getModifier(LivingGravityAdapter.MODIFIER_ID);
                require(ownedModifier!=null,"Expected exact owned modifier");
                REPORT.put("pigUuid",expectedUuid.toString());phase="LIVE";
            }
            phaseTick=ticks;writeReport();
        } catch(Exception error) {fail(error);}
    }

    private static void force() {
        require(!forceOwned,"Only one owned chunk ticket allowed");
        require(!level.getForcedChunks().contains(ChunkPos.asLong(CX,CZ)),"External forced owner appeared");
        forceOwned=true;level.setChunkForced(CX,CZ,true);
    }
    private static void releaseForce() {
        if(forceOwned) {level.setChunkForced(CX,CZ,false);forceOwned=false;}
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void leave(EntityLeaveLevelEvent event) {
        if(event.getLevel()!=level || event.getEntity()!=original || !phase.equals("WAIT_UNLOAD"))return;
        observedLeave=true;REPORT.put("leaveEventTick",ticks);
        REPORT.put("leaveEventRemovalReason",String.valueOf(original.getRemovalReason()));
        // Tracking can stop while the entity is HIDDEN, before persistence assigns removal reason.
        // Observe that later ordinary state independently; never publish synthetic leave/removal.
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void chunkUnloaded(ChunkEvent.Unload event) {
        if(event.getLevel()==level && event.getChunk()==originalChunk && phase.equals("WAIT_UNLOAD")) {
            observedChunkUnload=true;REPORT.put("actualChunkUnloadEventTick",ticks);
        }
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if(event.getServer()!=server || failed || finished)return;
        try {
            ticks++;require(ticks<=TIMEOUT,"Bounded runtime timeout: "+phase);
            require(server.getPlayerList().getPlayerCount()==0,"Players entered exclusive unload fixture");
            if(phase.equals("WAIT_UNLOAD") && observedLeave && original.getRemovalReason()==Entity.RemovalReason.UNLOADED_TO_CHUNK) {
                observedUnload=true;REPORT.put("actualRemovalReason",original.getRemovalReason().name());
            }
            if(phase.equals("LIVE")) {
                require(token.isActive() && domain.activeCount()==1 && original.isAlive(),"Ownership lost before ticket release/save");
                if(ticks-phaseTick<40)return;
                require(server.saveEverything(true,true,true),"Explicit world save failed");
                REPORT.put("savedWithActiveTransient",true);
                if(SCENARIO.equals("unload")) {
                    phase="WAIT_UNLOAD";phaseTick=ticks;releaseForce();REPORT.put("ticketReleasedAtTick",ticks);writeReport();
                } else {
                    writeMarker("SAVED_WAITING_FOR_STOP");phase="STOP_WRITER";
                    server.halt(false); // Real orderly shutdown of an explicitly fenced disposable server.
                }
            } else if(phase.equals("WAIT_UNLOAD") && observedUnload && observedChunkUnload) {
                // The real entity removal callback alone is not proof that its terrain chunk unloaded.
                if(level.getChunkSource().hasChunk(CX,CZ) || level.getChunkSource().getChunkNow(CX,CZ)!=null
                        || level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(CX,CZ))!=null)return;
                require(!token.isActive() && domain.activeCount()==0 && LivingGravityDomain.activeCount(server)==0,"Old ownership survived actual unload");
                require(original.getAttribute(Attributes.GRAVITY).getModifier(LivingGravityAdapter.MODIFIER_ID)==null,"Old transient remains after unload");
                require(level.getEntity(expectedUuid)==null,"Old instance still in loaded entity index");
                REPORT.put("unloadCleanupObserved",true);REPORT.put("terrainChunkAbsentBeforeReload",true);
                force();require(level.getChunk(CX,CZ)!=originalChunk,"Terrain reload reused old instance");
                REPORT.put("newTerrainInstance",true);phase="WAIT_RELOAD";phaseTick=ticks;writeReport();
            } else if(phase.equals("WAIT_RELOAD") || phase.equals("WAIT_RESTART_ENTITY")) {
                Entity found=level.getEntity(expectedUuid);
                if(found==null)return;
                require(found instanceof Pig,"Expected saved UUID is not a Pig");reloaded=(Pig)found;
                if(SCENARIO.equals("unload"))require(reloaded!=original,"Reload reused old Java instance");
                validateReload();phase="OBSERVE_RELOADED";phaseTick=ticks;
            } else if(phase.equals("OBSERVE_RELOADED")) {
                validateReload();if(ticks-phaseTick<40)return;
                REPORT.put("reloadedUuid",reloaded.getUUID().toString());REPORT.put("reloadObservedTicks",ticks-phaseTick);
                REPORT.put("newInstance",original==null || reloaded!=original);
                REPORT.put("status",SCENARIO.equals("unload")?"PASSED_ACTUAL_UNLOAD_RELOAD":"PASSED_NEW_PROCESS_SAVED_WORLD_READ");
                finished=true;writeReport();cleanup(true);server.halt(false);
            }
        } catch(Exception error) {fail(error);}
        finally {if(tickStart!=0 && timingCount<tickNanos.length)tickNanos[timingCount++]=System.nanoTime()-tickStart;tickStart=0;}
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void tickStarted(ServerTickEvent.Pre event) {
        if(event.getServer()==server && !failed && !finished)tickStart=System.nanoTime();
    }
    private static void validateReload() {
        require(reloaded.getUUID().equals(expectedUuid) && reloaded.level()==level && reloaded.isAlive() && !reloaded.isRemoved(),"Invalid loaded saved Pig");
        require(reloaded.getAttribute(Attributes.GRAVITY).getModifier(LivingGravityAdapter.MODIFIER_ID)==null,"Transient modifier persisted/reapplied");
        var foreign=reloaded.getAttribute(Attributes.GRAVITY).getModifier(FOREIGN);
        require(foreign!=null && foreign.operation()==AttributeModifier.Operation.ADD_VALUE && foreign.amount()==0.02,"Foreign modifier not preserved");
        require(Math.abs(reloaded.getAttribute(Attributes.GRAVITY).getBaseValue()-0.08)<1e-9
                && Math.abs(reloaded.getAttributeValue(Attributes.GRAVITY)-0.10)<1e-9,"Foreign/base gravity state changed");
        require(!reloaded.isNoGravity() && reloaded.isNoAi(),"Saved fixture flags changed");
        require(LivingGravityDomain.activeCount(server)==0 && (domain==null || domain.activeCount()==0),"Reload silently auto-registered");
        require(reloaded.chunkPosition().x==CX && reloaded.chunkPosition().z==CZ,"Saved Pig outside expected chunk");
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void beforeStop(ServerStoppingEvent event) {
        if(event.getServer()!=server || !SCENARIO.equals("restart-write") || failed)return;
        try {
            // Observe exact active ownership before the normal lifecycle relay performs cleanup.
            writerBeforeStop=phase.equals("STOP_WRITER") && ticks>=40 && domain.activeCount()==1
                    && LivingGravityDomain.activeCount(server)==1 && token.isActive()
                    && original.isAlive() && !original.isRemoved() && !domain.isClosed()
                    && original.getAttribute(Attributes.GRAVITY).getModifier(LivingGravityAdapter.MODIFIER_ID)==ownedModifier;
            require(writerBeforeStop,"Writer lost ownership before real stopping cleanup");
        } catch(Exception error) {fail(error);}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void stopping(ServerStoppingEvent event) {
        if(event.getServer()!=server)return;
        if(SCENARIO.equals("restart-write") && !failed) {
            try {
                require(writerBeforeStop && domain.isClosed() && domain.activeCount()==0 && LivingGravityDomain.activeCount(server)==0,"Writer shutdown cleanup failed");
                require(original.getAttribute(Attributes.GRAVITY).getModifier(LivingGravityAdapter.MODIFIER_ID)==null,"Writer modifier survived shutdown cleanup");
                stopChecks=true;REPORT.put("writerStoppingCleanup",true);
            } catch(Exception error) {fail(error);}
        }
        // Release our only force ticket before stopServer saves chunks. Writer Pig is never discarded.
        releaseForce();
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void stopped(ServerStoppedEvent event) {
        if(event.getServer()!=server || !SCENARIO.equals("restart-write") || failed)return;
        try {
            require(stopChecks && server.isStopped(),"Writer lacks real stopped evidence");
            writeMarker("PASSED_WRITE_STOP");REPORT.put("status","PASSED_REAL_SAVE_AND_STOP_WRITER");finished=true;writeReport();
        } catch(Exception error) {fail(error);}
    }
    private static void writeMarker(String status) throws Exception {
        var data=new LinkedHashMap<String,Object>();
        data.put("schema",1);data.put("status",status);data.put("runId",RUN);data.put("nonce",NONCE);
        data.put("runtimeNonce",RUNTIME);data.put("pid",PID);data.put("pigUuid",expectedUuid.toString());
        data.put("chunkX",CX);data.put("chunkZ",CZ);data.put("writtenAtMillis",System.currentTimeMillis());
        atomic(marker,data);
    }
    private static void writeReport() throws Exception {
        if(artifacts==null)return;
        REPORT.put("phase",phase);REPORT.put("ticks",ticks);REPORT.put("writtenAtMillis",System.currentTimeMillis());
        if(original!=null) {
            REPORT.put("originalRemoved",original.isRemoved());REPORT.put("originalRemovalReason",String.valueOf(original.getRemovalReason()));
            REPORT.put("observedLeave",observedLeave);REPORT.put("observedChunkUnload",observedChunkUnload);
        }
        if(timingCount>0) {
            long[] sorted=Arrays.copyOf(tickNanos,timingCount);Arrays.sort(sorted);
            double sum=0;for(long elapsed:sorted)sum+=elapsed/1e6;
            REPORT.put("tickIntervalTimings",Map.of("samples",timingCount,"meanMs",sum/timingCount,
                    "p50Ms",sorted[(timingCount-1)/2]/1e6,"p95Ms",sorted[(int)Math.ceil(timingCount*0.95)-1]/1e6,
                    "maxMs",sorted[timingCount-1]/1e6,"scope","ServerTickEvent.Pre-to-Post interval, one NoAI fixture; not large-object benchmark"));
        }
        atomic(artifacts.resolve("report.json"),REPORT);
    }
    private static void atomic(Path target,Map<String,Object> data) throws Exception {
        Path temp=Files.createTempFile(target.getParent(),".gravity-runtime-",".tmp");
        try {Files.writeString(temp,new GsonBuilder().setPrettyPrinting().create().toJson(data));
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temp);}
    }
    private static void cleanup(boolean discard) {
        try {if(domain!=null)domain.close();} finally {
            try {releaseForce();} finally {
                if(discard && reloaded!=null)reloaded.discard();
                if(discard && original!=null && !original.isRemoved())original.discard();
            }
        }
    }
    private static void fail(Exception error) {
        failed=true;REPORT.put("status","FAILED");REPORT.put("error",error.toString());
        org.slf4j.LoggerFactory.getLogger(GravityRuntimeProbe.class).error("Gravity runtime scenario rejected/failed",error);
        try {writeReport();}catch(Exception writeError){org.slf4j.LoggerFactory.getLogger(GravityRuntimeProbe.class).error("Probe audit write failed",writeError);}
        try {if(authorized && level!=null)cleanup(false);}catch(Exception cleanupError){org.slf4j.LoggerFactory.getLogger(GravityRuntimeProbe.class).error("Probe cleanup failed",cleanupError);}
        if(authorized && server!=null && !server.isStopped())server.halt(false);
    }
    private static void require(boolean condition,String message) {
        if(!condition)throw new IllegalStateException(message);
    }
}
