package dev.heiko.universe.api.planet;

import dev.heiko.universe.core.Realm;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanetSnapshotCodecTest {
    private static PlanetDefinition planet(String id, Realm realm, long seed) {
        return new PlanetDefinition(id, realm, seed, "addon:terrain/v1", "addon:atmosphere", "addon:surface");
    }
    private static PlanetRegistry.Snapshot sample() {
        return new PlanetRegistry.Snapshot(1, 2, true, List.of(
                planet("addon:z", Realm.END, Long.MIN_VALUE),
                planet("addon:a", Realm.NETHER, Long.MAX_VALUE)));
    }
    private static byte[] integer(byte[] input, int offset, int value) {
        var copy = input.clone(); ByteBuffer.wrap(copy).putInt(offset, value); return copy;
    }
    private static void rejects(byte[] input) {
        byte[] before = input.clone();
        assertThrows(IllegalArgumentException.class, () -> PlanetSnapshotCodec.decode(input));
        assertArrayEquals(before, input);
    }

    @Test void fiveThousandDefinitionsRoundTripWithoutWorldBootstrap() {
        var registry = new PlanetRegistry();
        for (int i = 0; i < 5000; i++) registry.register(planet("addon:p_" + i,
                Realm.values()[i % 3], i % 2 == 0 ? Long.MIN_VALUE + i : Long.MAX_VALUE - i));
        var snapshot = registry.freeze();
        byte[] encoded = PlanetSnapshotCodec.encode(snapshot);
        byte[] pristine = encoded.clone();
        var decoded = PlanetSnapshotCodec.decode(encoded);
        assertEquals(snapshot, decoded);
        assertEquals(snapshot.definitions().get(4999), decoded.definitions().get(4999));
        assertArrayEquals(pristine, encoded);
        assertArrayEquals(encoded, PlanetSnapshotCodec.encode(decoded));
        encoded[0] = 0;
        assertEquals(snapshot, decoded);
        assertThrows(UnsupportedOperationException.class, () -> decoded.definitions().clear());
    }

    @Test void preservesOrderExtremeSeedsAndBothFrozenStates() {
        var snapshot = sample();
        assertEquals(snapshot, PlanetSnapshotCodec.decode(PlanetSnapshotCodec.encode(snapshot)));
        assertEquals(List.of("addon:z", "addon:a"), snapshot.definitions().stream().map(PlanetDefinition::id).toList());
        for (boolean frozen : new boolean[]{false, true}) {
            var empty = new PlanetRegistry.Snapshot(1, 0, frozen, List.of());
            byte[] bytes = PlanetSnapshotCodec.encode(empty);
            assertEquals(25, bytes.length);
            assertEquals(empty, PlanetSnapshotCodec.decode(bytes));
        }
    }

    @Test void rejectsUnknownHeaderFlagsRevisionTrailingAndEveryTruncation() {
        byte[] valid = PlanetSnapshotCodec.encode(sample());
        rejects(integer(valid, 0, 0));
        rejects(integer(valid, 4, 2));
        rejects(integer(valid, 8, 2));
        var flag = valid.clone(); flag[20] = 2; rejects(flag);
        var revision = valid.clone(); ByteBuffer.wrap(revision).putLong(12, -1); rejects(revision);
        rejects(integer(valid, 21, -1));
        rejects(integer(valid, 21, Integer.MAX_VALUE));
        for (int length = 0; length < valid.length; length++) rejects(Arrays.copyOf(valid, length));
        rejects(Arrays.copyOf(valid, valid.length + 1));
    }

    @Test void invalidIdentifiersRealmUtf8AndDuplicateRecordsNeverGetReplaced() {
        byte[] valid = PlanetSnapshotCodec.encode(sample());
        var invalidId = valid.clone(); invalidId[29] = 'A'; rejects(invalidId);
        var malformed = valid.clone(); malformed[29] = (byte) 0xff; rejects(malformed);
        int realmStart = 29 + "addon:z".length() + 4;
        var realm = valid.clone(); realm[realmStart] = 'x'; rejects(realm);
        rejects(integer(valid, 25, 0));
        rejects(integer(valid, 25, -1));
        rejects(integer(valid, 25, Integer.MAX_VALUE));
        // Duplicate an otherwise valid entire record; preserve header count/revision.
        var one = new PlanetRegistry.Snapshot(1, 1, false, List.of(planet("addon:one", Realm.END, 42)));
        byte[] single = PlanetSnapshotCodec.encode(one);
        int recordLength = single.length - 25;
        byte[] duplicate = Arrays.copyOf(single, 25 + recordLength * 2);
        System.arraycopy(single, 25, duplicate, single.length, recordLength);
        ByteBuffer.wrap(duplicate).putLong(12, 2).putInt(21, 2);
        rejects(duplicate);
    }

    @Test void limitsApplyBeforeAllocationToEncodingAndDecoding() {
        byte[] valid = PlanetSnapshotCodec.encode(sample());
        var countLimit = new PlanetSnapshotCodec.Limits(1, PlanetSnapshotCodec.MAX_TOTAL_BYTES, 256);
        var byteLimit = new PlanetSnapshotCodec.Limits(2, valid.length - 1, 256);
        var stringLimit = new PlanetSnapshotCodec.Limits(2, valid.length, 3);
        for (var limits : List.of(countLimit, byteLimit, stringLimit)) {
            assertThrows(IllegalArgumentException.class, () -> PlanetSnapshotCodec.encode(sample(), limits));
            assertThrows(IllegalArgumentException.class, () -> PlanetSnapshotCodec.decode(valid, limits));
        }
        var exact = new PlanetSnapshotCodec.Limits(2, valid.length, 256);
        assertArrayEquals(valid, PlanetSnapshotCodec.encode(sample(), exact));
        assertEquals(sample(), PlanetSnapshotCodec.decode(valid, exact));
        assertThrows(IllegalArgumentException.class, () -> new PlanetSnapshotCodec.Limits(0, 25, 1));
        assertThrows(IllegalArgumentException.class, () -> new PlanetSnapshotCodec.Limits(1, 24, 1));
        assertThrows(IllegalArgumentException.class, () -> new PlanetSnapshotCodec.Limits(1, 25, 257));
        assertThrows(IllegalArgumentException.class, () -> new PlanetSnapshotCodec.Limits(1,
                PlanetSnapshotCodec.MAX_TOTAL_BYTES + 1, 1));
        rejects(new byte[PlanetSnapshotCodec.MAX_TOTAL_BYTES + 1]);
    }

    @Test void fixedEmptyGoldenVectorPinsHeaderEncoding() {
        byte[] expected = java.util.HexFormat.of().parseHex(
                "5550534e000000010000000100000000000000000100000000");
        assertArrayEquals(expected, PlanetSnapshotCodec.encode(new PlanetRegistry.Snapshot(1, 0, true, List.of())));
        assertEquals(new PlanetRegistry.Snapshot(1, 0, true, List.of()), PlanetSnapshotCodec.decode(expected));
    }
}

