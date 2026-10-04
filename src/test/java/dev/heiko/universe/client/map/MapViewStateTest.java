package dev.heiko.universe.client.map;

import dev.heiko.universe.core.Realm;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapViewStateTest {
    @Test void zoomNeverWrapsBetweenSurfaceAndGalaxy() {
        var state = new MapViewState();
        state.stepScale(-1);
        assertEquals(MapViewState.Scale.CHUNKS, state.scale());
        for (int i = 0; i < 20; i++) state.stepScale(Integer.MAX_VALUE);
        assertEquals(MapViewState.Scale.SECTORS, state.scale());
        state.stepScale(0);
        assertEquals(MapViewState.Scale.SECTORS, state.scale());
        for (int i = 0; i < 20; i++) state.stepScale(Integer.MIN_VALUE);
        assertEquals(MapViewState.Scale.CHUNKS, state.scale());
    }

    @Test void galaxySelectionDoesNotGrantAccessOrChangeScale() {
        var state = new MapViewState();
        state.selectScale(MapViewState.Scale.PLANET);
        state.cycleRealm(-1);
        assertEquals(Realm.NETHER, state.realm());
        assertEquals("map.universe.status.planned", state.statusTranslationKey());
        assertEquals(MapViewState.Scale.PLANET, state.scale());
        state.cycleRealm(1);
        assertEquals(Realm.MILKY_WAY, state.realm());
        assertEquals("map.universe.status.prototype", state.statusTranslationKey());
        state.selectRealm(Realm.END);
        assertEquals("map.universe.galaxy.silent_crown", state.galaxyTranslationKey());
        assertEquals("map.universe.status.planned", state.statusTranslationKey());
    }
}
