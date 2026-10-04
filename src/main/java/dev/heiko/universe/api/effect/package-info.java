/**
 * Pure Java 21 client-local effect library. No shipped effects, renderer, Photon dependencies,
 * Minecraft client classes or server simulation. A host selects one realm/local frame, supplies
 * bounded requests, calls EffectManager.update, and reconciles the returned set through a backend.
 * Catalog content does not imply live allocations. Seed and immutable execution descriptors permit
 * repeatable backend behavior; actual GPU performance and visual reproducibility require adapter tests.
 */
package dev.heiko.universe.api.effect;
