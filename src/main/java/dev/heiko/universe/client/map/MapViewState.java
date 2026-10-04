package dev.heiko.universe.client.map;

import dev.heiko.universe.core.Realm;
import java.util.Objects;

/** UI state only. Never queries the catalog, world, chunks, or discovery records. */
public final class MapViewState {
    public enum Scale {
        CHUNKS, REGIONS, PLANET, SYSTEM, SECTORS;
        public String translationKey() { return "map.universe.scale." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    private Scale scale = Scale.CHUNKS;
    private Realm realm = Realm.MILKY_WAY;

    public Scale scale() { return scale; }
    public Realm realm() { return realm; }
    public void selectScale(Scale value) { scale = Objects.requireNonNull(value); }
    public void selectRealm(Realm value) { realm = Objects.requireNonNull(value); }

    /** Positive moves outward; clamp rather than wrapping surface into galaxy. */
    public void stepScale(int direction) {
        int next = Math.clamp(scale.ordinal() + Integer.signum(direction), 0, Scale.values().length - 1);
        scale = Scale.values()[next];
    }

    public void cycleRealm(int direction) {
        Realm[] realms = Realm.values();
        realm = realms[Math.floorMod(realm.ordinal() + Integer.signum(direction), realms.length)];
    }

    public String galaxyTranslationKey() { return "map.universe.galaxy." + realm.galaxyId(); }
    public String statusTranslationKey() {
        return realm == Realm.MILKY_WAY ? "map.universe.status.prototype" : "map.universe.status.planned";
    }
}
