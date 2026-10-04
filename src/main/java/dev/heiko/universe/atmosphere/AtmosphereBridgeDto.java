package dev.heiko.universe.atmosphere;
import dev.heiko.universe.core.BodyKey;
import java.util.Objects;
/**
 * Backend-neutral frame input. No Photon/Iris/NeoForge dependency or renderer implementation.
 * Layers may overlap: backend must reconcile storm volume and enforce its own render budget.
 */
public record AtmosphereBridgeDto(int formatVersion, BodyKey planet, AtmosphereProfile profile,
        WeatherState weather, long universeTicks, double localX, double localZ) {
    public AtmosphereBridgeDto {
        if(formatVersion!=1) throw new IllegalArgumentException("unsupported bridge version");
        Objects.requireNonNull(planet); Objects.requireNonNull(profile); Objects.requireNonNull(weather);
        if(planet.kind()!=BodyKey.Kind.PLANET && planet.kind()!=BodyKey.Kind.MOON)
            throw new IllegalArgumentException("bridge requires planet or moon");
        Checks.range(localX,-0x1.0p40,0x1.0p40,"localX");
        Checks.range(localZ,-0x1.0p40,0x1.0p40,"localZ");
        if(!profile.hasAtmosphere() && !weather.equals(WeatherState.VACUUM))
            throw new IllegalArgumentException("vacuum weather");
    }
    public static AtmosphereBridgeDto sample(long seed, BodyKey planet, AtmosphereProfile profile,
                                            double x, double z, long ticks) {
        return new AtmosphereBridgeDto(1,planet,profile,
            new WeatherCell(seed,planet,profile).sample(x,z,ticks),ticks,x,z);
    }
}
