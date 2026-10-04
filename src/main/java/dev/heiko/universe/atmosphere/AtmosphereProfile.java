package dev.heiko.universe.atmosphere;
import java.util.List;
import java.util.Objects;
/** Artistic atmosphere; pressure and temperature are independent gameplay parameters. */
public record AtmosphereProfile(AtmosphereKind kind, double thickness, double densityFalloff,
        ColorRgb scattering, double aerosols, double absorption, double shellRadiusRatio,
        double pressure, double temperatureKelvin, Wind wind, List<CloudLayer> layers) {
    public AtmosphereProfile {
        Objects.requireNonNull(kind); Objects.requireNonNull(scattering); Objects.requireNonNull(wind);
        layers = List.copyOf(layers);
        Checks.nonnegative(thickness, "thickness"); Checks.nonnegative(densityFalloff, "densityFalloff");
        Checks.range(aerosols, 0, 1, "aerosols"); Checks.range(absorption, 0, 1, "absorption");
        Checks.range(shellRadiusRatio, 1, 2, "shellRadiusRatio");
        Checks.nonnegative(pressure, "pressure"); Checks.nonnegative(temperatureKelvin, "temperatureKelvin");
        if (layers.size() > 5 || layers.stream().map(CloudLayer::tier).distinct().count() != layers.size())
            throw new IllegalArgumentException("at most one layer per tier");
        if (kind == AtmosphereKind.VACUUM && (thickness != 0 || densityFalloff != 0 ||
                aerosols != 0 || absorption != 0 || pressure != 0 || shellRadiusRatio != 1 ||
                wind.x() != 0 || wind.z() != 0 || !layers.isEmpty()))
            throw new IllegalArgumentException("vacuum cannot contain atmosphere or clouds");
        if (kind != AtmosphereKind.VACUUM && (thickness == 0 || densityFalloff == 0 || shellRadiusRatio == 1))
            throw new IllegalArgumentException("atmosphere requires thickness, falloff and shell");
    }
    public boolean hasAtmosphere() { return kind != AtmosphereKind.VACUUM; }
}
