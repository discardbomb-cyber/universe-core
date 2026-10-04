/**
 * Public, server-neutral Java 21 planet metadata extension API.
 *
 * <p>Construct a PlanetDefinition, register it during bootstrap, then freeze its
 * PlanetRegistry before gameplay. Planet IDs are globally unique lowercase
 * namespace:path strings, not display names. All reference IDs must be explicitly
 * namespaced, at most 256 characters; empty, dot and dot-dot path segments are
 * rejected. IDs are never normalized or assigned a default namespace.
 *
 * <p>Generation and atmosphere profile IDs and surface binding IDs are unresolved
 * references. Registration never creates a Minecraft dimension, chunks, an
 * atmosphere renderer or a dynamic surface binding. A separate adapter must check
 * profile availability, supported generation versions and permanent predeclared
 * dimensions before permitting visits. Many metadata records may therefore exist
 * while only a small fixed subset has available surfaces.
 *
 * <p>PlanetPhysicsBinding and PlanetPhysicsResolver resolve companion gravity/gas
 * metadata from stable host snapshots, without changing PlanetDefinition or granting
 * a visitable surface. ResolvedPlanetPhysics can sample a target field or gas partial
 * pressure; applying movement, breathing and damage remains an integration concern.
 *
 * <p>Format version is distinct from the append-only content revision. There is no
 * persistence codec, automatic migration or registration replacement/removal.
 * Memory is bounded by the registry's entry capacity and ID lengths; caller-held
 * snapshot copies have independent lifetimes and are not included in this bound.
 */
package dev.heiko.universe.api.planet;
