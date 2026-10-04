package dev.heiko.universe.api.gravity;

import dev.heiko.universe.ships.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GravitySamplerTest {
    private static VerticalGravityProfile earth() {
        return new VerticalGravityProfile("test:earth", 4, 9.81, 100, 200);
    }

    @Test void surfaceGravityFadesContinuouslyIntoOrbitAtBothBoundaries() {
        var profile = earth();
        assertEquals(-9.81, GravitySampler.sample(profile, -100, "test:surface").accelerationMetersPerSecondSquared().y());
        assertEquals(-9.81, GravitySampler.sample(profile, 100, "test:surface").accelerationMetersPerSecondSquared().y());
        assertEquals(-4.905, GravitySampler.sample(profile, 150, "test:surface").accelerationMetersPerSecondSquared().y(), 1e-12);
        assertEquals(Vec3.ZERO, GravitySampler.sample(profile, 200, "test:surface").accelerationMetersPerSecondSquared());
        assertEquals(Vec3.ZERO, GravitySampler.sample(profile, 1000, "test:surface").accelerationMetersPerSecondSquared());
        double justInside = GravitySampler.sample(profile, Math.nextDown(200.0), "test:surface").accelerationMetersPerSecondSquared().length();
        assertTrue(justInside >= 0 && justInside < 1e-12);
    }

    @Test void samplerKeepsProfileRevisionAndCallerFrameWithExplicitTickConversion() {
        var sample = GravitySampler.sample(earth(), 150, "test:ship_local");
        assertEquals("test:earth", sample.fieldId());
        assertEquals(4, sample.revision());
        assertEquals("test:ship_local", sample.frameId());
        assertEquals(0, sample.accelerationBlocksPerTickSquared().x());
        assertEquals(-4.905 / 400.0, sample.accelerationBlocksPerTickSquared().y(), 1e-15);
        assertEquals(0, sample.accelerationBlocksPerTickSquared().z());
        var vector = new GravitySample("test:field", 0, "test:frame", new Vec3(3, -4, 12));
        assertEquals(3 / 400.0, vector.accelerationBlocksPerTickSquared().x(), 1e-15);
        assertEquals(-4 / 400.0, vector.accelerationBlocksPerTickSquared().y(), 1e-15);
        assertEquals(12 / 400.0, vector.accelerationBlocksPerTickSquared().z(), 1e-15);
    }

    @Test void orbitTransitionUsesSmoothFadeAndRemainsMonotonic() {
        var profile = earth();
        assertEquals(9.81 * 0.84375, GravitySampler.sample(profile, 125, "test:frame").accelerationMetersPerSecondSquared().length(), 1e-12);
        assertEquals(9.81 * 0.15625, GravitySampler.sample(profile, 175, "test:frame").accelerationMetersPerSecondSquared().length(), 1e-12);
        double previous = 9.81;
        for (int y = 100; y <= 200; y++) {
            double current = GravitySampler.sample(profile, y, "test:frame").accelerationMetersPerSecondSquared().length();
            assertTrue(current <= previous && current >= 0);
            previous = current;
        }
    }

    @Test void zeroAndMaximumGravityRemainValidAndDoNotAmplifyOutsideFadeInterval() {
        var zero = new VerticalGravityProfile("test:zero", 0, 0, -10, 10);
        var maximum = new VerticalGravityProfile("test:max", 0, 128, -10, 10);
        for (double y : new double[]{-Double.MAX_VALUE, -10, 0, 10, Double.MAX_VALUE}) {
            assertEquals(Vec3.ZERO, GravitySampler.sample(zero, y, "test:frame").accelerationMetersPerSecondSquared());
            double acceleration = GravitySampler.sample(maximum, y, "test:frame").accelerationMetersPerSecondSquared().length();
            assertTrue(Double.isFinite(acceleration) && acceleration <= 128);
        }
    }

    @Test void profileRejectsInvalidAccelerationAndNonFiniteOrOverflowingIntervals() {
        for (double acceleration : new double[]{-0.01, Math.nextUp(128.0), Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new VerticalGravityProfile("test:field", 0, acceleration, 0, 1));
        for (double[] interval : new double[][]{{0, 0}, {2, 1}, {Double.NaN, 1}, {0, Double.POSITIVE_INFINITY},
                {Double.NEGATIVE_INFINITY, 0}, {-Double.MAX_VALUE, Double.MAX_VALUE}})
            assertThrows(IllegalArgumentException.class, () -> new VerticalGravityProfile("test:field", 0, 9.81, interval[0], interval[1]));
    }

    @Test void sampleRejectsInvalidMagnitudeRevisionAndQueryCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new GravitySample("test:field", 0, "test:frame", new Vec3(100, 100, 100)));
        assertThrows(IllegalArgumentException.class, () -> new GravitySample("test:field", -1, "test:frame", Vec3.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new VerticalGravityProfile("test:field", -1, 9.81, 0, 1));
        for (double y : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> GravitySampler.sample(earth(), y, "test:frame"));
        assertDoesNotThrow(() -> new GravitySample("test:field", 0, "test:frame", new Vec3(128, 0, 0)));
    }

    @Test void gravityIdentifiersMustBeBoundedNamespacedReferences() {
        for (String invalid : new String[]{"field", "TEST:field", "test:bad path", "test:a//b", "test:../field", "t:" + "x".repeat(255)}) {
            assertThrows(IllegalArgumentException.class, () -> new VerticalGravityProfile(invalid, 0, 0, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> new GravitySample("test:field", 0, invalid, Vec3.ZERO));
            assertThrows(IllegalArgumentException.class, () -> GravitySampler.sample(earth(), 0, invalid));
        }
    }
}
