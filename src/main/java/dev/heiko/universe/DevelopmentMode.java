package dev.heiko.universe;

/** Examples are an explicit development option; the library has no built-in gameplay. */
public final class DevelopmentMode {
    private DevelopmentMode() {}
    public static boolean enabled() { return Boolean.getBoolean("universe.demo"); }
}
