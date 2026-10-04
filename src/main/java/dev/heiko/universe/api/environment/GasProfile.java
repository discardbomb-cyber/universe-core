package dev.heiko.universe.api.environment;

import dev.heiko.universe.api.ApiIds;
import java.util.Objects;

public record GasProfile(String id, long revision, GasState state) {
    public GasProfile {
        id = ApiIds.require(id);
        if (revision < 0) throw new IllegalArgumentException("Negative gas profile revision");
        Objects.requireNonNull(state, "state");
    }
}
