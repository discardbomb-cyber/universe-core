/**
 * Immutable gravity targets in explicit local frames, independent of Minecraft/Sable.
 * Hosts must supply entity/physics adapters, resolve ownership, and avoid applying base gravity twice.
 * Sampling alone does not change movement, fall damage, fluids, or a ship's orbit.
 */
package dev.heiko.universe.api.gravity;
