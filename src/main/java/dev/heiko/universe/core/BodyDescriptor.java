package dev.heiko.universe.core;

import java.util.Objects;

/** Catalog metadata only: it grants no dimension or visitability. */
public record BodyDescriptor(BodyKey key, long seed, Versions versions) {
    public BodyDescriptor { Objects.requireNonNull(key); Objects.requireNonNull(versions); }
}

