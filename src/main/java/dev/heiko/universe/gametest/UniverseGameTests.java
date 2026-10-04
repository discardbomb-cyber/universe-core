package dev.heiko.universe.gametest;

import dev.heiko.universe.UniverseManifest;
import dev.heiko.universe.persistence.ShipCatalog;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("universe")
@PrefixGameTestTemplate(false)
public final class UniverseGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void dimensionTypesAndServerDataLoad(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        // GameTestServer intentionally bakes a vanilla flat preset, excluding custom LevelStems.
        // Validate parsed dynamic types here; regular-world dimension creation is a separate check.
        var types = server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
        for (String dimension : new String[]{"space", "shipyards", "barren", "temperate"}) {
            var key = ResourceLocation.fromNamespaceAndPath("universe", dimension);
            helper.assertTrue(types.containsKey(key), "Missing dimension type: " + dimension);
        }
        helper.assertTrue(server.registryAccess().registryOrThrow(Registries.BIOME)
            .containsKey(ResourceLocation.fromNamespaceAndPath("universe", "vacuum")), "Missing vacuum biome");
        var manifest = UniverseManifest.get(server);
        helper.assertTrue(manifest.worldId().equals(UniverseManifest.get(server).worldId()), "World identity changed");
        helper.assertTrue(ShipCatalog.get(server) != null, "Ship catalog unavailable");
        helper.succeed();
    }
}
