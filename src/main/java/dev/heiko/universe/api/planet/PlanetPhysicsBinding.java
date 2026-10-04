package dev.heiko.universe.api.planet;

import dev.heiko.universe.api.ApiIds;

/** Companion metadata; does not change PlanetDefinition or claim a visitable surface. */
public record PlanetPhysicsBinding(String planetId, String gravityProfileId,
                                   String gasProfileId, String frameId) {
    public PlanetPhysicsBinding {
        planetId = ApiIds.require(planetId);
        gravityProfileId = ApiIds.require(gravityProfileId);
        gasProfileId = ApiIds.require(gasProfileId);
        frameId = ApiIds.require(frameId);
    }
}
