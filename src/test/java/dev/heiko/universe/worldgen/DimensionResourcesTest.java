package dev.heiko.universe.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DimensionResourcesTest {
    private static final String RESOURCE_ROOT = "data/universe/";
    private static final Set<String> DIMENSIONS = Set.of("space", "shipyards", "barren", "temperate");

    @Test
    void onlyTheFourApprovedDimensionsAreDeclared() throws IOException {
        // Inventory the source directory: checking only known classpath files would miss
        // accidental extra dimensions such as standalone End or Nether worlds.
        Path directory = Path.of("src/main/resources", RESOURCE_ROOT, "dimension");
        assertTrue(Files.isDirectory(directory), "Missing dimension resource directory");
        try (var files = Files.walk(directory)) {
            Set<String> actual = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(directory::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
            Set<String> expected = DIMENSIONS.stream().map(name -> name + ".json")
                    .collect(Collectors.toSet());
            assertEquals(expected, actual, "Only the four approved dimension keys are supported");
        }
    }

    @Test
    void everyDimensionResolvesItsTypeAndUsesValidVerticalBounds() throws IOException {
        for (String name : DIMENSIONS) {
            JsonObject dimension = resource("dimension/" + name + ".json");
            String typeKey = dimension.get("type").getAsString();
            assertEquals("universe:" + name, typeKey, name);
            JsonObject type = resource("dimension_type/" + name + ".json");
            int minY = type.get("min_y").getAsInt();
            int height = type.get("height").getAsInt();
            int logicalHeight = type.get("logical_height").getAsInt();
            assertEquals(0, Math.floorMod(minY, 16), name + " min_y must align to sections");
            assertEquals(0, height % 16, name + " height must align to sections");
            assertTrue(height > 0, name + " height must be positive");
            assertTrue(logicalHeight > 0 && logicalHeight <= height,
                    name + " logical height must fit inside the physical height");
            // Both surface worlds reuse Overworld noise, whose vertical range is -64..319.
            if (name.equals("barren") || name.equals("temperate")) {
                assertEquals(-64, minY, name + " must cover the vanilla noise range");
                assertEquals(384, height, name + " must cover the vanilla noise range");
            }
        }
    }

    @Test
    void spaceAndShipyardsGenerateNoTerrainFeaturesOrStructures() throws IOException {
        for (String name : Set.of("space", "shipyards")) {
            JsonObject generator = resource("dimension/" + name + ".json")
                    .getAsJsonObject("generator");
            assertEquals("minecraft:flat", generator.get("type").getAsString(), name);
            JsonObject settings = generator.getAsJsonObject("settings");
            assertEquals("universe:vacuum", settings.get("biome").getAsString(), name);
            assertFalse(settings.get("features").getAsBoolean(), name);
            assertFalse(settings.get("lakes").getAsBoolean(), name);
            assertTrue(settings.getAsJsonArray("layers").isEmpty(), name + " must have no terrain");
            assertTrue(settings.getAsJsonArray("structure_overrides").isEmpty(),
                    name + " must explicitly disable all structure sets");
        }
    }

    @Test
    void surfacesReferenceTheSupportedVanillaGeneratorSettingsAndBiomes() throws IOException {
        JsonObject barren = resource("dimension/barren.json").getAsJsonObject("generator");
        JsonObject temperate = resource("dimension/temperate.json").getAsJsonObject("generator");
        for (JsonObject generator : new JsonObject[]{barren, temperate}) {
            assertEquals("minecraft:noise", generator.get("type").getAsString());
            assertEquals("minecraft:overworld", generator.get("settings").getAsString());
        }
        JsonObject barrenBiomes = barren.getAsJsonObject("biome_source");
        assertEquals("minecraft:fixed", barrenBiomes.get("type").getAsString());
        assertEquals("minecraft:stony_peaks", barrenBiomes.get("biome").getAsString());
        JsonObject temperateBiomes = temperate.getAsJsonObject("biome_source");
        assertEquals("minecraft:multi_noise", temperateBiomes.get("type").getAsString());
        assertEquals("minecraft:overworld", temperateBiomes.get("preset").getAsString());
    }

    @Test
    void vacuumHasNoPrecipitationCarversSpawnsOrAutomaticPlatform() throws IOException {
        JsonObject biome = resource("worldgen/biome/vacuum.json");
        assertFalse(biome.get("has_precipitation").getAsBoolean());
        assertTrue(biome.getAsJsonObject("carvers").entrySet().isEmpty());
        for (JsonElement step : biome.getAsJsonArray("features")) {
            assertTrue(step.getAsJsonArray().isEmpty(), "Vacuum must not place any features");
        }
        for (var spawnCategory : biome.getAsJsonObject("spawners").entrySet()) {
            assertTrue(spawnCategory.getValue().getAsJsonArray().isEmpty(),
                    "Unexpected vacuum spawns: " + spawnCategory.getKey());
        }
    }

    private static JsonObject resource(String relativePath) throws IOException {
        String path = RESOURCE_ROOT + relativePath;
        InputStream input = DimensionResourcesTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(input, "Missing classpath resource: " + path);
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
