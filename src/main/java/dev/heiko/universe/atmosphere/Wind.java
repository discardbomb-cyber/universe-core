package dev.heiko.universe.atmosphere;
/** Local blocks per tick, continuously interpolated as Cartesian components. */
public record Wind(double x, double z) {
    public Wind { Checks.range(x, -100, 100, "wind x"); Checks.range(z, -100, 100, "wind z"); }
}
