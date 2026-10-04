package dev.heiko.universe.ships;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Section summaries of a 1-block-thick shell, not a loaded Minecraft hull. */
class LargeSparseShipTest {
    private static final ShipId ID = new ShipId(new UUID(0,42));
    private static final int LENGTH=1024, HEIGHT=256, WIDTH=256;
    private record Entry(ShipSection section, ShipStructure.Summary summary) {}
    private record Fixture(ShipStructure structure, List<Entry> shell) {}

    private static Fixture shell() {
        var structure = new ShipStructure(ID, 64*16*16);
        List<Entry> entries = new ArrayList<>();
        for (int x=0;x<64;x++) for (int y=0;y<16;y++) for (int z=0;z<16;z++) {
            int innerX = x==0 || x==63 ? 15 : 16;
            int innerY = y==0 || y==15 ? 15 : 16;
            int innerZ = z==0 || z==15 ? 15 : 16;
            int blocks = ShipSection.VOLUME-innerX*innerY*innerZ;
            if (blocks==0) continue;
            // Center X around zero to exercise sections on both sides of the origin.
            var section = new ShipSection(ID,x-32,y,z);
            var summary = new ShipStructure.Summary(0,blocks,blocks*.5);
            entries.add(new Entry(section,summary));
            structure.update(section,summary);
        }
        return new Fixture(structure,List.copyOf(entries));
    }
    @Test void millionConceptualShellBlocksRequireOnlyBoundarySectionSummaries() {
        Fixture fixture=shell(); var hull=fixture.structure;
        long shellBlocks=(long)LENGTH*HEIGHT*WIDTH-(long)(LENGTH-2)*(HEIGHT-2)*(WIDTH-2);
        assertEquals(1_173_512,shellBlocks);
        assertEquals(shellBlocks,hull.occupiedBlocks());
        assertEquals(shellBlocks*.5,hull.mass());
        assertEquals(4232,hull.knownSections());
        assertEquals(new ShipStructure.WorkCounters(4232,4232,0),hull.workCounters());
        assertTrue(hull.knownSections() < 64*16*16);
    }
    @Test void tenThousandAnalyticMovementSamplesTouchNoHullSections() {
        var fixture=shell(); var hull=fixture.structure;
        var before=hull.workCounters(); long blocks=hull.occupiedBlocks();double mass=hull.mass();
        var orbit=new OrbitState(ID,"home",new ShipPose.SpaceContext("normal","milky_way","home"),
                Vec3.ZERO,new Vec3(1,0,0),new Vec3(0,0,1),10_000,0,0,.01,Rotation.IDENTITY);
        for (int tick=0;tick<10_000;tick++) {
            var pose=orbit.at(tick/20.0);
            assertEquals(10_000,pose.position().length(),1e-8);
            assertEquals(100,pose.velocity().length(),1e-8);
        }
        assertEquals(before,hull.workCounters());
        assertEquals(blocks,hull.occupiedBlocks());assertEquals(mass,hull.mass());
        // The orbit API has no ShipStructure/block-provider dependency to secretly scan.
        assertTrue(java.util.Arrays.stream(OrbitState.class.getRecordComponents())
                .noneMatch(c->c.getType()==ShipStructure.class));
    }
    @Test void thirtyTwoEditsAggregateOnlyChangedSectionsAndReplaysDoNoAggregateWork() {
        var fixture=shell();var hull=fixture.structure;var before=hull.workCounters();
        long blocks=hull.occupiedBlocks();double mass=hull.mass();
        for(int i=0;i<32;i++) {
            Entry entry=fixture.shell.get(i);
            var changed=new ShipStructure.Summary(1,entry.summary.occupiedBlocks()-1,entry.summary.mass()-.5);
            assertTrue(hull.update(entry.section,changed));
            assertFalse(hull.update(entry.section,changed));
        }
        var after=hull.workCounters();
        assertEquals(64,after.sectionLookups()-before.sectionLookups());
        assertEquals(32,after.aggregateUpdates()-before.aggregateUpdates());
        assertEquals(0,after.snapshotEntriesCopied()-before.snapshotEntriesCopied());
        assertEquals(blocks-32,hull.occupiedBlocks());assertEquals(mass-16,hull.mass());
        var snapshot=hull.snapshot();
        assertEquals(4232,snapshot.size());assertEquals(4232,hull.workCounters().snapshotEntriesCopied());
        assertEquals(fixture.shell.get(100).summary,snapshot.get(fixture.shell.get(100).section));
        assertEquals(32,snapshot.values().stream().filter(s->s.revision()==1).count());
    }
}
