package dev.heiko.universe.api.planet;

import dev.heiko.universe.api.ApiIds;
import dev.heiko.universe.api.environment.GasProfile;
import dev.heiko.universe.api.gravity.GravitySample;
import dev.heiko.universe.api.gravity.GravitySampler;
import dev.heiko.universe.api.gravity.VerticalGravityProfile;
import java.util.Objects;

/** Complete immutable metadata result. It creates no dimension and grants no visitability. */
public record ResolvedPlanetPhysics(PlanetDefinition planet, VerticalGravityProfile gravity,
                                    GasProfile gas, String frameId) {
    public ResolvedPlanetPhysics {
        Objects.requireNonNull(planet, "planet");
        Objects.requireNonNull(gravity, "gravity");
        Objects.requireNonNull(gas, "gas");
        frameId = ApiIds.require(frameId);
    }

    public GravitySample gravityAt(double localY) { return GravitySampler.sample(gravity, localY, frameId); }

    public double partialPressure(String speciesId) { return gas.state().partialPressure(speciesId); }
}
