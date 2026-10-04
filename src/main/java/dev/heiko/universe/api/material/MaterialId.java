package dev.heiko.universe.api.material;

import java.util.Objects;
import java.util.regex.Pattern;

/** Persistent logical key; independent of Minecraft registry numeric IDs. */
public record MaterialId(String namespace, String path) implements Comparable<MaterialId> {
    public static final int MAX_LENGTH = 256;
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

    public MaterialId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.length() + path.length() + 1 > MAX_LENGTH
                || !NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid namespaced material key");
        }
    }

    /** Requires an explicit namespace; never silently defaults to minecraft. */
    public static MaterialId parse(String value) {
        Objects.requireNonNull(value, "value");
        int colon = value.indexOf(':');
        if (colon < 1 || colon != value.lastIndexOf(':')) {
            throw new IllegalArgumentException("Expected namespace:path");
        }
        return new MaterialId(value.substring(0, colon), value.substring(colon + 1));
    }

    @Override public String toString() { return namespace + ":" + path; }

    @Override public int compareTo(MaterialId other) {
        int namespaceOrder = namespace.compareTo(other.namespace);
        return namespaceOrder != 0 ? namespaceOrder : path.compareTo(other.path);
    }
}
