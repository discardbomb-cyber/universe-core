package dev.heiko.universe.atmosphere;
import java.util.List;
/** Initial artistic defaults; dense/thin/giant remain provisional presets. */
public final class AtmosphereProfiles {
    private AtmosphereProfiles() {}
    private static final ColorRgb WHITE = new ColorRgb(.9, .92, .95);
    public static AtmosphereProfile of(AtmosphereKind kind) {
        if (kind == AtmosphereKind.VACUUM)
            return new AtmosphereProfile(kind, 0, 0, new ColorRgb(0,0,0), 0,0,1,0,250,new Wind(0,0),List.of());
        double multiplier = switch(kind) { case THIN -> .25; case DENSE -> 1.8; case GAS_GIANT -> 3; default -> 1; };
        List<CloudLayer> layers = kind == AtmosphereKind.THIN
            ? List.of(layer(CloudTier.HIGH, CloudType.CIRRUS, 640, 960, .15, Precipitation.NONE))
            : List.of(layer(CloudTier.GROUND, CloudType.FOG, 0,48,.15,Precipitation.NONE),
                layer(CloudTier.LOW, CloudType.CUMULUS,96,192,.45,Precipitation.RAIN),
                layer(CloudTier.MIDDLE, CloudType.ALTOSTRATUS,256,448,.3,Precipitation.RAIN),
                layer(CloudTier.HIGH, CloudType.CIRRUS,640,960,.2,Precipitation.NONE),
                layer(CloudTier.STORM, CloudType.CUMULONIMBUS,96,768,.1,Precipitation.RAIN));
        return new AtmosphereProfile(kind, 1024 * multiplier, 1.0 / (256 * multiplier),
            new ColorRgb(.35,.55,.9), Math.min(.15*multiplier,1), .1,1 + .01*multiplier,
            multiplier,288,new Wind(.03,.01),layers);
    }
    private static CloudLayer layer(CloudTier tier, CloudType type, double bottom, double top,
                                    double coverage, Precipitation precipitation) {
        return new CloudLayer(tier,type,bottom,top,coverage,.5,128,new Wind(.03,.01),
            precipitation,WHITE,Quality.MEDIUM);
    }
}
