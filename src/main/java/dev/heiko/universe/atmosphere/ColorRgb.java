package dev.heiko.universe.atmosphere;
public record ColorRgb(double red, double green, double blue) {
    public ColorRgb {
        Checks.range(red, 0, 1, "red"); Checks.range(green, 0, 1, "green"); Checks.range(blue, 0, 1, "blue");
    }
}
