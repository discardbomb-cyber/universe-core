package dev.heiko.universe.api.material;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MaterialCatalogTest {
    private static MaterialDefinition material(int index) {
        return new MaterialDefinition(MaterialId.parse("addon:planet/rock_" + index),
                MaterialId.parse("addon:rock"), List.of(index & 0xFFFFFF, 0x808080),
                RenderCategory.OPAQUE, Set.of(MaterialId.parse("addon:surface")));
    }

    @Test void fiveThousandDefinitionsHaveDistinctIdsAndOrderIndependentLookup() {
        List<MaterialDefinition> inputs = new ArrayList<>();
        for (int i = 0; i < 5000; i++) inputs.add(material(i));
        MaterialCatalog.Builder forward = MaterialCatalog.builder(5000);
        inputs.forEach(forward::register);
        MaterialCatalog first = forward.freeze();
        Collections.shuffle(inputs, new java.util.Random(42));
        MaterialCatalog.Builder shuffled = MaterialCatalog.builder(5000);
        inputs.forEach(shuffled::register);
        MaterialCatalog second = shuffled.freeze();
        assertEquals(5000, first.size());
        assertEquals(5000, first.definitions().stream().map(MaterialDefinition::id).distinct().count());
        assertEquals(first.definitions(), second.definitions());
        for (MaterialDefinition input : inputs) {
            assertEquals(input, first.find(input.id()).orElseThrow());
            assertEquals(input, second.find(input.id()).orElseThrow());
        }
        assertTrue(first.find(MaterialId.parse("addon:missing")).isEmpty());
    }

    @Test void duplicatesCapacityAndFreezeCannotSilentlyDiscardDefinitions() {
        MaterialCatalog.Builder builder = MaterialCatalog.builder(2).register(material(0));
        assertThrows(IllegalArgumentException.class, () -> builder.register(material(0)));
        builder.register(material(1));
        assertThrows(IllegalStateException.class, () -> builder.register(material(2)));
        MaterialCatalog catalog = builder.freeze();
        assertEquals(2, catalog.size());
        assertSame(catalog, builder.freeze());
        assertThrows(IllegalStateException.class, () -> builder.register(material(3)));
        assertThrows(UnsupportedOperationException.class, () -> catalog.definitions().clear());
        assertThrows(IllegalArgumentException.class, () -> MaterialCatalog.builder(0));
        assertThrows(IllegalArgumentException.class,
                () -> MaterialCatalog.builder(MaterialCatalog.MAX_CAPACITY + 1));
    }

    @Test void palettesAndTagsAreDefensivelyCopiedAndBounded() {
        List<Integer> colors = new ArrayList<>(List.of(0x112233));
        Set<MaterialId> tags = new HashSet<>(Set.of(MaterialId.parse("addon:z"), MaterialId.parse("addon:a")));
        MaterialDefinition descriptor = definition(colors, tags);
        colors.set(0, 0);
        tags.clear();
        assertEquals(List.of(0x112233), descriptor.paletteColors());
        assertEquals(List.of(MaterialId.parse("addon:a"), MaterialId.parse("addon:z")),
                new ArrayList<>(descriptor.generationTags()));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.paletteColors().clear());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.generationTags().clear());
        assertThrows(IllegalArgumentException.class, () -> definition(List.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> definition(List.of(-1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> definition(List.of(0x1000000), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> definition(Collections.nCopies(MaterialDefinition.MAX_PALETTE_COLORS + 1, 0), Set.of()));
        Set<MaterialId> excessive = new HashSet<>();
        for (int i = 0; i <= MaterialDefinition.MAX_GENERATION_TAGS; i++) {
            excessive.add(MaterialId.parse("addon:tag_" + i));
        }
        assertThrows(IllegalArgumentException.class, () -> definition(List.of(0), excessive));
    }

    @Test void idsRequireExplicitValidBoundedNamespaces() {
        for (String invalid : List.of("rock", ":rock", "addon:", "Addon:rock", "addon:a:b", "addon:bad path")) {
            assertThrows(IllegalArgumentException.class, () -> MaterialId.parse(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> MaterialId.parse("addon:" + "a".repeat(MaterialId.MAX_LENGTH)));
        assertEquals("addon:planet/rock", MaterialId.parse("addon:planet/rock").toString());
        assertNotEquals(MaterialId.parse("addon:rock"), MaterialId.parse("other:rock"));
    }

    private static MaterialDefinition definition(List<Integer> colors, Set<MaterialId> tags) {
        return new MaterialDefinition(MaterialId.parse("addon:rock"), MaterialId.parse("addon:stone"),
                colors, RenderCategory.OPAQUE, tags);
    }
}
