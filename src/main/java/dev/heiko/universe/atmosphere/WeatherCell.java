package dev.heiko.universe.atmosphere;
import dev.heiko.universe.core.BodyKey;
import dev.heiko.universe.core.StableSeed;
import dev.heiko.universe.core.Versions;
import java.util.Objects;
/**
 * Stateless weather v1. Coordinates are planet-local blocks; time is authoritative universe ticks.
 * Eight lattice nodes per sample, no caches or galaxy ticking. Realm and complete body address
 * participate in the seed. Negative coordinates/time use floor semantics.
 */
public final class WeatherCell {
    public static final int CELL_SIZE = 2048;
    public static final int EPOCH_TICKS = 100;
    private final long seed;
    private final BodyKey planet;
    private final AtmosphereProfile profile;
    public WeatherCell(long universeSeed, BodyKey planet, AtmosphereProfile profile) {
        this.planet = Objects.requireNonNull(planet); this.profile = Objects.requireNonNull(profile);
        if (planet.kind() != BodyKey.Kind.PLANET && planet.kind() != BodyKey.Kind.MOON)
            throw new IllegalArgumentException("weather requires planet or moon");
        seed = StableSeed.derive(universeSeed, Versions.CURRENT, planet.system().sector(),
            "weather.v1/" + planet.kind().name(), planet.system().slot(), planet.parentSlot(), planet.slot());
    }
    public BodyKey planet() { return planet; }
    public WeatherState sample(double x, double z, long ticks) {
        Checks.range(x,-0x1.0p40,0x1.0p40,"x"); Checks.range(z,-0x1.0p40,0x1.0p40,"z");
        if (!profile.hasAtmosphere()) return WeatherState.VACUUM;
        double gx=x/CELL_SIZE, gz=z/CELL_SIZE;
        long ix=(long)StrictMath.floor(gx), iz=(long)StrictMath.floor(gz);
        long epoch=Math.floorDiv(ticks,EPOCH_TICKS);
        double fx=smooth(gx-ix), fz=smooth(gz-iz);
        double ft=smooth(Math.floorMod(ticks,EPOCH_TICKS)/(double)EPOCH_TICKS);
        double[] values=new double[5];
        for(int dx=0;dx<2;dx++) for(int dz=0;dz<2;dz++) for(int dt=0;dt<2;dt++) {
            double weight=(dx==0?1-fx:fx)*(dz==0?1-fz:fz)*(dt==0?1-ft:ft);
            long node=mix(seed ^ mix(ix+dx) ^ Long.rotateLeft(mix(iz+dz),21) ^
                Long.rotateLeft(mix(epoch+dt),42));
            for(int field=0;field<5;field++) values[field]+=weight*unit(mix(node+field*0x9e3779b97f4a7c15L));
        }
        double base=profile.layers().stream().mapToDouble(CloudLayer::coverage).max().orElse(0);
        boolean wet=profile.layers().stream().anyMatch(l->l.precipitation()==Precipitation.RAIN ||
            l.precipitation()==Precipitation.SNOW);
        boolean storms=profile.layers().stream().anyMatch(l->l.tier()==CloudTier.STORM);
        double coverage=Math.min(1,base*(.5+values[0]));
        double precipitation=wet?coverage*values[1]:0;
        double storm=storms?coverage*values[2]:0;
        return new WeatherState(coverage,precipitation,storm,
            new Wind(clampWind(profile.wind().x()+(values[3]-.5)*.02),
                clampWind(profile.wind().z()+(values[4]-.5)*.02)));
    }
    private static double clampWind(double value) { return Math.max(-100,Math.min(100,value)); }
    private static double smooth(double v) { return v*v*(3-2*v); }
    private static double unit(long v) { return (v>>>11)*0x1.0p-53; }
    private static long mix(long v) {
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;
        v=(v^(v>>>27))*0x94d049bb133111ebL;
        return v^(v>>>31);
    }
}
