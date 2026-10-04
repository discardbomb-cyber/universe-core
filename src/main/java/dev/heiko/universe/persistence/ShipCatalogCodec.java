package dev.heiko.universe.persistence;

import dev.heiko.universe.ships.*;
import java.io.*;
import java.util.*;

/** Pure, bounded versioned codec. No Minecraft bootstrap is needed for its tests. */
public final class ShipCatalogCodec {
    public static final int SCHEMA = 1;
    public static final int MAX_SHIPS = 1024;
    public static final int MAX_SECTIONS_PER_SHIP = 4096;
    public static final int MAX_TOTAL_SECTIONS = 65536;
    public static final int MAX_LINKS = 512;
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAGIC = 0x554E5348;
    private ShipCatalogCodec() {}

    /** Unresolved references are retained, never interpreted as a usable passage. */
    public record Link(UUID id, ShipId first, UUID firstPort, ShipId second, UUID secondPort,
                       ShipId root, long revision, String savedState) {
        public Link {
            Objects.requireNonNull(id); Objects.requireNonNull(first); Objects.requireNonNull(firstPort);
            Objects.requireNonNull(second); Objects.requireNonNull(secondPort); Objects.requireNonNull(root);
            checkText(savedState);
            if (first.equals(second) || revision < 0 || (!root.equals(first) && !root.equals(second)))
                throw new IllegalArgumentException("Invalid ship link");
        }
        public static Link from(DockConnection connection) {
            return new Link(connection.id(), connection.first().shipId(), connection.first().id(),
                    connection.second().shipId(), connection.second().id(), connection.root(),
                    connection.revision(), connection.state().name());
        }
        public DockConnection.State recoveryState() { return DockConnection.State.BLOCKED; }
    }
    public record Snapshot(Map<ShipId, ShipMetadata> ships, Map<UUID, Link> links) {
        public Snapshot {
            ships = Map.copyOf(ships); links = Map.copyOf(links);
            if (ships.size() > MAX_SHIPS || links.size() > MAX_LINKS)
                throw new IllegalArgumentException("Ship catalog capacity");
            int sections = 0;
            for (var entry : ships.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().id())) throw new IllegalArgumentException("Ship key mismatch");
                sections = Math.addExact(sections, entry.getValue().sections().size());
            }
            if (sections > MAX_TOTAL_SECTIONS) throw new IllegalArgumentException("Total section capacity");
            for (var entry : links.entrySet())
                if (!entry.getKey().equals(entry.getValue().id())) throw new IllegalArgumentException("Link key mismatch");
        }
    }
    static void checkText(String value) {
        if (value == null || value.isBlank() || value.length() > 256)
            throw new IllegalArgumentException("Invalid metadata text");
    }
    public static byte[] encode(Snapshot snapshot) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(SCHEMA); out.writeInt(snapshot.ships().size());
            for (ShipId id : new TreeSet<>(snapshot.ships().keySet())) {
                var ship = snapshot.ships().get(id);
                uuid(out, id.value()); out.writeLong(ship.revision()); out.writeInt(ship.generatorVersion());
                out.writeUTF(ship.interiorDimension()); pose(out, ship.pose()); out.writeUTF(ship.status().name());
                var sections = new ArrayList<>(ship.sections().keySet());
                sections.sort(Comparator.comparingInt(ShipSection::x).thenComparingInt(ShipSection::y).thenComparingInt(ShipSection::z));
                out.writeInt(sections.size());
                for (var section : sections) {
                    var summary = ship.sections().get(section);
                    out.writeInt(section.x()); out.writeInt(section.y()); out.writeInt(section.z());
                    out.writeLong(summary.revision()); out.writeInt(summary.occupiedBlocks()); out.writeDouble(summary.mass());
                }
            }
            out.writeInt(snapshot.links().size());
            for (UUID id : new TreeSet<>(snapshot.links().keySet())) {
                var link = snapshot.links().get(id);
                uuid(out,id); uuid(out,link.first().value()); uuid(out,link.firstPort());
                uuid(out,link.second().value()); uuid(out,link.secondPort()); uuid(out,link.root().value());
                out.writeLong(link.revision()); out.writeUTF(link.savedState());
            }
            out.flush();
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Ship catalog byte capacity");
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException("Cannot encode ship catalog", e); }
    }
    public static Snapshot decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Ship catalog byte capacity");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC || in.readInt() != SCHEMA)
                throw new IllegalArgumentException("Unknown or damaged ship catalog schema; restore backup");
            int count = count(in, MAX_SHIPS), total = 0;
            Map<ShipId, ShipMetadata> ships = new HashMap<>();
            for (int i=0; i<count; i++) {
                ShipId id = new ShipId(uuid(in)); long revision = in.readLong(); int generator = in.readInt();
                String dimension = text(in); ShipPose pose = pose(in); String state = text(in);
                ShipMetadata.Status status;
                try { status = ShipMetadata.Status.valueOf(state); }
                catch (IllegalArgumentException unknown) { status = ShipMetadata.Status.BLOCKED; }
                int size = count(in, MAX_SECTIONS_PER_SHIP);
                total = Math.addExact(total, size);
                if (total > MAX_TOTAL_SECTIONS) throw new IllegalArgumentException("Total section capacity");
                Map<ShipSection, ShipStructure.Summary> sections = new HashMap<>();
                for (int j=0; j<size; j++) {
                    var section = new ShipSection(id, in.readInt(), in.readInt(), in.readInt());
                    var summary = new ShipStructure.Summary(in.readLong(), in.readInt(), in.readDouble());
                    if (sections.putIfAbsent(section, summary) != null) throw new IllegalArgumentException("Duplicate section");
                }
                var ship = new ShipMetadata(id, revision, generator, dimension, pose, sections, status);
                if (ships.putIfAbsent(id, ship) != null) throw new IllegalArgumentException("Duplicate ship");
            }
            int linkCount = count(in, MAX_LINKS);
            Map<UUID, Link> links = new HashMap<>();
            for (int i=0; i<linkCount; i++) {
                var link = new Link(uuid(in), new ShipId(uuid(in)), uuid(in), new ShipId(uuid(in)), uuid(in),
                        new ShipId(uuid(in)), in.readLong(), text(in));
                if (links.putIfAbsent(link.id(),link) != null) throw new IllegalArgumentException("Duplicate link");
            }
            if (in.read() != -1) throw new IllegalArgumentException("Trailing catalog data");
            return new Snapshot(ships, links);
        } catch (IOException e) { throw new IllegalArgumentException("Truncated or damaged ship catalog; restore backup", e); }
    }
    private static int count(DataInputStream in, int limit) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > limit) throw new IllegalArgumentException("Catalog count exceeds limit");
        return size;
    }
    private static String text(DataInputStream in) throws IOException { String s=in.readUTF(); checkText(s); return s; }
    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void vector(DataOutputStream out, Vec3 v) throws IOException {
        out.writeDouble(v.x()); out.writeDouble(v.y()); out.writeDouble(v.z());
    }
    private static Vec3 vector(DataInputStream in) throws IOException { return new Vec3(in.readDouble(),in.readDouble(),in.readDouble()); }
    private static void pose(DataOutputStream out, ShipPose p) throws IOException {
        out.writeUTF(p.context().realmId()); out.writeUTF(p.context().galaxyId()); out.writeUTF(p.context().systemId());
        vector(out,p.position()); out.writeDouble(p.rotation().x()); out.writeDouble(p.rotation().y());
        out.writeDouble(p.rotation().z()); out.writeDouble(p.rotation().w()); vector(out,p.velocity());
    }
    private static ShipPose pose(DataInputStream in) throws IOException {
        var context = new ShipPose.SpaceContext(text(in),text(in),text(in));
        var position = vector(in);
        var rotation = new Rotation(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());
        return new ShipPose(context,position,rotation,vector(in));
    }
}
