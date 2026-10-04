package dev.heiko.universe.persistence;

import dev.heiko.universe.ships.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShipCatalogCodecTest {
    private final ShipId id = ShipId.parse("00000000-0000-0000-0000-000000000001");
    private ShipMetadata metadata() {
        var pose = new ShipPose(new ShipPose.SpaceContext("normal","milky_way","home"),
                new Vec3(12,-3,8),Rotation.IDENTITY,new Vec3(1,0,0));
        return new ShipMetadata(id,4,1,"universe:shipyard",pose,
                Map.of(new ShipSection(id,-1,0,2),new ShipStructure.Summary(3,0,0)),ShipMetadata.Status.ACTIVE);
    }
    @Test void roundTripPreservesIdentityPoseAndDeletionTombstone() {
        var source = new ShipCatalogCodec.Snapshot(Map.of(id,metadata()),Map.of());
        byte[] bytes = ShipCatalogCodec.encode(source);
        assertEquals(source,ShipCatalogCodec.decode(bytes));
        assertArrayEquals(bytes,ShipCatalogCodec.encode(ShipCatalogCodec.decode(bytes)));
        assertThrows(UnsupportedOperationException.class,()->source.ships().clear());
    }
    @Test void recoveryStopsMotionAndBlocksPassagesIncludingUnknownLinks() {
        var blocked=metadata().blocked();
        assertEquals(ShipMetadata.Status.BLOCKED,blocked.status());
        assertEquals(Vec3.ZERO,blocked.pose().velocity());
        assertEquals(metadata().id(),blocked.id());
        assertEquals(metadata().sections(),blocked.sections());
        var unknown=new ShipCatalogCodec.Link(UUID.randomUUID(),id,UUID.randomUUID(),
                new ShipId(UUID.randomUUID()),UUID.randomUUID(),id,7,"FUTURE_STATE");
        var snapshot=new ShipCatalogCodec.Snapshot(Map.of(id,metadata()),Map.of(unknown.id(),unknown));
        var restored=ShipCatalogCodec.decode(ShipCatalogCodec.encode(snapshot));
        assertEquals(unknown,restored.links().get(unknown.id()));
        assertEquals(DockConnection.State.BLOCKED,unknown.recoveryState());
    }
    @Test void rejectsUnknownSchemaTruncationTrailingBytesAndHostileCounts() {
        byte[] valid=ShipCatalogCodec.encode(new ShipCatalogCodec.Snapshot(Map.of(id,metadata()),Map.of()));
        byte[] future=valid.clone(); ByteBuffer.wrap(future).putInt(4,2);
        assertThrows(IllegalArgumentException.class,()->ShipCatalogCodec.decode(future));
        for (int length=0;length<valid.length;length++) {
            byte[] truncated=Arrays.copyOf(valid,length);
            assertThrows(IllegalArgumentException.class,()->ShipCatalogCodec.decode(truncated));
        }
        assertThrows(IllegalArgumentException.class,()->ShipCatalogCodec.decode(Arrays.copyOf(valid,valid.length+1)));
        byte[] hostile=valid.clone(); ByteBuffer.wrap(hostile).putInt(8,Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,()->ShipCatalogCodec.decode(hostile));
    }
    @Test void rejectsWrongShipSectionsAndOversizedText() {
        var original=metadata();
        var section=new ShipSection(new ShipId(UUID.randomUUID()),0,0,0);
        assertThrows(IllegalArgumentException.class,()->new ShipMetadata(id,0,1,original.interiorDimension(),
                original.pose(),Map.of(section,new ShipStructure.Summary(0,1,1)),ShipMetadata.Status.ACTIVE));
        assertThrows(IllegalArgumentException.class,()->new ShipMetadata(id,0,1,"x".repeat(257),
                original.pose(),Map.of(),ShipMetadata.Status.ACTIVE));
    }
    @Test void rejectsCatalogCapacityAndWrongIdentityKeys() {
        Map<ShipId,ShipMetadata> excessive = new HashMap<>();
        var original = metadata();
        for (int i=0;i<=ShipCatalogCodec.MAX_SHIPS;i++) {
            var nextId = new ShipId(new UUID(0,i));
            excessive.put(nextId,new ShipMetadata(nextId,0,1,original.interiorDimension(),
                    original.pose(),Map.of(),ShipMetadata.Status.BLOCKED));
        }
        assertThrows(IllegalArgumentException.class,()->new ShipCatalogCodec.Snapshot(excessive,Map.of()));
        assertThrows(IllegalArgumentException.class,()->new ShipCatalogCodec.Snapshot(
                Map.of(new ShipId(UUID.randomUUID()),original),Map.of()));
        assertThrows(IllegalArgumentException.class,()->ShipCatalogCodec.decode(new byte[ShipCatalogCodec.MAX_BYTES+1]));
    }
}
