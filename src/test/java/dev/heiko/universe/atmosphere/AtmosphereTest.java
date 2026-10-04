package dev.heiko.universe.atmosphere;

import dev.heiko.universe.core.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtmosphereTest {
    private static BodyKey planet(Realm realm, int slot) {
        return new BodyKey(new SystemKey(new SectorKey(realm,-2,3,0),1),BodyKey.Kind.PLANET,-1,slot);
    }
    @Test void vacuumHasNoCloudsOrWeather() {
        var profile=AtmosphereProfiles.of(AtmosphereKind.VACUUM);
        assertFalse(profile.hasAtmosphere()); assertTrue(profile.layers().isEmpty());
        var weather=new WeatherCell(42,planet(Realm.MILKY_WAY,0),profile);
        assertEquals(WeatherState.VACUUM,weather.sample(-2048,2048,Long.MIN_VALUE));
        assertEquals(WeatherState.VACUUM,weather.sample(500,900,Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->new AtmosphereBridgeDto(1,weather.planet(),profile,
            new WeatherState(.1,0,0,new Wind(0,0)),0,0,0));
    }
    @Test void presetsExposeFiveTiersAndAreImmutable() {
        for(var kind:AtmosphereKind.values()) assertNotNull(AtmosphereProfiles.of(kind));
        var original=AtmosphereProfiles.of(AtmosphereKind.TEMPERATE);
        assertEquals(5,original.layers().size());
        var input=new ArrayList<>(original.layers());
        var copy=copyWithLayers(original,input); input.clear();
        assertEquals(5,copy.layers().size());
        assertThrows(UnsupportedOperationException.class,()->copy.layers().clear());
    }
    @Test void rejectsInvalidRangesAndIncompatibleLayers() {
        var p=AtmosphereProfiles.of(AtmosphereKind.TEMPERATE);
        assertThrows(IllegalArgumentException.class,()->new ColorRgb(Double.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->new Wind(Double.POSITIVE_INFINITY,0));
        assertThrows(IllegalArgumentException.class,()->layer(96,96,0.5,CloudType.CUMULUS));
        assertThrows(IllegalArgumentException.class,()->layer(-1,96,0.5,CloudType.CUMULUS));
        assertThrows(IllegalArgumentException.class,()->layer(0,96,1.1,CloudType.CUMULUS));
        assertThrows(IllegalArgumentException.class,()->layer(0,96,.5,CloudType.FOG));
        assertThrows(IllegalArgumentException.class,()->copyWithLayers(p,List.of(p.layers().get(1),p.layers().get(1))));
        assertThrows(IllegalArgumentException.class,()->new AtmosphereProfile(AtmosphereKind.VACUUM,
            0,0,new ColorRgb(0,0,0),0,0,1,0,250,new Wind(0,0),p.layers()));
        var weather=new WeatherCell(1,planet(Realm.END,0),p);
        assertThrows(IllegalArgumentException.class,()->weather.sample(Double.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->weather.sample(0x1.0p41,0,0));
    }
    @Test void determinismSeparatesRealmPlanetAndSeed() {
        var p=AtmosphereProfiles.of(AtmosphereKind.TEMPERATE);
        var a=new WeatherCell(42,planet(Realm.MILKY_WAY,0),p);
        var b=new WeatherCell(42,planet(Realm.MILKY_WAY,0),p);
        var expected=a.sample(-3200,1742,81234);
        a.sample(999,100,400); // request order has no state
        assertEquals(expected,b.sample(-3200,1742,81234));
        assertEquals(expected,a.sample(-3200,1742,81234));
        assertNotEquals(expected,new WeatherCell(42,planet(Realm.END,0),p).sample(-3200,1742,81234));
        assertNotEquals(expected,new WeatherCell(42,planet(Realm.MILKY_WAY,1),p).sample(-3200,1742,81234));
        assertNotEquals(expected,new WeatherCell(43,planet(Realm.MILKY_WAY,0),p).sample(-3200,1742,81234));
    }
    @Test void continuousAcrossPositiveNegativeSpatialAndTemporalBoundaries() {
        var w=new WeatherCell(42,planet(Realm.NETHER,3),AtmosphereProfiles.of(AtmosphereKind.TEMPERATE));
        for(double boundary:new double[]{-2048,0,2048}) {
            close(w.sample(boundary-1e-4,123,37),w.sample(boundary+1e-4,123,37),1e-8);
            close(w.sample(123,boundary-1e-4,37),w.sample(123,boundary+1e-4,37),1e-8);
        }
        for(long boundary:new long[]{-100,0,100}) {
            close(w.sample(513,-400,boundary-1),w.sample(513,-400,boundary),.001);
            close(w.sample(513,-400,boundary),w.sample(513,-400,boundary+1),.001);
        }
    }
    @Test void bridgeCarriesAuthoritativeIdentityTimeAndSample() {
        var id=planet(Realm.END,2); var p=AtmosphereProfiles.of(AtmosphereKind.THIN);
        var dto=AtmosphereBridgeDto.sample(44,id,p,12,34,567);
        assertEquals(id,dto.planet()); assertEquals(567,dto.universeTicks());
        assertEquals(new WeatherCell(44,id,p).sample(12,34,567),dto.weather());
        assertEquals(0,dto.weather().precipitationIntensity());
        assertEquals(0,dto.weather().stormActivity());
    }
    private static CloudLayer layer(double bottom,double top,double coverage,CloudType type) {
        return new CloudLayer(CloudTier.LOW,type,bottom,top,coverage,.5,128,new Wind(0,0),
            Precipitation.NONE,new ColorRgb(1,1,1),Quality.LOW);
    }
    private static AtmosphereProfile copyWithLayers(AtmosphereProfile p,List<CloudLayer> layers) {
        return new AtmosphereProfile(p.kind(),p.thickness(),p.densityFalloff(),p.scattering(),p.aerosols(),
            p.absorption(),p.shellRadiusRatio(),p.pressure(),p.temperatureKelvin(),p.wind(),layers);
    }
    private static void close(WeatherState a,WeatherState b,double tolerance) {
        assertEquals(a.coverage(),b.coverage(),tolerance);
        assertEquals(a.precipitationIntensity(),b.precipitationIntensity(),tolerance);
        assertEquals(a.stormActivity(),b.stormActivity(),tolerance);
        assertEquals(a.wind().x(),b.wind().x(),tolerance);
        assertEquals(a.wind().z(),b.wind().z(),tolerance);
    }
}
