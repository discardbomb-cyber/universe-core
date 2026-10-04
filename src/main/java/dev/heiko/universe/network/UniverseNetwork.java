package dev.heiko.universe.network;

import dev.heiko.universe.UniverseManifest;
import dev.heiko.universe.UniverseMod;
import dev.heiko.universe.core.LazyUniverseCatalog;
import dev.heiko.universe.core.Versions;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Public count-only catalogue endpoint. Handlers use the default MAIN thread. */
@EventBusSubscriber(modid = UniverseMod.ID, bus = EventBusSubscriber.Bus.MOD)
public final class UniverseNetwork {
    private static final WeakHashMap<MinecraftServer, ServerState> STATES = new WeakHashMap<>();
    private UniverseNetwork() {}

    public record SectorRequest(int requestId, String realm, long x, long y, long z)
            implements CustomPacketPayload {
        public static final Type<SectorRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(UniverseMod.ID, "sector_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SectorRequest> CODEC = new StreamCodec<>() {
            public SectorRequest decode(RegistryFriendlyByteBuf buf) {
                return new SectorRequest(buf.readVarInt(), buf.readUtf(32), buf.readLong(), buf.readLong(), buf.readLong());
            }
            public void encode(RegistryFriendlyByteBuf buf, SectorRequest value) {
                buf.writeVarInt(value.requestId()); buf.writeUtf(value.realm(), 32);
                buf.writeLong(value.x()); buf.writeLong(value.y()); buf.writeLong(value.z());
            }
        };
        public Type<SectorRequest> type() { return TYPE; }
    }

    public record SectorSummary(UUID worldId, int requestId, String realm, long x, long y, long z,
                                int schema, int generator, int projection, int systemCount, int bodyCount)
            implements CustomPacketPayload {
        public static final Type<SectorSummary> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(UniverseMod.ID, "sector_summary"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SectorSummary> CODEC = new StreamCodec<>() {
            public SectorSummary decode(RegistryFriendlyByteBuf buf) {
                return new SectorSummary(buf.readUUID(), buf.readVarInt(), buf.readUtf(32),
                    buf.readLong(), buf.readLong(), buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
            }
            public void encode(RegistryFriendlyByteBuf buf, SectorSummary value) {
                buf.writeUUID(value.worldId()); buf.writeVarInt(value.requestId()); buf.writeUtf(value.realm(), 32);
                buf.writeLong(value.x()); buf.writeLong(value.y()); buf.writeLong(value.z());
                buf.writeVarInt(value.schema()); buf.writeVarInt(value.generator()); buf.writeVarInt(value.projection());
                buf.writeVarInt(value.systemCount()); buf.writeVarInt(value.bodyCount());
            }
        };
        public Type<SectorSummary> type() { return TYPE; }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(SectorRequest.TYPE, SectorRequest.CODEC, UniverseNetwork::handleRequest);
        registrar.playToClient(SectorSummary.TYPE, SectorSummary.CODEC, UniverseNetwork::handleSummary);
    }

    /** A map may call this with monotonically increasing nonnegative request IDs. */
    public static void requestSector(int requestId, String realm, long x, long y, long z) {
        if (requestId < 0 || SectorProtocol.key(realm, x, y, z) == null)
            throw new IllegalArgumentException("Invalid sector request");
        PacketDistributor.sendToServer(new SectorRequest(requestId, realm, x, y, z));
    }

    private static void handleRequest(SectorRequest request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        var manifest = UniverseManifest.get(server);
        var state = STATES.computeIfAbsent(server, ignored -> new ServerState(manifest.seed()));
        // Charge invalid requests too. Five accepted requests per second, no burst.
        if (!state.allow(player.getUUID(), System.nanoTime())) return;
        var key = SectorProtocol.key(request.realm(), request.x(), request.y(), request.z());
        if (request.requestId() < 0 || key == null) return;
        var sector = state.catalog.sector(key);
        int bodies = sector.systems().stream().mapToInt(system -> system.bodies().size()).sum();
        var version = sector.versions();
        PacketDistributor.sendToPlayer(player, new SectorSummary(manifest.worldId(), request.requestId(),
            key.realm().id(), key.x(), key.y(), key.z(), version.schema(), version.generator(),
            version.projection(), sector.systems().size(), bodies));
    }

    private static void handleSummary(SectorSummary summary, IPayloadContext context) {
        var key = SectorProtocol.key(summary.realm(), summary.x(), summary.y(), summary.z());
        if (key == null || summary.requestId() < 0 || summary.systemCount() < 0 || summary.systemCount() > 8
                || summary.bodyCount() < summary.systemCount() || summary.bodyCount() > summary.systemCount() * 63)
            return;
        if (summary.schema() != Versions.CURRENT.schema() || summary.generator() != Versions.CURRENT.generator()
                || summary.projection() != Versions.CURRENT.projection()) return;
        SectorSnapshotCache.accept(new SectorSnapshotCache.Snapshot(summary.worldId(), summary.requestId(),
            key, Versions.CURRENT, summary.systemCount(), summary.bodyCount()));
    }

    private static final class ServerState {
        final LazyUniverseCatalog catalog;
        final LinkedHashMap<UUID, Long> nextRequest = new LinkedHashMap<>();
        long windowStarted;
        int windowRequests;
        ServerState(long seed) { catalog = new LazyUniverseCatalog(seed, Versions.CURRENT, 256); }
        boolean allow(UUID player, long now) {
            if (now - windowStarted >= 1_000_000_000L || windowStarted == 0) {
                windowStarted = now; windowRequests = 0;
            }
            if (++windowRequests > 100) return false;
            Long deadline = nextRequest.get(player);
            if (deadline != null && now - deadline < 0) return false;
            nextRequest.put(player, now + 200_000_000L);
            // Keep rate entries bounded, including players who have disconnected.
            if (nextRequest.size() > 4096) nextRequest.remove(nextRequest.keySet().iterator().next());
            return true;
        }
    }
}

