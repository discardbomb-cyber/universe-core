package dev.heiko.universe.api.planet;

import java.util.Objects;
import java.util.regex.Pattern;

final class PlanetIds {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private PlanetIds() {}

    static String requireValid(String id) {
        Objects.requireNonNull(id, "id");
        if (id.length() > 256 || !ID.matcher(id).matches())
            throw new IllegalArgumentException("Expected lowercase namespace:path ID, at most 256 characters");
        String path = id.substring(id.indexOf(':') + 1);
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("ID path must contain nonempty, nontraversal segments");
        }
        return id;
    }
}

