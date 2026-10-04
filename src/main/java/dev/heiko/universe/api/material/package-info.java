/**
 * Pure Java descriptors for planetary materials. This API registers no Minecraft blocks.
 * A catalog with 5000 entries establishes logical identity and palette metadata only;
 * it does not establish 5000 playable Block types, rendering support or network registry IDs.
 * Save namespaced IDs, never catalog offsets. Persist generator/profile versions separately
 * when changes to material definitions affect terrain. Families and tags are logical keys,
 * not automatically Minecraft block/item tags. No per-block ticking or entities are created.
 */
package dev.heiko.universe.api.material;
