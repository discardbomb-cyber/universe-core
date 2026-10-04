package dev.heiko.universe.core;

public record Versions(int schema, int generator, int projection) {
    public static final Versions CURRENT = new Versions(1, 1, 1);
    public Versions {
        if (schema != 1 || generator != 1 || projection != 1)
            throw new IllegalArgumentException("Unsupported Universe versions");
    }
}

