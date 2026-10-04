/**
 * Minecraft-independent Universe address and catalog contracts.
 *
 * <p>Structural records, including realm and its persistent galaxy identifier, are
 * primary addresses. Names, dimensions and enum ordinals are not identifiers.
 * Sector units are the plan's conditional units, not blocks or astronomical units.
 * Only chart zero and format/generator/projection version one are currently supported.
 *
 * <p>The lazy catalog is a deterministic metadata prototype: counts and seeds only.
 * It applies finite GalaxyProfile envelopes, but does not establish visitability, dimensions, density, placement,
 * orbital elements, minimum separation or discovery permissions. Those require a
 * separate generation/integration layer. Extending the generation algorithm requires
 * a new supported generator version instead of silently changing existing worlds.
 * Cache eviction never deletes authoritative world data. Capacity bounds retained
 * sectors (at most 504 body descriptors each), not bytes or caller-retained snapshots.
 */
package dev.heiko.universe.core;
