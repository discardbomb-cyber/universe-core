package dev.heiko.universe.atmosphere;
final class Checks {
    private Checks() {}
    static double range(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(name);
        return value;
    }
    static double nonnegative(double value, String name) { return range(value, 0, Double.MAX_VALUE, name); }
}
