package dev.heiko.universe.api.environment;

import dev.heiko.universe.api.ApiIds;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Known gas state in game pressure units. Unknown/stale samples must be represented separately. */
public record GasState(double totalPressureGameUnits, Map<String, Double> fractions) {
    public static final int MAX_SPECIES = 16;
    public static final double FRACTION_SUM_TOLERANCE = 1e-9;

    public GasState {
        Objects.requireNonNull(fractions, "fractions");
        if (!Double.isFinite(totalPressureGameUnits) || totalPressureGameUnits < 0) {
            throw new IllegalArgumentException("Gas pressure must be finite and nonnegative");
        }
        if (fractions.size() > MAX_SPECIES) throw new IllegalArgumentException("At most 16 gas species");
        var sorted = new TreeMap<String, Double>();
        for (var entry : fractions.entrySet()) {
            String id = ApiIds.require(entry.getKey());
            double fraction = Objects.requireNonNull(entry.getValue(), "fraction");
            if (!Double.isFinite(fraction) || fraction <= 0 || fraction > 1) {
                throw new IllegalArgumentException("Gas fraction must be finite and within (0,1]");
            }
            sorted.put(id, fraction);
        }
        if (totalPressureGameUnits == 0) {
            if (!sorted.isEmpty()) throw new IllegalArgumentException("Vacuum must have an empty composition");
            totalPressureGameUnits = 0; // Canonicalize negative zero as the explicit vacuum state.
        } else {
            double sum = 0;
            for (double fraction : sorted.values()) sum += fraction;
            if (sorted.isEmpty() || Math.abs(sum - 1.0) > FRACTION_SUM_TOLERANCE) {
                throw new IllegalArgumentException("Gas fractions must sum to 1 within 1e-9");
            }
        }
        // Preserve accepted fractions exactly; never normalize or add an implicit component.
        fractions = Collections.unmodifiableMap(sorted);
    }

    public static GasState vacuum() { return new GasState(0, Map.of()); }

    public boolean isVacuum() { return totalPressureGameUnits == 0; }

    public double partialPressure(String speciesId) {
        return totalPressureGameUnits * fractions.getOrDefault(ApiIds.require(speciesId), 0.0);
    }
}
