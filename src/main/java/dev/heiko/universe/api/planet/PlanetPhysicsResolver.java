package dev.heiko.universe.api.planet;

import dev.heiko.universe.api.environment.GasProfile;
import dev.heiko.universe.api.gravity.VerticalGravityProfile;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes a result only after all three references are resolved and IDs match.
 * Hosts must provide stable read-only snapshots for the duration of a call: this is
 * not a transaction across concurrent reloads of three independently mutable maps.
 * Does not mutate registries, fall back to vacuum/zero-g, or resolve terrain/surface references.
 */
public final class PlanetPhysicsResolver {
    private PlanetPhysicsResolver() {}

    public static ResolvedPlanetPhysics resolve(PlanetPhysicsBinding binding,
            Map<String, PlanetDefinition> planets,
            Map<String, VerticalGravityProfile> gravityProfiles,
            Map<String, GasProfile> gasProfiles) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(planets, "planets");
        Objects.requireNonNull(gravityProfiles, "gravityProfiles");
        Objects.requireNonNull(gasProfiles, "gasProfiles");
        var planet = requireFound(planets.get(binding.planetId()), "planet", binding.planetId());
        requireId(planet.id(), binding.planetId(), "planet");
        var gravity = requireFound(gravityProfiles.get(binding.gravityProfileId()), "gravity profile", binding.gravityProfileId());
        requireId(gravity.fieldId(), binding.gravityProfileId(), "gravity profile");
        var gas = requireFound(gasProfiles.get(binding.gasProfileId()), "gas profile", binding.gasProfileId());
        requireId(gas.id(), binding.gasProfileId(), "gas profile");
        return new ResolvedPlanetPhysics(planet, gravity, gas, binding.frameId());
    }

    private static <T> T requireFound(T value, String kind, String id) {
        if (value == null) throw new IllegalArgumentException("Missing " + kind + ": " + id);
        return value;
    }

    private static void requireId(String actual, String expected, String kind) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Mismatched " + kind + " ID for " + expected + ": " + actual);
        }
    }
}
