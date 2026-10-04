package dev.heiko.universe.api.planet;

import dev.heiko.universe.core.Realm;
import java.util.Objects;

/**
 * Immutable planet metadata. IDs are explicit lowercase namespace:path identifiers.
 * Profile and binding references are unresolved: construction creates no worlds.
 * The seed and stable ID must be persisted by the integrating application.
 */
public record PlanetDefinition(String id, Realm realm, long seed,
                               String generationProfileId, String atmosphereProfileId,
                               String surfaceBindingId) {
    public PlanetDefinition {
        id = PlanetIds.requireValid(id);
        Objects.requireNonNull(realm, "realm");
        generationProfileId = PlanetIds.requireValid(generationProfileId);
        atmosphereProfileId = PlanetIds.requireValid(atmosphereProfileId);
        surfaceBindingId = PlanetIds.requireValid(surfaceBindingId);
    }
}

