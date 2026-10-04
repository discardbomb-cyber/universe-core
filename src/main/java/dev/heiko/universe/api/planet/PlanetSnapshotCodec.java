package dev.heiko.universe.api.planet;

import dev.heiko.universe.core.Realm;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.BufferUnderflowException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Deterministic in-memory snapshot format; no IO, registry bootstrap or dimensions.
 * Big-endian: magic UPSN, codec schema, snapshot format, revision, frozen byte,
 * definition count; then ordered records (id, realm.id, seed, generation,
 * atmosphere, surface). Strings are int byte length followed by strict UTF-8.
 * Schema 1 has no extension fields: unknown schemas and trailing bytes fail.
 * Future fields require a new schema and an explicit compatible reader/migration.
 * This is structural validation, not a checksum or proof of profile availability.
 */
public final class PlanetSnapshotCodec {
    public static final int MAGIC = 0x5550534e;
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    public static final int MAX_STRING_BYTES = 256;
    public static final int HEADER_BYTES = 25;
    public static final Limits DEFAULT_LIMITS =
            new Limits(PlanetRegistry.MAX_CAPACITY, MAX_TOTAL_BYTES, MAX_STRING_BYTES);

    /** Callers may tighten, never enlarge, the format's allocation limits. */
    public record Limits(int maxDefinitions, int maxTotalBytes, int maxStringBytes) {
        public Limits {
            if (maxDefinitions < 1 || maxDefinitions > PlanetRegistry.MAX_CAPACITY
                    || maxTotalBytes < HEADER_BYTES || maxTotalBytes > MAX_TOTAL_BYTES
                    || maxStringBytes < 1 || maxStringBytes > MAX_STRING_BYTES)
                throw new IllegalArgumentException("Invalid snapshot codec limits");
        }
    }

    private PlanetSnapshotCodec() {}

    public static byte[] encode(PlanetRegistry.Snapshot snapshot) {
        return encode(snapshot, DEFAULT_LIMITS);
    }

    public static byte[] encode(PlanetRegistry.Snapshot snapshot, Limits limits) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(limits, "limits");
        if (snapshot.definitions().size() > limits.maxDefinitions())
            throw invalid("Definition count exceeds limit");
        long size = HEADER_BYTES;
        for (PlanetDefinition definition : snapshot.definitions()) {
            size += 8L + stringSize(definition.id(), limits) + stringSize(definition.realm().id(), limits)
                    + stringSize(definition.generationProfileId(), limits)
                    + stringSize(definition.atmosphereProfileId(), limits)
                    + stringSize(definition.surfaceBindingId(), limits);
            if (size > limits.maxTotalBytes()) throw invalid("Snapshot bytes exceed limit");
        }
        var output = ByteBuffer.allocate((int) size).order(ByteOrder.BIG_ENDIAN);
        output.putInt(MAGIC).putInt(SCHEMA_VERSION).putInt(snapshot.formatVersion())
                .putLong(snapshot.revision()).put((byte) (snapshot.frozen() ? 1 : 0))
                .putInt(snapshot.definitions().size());
        for (PlanetDefinition definition : snapshot.definitions()) {
            writeString(output, definition.id());
            writeString(output, definition.realm().id());
            output.putLong(definition.seed());
            writeString(output, definition.generationProfileId());
            writeString(output, definition.atmosphereProfileId());
            writeString(output, definition.surfaceBindingId());
        }
        return output.array();
    }

    public static PlanetRegistry.Snapshot decode(byte[] input) {
        return decode(input, DEFAULT_LIMITS);
    }

    /** Input is read-only and is never retained by the returned snapshot. */
    public static PlanetRegistry.Snapshot decode(byte[] input, Limits limits) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(limits, "limits");
        if (input.length < HEADER_BYTES || input.length > limits.maxTotalBytes())
            throw invalid("Invalid snapshot byte count");
        var buffer = ByteBuffer.wrap(input).asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        try {
            if (buffer.getInt() != MAGIC) throw invalid("Invalid snapshot magic");
            if (buffer.getInt() != SCHEMA_VERSION) throw invalid("Unsupported snapshot codec schema");
            int format = buffer.getInt();
            if (format != PlanetRegistry.FORMAT_VERSION) throw invalid("Unsupported snapshot format");
            long revision = buffer.getLong();
            byte frozen = buffer.get();
            if (frozen != 0 && frozen != 1) throw invalid("Invalid frozen flag");
            int count = buffer.getInt();
            // Every record needs five nonempty length-prefixed strings and one long.
            if (count < 0 || count > limits.maxDefinitions() || count > buffer.remaining() / 33
                    || revision != count) throw invalid("Invalid snapshot count/revision");
            var definitions = new ArrayList<PlanetDefinition>(count);
            for (int i = 0; i < count; i++) {
                String id = readString(buffer, limits);
                Realm realm = readRealm(readString(buffer, limits));
                long seed = buffer.getLong();
                definitions.add(new PlanetDefinition(id, realm, seed, readString(buffer, limits),
                        readString(buffer, limits), readString(buffer, limits)));
            }
            if (buffer.hasRemaining()) throw invalid("Trailing snapshot bytes");
            // Reuse the existing duplicate-ID, immutable-list and version invariants.
            return new PlanetRegistry.Snapshot(format, revision, frozen == 1, definitions);
        } catch (BufferUnderflowException ex) {
            throw new IllegalArgumentException("Truncated planet snapshot", ex);
        }
    }

    private static int stringSize(String value, Limits limits) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 1 || bytes > limits.maxStringBytes()) throw invalid("String bytes exceed limit");
        return Integer.BYTES + bytes;
    }

    private static void writeString(ByteBuffer output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.putInt(bytes.length).put(bytes);
    }

    private static String readString(ByteBuffer input, Limits limits) {
        int size = input.getInt();
        if (size < 1 || size > limits.maxStringBytes() || size > input.remaining())
            throw invalid("Invalid or truncated string length");
        var bytes = input.slice();
        bytes.limit(size);
        input.position(input.position() + size);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Invalid snapshot UTF-8", ex);
        }
    }

    private static Realm readRealm(String id) {
        for (Realm realm : Realm.values()) if (realm.id().equals(id)) return realm;
        throw invalid("Unknown realm ID");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}

