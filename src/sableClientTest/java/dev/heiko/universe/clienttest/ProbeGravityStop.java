package dev.heiko.universe.clienttest;

import dev.heiko.universe.api.gravity.GravitySample;
import dev.heiko.universe.integration.gravity.LivingGravityAdapter;
import dev.heiko.universe.integration.gravity.LivingGravityDomain;
import dev.heiko.universe.ships.Vec3;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Audits real core lifecycle cleanup. Never closes the domain before observing the relay. */
@EventBusSubscriber(modid = ClientProbeMod.ID)
public final class ProbeGravityStop {
    private static final boolean ENABLED = Boolean.getBoolean("universe.clientProbe.gravityStop");
    private static final String FRAME = "universe_client_probe:fixed_world_y";
    private static final Map<String, Object> AUDIT = new LinkedHashMap<>();
    private static MinecraftServer owner;
    private static LivingGravityDomain domain;
    private static LivingGravityDomain.Registration token;
    private static Pig pig;
    private static AttributeModifier ownedModifier;
    private static boolean beforeStoppingOwned;
    private static boolean chunkOwned;
    private static boolean setupApplied, stoppingPassed, ownershipLost;
    private static int observedTicks;

    private ProbeGravityStop() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void start(ServerStartedEvent event) {
        if (!ClientProbeMod.ENABLED || !ENABLED || !event.getServer().isDedicatedServer()) return;
        owner = event.getServer();
        try {
            if (!"127.0.0.1".equals(owner.getLocalIp()) || owner.usesAuthentication())
                throw new IllegalStateException("Requires disposable offline loopback server");
            ClientProbeMod.claim("server");
            AUDIT.put("serverStartedAtMillis", System.currentTimeMillis());
            var level = owner.overworld();
            if (level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(0, 0)))
                throw new IllegalStateException("Gravity fixture chunk already has a force-load owner");
            chunkOwned = true;
            level.setChunkForced(0, 0, true);
            level.getChunk(0, 0);
            pig = EntityType.PIG.create(level);
            if (pig == null) throw new IllegalStateException("Pig allocation failed");
            pig.setNoAi(true); // Intentional: no movement/trajectory claim in this shutdown audit.
            pig.setPersistenceRequired();
            pig.setInvulnerable(true);
            pig.moveTo(4, 82, 4, 0, 0);
            if (!level.addFreshEntity(pig)) throw new IllegalStateException("Pig insertion failed");
            domain = LivingGravityDomain.open(owner, "universe_client_probe:stop_" + ClientProbeMod.RUNTIME_NONCE, 1);
            var attempt = domain.register(pig,
                    new GravitySample("universe_client_probe:stop_field", 0, FRAME, new Vec3(0, -16, 0)), FRAME);
            token = attempt.registration();
            setupApplied = attempt.applied() && token != null && token.isActive()
                    && domain.activeCount() == 1 && LivingGravityDomain.activeCount(owner) == 1
                    && pig.getAttribute(Attributes.GRAVITY).hasModifier(LivingGravityAdapter.MODIFIER_ID);
            AUDIT.put("registrationResult", attempt.result().toString());
            AUDIT.put("setupApplied", setupApplied);
            AUDIT.put("pigUuid", pig.getUUID().toString());
            AUDIT.put("pigNoAI", pig.isNoAi());
            if (!setupApplied) throw new IllegalStateException("Actual gravity registration did not acquire one entry");
            ownedModifier = pig.getAttribute(Attributes.GRAVITY).getModifier(LivingGravityAdapter.MODIFIER_ID);
            write("RUNNING");
        } catch (Exception error) {
            AUDIT.put("setupError", error.toString());
            write("FAILED_SETUP");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void live(ServerTickEvent.Pre event) {
        if (event.getServer() != owner || !setupApplied) return;
        try {
            boolean active = token.isActive() && !domain.isClosed() && domain.activeCount() == 1
                    && LivingGravityDomain.activeCount(owner) == 1 && pig.isAlive() && !pig.isRemoved()
                    && pig.getAttribute(Attributes.GRAVITY).hasModifier(LivingGravityAdapter.MODIFIER_ID);
            if (!active && !ownershipLost) {
                AUDIT.put("firstLostAtMillis", System.currentTimeMillis());
                AUDIT.put("firstLostRemoved", pig.isRemoved());
                AUDIT.put("firstLostRemovalReason", String.valueOf(pig.getRemovalReason()));
                AUDIT.put("firstLostAlive", pig.isAlive());
                AUDIT.put("firstLostServerStopped", owner.isStopped());
                AUDIT.put("firstLostDomainClosed", domain.isClosed());
                AUDIT.put("firstLostDomainEntries", domain.activeCount());
                AUDIT.put("firstLostScopeEntries", LivingGravityDomain.activeCount(owner));
                AUDIT.put("firstLostModifier", String.valueOf(pig.getAttribute(Attributes.GRAVITY)
                        .getModifier(LivingGravityAdapter.MODIFIER_ID)));
                AUDIT.put("firstLostNoGravity", pig.isNoGravity());
                AUDIT.put("firstLostPassenger", pig.isPassenger());
                AUDIT.put("firstLostVehicle", pig.isVehicle());
                AUDIT.put("firstLostWater", pig.isInWaterOrBubble());
                write("FAILED_PRESTOP_OWNERSHIP");
            }
            ownershipLost |= !active;
            observedTicks++;
            AUDIT.put("lastLiveTickAtMillis", System.currentTimeMillis());
            AUDIT.put("lastLiveTokenActive", active);
        } catch (Exception error) {
            ownershipLost = true;
            AUDIT.put("liveObservationError", error.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeStopping(ServerStoppingEvent event) {
        if (event.getServer() != owner) return;
        AUDIT.put("beforeStoppingAtMillis", System.currentTimeMillis());
        try {
            beforeStoppingOwned = setupApplied && token != null && token.isActive()
                    && domain != null && !domain.isClosed() && domain.activeCount() == 1
                    && LivingGravityDomain.activeCount(owner) == 1
                    && pig != null && pig.isAlive() && !pig.isRemoved()
                    && pig.getAttribute(Attributes.GRAVITY).getModifier(LivingGravityAdapter.MODIFIER_ID) == ownedModifier;
            AUDIT.put("beforeStoppingOwned", beforeStoppingOwned);
        } catch (Exception error) {
            beforeStoppingOwned = false;
            AUDIT.put("beforeStoppingError", error.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopping(ServerStoppingEvent event) {
        if (event.getServer() != owner) return;
        AUDIT.put("serverStoppingAtMillis", System.currentTimeMillis());
        try {
            int scopeEntries = LivingGravityDomain.activeCount(owner);
            boolean closed = domain != null && domain.isClosed();
            boolean inactive = token != null && !token.isActive();
            boolean modifierAbsent = pig != null && pig.getAttribute(Attributes.GRAVITY) != null
                    && !pig.getAttribute(Attributes.GRAVITY).hasModifier(LivingGravityAdapter.MODIFIER_ID);
            AUDIT.put("stoppingScopeEntries", scopeEntries);
            AUDIT.put("stoppingDomainEntries", domain == null ? -1 : domain.activeCount());
            AUDIT.put("stoppingDomainClosed", closed);
            AUDIT.put("stoppingTokenInactive", inactive);
            AUDIT.put("stoppingModifierAbsent", modifierAbsent);
            AUDIT.put("observedLiveTicks", observedTicks);
            AUDIT.put("ownershipLostBeforeStop", ownershipLost);
            stoppingPassed = setupApplied && beforeStoppingOwned && observedTicks > 0 && !ownershipLost
                    && scopeEntries == 0 && closed && inactive && modifierAbsent && domain.activeCount() == 0;
            AUDIT.put("stoppingAssertionsPassed", stoppingPassed);
            write(stoppingPassed ? "STOPPING_AUDIT_PASSED" : "FAILED_STOPPING_AUDIT");
        } catch (Exception error) {
            AUDIT.put("stoppingError", error.toString());
            write("FAILED_STOPPING_AUDIT");
        } finally {
            if (chunkOwned) {
                owner.overworld().setChunkForced(0, 0, false);
                chunkOwned = false;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopped(ServerStoppedEvent event) {
        if (event.getServer() != owner) return;
        AUDIT.put("serverStoppedAtMillis", System.currentTimeMillis());
        boolean rejected = false;
        try {
            boolean stopped = owner.isStopped();
            AUDIT.put("isStopped", stopped);
            AUDIT.put("stoppedOnServerThread", owner.isSameThread());
            // New unique host avoids a duplicate-host rejection masking a lifecycle bug.
            String host = "universe_client_probe:after_stop_" + java.util.UUID.randomUUID();
            AUDIT.put("attemptedPostStopHost", host);
            LivingGravityDomain unexpected = null;
            try {
                unexpected = LivingGravityDomain.open(owner, host, 1);
            } catch (IllegalStateException expected) {
                rejected = stopped && owner.isSameThread()
                        && "Server gravity lifecycle is stopped".equals(expected.getMessage());
                AUDIT.put("postStopRefusal", expected.getMessage());
            }
            if (unexpected != null) unexpected.close(); // After recording erroneous acceptance, not before the audit.
            AUDIT.put("postStopOpenRejected", rejected);
            AUDIT.put("stoppedScopeEntries", LivingGravityDomain.activeCount(owner));
            boolean passed = stoppingPassed && stopped && owner.isSameThread() && rejected
                    && LivingGravityDomain.activeCount(owner) == 0;
            AUDIT.put("assertionsPassed", passed);
            write(passed ? "PASSED_REAL_SHUTDOWN_AUDIT" : "FAILED_REAL_SHUTDOWN_AUDIT");
        } catch (Exception error) {
            AUDIT.put("stoppedError", error.toString());
            AUDIT.put("assertionsPassed", false);
            write("FAILED_REAL_SHUTDOWN_AUDIT");
        }
        // Preserve references/evidence until the process exits; no synthetic events or stopped mutation.
    }

    private static void write(String status) {
        try {
            AUDIT.put("runId", ClientProbeMod.RUN_ID);
            AUDIT.put("sessionNonce", ClientProbeMod.SESSION_NONCE);
            AUDIT.put("runtimeNonce", ClientProbeMod.RUNTIME_NONCE);
            AUDIT.put("runtimeStartedAtMillis", ClientProbeMod.RUNTIME_STARTED_MILLIS);
            AUDIT.put("writtenAtMillis", System.currentTimeMillis());
            AUDIT.put("status", status);
            AUDIT.put("trajectoryAcceptance", "NOT_EVALUATED_NOAI_FIXTURE");
            ClientProbeMod.atomicJson(ClientProbeMod.claim("server").resolve("gravity-stop.json"), AUDIT, true);
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(ProbeGravityStop.class).error("Cannot write real gravity-stop audit", error);
        }
    }
}
