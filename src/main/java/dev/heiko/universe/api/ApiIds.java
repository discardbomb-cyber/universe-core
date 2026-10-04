package dev.heiko.universe.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Explicit bounded resource IDs for extension APIs; never assigns a default namespace. */
public final class ApiIds {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public static final int MAX_LENGTH = 256;

    private ApiIds() {}

    public static String require(String value) {
        Objects.requireNonNull(value, "id");
        if (value.length() > MAX_LENGTH || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Expected lowercase namespace:path ID, at most 256 characters");
        }
        for (String segment : value.substring(value.indexOf(':') + 1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("ID path must contain nonempty, nontraversal segments");
            }
        }
        return value;
    }
}
