package dev.heiko.universe.ships;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static dev.heiko.universe.ships.DockConnection.State.*;
import static dev.heiko.universe.ships.DockingController.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class DockingControllerTest {
    private final ShipPose.SpaceContext context = new ShipPose.SpaceContext("normal","milky_way","home");
    private final DockPort a = port(1,context,"standard",2);
    private final DockPort b = port(2,context,"standard",2);
    private final ShipPose relative = new ShipPose(context,new Vec3(5,0,0),Rotation.IDENTITY,Vec3.ZERO);
    private DockPort port(int id, ShipPose.SpaceContext c, String connector, int size) {
        return new DockPort(new UUID(0,id),new ShipId(new UUID(1,id)),c,Vec3.ZERO,Rotation.IDENTITY,connector,size,0,true);
    }
    private DockingController controller() {
        DockingController d = new DockingController(2000); d.putPort(a); d.putPort(b); return d;
    }
    private DockingController.Evidence evidence(double gap, boolean ready, boolean departure) {
        return new DockingController.Evidence(0,0,true,true,gap,0,0,true,true,ready,true,true,departure,relative);
    }
    private DockConnection reserve(DockingController d) {
        var r = d.reserve(UUID.randomUUID(),a.id(),b.id(),a.shipId(),0,100,true);
        assertEquals(OK,r.code()); return r.connection();
    }
    private DockConnection step(DockingController d, DockConnection c, DockConnection.State next) {
        var r = d.advance(UUID.randomUUID(),c.id(),c.revision(),next,1,evidence(0,true,true));
        assertEquals(OK,r.code()); return r.connection();
    }
    @Test void hundredCyclesPreserveIdentityAndReleaseBothObjects() {
        DockingController d=controller();
        for (int i=0;i<100;i++) {
            DockConnection c=reserve(d);
            for (var state : new DockConnection.State[]{APPROACH,ALIGNING,CAPTURED,SEALED,DOCKED}) {
                c=step(d,c,state); assertEquals(state==DOCKED,c.passageOpen());
            }
            assertEquals(relative,c.relativePose()); assertEquals(a.shipId(),c.root());
            c=step(d,c,RELEASED); assertFalse(c.passageOpen());
            assertTrue(d.occupiedBy(a.shipId()).isEmpty()); assertTrue(d.occupiedBy(b.shipId()).isEmpty());
        }
    }
    @Test void replayReturnsOriginalResultAndDifferentPayloadConflicts() {
        var d=controller(); UUID op=UUID.randomUUID();
        var original=d.reserve(op,a.id(),b.id(),a.shipId(),0,100,true);
        DockConnection moved=step(d,original.connection(),APPROACH);
        assertEquals(original,d.reserve(op,a.id(),b.id(),a.shipId(),0,100,true));
        assertEquals(CONFLICT,d.reserve(op,b.id(),a.id(),a.shipId(),0,100,true).code());
        UUID stepId=UUID.randomUUID();
        var response=d.advance(stepId,moved.id(),moved.revision(),ALIGNING,1,evidence(0,true,true));
        assertEquals(response,d.advance(stepId,moved.id(),moved.revision(),ALIGNING,1,evidence(0,true,true)));
        assertEquals(STALE,d.advance(UUID.randomUUID(),moved.id(),moved.revision(),CAPTURED,1,evidence(0,true,true)).code());
    }
    @Test void rejectsRealmSystemConnectorSizeAndSelf() {
        for (DockPort incompatible : new DockPort[]{
                port(2,new ShipPose.SpaceContext("end","milky_way","home"),"standard",2),
                port(2,new ShipPose.SpaceContext("normal","milky_way","other"),"standard",2),
                port(2,new ShipPose.SpaceContext("normal","other","home"),"standard",2),
                port(2,context,"other",2),port(2,context,"standard",3),a}) {
            var d=new DockingController(20); d.putPort(a); d.putPort(incompatible);
            assertEquals(INCOMPATIBLE,d.reserve(UUID.randomUUID(),a.id(),incompatible.id(),a.shipId(),0,100,true).code());
            assertTrue(d.occupiedBy(a.shipId()).isEmpty());
        }
    }
    @Test void secondPortOnSameObjectCannotMakeAnotherConnection() {
        var d=controller(); reserve(d);
        var extra=new DockPort(UUID.randomUUID(),a.shipId(),context,Vec3.ZERO,Rotation.IDENTITY,"standard",2,0,true);
        var third=port(3,context,"standard",2); d.putPort(extra);d.putPort(third);
        assertEquals(BUSY,d.reserve(UUID.randomUUID(),extra.id(),third.id(),a.shipId(),0,100,true).code());
    }
    @Test void expiryReleasesUncapturedPairAndUnsafeCaptureBlocksPair() {
        var d=controller();var c=reserve(d);
        assertEquals(EXPIRED,d.advance(UUID.randomUUID(),c.id(),0,APPROACH,100,evidence(0,true,true)).code());
        assertTrue(d.occupiedBy(a.shipId()).isEmpty());
        c=step(d,step(d,reserve(d),APPROACH),ALIGNING);
        var failed=d.advance(UUID.randomUUID(),c.id(),c.revision(),CAPTURED,1,evidence(2,true,true));
        assertEquals(UNSAFE,failed.code());assertEquals(BLOCKED,failed.connection().state());
        assertTrue(d.occupiedBy(a.shipId()).isPresent());assertFalse(failed.connection().passageOpen());
    }
    @Test void invalidOrderCannotOpenPassageAndPortEditClosesIt() {
        var d=controller();var c=reserve(d);
        assertEquals(INVALID,d.advance(UUID.randomUUID(),c.id(),0,DOCKED,1,evidence(0,true,true)).code());
        for(var s:new DockConnection.State[]{APPROACH,ALIGNING,CAPTURED,SEALED,DOCKED}) c=step(d,c,s);
        d.putPort(new DockPort(a.id(),a.shipId(),context,Vec3.ZERO,Rotation.IDENTITY,"standard",2,1,false));
        assertEquals(BLOCKED,d.connection(c.id()).orElseThrow().state());
        assertFalse(d.connection(c.id()).orElseThrow().passageOpen());
    }
    @Test void unsafeUndockAndRecoveryKeepPassageClosedUntilSafeRelease() {
        var d=controller();var c=reserve(d);
        for(var s:new DockConnection.State[]{APPROACH,ALIGNING,CAPTURED,SEALED,DOCKED}) c=step(d,c,s);
        var failed=d.advance(UUID.randomUUID(),c.id(),c.revision(),RELEASED,1,evidence(0,true,false));
        assertEquals(UNSAFE,failed.code()); assertEquals(BLOCKED,failed.connection().state());
        var recovered=controller();recovered.restoreBlocked(c);
        var blocked=recovered.connection(c.id()).orElseThrow();assertFalse(blocked.passageOpen());
        assertThrows(IllegalArgumentException.class,()->recovered.restoreBlocked(blocked));
        step(recovered,blocked,RELEASED);assertTrue(recovered.occupiedBy(a.shipId()).isEmpty());
    }
    @Test void boundedReplayHistoryRejectsNewOperationsWithoutEviction() {
        var d=new DockingController(1);d.putPort(a);d.putPort(b);
        UUID id=UUID.randomUUID();var r=d.reserve(id,a.id(),b.id(),a.shipId(),0,100,true);
        assertEquals(CAPACITY,d.advance(UUID.randomUUID(),r.connection().id(),0,APPROACH,1,evidence(0,true,true)).code());
        assertEquals(r,d.reserve(id,a.id(),b.id(),a.shipId(),0,100,true));
    }
    @Test void permissionsAndUnpreparedPassageFailClosed() {
        var d=controller();
        assertEquals(UNSAFE,d.reserve(UUID.randomUUID(),a.id(),b.id(),a.shipId(),0,100,false).code());
        assertTrue(d.occupiedBy(a.shipId()).isEmpty());
        var c=reserve(d);
        for(var state:new DockConnection.State[]{APPROACH,ALIGNING,CAPTURED,SEALED}) c=step(d,c,state);
        var r=d.advance(UUID.randomUUID(),c.id(),c.revision(),DOCKED,1,evidence(0,false,true));
        assertEquals(UNSAFE,r.code());assertFalse(r.connection().passageOpen());
    }
    @Test void failedCommandsReplaysAndFloodCannotGrowHistoryBeyondLimit() {
        int limit=16;
        var d=new DockingController(limit);d.putPort(a);d.putPort(b);
        UUID originalId=UUID.randomUUID();
        var original=d.reserve(originalId,a.id(),b.id(),a.shipId(),0,100,true);
        // The failed commands also consume capacity, so a caller cannot create an unbounded error cache.
        for(int i=1;i<limit;i++)
            assertEquals(BUSY,d.reserve(UUID.randomUUID(),a.id(),b.id(),a.shipId(),0,100,true).code());
        assertEquals(limit,d.operationHistorySize());assertEquals(limit,d.operationHistoryLimit());
        var before=d.connection(original.connection().id()).orElseThrow();
        for(int i=0;i<10_000;i++) {
            assertEquals(CAPACITY,d.reserve(UUID.randomUUID(),a.id(),b.id(),a.shipId(),0,100,true).code());
            assertEquals(CAPACITY,d.advance(UUID.randomUUID(),before.id(),before.revision(),APPROACH,1,evidence(0,true,true)).code());
            assertEquals(original,d.reserve(originalId,a.id(),b.id(),a.shipId(),0,100,true));
        }
        assertEquals(CONFLICT,d.reserve(originalId,b.id(),a.id(),a.shipId(),0,100,true).code());
        assertEquals(limit,d.operationHistorySize());
        assertEquals(before,d.connection(before.id()).orElseThrow());
        assertEquals(before.id(),d.occupiedBy(a.shipId()).orElseThrow());
    }
}
