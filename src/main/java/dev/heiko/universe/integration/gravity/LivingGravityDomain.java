package dev.heiko.universe.integration.gravity;

import dev.heiko.universe.api.ApiIds;
import dev.heiko.universe.api.gravity.GravitySample;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit server/host ownership domain. Closing never restores a stale foreign snapshot.
 * Operations throw before mutation off-thread; adapter validation still supplies typed refusals.
 * Players remain unsupported. A domain never samples or automatically registers an entity.
 */
public final class LivingGravityDomain implements AutoCloseable {
    public static final int MAX_ENTRIES = 1024;
    public static final int MAX_DOMAINS = 128;
    // Map operations are synchronized for independent server threads; each scope is then
    // touched exclusively on its own server thread, never a client/worker callback.
    private static final Map<MinecraftServer, Scope> SCOPES = Collections.synchronizedMap(new IdentityHashMap<>());

    private static final class Scope {
        final Map<String, LivingGravityDomain> domains = new HashMap<>();
        final IdentityHashMap<LivingEntity, Registration> entries = new IdentityHashMap<>();
        boolean stopping;
    }

    private final MinecraftServer server;
    private final String hostId;
    private final int capacity;
    private final Scope scope;
    private final LivingGravityAdapter adapter;
    private final IdentityHashMap<LivingEntity, Registration> entries = new IdentityHashMap<>();
    private boolean closed;

    private LivingGravityDomain(MinecraftServer server, String hostId, int capacity, Scope scope) {
        this.server=server; this.hostId=hostId; this.capacity=capacity; this.scope=scope;
        this.adapter=new LivingGravityAdapter(server);
    }

    public static LivingGravityDomain open(MinecraftServer server, String hostId) {
        return open(server, hostId, MAX_ENTRIES);
    }

    /** A smaller host limit is useful for quotas; the total server limit is always 1024. */
    public static LivingGravityDomain open(MinecraftServer server, String hostId, int capacity) {
        Objects.requireNonNull(server); requireThread(server); ApiIds.require(hostId);
        // Stopped relay removes the scope. A later listener must not recreate it.
        if (server.isStopped()) throw new IllegalStateException("Server gravity lifecycle is stopped");
        if (capacity < 1 || capacity > MAX_ENTRIES) throw new IllegalArgumentException("Gravity capacity 1..1024");
        Scope scope=SCOPES.computeIfAbsent(server, ignored -> new Scope());
        if (scope.stopping) throw new IllegalStateException("Server gravity lifecycle is stopping");
        if (scope.domains.containsKey(hostId)) throw new IllegalStateException("Gravity host already open: " + hostId);
        if (scope.domains.size() >= MAX_DOMAINS) throw new IllegalStateException("Gravity host capacity");
        var domain=new LivingGravityDomain(server,hostId,capacity,scope);
        scope.domains.put(hostId,domain);
        return domain;
    }

    public record Attempt(LivingGravityAdapter.Result result, Registration registration) {
        public boolean applied() { return result == LivingGravityAdapter.Result.APPLIED; }
    }

    /** Acquires once. Reapplying uses the returned token, not another registration. */
    public Attempt register(LivingEntity entity, GravitySample sample, String expectedFrameId) {
        checkOpen();
        if (entity == null) return new Attempt(LivingGravityAdapter.Result.INVALID_ENTITY,null);
        if (scope.entries.containsKey(entity)) return new Attempt(LivingGravityAdapter.Result.MODIFIER_CONFLICT,null);
        if (entries.size() >= capacity || scope.entries.size() >= MAX_ENTRIES)
            return new Attempt(LivingGravityAdapter.Result.CAPACITY_REACHED,null);
        var result=adapter.apply(entity,sample,expectedFrameId);
        if (result != LivingGravityAdapter.Result.APPLIED) return new Attempt(result,null);
        var token=new Registration(entity,(ServerLevel)entity.level(),expectedFrameId);
        entries.put(entity,token); scope.entries.put(entity,token);
        return new Attempt(result,token);
    }

    public int activeCount() { requireThread(server); return entries.size(); }
    public static int activeCount(MinecraftServer server) {
        requireThread(server);
        var scope=SCOPES.get(server);
        return scope==null ? 0 : scope.entries.size();
    }
    public boolean isClosed() { requireThread(server); return closed; }

    private void checkOpen() {
        requireThread(server);
        if (server.isStopped() || closed || scope.stopping) throw new IllegalStateException("Gravity domain closed");
    }
    private static void requireThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Gravity lifecycle requires server thread");
    }

    public final class Registration implements AutoCloseable {
        private final LivingEntity entity;
        private final ServerLevel origin;
        private final String frame;
        private boolean ended;
        private LivingGravityDomain domain() { return LivingGravityDomain.this; }
        private Registration(LivingEntity entity, ServerLevel origin, String frame) {
            this.entity=entity; this.origin=origin; this.frame=frame;
        }
        public boolean isActive() {
            requireThread(server);
            return !server.isStopped() && !ended && entries.get(entity)==this && scope.entries.get(entity)==this;
        }
        /** A changed frame requires closing and acquiring a new token. */
        public LivingGravityAdapter.Result update(GravitySample sample) {
            requireThread(server);
            if (server.isStopped() || !isActive() || closed || scope.stopping) return LivingGravityAdapter.Result.NOT_OWNED;
            if (!contextValid() || !adapter.ownsExact(entity)) {
                end(); return LivingGravityAdapter.Result.NOT_OWNED;
            }
            var result=adapter.apply(entity,sample,frame);
            if (result != LivingGravityAdapter.Result.APPLIED) end();
            return result;
        }
        private boolean contextValid() {
            return !server.isStopped() && entity.level()==origin && origin.getServer()==server
                    && entity.isAlive() && !entity.isRemoved();
        }
        private boolean movementValid() {
            return !entity.isNoGravity() && !entity.isInWaterOrBubble() && !entity.isInLava()
                    && !entity.isFallFlying() && !entity.isPassenger() && !entity.isVehicle()
                    && !entity.hasEffect(MobEffects.LEVITATION) && !entity.hasEffect(MobEffects.SLOW_FALLING);
        }
        private void end() {
            if (ended) return;
            ended=true;
            // Exact token identity prevents a former token from releasing a successor.
            if (entries.get(entity)==this && scope.entries.get(entity)==this) {
                adapter.releaseForLifecycle(entity);
                entries.remove(entity); scope.entries.remove(entity);
            }
        }
        @Override public void close() { requireThread(server); end(); }
    }

    @Override public void close() {
        requireThread(server);
        if (closed) return;
        closed=true;
        for (var token : new ArrayList<>(entries.values())) token.end();
        scope.domains.remove(hostId,this);
    }

    static void leaving(LivingEntity entity, Level oldLevel) {
        if (!(oldLevel instanceof ServerLevel level)) return;
        var server=level.getServer(); requireThread(server);
        var scope=SCOPES.get(server);
        if (scope == null) return;
        var token=scope.entries.get(entity);
        if (token != null && token.origin==level) token.close();
    }
    static void sweep(MinecraftServer server) {
        requireThread(server);
        var scope=SCOPES.get(server);
        if (scope == null) return;
        // Snapshot and work are bounded by MAX_ENTRIES; never enumerate the world's mobs.
        for (var token : new ArrayList<>(scope.entries.values())) {
            if (!token.contextValid() || !token.movementValid()
                    || !token.domain().adapter.ownsExact(token.entity)) token.close();
        }
    }
    static void unloading(ServerLevel level) {
        var server=level.getServer(); requireThread(server);
        var scope=SCOPES.get(server);
        if (scope == null) return;
        for (var token : new ArrayList<>(scope.entries.values()))
            if (token.origin==level) token.close();
    }
    static void stopping(MinecraftServer server) {
        requireThread(server);
        var scope=SCOPES.get(server);
        if (scope == null) return;
        scope.stopping=true;
        for (var domain : new ArrayList<>(scope.domains.values())) domain.close();
    }
    static void stopped(MinecraftServer server) {
        requireThread(server); stopping(server); SCOPES.remove(server);
    }
}
