package dev.heiko.universe.api.material;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.Collections;

/**
 * Immutable logical material descriptor, NOT a registered Minecraft Block.
 * Thousands of descriptors may describe planetary palettes without allocating block
 * registries, block entities, tick handlers, textures or per-position objects.
 * An integration layer must explicitly map a descriptor to supported registered blocks.
 * Generation tags classify eligibility; they do not place resources by themselves.
 */
public record MaterialDefinition(MaterialId id, MaterialId family, List<Integer> paletteColors,
                                 RenderCategory renderCategory, Set<MaterialId> generationTags) {
    public static final int MAX_PALETTE_COLORS = 16;
    public static final int MAX_GENERATION_TAGS = 64;

    public MaterialDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(renderCategory, "renderCategory");
        Objects.requireNonNull(paletteColors, "paletteColors");
        Objects.requireNonNull(generationTags, "generationTags");
        if (paletteColors.isEmpty() || paletteColors.size() > MAX_PALETTE_COLORS) {
            throw new IllegalArgumentException("Palette must contain 1..16 RGB colors");
        }
        for (Integer color : paletteColors) {
            if (color == null || color < 0 || color > 0xFFFFFF) {
                throw new IllegalArgumentException("Palette colors must be unsigned 24-bit RGB");
            }
        }
        if (generationTags.size() > MAX_GENERATION_TAGS) {
            throw new IllegalArgumentException("Too many generation tags");
        }
        paletteColors = List.copyOf(paletteColors);
        // Canonical iteration order is useful for deterministic serialization and generation.
        generationTags = Collections.unmodifiableSet(new TreeSet<>(generationTags));
    }
}
