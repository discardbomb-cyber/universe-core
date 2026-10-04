package dev.heiko.universe.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Version 1: SHA-256, big-endian integers, length-prefixed UTF-8; first 8 bytes as signed long. */
public final class StableSeed {
    private StableSeed() {}
    public static long derive(long universeSeed, Versions versions, SectorKey key, String field, int... slots) {
        Objects.requireNonNull(versions); Objects.requireNonNull(key); Objects.requireNonNull(field); Objects.requireNonNull(slots);
        if (field.isEmpty() || field.length() > 128 || slots.length > 8) throw new IllegalArgumentException("Invalid seed domain");
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            write(out, "universe.seed.v1");
            out.writeLong(universeSeed); out.writeInt(versions.generator());
            write(out, key.realm().id()); write(out, key.realm().galaxyId());
            out.writeLong(key.x()); out.writeLong(key.y()); out.writeLong(key.z());
            write(out, field); out.writeInt(slots.length);
            for (int slot : slots) out.writeInt(slot);
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())).getLong();
        } catch (IOException | NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
}

