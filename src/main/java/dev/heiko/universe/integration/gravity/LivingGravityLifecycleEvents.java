package dev.heiko.universe.integration.gravity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Main-game-bus relay. Empty servers incur no entity scan and no attribute mutation. */
@EventBusSubscriber(modid = "universe")
public final class LivingGravityLifecycleEvents {
    private LivingGravityLifecycleEvents() {}

    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity living)
            LivingGravityDomain.leaving(living,event.getLevel());
    }
    @SubscribeEvent public static void travel(EntityTravelToDimensionEvent event) {
        // Fail closed on a requested frame transition, even if a later listener cancels it.
        // Host must explicitly reacquire; this event is not a successful-transfer receipt.
        if (event.getEntity() instanceof LivingEntity living)
            LivingGravityDomain.leaving(living,living.level());
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LivingGravityDomain.unloading(level);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        // Actual death is checked here, after cancellable death callbacks, including entities
        // that stop ticking. A cancelled death with restored health keeps its registration.
        LivingGravityDomain.sweep(event.getServer());
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        LivingGravityDomain.stopping(event.getServer());
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        LivingGravityDomain.stopped(event.getServer());
    }
    // Players cannot register. These are defensive releases, never proof of player support.
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        LivingGravityDomain.leaving(event.getEntity(),event.getEntity().level());
    }
    @SubscribeEvent public static void clone(PlayerEvent.Clone event) {
        LivingGravityDomain.leaving(event.getOriginal(),event.getOriginal().level());
    }
    @SubscribeEvent public static void playerDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        LivingGravityDomain.leaving(event.getEntity(),event.getEntity().level());
    }
}
