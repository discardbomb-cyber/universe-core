package dev.heiko.universe.sabletest;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import org.joml.Vector3d;

/** Candidate only: ordinary live mob + stationary isolated native bodies. No player or production claim. */
@GameTestHolder(SableTestMod.ID)
@PrefixGameTestTemplate(false)
public final class SableStandingPassengerProbe {
    private static final TicketType<UUID> WORLD_TICKET = TicketType.create("universe_passenger_probe", UUID::compareTo);
    private static final String OWNER = "universe_passenger_probe_owner";
    private static final List<String> NBT_KEYS = List.of("UUID", "Health", "CustomName", "CustomNameVisible",
            "Invulnerable", "PersistenceRequired", "Inventory");
    private static final int WAIT_LIMIT = 80, STANDING_TICKS = 5;
    private static final double LOCAL_TOLERANCE = .03;
    private static final double MAX_HORIZONTAL_WITNESS = 320;

    @GameTest(template="empty", timeoutTicks=900, required=true, batch="sable_passenger_standing_roundtrip")
    public static void oneStandingVillagerRoundTrip(GameTestHelper helper) { new Session(helper, Mode.ROUND_TRIP).start(); }

    @GameTest(template="empty", timeoutTicks=900, required=true, batch="sable_passenger_compensating_return")
    public static void abortBeforeShipCommitCompensatesVillager(GameTestHelper helper) { new Session(helper, Mode.COMPENSATING_RETURN).start(); }

    @GameTest(template="empty", timeoutTicks=900, required=true, batch="sable_passenger_travel_refusal")
    public static void actualTravelHookRefusalPreservesOriginalPassenger(GameTestHelper helper) { new Session(helper, Mode.TRAVEL_REFUSAL).start(); }

    private enum Mode { ROUND_TRIP, COMPENSATING_RETURN, TRAVEL_REFUSAL }
    private record WorldTicket(ServerLevel level, ChunkPos chunk) {}
    private record PassengerState(CompoundTag nbt, Vec3 local) {}

    private static final class Session {
        final GameTestHelper helper;
        final Mode mode;
        final UUID logicalShip = UUID.randomUUID();
        final ArrayList<ServerSubLevel> bodies = new ArrayList<>();
        final LinkedHashSet<ServerSubLevel> releasedNativeTickets = new LinkedHashSet<>();
        final ArrayList<Villager> entityReferences = new ArrayList<>();
        final ArrayList<WorldTicket> worldTickets = new ArrayList<>();
        final Map<ServerLevel, Boolean> originalPause = new LinkedHashMap<>();
        ServerLevel overworld, end;
        ServerSubLevel first;
        Villager current;
        UUID passengerId;
        PassengerState baseline;
        int slotX, slotZ;
        boolean stopped;
        boolean travelListening;
        Consumer<EntityTravelToDimensionEvent> travelListener;
        boolean joinListening;
        Consumer<EntityJoinLevelEvent> joinListener;
        String phase = "setup";

        Session(GameTestHelper helper, Mode mode) { this.helper=helper; this.mode=mode; }

        void start() { guarded(() -> {
            var server=helper.getLevel().getServer();
            overworld=server.getLevel(Level.OVERWORLD); end=server.getLevel(Level.END);
            require(overworld!=null && end!=null, "Missing real Overworld/End");
            require(container(overworld).getAllSubLevels().isEmpty() && container(end).getAllSubLevels().isEmpty(),
                    "Probe needs two isolated empty native scenes");
            require(container(overworld).getOrigin().equals(container(end).getOrigin())
                    && container(overworld).getLogPlotSize()==container(end).getLogPlotSize(), "Plot layouts differ");
            for (var level : List.of(overworld,end)) originalPause.put(level,container(level).physicsSystem().getPaused());
            first=createFirst();
            var metadata=first.getPlot().save(); slotX=metadata.getInt("plot_x"); slotZ=metadata.getInt("plot_z");
            require(container(end).getSubLevel(slotX,slotZ)==null,"Target source slot occupied");
            ready(first,0,() -> {
                hold(first);
                var local=deckCenter(first).add(0,.25,0);
                Vec3 world=first.logicalPose().transformPosition(local);
                current=EntityType.VILLAGER.create(overworld);
                require(current!=null,"Vanilla villager factory failed");
                entityReferences.add(current); passengerId=current.getUUID();
                current.setNoAi(false); current.setPersistenceRequired(); current.setInvulnerable(true);
                current.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.0);
                current.setHealth(17.0F); current.setCustomName(Component.literal("Universe passenger "+passengerId));
                current.setCustomNameVisible(true); current.getPersistentData().putUUID(OWNER,logicalShip);
                current.getInventory().setItem(0,new ItemStack(Items.WHEAT,13));
                // Vanilla SimpleContainer.fromTag uses addItem, so this first witness uses contiguous slots.
                current.getInventory().setItem(1,new ItemStack(Items.EMERALD,5));
                current.moveTo(world.x,world.y,world.z,25.0F,0.0F);
                current.setDeltaMovement(Vec3.ZERO);
                require(overworld.addFreshEntity(current),"Real mob insertion failed");
                // No tracking setter, push/pop, manual entity tick, or fabricated collision.
                standing(current,first,0,0,() -> {
                    first.getUserDataTag().putUUID("passenger_uuid",passengerId);
                    baseline=new PassengerState(current.saveWithoutId(new CompoundTag()).copy(),
                            first.logicalPose().transformPositionInverse(current.position()));
                    require(baseline.nbt.hasUUID("UUID") && baseline.nbt.contains("Inventory"),"Actual native mob NBT missing");
                    leg(first,end);
                });
            });
        }); }

        ServerSubLevel createFirst() {
            container(overworld).physicsSystem().setPaused(false);
            Pose3d pose=new Pose3d(); pose.position().set(64,160,64); pose.orientation().rotationY(.3);
            var body=(ServerSubLevel)container(overworld).allocateNewSubLevel(pose); own(body);
            var chunk=body.getPlot().getCenterChunk(); body.getPlot().newEmptyChunk(chunk);
            BlockPos origin=origin(body);
            for(int x=0;x<5;x++) for(int z=0;z<5;z++)
                overworld.setBlock(origin.offset(x,0,z),Blocks.STONE.defaultBlockState(),Block.UPDATE_ALL);
            overworld.setBlock(chestPos(body),Blocks.CHEST.defaultBlockState(),Block.UPDATE_ALL);
            var chest=chest(body); chest.setItem(0,new ItemStack(Items.DIAMOND,7));
            chest.setItem(8,new ItemStack(Items.IRON_INGOT,19)); chest.setChanged();
            body.logicalPose().rotationPoint().set(origin.getX()+2.5,64,origin.getZ()+2.5);
            body.setName("Universe single standing passenger probe");
            var tag=new CompoundTag(); tag.putUUID("logical_ship",logicalShip); body.setUserDataTag(tag);
            return body;
        }

        void leg(ServerSubLevel source, ServerLevel destination) {
            phase=source.getLevel()==overworld?"outward":"return";
            verifyMob(current,source); verifyBody(source);
            var target=stage(source,destination);
            ready(target,0,() -> {
                hold(target); verifyBody(source); verifyBody(target);
                require(!source.isRemoved(),"Source ship removed before passenger move");
                worldReady(current,source,target,0,() -> {
                Villager old=current;
                Vec3 local=source.logicalPose().transformPositionInverse(old.position());
                Vec3 targetWorld=target.logicalPose().transformPosition(local);
                if(mode==Mode.TRAVEL_REFUSAL) {
                    refuseActualTravel(old,source,target,local,targetWorld);
                    return;
                }
                current=move(old,source,target,local,targetWorld);
                require(!source.isRemoved(),"Source ship removed by mob transfer");
                standing(current,target,0,0,() -> {
                    verifyMob(current,target); verifyBody(source); verifyBody(target);
                    require(!source.isRemoved(),"Source ship removed before destination passenger verified");
                    if(mode==Mode.COMPENSATING_RETURN) {
                        require(source==first,"Abort candidate must only transfer outward once");
                        phase="compensating-return";
                        worldReady(current,target,first,0,() -> {
                        Vec3 rollbackWorld=first.logicalPose().transformPosition(baseline.local);
                        current=move(current,target,first,baseline.local,rollbackWorld);
                        standing(current,first,0,0,() -> {
                            verifyMob(current,first); verifyBody(first); verifyBody(target);
                            require(container(overworld).getSubLevel(first.getUniqueId())==first && !first.isRemoved(),
                                    "Compensation destroyed the original source ship");
                            removeBody(target);
                            waitForRemoval(target,0,() -> { verifyMob(current,first);verifyBody(first);finish(); });
                        });
                        });
                        return;
                    }
                    removeBody(source);
                    require(container(source.getLevel()).getSubLevel(source.getUniqueId())==null,
                            "Committed source ship still registered");
                    // Public draining completes registration removal, but the ordinary chunk/BE
                    // lifecycle must get a real tick before the same storage slot is allocated again.
                    waitForRemoval(source,0,() -> {
                        verifyMob(current,target);verifyBody(target);
                        if(destination==end) leg(target,overworld);
                        else finish();
                    });
                });
                });
            });
        }

        Villager move(Villager old, ServerSubLevel source, ServerSubLevel target, Vec3 local, Vec3 destination) {
            DimensionTransition transition=preflight(old,source,target,local,destination);
            Vec3 speed=transition.speed(); float yaw=transition.yRot(),pitch=transition.xRot();
            Entity moved=invokeOwnedDimensionChange(old,target,transition);
            if(moved instanceof Villager villager && villager.getClass()==Villager.class
                    && villager.getType()==EntityType.VILLAGER && villager.getUUID().equals(passengerId)
                    && villager.getPersistentData().hasUUID(OWNER)
                    && villager.getPersistentData().getUUID(OWNER).equals(logicalShip)
                    && !entityReferences.contains(villager)) entityReferences.add(villager);
            require(moved instanceof Villager && moved!=old,"Vanilla mob move refused or reused source object");
            var result=(Villager)moved;
            require(old.isRemoved() && old.getRemovalReason()==Entity.RemovalReason.CHANGED_DIMENSION,
                    "Old mob was not removed by ordinary dimension lifecycle");
            require(source.getLevel().getEntity(passengerId)==null,"Old level still registers passenger UUID");
            require(result.getUUID().equals(passengerId) && result.level()==target.getLevel()
                    && target.getLevel().getEntity(passengerId)==result,"Destination registration/UUID mismatch");
            require(result.position().distanceTo(destination)<1e-5
                    && result.getDeltaMovement().distanceTo(speed)<1e-6,"Immediate public transition pose/speed changed");
            require(Math.abs(Mth.wrapDegrees(result.getYRot()-yaw))<1e-4 && Math.abs(result.getXRot()-pitch)<1e-4,
                    "Immediate ordinary mob yaw/pitch changed");
            require(target.logicalPose().transformPositionInverse(result.position()).distanceTo(local)<1e-5,
                    "Full-pose local/global mapping mismatch");
            state(result); oneLiveOwner(result);
            System.out.println("SABLE_PASSENGER phase="+phase+" uuid="+passengerId+" logicalShip="+logicalShip
                    +" targetShip="+target.getUniqueId()+" global="+result.position()+" local="+local
                    +" sourceShipAlive="+!source.isRemoved()+" oldEntityRemoved="+old.isRemoved());
            return result;
        }

        Entity invokeOwnedDimensionChange(Villager old,ServerSubLevel target,DimensionTransition transition) {
            require(!joinListening && joinListener==null,"Another temporary join observer is already owned");
            joinListener=event -> {
                var candidate=event.getEntity();
                if(event.getLevel()!=target.getLevel() || candidate==old || candidate.getClass()!=Villager.class
                        || candidate.getType()!=EntityType.VILLAGER || !candidate.getUUID().equals(passengerId)
                        || candidate.level()!=target.getLevel() || !candidate.getPersistentData().hasUUID(OWNER)
                        || !candidate.getPersistentData().getUUID(OWNER).equals(logicalShip)) return;
                require(helper.getLevel().getServer().isSameThread(),"Owned target join observer ran on another thread");
                var owned=(Villager)candidate;
                // Observe the exact live fixture reference before registration, even if another
                // listener cancelled joining. Do not cancel, mutate, clone, or refill any entity.
                if(!entityReferences.contains(owned)) entityReferences.add(owned);
            };
            joinListening=true;
            try {
                NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,true,EntityJoinLevelEvent.class,joinListener);
                return old.changeDimension(transition);
            } finally {removeJoinListener();}
        }

        void removeJoinListener() {
            if(joinListening && joinListener!=null) {
                NeoForge.EVENT_BUS.unregister(joinListener);
                joinListening=false;joinListener=null;
            }
        }

        DimensionTransition preflight(Villager old,ServerSubLevel source,ServerSubLevel target,Vec3 local,Vec3 destination) {
            var server=helper.getLevel().getServer();
            require(server.isSameThread() && !server.isStopped(),"Inactive/wrong-thread owning server");
            require(source!=target && source.getLevel()!=target.getLevel()
                    && source.getLevel().getServer()==server && target.getLevel().getServer()==server
                    && server.getLevel(source.getLevel().dimension())==source.getLevel()
                    && server.getLevel(target.getLevel().dimension())==target.getLevel(),"Source/target server-level identity mismatch");
            require(bodies.contains(source) && bodies.contains(target),"Foreign source/target native body");
            require(old==current && old.getClass()==Villager.class && old.getType()==EntityType.VILLAGER,
                    "Only the exact owned ordinary vanilla Villager is whitelisted");
            require(old.getUUID().equals(passengerId) && old.isAlive() && !old.isDeadOrDying() && !old.isRemoved()
                    && old.level()==source.getLevel() && source.getLevel().getEntity(passengerId)==old,
                    "Source mob alive/current registration preflight failed");
            require(old.canChangeDimensions(source.getLevel(),target.getLevel()),"Mob disallows this dimension pair");
            verifyBody(source); verifyBody(target);
            for(var body:List.of(source,target)) {
                var c=container(body.getLevel());var h=RigidBodyHandle.of(body);
                var p=body.logicalPose().position();
                require(Math.abs(p.x())<=MAX_HORIZONTAL_WITNESS && Math.abs(p.z())<=MAX_HORIZONTAL_WITNESS,
                        "Native body left the bounded calibration volume");
                require(c.getAllSubLevels().size()==1 && c.getSubLevel(body.getUniqueId())==body && c.physicsSystem().getPaused(),
                        "Native scene changed after staging/hold");
                require(h!=null && h.isValid() && h.getLinearVelocity(new Vector3d()).length()<1e-6
                        && h.getAngularVelocity(new Vector3d()).length()<1e-6,"Native stationary handle gate failed");
            }
            verifyMob(old,source);
            require(target.getLevel().getEntity(passengerId)==null,"Destination already contains passenger UUID");
            require(!old.isPassenger() && old.getPassengers().isEmpty() && !old.isLeashed()
                    && old.getTradingPlayer()==null && !old.isSleeping(),"Unsupported mob relationship");
            require(old.getPersistentData().hasUUID(OWNER) && old.getPersistentData().getUUID(OWNER).equals(logicalShip),
                    "No explicit fixture passenger ownership");
            target.getLevel().getChunkAt(BlockPos.containing(destination));
            require(target.getLevel().isLoaded(BlockPos.containing(destination)),"Destination world volume is not loaded");
            require(worldVolumeReady(target.getLevel(),old,destination),"Destination world entity visibility/ticking is not ready");
            require(source.getLevel().areEntitiesLoaded(ChunkPos.asLong(old.blockPosition()))
                    && source.getLevel().isPositionEntityTicking(old.blockPosition()),"Source world entity ticking readiness was lost");
            require(target.getLevel().noCollision(old,old.getBoundingBox().move(destination.subtract(old.position()))),
                    "Destination ordinary-world collision volume is blocked");
            Vec3 speed=target.logicalPose().transformNormal(source.logicalPose().transformNormalInverse(old.getDeltaMovement()));
            Vec3 look=target.logicalPose().transformNormal(source.logicalPose().transformNormalInverse(old.getLookAngle()));
            float yaw=(float)Math.toDegrees(Math.atan2(-look.x,look.z));
            float pitch=old.getXRot();
            require(Double.isFinite(local.x+local.y+local.z+destination.x+destination.y+destination.z+speed.x+speed.y+speed.z)
                    && Float.isFinite(yaw) && Float.isFinite(pitch),"Nonfinite fresh transition capture");
            require(source.logicalPose().transformPositionInverse(old.position()).distanceTo(local)<=LOCAL_TOLERANCE,
                    "Local anchor does not match fresh source mob capture");
            return new DimensionTransition(target.getLevel(),destination,speed,yaw,pitch,DimensionTransition.DO_NOTHING);
        }

        void refuseActualTravel(Villager old,ServerSubLevel source,ServerSubLevel target,Vec3 local,Vec3 destination) {
            require(source==first && source.getLevel()==overworld && target.getLevel()==end,"Unexpected refusal fixture pair");
            phase="actual-travel-hook-refusal";
            // The exact same whitelist/native/vanilla preflight is used before the real refused API call.
            DimensionTransition transition=preflight(old,source,target,local,destination);
            CompoundTag before=old.saveWithoutId(new CompoundTag()).copy();
            CompoundTag beforeChest=chest(source).saveWithFullMetadata(source.getLevel().registryAccess()).copy();
            Vec3 beforePosition=old.position(),beforeSpeed=old.getDeltaMovement();
            var beforeShipPose=new Pose3d(source.logicalPose());
            int[] receipts={0};boolean[] alreadyCancelled={false},cancelRecorded={false};
            require(!travelListening && travelListener==null,"Another temporary listener is already owned");
            travelListener=event -> {
                if(event.getEntity()!=old || !event.getEntity().getUUID().equals(passengerId)
                        || !event.getDimension().equals(Level.END)) return;
                receipts[0]++;alreadyCancelled[0]|=event.isCanceled();
                event.setCanceled(true);cancelRecorded[0]|=event.isCanceled();
            };
            Entity returned;
            travelListening=true;
            try {
                NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,true,EntityTravelToDimensionEvent.class,travelListener);
                returned=invokeOwnedDimensionChange(old,target,transition);
            } finally { removeTravelListener(); }
            // Never clone/refill/revive the source to manufacture refusal preservation.
            require(receipts[0]==1 && !alreadyCancelled[0] && cancelRecorded[0],"Real uncancelled owned travel callback was not cancelled exactly once");
            require(returned==null,"Cancelled vanilla changeDimension did not return null");
            require(current==old && old.isAlive() && !old.isRemoved() && old.getRemovalReason()==null
                    && old.level()==overworld && overworld.getEntity(passengerId)==old,"Refusal changed the original live source object");
            require(end.getEntity(passengerId)==null,"Refusal inserted a target mob");
            require(before.equals(old.saveWithoutId(new CompoundTag())) && old.position().equals(beforePosition)
                    && old.getDeltaMovement().equals(beforeSpeed),"Synchronous refusal changed full source NBT/pose/speed");
            require(source.logicalPose().position().equals(beforeShipPose.position())
                    && source.logicalPose().orientation().equals(beforeShipPose.orientation())
                    && source.logicalPose().rotationPoint().equals(beforeShipPose.rotationPoint()),"Refusal changed native carrier pose");
            require(beforeChest.equals(chest(source).saveWithFullMetadata(source.getLevel().registryAccess())),"Refusal changed native source chest");
            verifyMob(old,source);verifyBody(source);verifyBody(target);oneLiveOwner(old);
            require(!travelListening && travelListener==null && !joinListening && joinListener==null,
                    "Temporary travel/join listeners were not released");
            standing(old,source,0,0,() -> {
                require(receipts[0]==1 && !travelListening && travelListener==null && !joinListening && joinListener==null,
                        "Refusal callback repeated or listener ownership leaked");
                require(current==old && overworld.getEntity(passengerId)==old && end.getEntity(passengerId)==null,
                        "Delayed refusal changed original registration/target absence");
                verifyMob(old,source);verifyBody(source);verifyBody(target);oneLiveOwner(old);
                require(!first.isRemoved() && container(overworld).getSubLevel(first.getUniqueId())==first,
                        "Refusal removed the original source native carrier");
                System.out.println("SABLE_PASSENGER_REFUSAL callbacks="+receipts[0]+" uuid="+passengerId
                        +" sourceSameObject=true targetMobAbsent=true originalCarrierAlive=true");
                removeBody(target);
                waitForRemoval(target,0,() -> {verifyMob(old,source);verifyBody(source);finish();});
            });
        }

        void removeTravelListener() {
            if(travelListening && travelListener!=null) {
                NeoForge.EVENT_BUS.unregister(travelListener);
                travelListening=false;travelListener=null;
            }
        }

        ServerSubLevel stage(ServerSubLevel source,ServerLevel destination) {
            require(container(destination).getSubLevel(slotX,slotZ)==null,"Matching destination plot occupied");
            require(container(destination).getAllSubLevels().isEmpty(),"Destination scene contains other bodies");
            CompoundTag snapshot=source.getPlot().save().copy();
            require(snapshot.getInt("data_version")==1 && snapshot.getInt("plot_x")==slotX && snapshot.getInt("plot_z")==slotZ,
                    "Unsupported native plot envelope");
            // Same project-owned section-index envelope adaptation as the narrow block probe; no serializer reimplementation.
            var chunks=snapshot.getCompound("chunks");
            for(String chunkKey:chunks.getAllKeys()) {
                var chunk=chunks.getCompound(chunkKey); var original=chunk.getCompound("sections"); var remapped=new CompoundTag();
                for(String index:original.getAllKeys()) {
                    int worldY=source.getLevel().getSectionYFromSectionIndex(Integer.parseInt(index));
                    int targetIndex=destination.getSectionIndexFromSectionY(worldY);
                    require(targetIndex>=0 && targetIndex<destination.getSectionsCount(),"Section outside target height");
                    remapped.put(Integer.toString(targetIndex),original.getCompound(index).copy());
                }
                chunk.put("sections",remapped); chunk.remove("heightmaps");
            }
            var pose=new Pose3d(source.logicalPose());
            pose.position().set(destination==end?192:64,160,destination==end?192:64);
            pose.orientation().rotationY(destination==end?1.1:.3);
            var target=(ServerSubLevel)container(destination).allocateSubLevel(UUID.randomUUID(),slotX,slotZ,pose);
            own(target); target.getPlot().load(snapshot); target.setName(source.getName());
            target.setUserDataTag(source.getUserDataTag().copy()); verifyBody(target);
            // A paused destination still needs ordinary native initialization steps before its next hold.
            container(destination).physicsSystem().setPaused(false);
            require(destination.getEntity(passengerId)==null,"Native plot load unexpectedly inserted passenger");
            oneLiveOwner(current);
            return target;
        }

        void own(ServerSubLevel body) {
            require(body!=null,"Native allocation failed"); bodies.add(body);
            container(body.getLevel()).addForceLoadTicket(body,SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);
            var position=body.logicalPose().position(); var chunk=new ChunkPos(BlockPos.containing(position.x,position.y,position.z));
            ownWorldTicket(body.getLevel(),chunk);
        }

        void ownWorldTicket(ServerLevel level,ChunkPos chunk) {
            // Capture cleanup ownership before a mutating API that could throw after insertion.
            var ticket=new WorldTicket(level,chunk);
            if(worldTickets.contains(ticket)) return;
            worldTickets.add(ticket);
            level.getChunkSource().addRegionTicket(WORLD_TICKET,chunk,3,logicalShip);
        }

        void worldReady(Villager mob,ServerSubLevel source,ServerSubLevel target,int waited,Runnable continuation) {
            // Native handle readiness is independent of ordinary world-entity section visibility.
            // Wait BEFORE changeDimension removes the source; strict immediate UUID checks remain.
            later(() -> {
                require(mob==current && !source.isRemoved() && !target.isRemoved(),"World readiness lost current/native fixture");
                verifyMob(mob,source);verifyBody(source);verifyBody(target);
                Vec3 destination=target.logicalPose().transformPosition(source.logicalPose().transformPositionInverse(mob.position()));
                var position=BlockPos.containing(destination);var chunk=new ChunkPos(position);
                ownWorldTicket(target.getLevel(),chunk);
                target.getLevel().getChunkAt(position);
                boolean loaded=target.getLevel().areEntitiesLoaded(chunk.toLong());
                boolean ticking=target.getLevel().isPositionEntityTicking(position);
                if(loaded && ticking && worldVolumeReady(target.getLevel(),mob,destination)) {
                    System.out.println("SABLE_PASSENGER_WORLD_READY phase="+phase+" level="+target.getLevel().dimension().location()
                            +" destination="+destination+" waited="+waited+" entitiesLoaded=true entityTicking=true sourceAlive="+mob.isAlive());
                    continuation.run();
                } else {
                    require(waited<WAIT_LIMIT,"Ordinary destination world entity readiness timeout; entitiesLoaded="+loaded+" entityTicking="+ticking);
                    worldReady(mob,source,target,waited+1,continuation);
                }
            });
        }

        boolean worldVolumeReady(ServerLevel level,Villager mob,Vec3 destination) {
            var volume=mob.getBoundingBox().move(destination.subtract(mob.position()));
            var min=BlockPos.containing(volume.minX,volume.minY,volume.minZ);
            var max=BlockPos.containing(Math.nextDown(volume.maxX),Math.nextDown(volume.maxY),Math.nextDown(volume.maxZ));
            var firstChunk=new ChunkPos(min);var lastChunk=new ChunkPos(max);
            require(lastChunk.x-firstChunk.x<=1 && lastChunk.z-firstChunk.z<=1,"Unsupported oversized passenger world volume");
            for(int x=firstChunk.x;x<=lastChunk.x;x++) for(int z=firstChunk.z;z<=lastChunk.z;z++) {
                var chunk=new ChunkPos(x,z);var position=new BlockPos(chunk.getMinBlockX(),min.getY(),chunk.getMinBlockZ());
                if(!level.areEntitiesLoaded(chunk.toLong()) || !level.isPositionEntityTicking(position)) return false;
            }
            return true;
        }

        void ready(ServerSubLevel body,int waited,Runnable continuation) {
            later(() -> {
                require(!body.isRemoved() && container(body.getLevel()).getSubLevel(body.getUniqueId())==body,"Native body disappeared");
                var h=RigidBodyHandle.of(body);
                if(h!=null && h.isValid() && body.getMassTracker().getMass()>0
                        && Double.isFinite(h.getLinearVelocity(new Vector3d()).length())) continuation.run();
                else { require(waited<WAIT_LIMIT,"Native readiness timeout"); ready(body,waited+1,continuation); }
            });
        }

        void hold(ServerSubLevel body) {
            var system=container(body.getLevel()).physicsSystem(); system.setPaused(true);
            var h=RigidBodyHandle.of(body);
            h.addLinearAndAngularVelocity(h.getLinearVelocity(new Vector3d()).negate(),h.getAngularVelocity(new Vector3d()).negate());
            require(h.getLinearVelocity(new Vector3d()).length()<1e-6 && h.getAngularVelocity(new Vector3d()).length()<1e-6,
                    "Native stationary fixture did not reach zero motion");
        }

        void standing(Villager mob,ServerSubLevel ship,int waited,int stable,Runnable continuation) {
            int beforeTick=mob.tickCount;
            later(() -> {
                require(mob.isAlive() && !mob.isRemoved() && mob.level()==ship.getLevel(),"Live passenger unavailable");
                boolean standing=mob.tickCount>beforeTick && mob.onGround() && Sable.HELPER.getTrackingSubLevel(mob)==ship;
                int count=standing?stable+1:0;
                if(count>=STANDING_TICKS) continuation.run();
                else { require(waited<WAIT_LIMIT,"Natural standing/tracking timeout; no raw tracking setter allowed");
                    standing(mob,ship,waited+1,count,continuation); }
            });
        }

        void verifyMob(Villager mob,ServerSubLevel ship) {
            require(mob.isAlive() && !mob.isRemoved() && mob.level()==ship.getLevel()
                    && ship.getLevel().getEntity(passengerId)==mob,"Passenger is not the live registered object");
            require(mob.onGround() && Sable.HELPER.getTrackingSubLevel(mob)==ship,"Passenger did not acquire actual native deck tracking");
            if(baseline!=null) {
                require(ship.logicalPose().transformPositionInverse(mob.position()).distanceTo(baseline.local)<=LOCAL_TOLERANCE,
                        "Standing local pose was not retained");
                state(mob);
            }
            oneLiveOwner(mob);
        }

        void state(Villager mob) {
            require(!mob.isNoAi() && !mob.isNoGravity() && Math.abs(mob.getHealth()-17.0F)<1e-5,"Ordinary living fixture state changed");
            require(mob.getAttribute(Attributes.MOVEMENT_SPEED)!=null
                    && mob.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue()==0.0,"Fixture movement-speed base changed");
            require(mob.getPersistentData().hasUUID(OWNER) && mob.getPersistentData().getUUID(OWNER).equals(logicalShip),"Owner NBT lost");
            var inventory=mob.getInventory(); require(inventory.getContainerSize()==8,"Unexpected vanilla inventory");
            for(int slot=0;slot<8;slot++) {
                var item=inventory.getItem(slot);
                require(slot==0?item.is(Items.WHEAT)&&item.getCount()==13:
                        slot==1?item.is(Items.EMERALD)&&item.getCount()==5:item.isEmpty(),"Actual mob inventory changed at slot "+slot);
            }
            if(baseline!=null) {
                var now=mob.saveWithoutId(new CompoundTag());
                for(String key:NBT_KEYS) require(java.util.Objects.equals(baseline.nbt.get(key),now.get(key)),"Persisted mob field changed: "+key);
            }
        }

        void oneLiveOwner(Villager expected) {
            int matches=0, inspected=0;
            for(var level:helper.getLevel().getServer().getAllLevels()) for(var entity:level.getAllEntities()) {
                require(++inspected<=4096,"Probe refuses oversized live entity scan");
                if(!entity.isRemoved() && entity.getUUID().equals(passengerId)) {
                    require(entity==expected && entity instanceof Villager,"Duplicate live passenger/inventory owner"); matches++;
                }
            }
            require(matches==1,"Passenger UUID live count must be exactly one");
        }

        void verifyBody(ServerSubLevel body) {
            require(!body.isRemoved() && container(body.getLevel()).getSubLevel(body.getUniqueId())==body,"Ship lost registration");
            require(body.getUserDataTag()!=null && body.getUserDataTag().hasUUID("logical_ship")
                    && body.getUserDataTag().getUUID("logical_ship").equals(logicalShip),"Ship ownership lost");
            require(body.getPlot().getContraptions().isEmpty() && body.getPlot().getLoadedChunks().size()==1,"Unsupported native plot");
            int count=0;
            for(var holder:body.getPlot().getLoadedChunks()) {
                require(holder.getChunk().getBlockEntities().size()==1,"Extra BE or missing chest");
                for(var section:holder.getChunk().getSections()) if(!section.hasOnlyAir())
                    for(int x=0;x<16;x++) for(int y=0;y<16;y++) for(int z=0;z<16;z++) {
                        var block=section.getBlockState(x,y,z); if(!block.isAir()) {
                            require(block.is(Blocks.STONE)||block.is(Blocks.CHEST),"Unsupported block"); count++;
                        }
                    }
            }
            require(count==26,"Native block count changed");
            for(int x=0;x<5;x++) for(int z=0;z<5;z++) require(body.getLevel().getBlockState(origin(body).offset(x,0,z))
                    .equals(Blocks.STONE.defaultBlockState()),"Native deck coordinate/state changed");
            require(body.getLevel().getBlockState(chestPos(body)).equals(Blocks.CHEST.defaultBlockState()),
                    "Native chest coordinate/state changed");
            var chest=chest(body);
            for(int slot=0;slot<chest.getContainerSize();slot++) {
                var item=chest.getItem(slot);
                require(slot==0?item.is(Items.DIAMOND)&&item.getCount()==7:
                        slot==8?item.is(Items.IRON_INGOT)&&item.getCount()==19:item.isEmpty(),"Ship chest inventory changed");
            }
            if(passengerId!=null && baseline!=null) require(body.getUserDataTag().hasUUID("passenger_uuid")
                    && body.getUserDataTag().getUUID("passenger_uuid").equals(passengerId),"Ship passenger binding changed");
        }

        void removeBody(ServerSubLevel body) {
            var c=container(body.getLevel());
            RuntimeException failure=null;
            try { requestBodyRemoval(body); }
            catch(RuntimeException e) { failure=collect(failure,e); }
            // Paused dimensions will not consume this queue in their solver step.
            try { c.processSubLevelRemovals(); }
            catch(RuntimeException e) { failure=collect(failure,e); }
            try { forgetReleasedBody(body); }
            catch(RuntimeException e) { failure=collect(failure,e); }
            if(failure!=null) throw failure;
            require(c.getSubLevel(body.getUniqueId())==null,"Owned body removal incomplete");
        }

        void requestBodyRemoval(ServerSubLevel body) {
            var c=container(body.getLevel()); RuntimeException failure=null;
            if(!releasedNativeTickets.contains(body)) try {
                c.removeForceLoadTicket(body,SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);
                releasedNativeTickets.add(body);
            } catch(RuntimeException e) {failure=collect(failure,e);}
            try { if(c.getSubLevel(body.getUniqueId())==body) c.removeSubLevel(body,SubLevelRemovalReason.REMOVED); }
            catch(RuntimeException e) {failure=collect(failure,e);}
            if(failure!=null) throw failure;
        }

        void forgetReleasedBody(ServerSubLevel body) {
            if(releasedNativeTickets.contains(body) && container(body.getLevel()).getSubLevel(body.getUniqueId())==null) {
                bodies.remove(body);releasedNativeTickets.remove(body);
            }
        }

        void waitForRemoval(ServerSubLevel body,int waited,Runnable continuation) {
            // Always yield once, even when the explicit drain already removed the UUID.
            later(() -> {
                var c=container(body.getLevel());c.processSubLevelRemovals();
                if(c.getSubLevel(body.getUniqueId())==null && c.getSubLevel(slotX,slotZ)==null) continuation.run();
                else {require(waited<20,"Native UUID/slot removal barrier timed out");waitForRemoval(body,waited+1,continuation);}
            });
        }

        void finish() { cleanup(); helper.succeed(); }
        void later(Runnable action) { helper.runAfterDelay(1,() -> guarded(action)); }
        void guarded(Runnable action) {
            if(stopped) return;
            try {
                require(helper.getLevel().getServer().isSameThread(),"Wrong server thread"); action.run();
            } catch(Exception failure) {
                System.out.println("SABLE_PASSENGER_FAILED phase="+phase+" error="+failure);
                try { cleanup(); } catch(Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                helper.fail("Candidate standing passenger probe failed at "+phase+": "+failure);
            }
        }

        void cleanup() {
            stopped=true; RuntimeException failure=null;
            try { removeTravelListener(); } catch(RuntimeException e) {failure=collect(failure,e);}
            try { removeJoinListener(); } catch(RuntimeException e) {failure=collect(failure,e);}
            // changeDimension can throw after target registration, before a returned reference is
            // available to move(). Reconcile actual UUID owners; never clone/refill/revive them.
            if(passengerId!=null) for(var level:helper.getLevel().getServer().getAllLevels()) try {
                var registered=level.getEntity(passengerId);
                if(registered!=null && !registered.isRemoved()) {
                    require(registered.getClass()==Villager.class && registered.getType()==EntityType.VILLAGER
                            && registered.getUUID().equals(passengerId) && registered.level()==level
                            && registered.getPersistentData().hasUUID(OWNER)
                            && registered.getPersistentData().getUUID(OWNER).equals(logicalShip),
                            "Cleanup refuses a foreign UUID registration in "+level.dimension().location());
                    var owned=(Villager)registered;
                    if(!entityReferences.contains(owned)) {
                        System.out.println("SABLE_PASSENGER_PARTIAL_REGISTERED phase="+phase+" uuid="+passengerId
                                +" level="+level.dimension().location()+" recoveryClaim=false");
                        entityReferences.add(owned);
                    }
                }
            } catch(RuntimeException e) {failure=collect(failure,e);}
            for(var mob:new ArrayList<>(entityReferences)) try { if(!mob.isRemoved()) mob.discard(); }
                catch(RuntimeException e) {failure=collect(failure,e);}
            var touchedLevels=new LinkedHashSet<ServerLevel>(originalPause.keySet());
            for(var body:new ArrayList<>(bodies)) {
                touchedLevels.add(body.getLevel());
                try { requestBodyRemoval(body); }
                catch(RuntimeException e) {failure=collect(failure,e);}
            }
            // A failed request must not prevent draining the other touched paused dimension.
            for(var level:touchedLevels) try {container(level).processSubLevelRemovals();}
                catch(RuntimeException e) {failure=collect(failure,e);}
            for(var body:new ArrayList<>(bodies)) try {forgetReleasedBody(body);}
                catch(RuntimeException e) {failure=collect(failure,e);}
            for(var ticket:new ArrayList<>(worldTickets)) try {
                ticket.level.getChunkSource().removeRegionTicket(WORLD_TICKET,ticket.chunk,3,logicalShip); worldTickets.remove(ticket);
            } catch(RuntimeException e) {failure=collect(failure,e);}
            for(var entry:originalPause.entrySet()) try { container(entry.getKey()).physicsSystem().setPaused(entry.getValue()); }
                catch(RuntimeException e) {failure=collect(failure,e);}
            if(failure!=null) throw failure;
            require(bodies.isEmpty() && releasedNativeTickets.isEmpty() && worldTickets.isEmpty()
                    && !travelListening && travelListener==null && !joinListening && joinListener==null,"Owned fixture resources leaked");
            if(passengerId!=null) for(var level:helper.getLevel().getServer().getAllLevels())
                require(level.getEntity(passengerId)==null,"Cleanup left a live passenger UUID");
            entityReferences.clear();
        }
    }

    private static RuntimeException collect(RuntimeException a,RuntimeException b) {if(a==null)return b;if(a!=b)a.addSuppressed(b);return a;}
    private static BlockPos origin(ServerSubLevel body) {var c=body.getPlot().getCenterChunk();return new BlockPos(c.getMinBlockX()+3,64,c.getMinBlockZ()+3);}
    private static BlockPos chestPos(ServerSubLevel body) {return origin(body).offset(0,1,0);}
    private static Vec3 deckCenter(ServerSubLevel body) {var p=origin(body);return new Vec3(p.getX()+2.5,65,p.getZ()+2.5);}
    private static ChestBlockEntity chest(ServerSubLevel body) {
        var be=body.getLevel().getBlockEntity(chestPos(body));require(be instanceof ChestBlockEntity,"Native chest missing");return (ChestBlockEntity)be;
    }
    private static ServerSubLevelContainer container(ServerLevel level) {
        ServerSubLevelContainer c=SubLevelContainer.getContainer(level);require(c!=null && c.physicsSystem()!=null,"Native Sable container missing");return c;
    }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
