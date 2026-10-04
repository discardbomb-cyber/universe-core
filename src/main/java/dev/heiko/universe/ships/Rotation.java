package dev.heiko.universe.ships;

/** Unit quaternion, component order x/y/z/w. */
public record Rotation(double x, double y, double z, double w) {
    public static final Rotation IDENTITY = new Rotation(0, 0, 0, 1);
    public Rotation {
        double n = Math.hypot(Math.hypot(x,y), Math.hypot(z,w));
        if (!(n > 0) || !Double.isFinite(n)) throw new IllegalArgumentException("Invalid quaternion");
        x /= n; y /= n; z /= n; w /= n;
    }
    public Rotation multiply(Rotation b) {
        return new Rotation(w*b.x+x*b.w+y*b.z-z*b.y, w*b.y-x*b.z+y*b.w+z*b.x,
                w*b.z+x*b.y-y*b.x+z*b.w, w*b.w-x*b.x-y*b.y-z*b.z);
    }
    public Rotation inverse() { return new Rotation(-x,-y,-z,w); }
    public Vec3 apply(Vec3 v) {
        Vec3 q = new Vec3(x,y,z);
        Vec3 t = q.cross(v).scale(2);
        return v.add(t.scale(w)).add(q.cross(t));
    }
}
