package dev.heiko.universe.client.map;

import com.mojang.blaze3d.platform.InputConstants;
import dev.heiko.universe.UniverseMod;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = UniverseMod.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MapKeyBindings {
    public static final KeyMapping OPEN_MAP = new KeyMapping("key.universe.map", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.universe");
    private MapKeyBindings() {}

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) { event.register(OPEN_MAP); }
}
