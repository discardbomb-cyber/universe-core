package dev.heiko.universe.ships;

public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public Vec3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Non-finite vector");
    }
    public Vec3 add(Vec3 b) { return new Vec3(x+b.x, y+b.y, z+b.z); }
    public Vec3 subtract(Vec3 b) { return new Vec3(x-b.x, y-b.y, z-b.z); }
    public Vec3 scale(double s) { return new Vec3(x*s, y*s, z*s); }
    public double dot(Vec3 b) { return x*b.x+y*b.y+z*b.z; }
    public Vec3 cross(Vec3 b) { return new Vec3(y*b.z-z*b.y, z*b.x-x*b.z, x*b.y-y*b.x); }
    public double length() { return Math.hypot(Math.hypot(x, y), z); }
    public Vec3 normalized() {
        double n = length();
        if (!(n > 0) || !Double.isFinite(n)) throw new IllegalArgumentException("Invalid direction");
        return scale(1/n);
    }
}
