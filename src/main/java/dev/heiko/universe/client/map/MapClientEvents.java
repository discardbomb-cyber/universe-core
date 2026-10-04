package dev.heiko.universe.client.map;

import dev.heiko.universe.UniverseMod;
import dev.heiko.universe.network.SectorSnapshotCache;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = UniverseMod.ID, value = Dist.CLIENT)
public final class MapClientEvents {
    private static Object connection;
    private MapClientEvents() {}

    @SubscribeEvent
    public static void afterTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (connection != client.getConnection()) {
            SectorSnapshotCache.clear();
            connection = client.getConnection();
        }
        while (MapKeyBindings.OPEN_MAP.consumeClick()) {
            if (client.player != null && client.screen == null) client.setScreen(new UniverseMapScreen());
        }
    }
}
