package dev.heiko.universe;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(UniverseMod.ID)
public final class UniverseMod {
    public static final String ID = "universe";

    public UniverseMod() {
        if (DevelopmentMode.enabled()) NeoForge.EVENT_BUS.addListener(UniverseCommands::register);
    }
}
