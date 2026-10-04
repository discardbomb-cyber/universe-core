package dev.heiko.universe.atmosphere;
/** Intensities are visual/gameplay inputs, not instructions to spawn particles or deal damage. */
public record WeatherState(double coverage, double precipitationIntensity, double stormActivity, Wind wind) {
    public WeatherState {
        Checks.range(coverage,0,1,"coverage"); Checks.range(precipitationIntensity,0,1,"precipitation");
        Checks.range(stormActivity,0,1,"storm"); java.util.Objects.requireNonNull(wind);
    }
    public static final WeatherState VACUUM = new WeatherState(0,0,0,new Wind(0,0));
}
