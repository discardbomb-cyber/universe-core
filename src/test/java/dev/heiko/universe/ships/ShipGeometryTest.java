package dev.heiko.universe.ships;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ShipGeometryTest {
    private final ShipId ship = ShipId.parse("00000000-0000-0000-0000-000000000001");
    private final ShipPose.SpaceContext context = new ShipPose.SpaceContext("normal", "milky_way", "home");

    @Test void identitySurvivesTextRoundTrip() { assertEquals(ship, ShipId.parse(ship.value().toString())); }
    @Test void sectionsCoverNegativeBoundariesAndAll4096Cells() {
        assertEquals(new ShipSection(ship,-1,-2,1), ShipSection.containing(ship,-1,-17,16));
        boolean[] seen = new boolean[4096];
        for (int y=-16;y<0;y++) for (int z=-16;z<0;z++) for (int x=-16;x<0;x++) {
            int i = ShipSection.index(x,y,z);
            assertFalse(seen[i]); seen[i]=true;
            assertEquals(x+16, ShipSection.localX(i));
            assertEquals(y+16, ShipSection.localY(i));
            assertEquals(z+16, ShipSection.localZ(i));
        }
        assertThrows(IllegalArgumentException.class, () -> ShipSection.localY(4096));
    }
    @Test void rotatedTransformRoundTripsAndChildFollowsRoot() {
        Rotation r = new Rotation(0,0,Math.sin(Math.PI/4),Math.cos(Math.PI/4));
        ShipPose root = new ShipPose(context,new Vec3(10,20,30),r,new Vec3(1,0,0));
        Vec3 point = new Vec3(2,3,4);
        near(point,root.spaceToLocal(root.localToSpace(point)));
        ShipPose child = new ShipPose(context,new Vec3(7,22,34),Rotation.IDENTITY,root.velocity());
        ShipPose restored = root.follow(child.relativeTo(root));
        near(child.position(), restored.position());
        near(new Vec3(1,2,0), root.velocityAt(new Vec3(0,-2,0),new Vec3(0,0,1)));
    }
    @Test void orbitHasAnalyticVelocityAndCanBeRestoredAtAnyTime() {
        OrbitState orbit = new OrbitState(ship,"earth",context,new Vec3(10,0,0),
                new Vec3(1,0,0),new Vec3(0,0,1),100,0,1000,Math.PI/2,Rotation.IDENTITY);
        near(new Vec3(110,0,0),orbit.at(1000).position());
        near(new Vec3(10,0,100),orbit.at(1001).position());
        near(orbit.at(1000).position(),orbit.at(1004).position());
        double h = 1e-5;
        near(orbit.at(1001+h).position().subtract(orbit.at(1001-h).position()).scale(1/(2*h)),orbit.at(1001).velocity(),1e-5);
        assertThrows(IllegalArgumentException.class, () -> orbit.at(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new OrbitState(ship,"x",context,Vec3.ZERO,
                new Vec3(1,0,0),new Vec3(1,0,0),1,0,0,1,Rotation.IDENTITY));
    }
    @Test void rejectsInvalidNumerics() {
        assertThrows(IllegalArgumentException.class, () -> new Vec3(Double.POSITIVE_INFINITY,0,0));
        assertThrows(IllegalArgumentException.class, () -> new Rotation(0,0,0,0));
        assertThrows(IllegalArgumentException.class, () -> Vec3.ZERO.normalized());
        assertThrows(NullPointerException.class, () -> new ShipId((UUID)null));
    }
    @Test void summariesUpdateByEventAndKeepDeletionRevision() {
        ShipStructure hull=new ShipStructure(ship,2);
        ShipSection section=new ShipSection(ship,0,0,0);
        var first=new ShipStructure.Summary(0,4096,100);
        assertTrue(hull.update(section,first));assertFalse(hull.update(section,first));
        hull.update(new ShipSection(ship,1,0,0),new ShipStructure.Summary(0,10,5));
        assertEquals(4106,hull.occupiedBlocks());assertEquals(105,hull.mass());
        hull.update(section,new ShipStructure.Summary(1,0,0));
        assertEquals(10,hull.occupiedBlocks());assertEquals(5,hull.mass());
        assertThrows(IllegalArgumentException.class,()->hull.update(section,first));
        assertThrows(IllegalStateException.class,()->hull.update(new ShipSection(ship,2,0,0),first));
        assertThrows(UnsupportedOperationException.class,()->hull.snapshot().clear());
        assertThrows(IllegalArgumentException.class,()->new ShipStructure.Summary(0,4097,1));
    }
    private static void near(Vec3 expected, Vec3 actual) { near(expected, actual, 1e-9); }
    private static void near(Vec3 expected, Vec3 actual, double delta) {
        assertEquals(expected.x(),actual.x(),delta); assertEquals(expected.y(),actual.y(),delta); assertEquals(expected.z(),actual.z(),delta);
    }
}
