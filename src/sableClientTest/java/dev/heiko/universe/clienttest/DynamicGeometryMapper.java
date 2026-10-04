package dev.heiko.universe.clienttest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector4f;

/**
 * Uncompiled offline candidate. Numeric frame observations -> geometry masks only.
 * No PNG reading, rendering, Sable mutation, telemetry replacement or PASS verdict.
 */
public final class DynamicGeometryMapper {
    public static final int WIDTH = 960;
    public static final int HEIGHT = 540;
    public static final int VISIBLE_INSET = 2;
    public static final int WALL_INSET = 4;
    private static final int PIXELS = WIDTH * HEIGHT;
    private static final long MAX_RASTER_VISITS = 24_000_000;
    private static final double DEPTH_EPSILON = 1e-7;
    private static final double MAX_ABSOLUTE_COORDINATE = 1e9;
    private static final double MAX_CAMERA_RELATIVE_COORDINATE = 512;

    public enum Marker { RED, BLUE, LIME, YELLOW }
    public enum Material {
        RED, BLUE, LIME, YELLOW, WHITE, SEA_LANTERN,
        MAGENTA_WALL, NEUTRAL_OPAQUE, CYAN_LANDMARK
    }
    public enum Space { MOVING_STORAGE, STATIONARY_WORLD }

    public record Vec3(double x, double y, double z) {
        public Vec3 {
            requireFinite(x, y, z);
            if (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) > MAX_ABSOLUTE_COORDINATE)
                throw refusal("Coordinate outside bounded fixture domain");
        }
    }

    /** Quaternion components use the observed x,y,z,w order; never normalize a bad observation. */
    public record Quaternion(double x, double y, double z, double w) {
        public Quaternion {
            requireFinite(x, y, z, w);
            double normSquared = x*x + y*y + z*z + w*w;
            if (Math.abs(normSquared - 1) > 1e-5)
                throw refusal("Observed render quaternion is not unit length");
        }
    }

    /** Defensive copies. Both float arrays are JOML Matrix4f.get(float[16]) column-major arrays. */
    public record FrameSnapshot(long frameId, int width, int height,
            Vec3 position, Quaternion orientation, Vec3 rotationPoint, Vec3 scale, Vec3 camera,
            float[] modelViewColumnMajor, float[] projectionColumnMajor) {
        public FrameSnapshot {
            if (frameId < 0 || width != WIDTH || height != HEIGHT)
                throw refusal("Expected a nonnegative frame ID and a 960x540 framebuffer");
            Objects.requireNonNull(position); Objects.requireNonNull(orientation);
            Objects.requireNonNull(rotationPoint); Objects.requireNonNull(scale); Objects.requireNonNull(camera);
            if (scale.x() < .125 || scale.y() < .125 || scale.z() < .125
                    || scale.x() > 8 || scale.y() > 8 || scale.z() > 8)
                throw refusal("Only positive bounded render scales are supported");
            modelViewColumnMajor = copyMatrix(modelViewColumnMajor);
            projectionColumnMajor = copyMatrix(projectionColumnMajor);
        }
        @Override public float[] modelViewColumnMajor() { return modelViewColumnMajor.clone(); }
        @Override public float[] projectionColumnMajor() { return projectionColumnMajor.clone(); }
    }

    public record Cell(int x, int y, int z) {
        public Cell {
            if (Math.max(Math.abs((long)x), Math.max(Math.abs((long)y), Math.abs((long)z))) > 999_999_900)
                throw refusal("Cell outside bounded integer domain");
        }
        Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
    }

    /** Every item is an opaque, full, axis-aligned vanilla cube before the moving pose is applied. */
    public record Cube(Cell cell, Material material) {
        public Cube { Objects.requireNonNull(cell); Objects.requireNonNull(material); }
    }

    /**
     * Strict ten-cube moving board and 136-cube ordinary-world fixture.
     * Root must first verify these cells and block roles against the actual fixture manifest.
     */
    public record Fixture(Cell markerOrigin, List<Cube> moving, List<Cube> stationary) {
        public Fixture {
            Objects.requireNonNull(markerOrigin);
            moving = List.copyOf(moving); stationary = List.copyOf(stationary);
            if (moving.size() != 10 || stationary.size() != 129
                    || !cellMap(moving).equals(cellMap(expectedMoving(markerOrigin)))
                    || !cellMap(stationary).equals(cellMap(expectedStationary())))
                throw refusal("Fixture manifest differs from the fixed board/floor/wall/landmark geometry");
        }
    }

    /** Convenience only; it does not create or observe any Minecraft blocks. */
    public static Fixture standardFixture(Cell actualStorageOrigin) {
        return new Fixture(actualStorageOrigin, expectedMoving(actualStorageOrigin), expectedStationary());
    }

    public record PixelPoint(double x, double y) {
        public PixelPoint { requireFinite(x, y); }
    }
    public record Bounds(int x, int y, int width, int height) { }
    public record MaskRun(int y, int xStart, int xEndExclusive) { }
    public record Polygon2(List<PixelPoint> vertices) {
        public Polygon2 { vertices = List.copyOf(vertices); }
    }
    /** NDC depth, rather than eye-space depth, is interpolated in screen barycentric coordinates. */
    public record ProjectedVertex(double x, double y, double ndcDepth, double clipW) {
        public ProjectedVertex { requireFinite(x, y, ndcDepth, clipW); }
        public PixelPoint pixelPoint() { return new PixelPoint(x, y); }
    }
    /** A clipped, front-facing exterior face BEFORE any nearer opaque face is subtracted. */
    public record FaceProjection(Space space, Cell cell, Material material, String face,
            List<ProjectedVertex> vertices) {
        public FaceProjection { vertices = List.copyOf(vertices); }
    }

    /**
     * Immutable geometric pixel-center coverage. Bounds are iteration bounds, never membership.
     * areaPixels()/centroid() are computed from this mask, never from classified image pixels.
     */
    public static final class PixelMask {
        private final BitSet bits;
        private final Bounds bounds;
        private final int area;
        private final PixelPoint centroid;

        private PixelMask(BitSet input) {
            if (input.length() > PIXELS) throw refusal("Mask outside framebuffer");
            bits = (BitSet) input.clone();
            area = bits.cardinality();
            int minX = WIDTH, minY = HEIGHT, maxX = -1, maxY = -1;
            long sumX = 0, sumY = 0;
            for (int index = bits.nextSetBit(0); index >= 0; index = bits.nextSetBit(index + 1)) {
                int x = index % WIDTH, y = index / WIDTH;
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                sumX += x; sumY += y;
            }
            bounds = area == 0 ? new Bounds(0, 0, 0, 0)
                    : new Bounds(minX, minY, maxX - minX + 1, maxY - minY + 1);
            centroid = area == 0 ? null : new PixelPoint((double)sumX / area + .5, (double)sumY / area + .5);
        }

        public boolean contains(int x, int y) {
            return x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT && bits.get(y * WIDTH + x);
        }
        public int areaPixels() { return area; }
        public Bounds iterationBounds() { return bounds; }
        public Optional<PixelPoint> centroidIfNonempty() { return Optional.ofNullable(centroid); }
        public PixelPoint centroid() {
            if (centroid == null) throw refusal("An empty mask has no finite expected centroid");
            return centroid;
        }
        /** Root PNG evaluator indexes the returned array by y*960+x. A fresh array is returned. */
        public boolean[] copyBooleanMask() {
            boolean[] result = new boolean[PIXELS];
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) result[i] = true;
            return result;
        }
        /** Exact depth-clipped mask polygon set, represented as one unit-high rectangle per row run. */
        public List<MaskRun> rowRuns() {
            List<MaskRun> result = new ArrayList<>();
            for (int y = bounds.y(); y < bounds.y() + bounds.height(); y++) {
                int rowStart = y * WIDTH, rowEnd = rowStart + WIDTH;
                int start = bits.nextSetBit(rowStart);
                while (start >= 0 && start < rowEnd) {
                    int end = Math.min(bits.nextClearBit(start), rowEnd);
                    result.add(new MaskRun(y, start - rowStart, end - rowStart));
                    start = bits.nextSetBit(end);
                }
            }
            return List.copyOf(result);
        }
        public List<Polygon2> polygons() {
            List<Polygon2> result = new ArrayList<>();
            for (MaskRun run : rowRuns()) result.add(new Polygon2(List.of(
                    new PixelPoint(run.xStart(), run.y()), new PixelPoint(run.xEndExclusive(), run.y()),
                    new PixelPoint(run.xEndExclusive(), run.y() + 1), new PixelPoint(run.xStart(), run.y() + 1))));
            return List.copyOf(result);
        }
        /** Erode the UNION mask, not each thin polygon separately. Square/Chebyshev pixel margin. */
        public PixelMask inset(int radius) {
            if (radius < 0 || radius > WALL_INSET) throw refusal("Inset radius outside 0..4");
            BitSet result = new BitSet(PIXELS);
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                int x = i % WIDTH, y = i / WIDTH;
                boolean inside = x >= radius && x + radius < WIDTH && y >= radius && y + radius < HEIGHT;
                for (int dy = -radius; inside && dy <= radius; dy++)
                    for (int dx = -radius; inside && dx <= radius; dx++) inside = contains(x + dx, y + dy);
                if (inside) result.set(i);
            }
            return new PixelMask(result);
        }
    }

    public record MarkerProjection(Marker marker, ProjectedVertex projectedCubeCenter,
            List<FaceProjection> projectedExteriorFaces,
            PixelMask unoccludedByStationary, PixelMask visible, PixelMask visibleInterior,
            PixelMask hiddenByWall, PixelMask hiddenByWallInterior, PixelMask hiddenByOtherStationary) {
        public MarkerProjection { projectedExteriorFaces = List.copyOf(projectedExteriorFaces); }
    }
    public record LandmarkProjection(Cell knownWorldCell, ProjectedVertex projectedCubeCenter,
            PixelMask visible, PixelMask interior) { }
    public record Basis(double dx, double dy, double angleDegrees, double lengthPixels) { }
    public record FrameGeometry(long frameId, Map<Marker, MarkerProjection> markers,
            List<FaceProjection> exteriorFaces, PixelMask wallVisible, PixelMask wallInterior,
            PixelMask hiddenMarkerWallInterior, LandmarkProjection landmark, long rasterPixelVisits) {
        public FrameGeometry {
            markers = Collections.unmodifiableMap(new EnumMap<>(markers));
            exteriorFaces = List.copyOf(exteriorFaces);
        }
        /** Expected centroid-to-centroid basis in PNG coordinates. Root compares the SAME detected pair. */
        public Basis visibleBasis(Marker from, Marker to) {
            PixelPoint a = markers.get(from).visibleInterior().centroid();
            PixelPoint b = markers.get(to).visibleInterior().centroid();
            double dx = b.x() - a.x(), dy = b.y() - a.y(), length = Math.hypot(dx, dy);
            if (from == to || length < 1) throw refusal("No nondegenerate visible marker basis");
            return new Basis(dx, dy, Math.toDegrees(Math.atan2(dy, dx)), length);
        }
    }

    private DynamicGeometryMapper() { }

    /** Actual Pose3dc semantics: world = q * ((storage - rotationPoint) componentwise scale) + position. */
    public static Vec3 localToWorld(FrameSnapshot frame, Vec3 storage) {
        Vec3 rp = frame.rotationPoint(), scale = frame.scale();
        double dx = storage.x() - rp.x(), dy = storage.y() - rp.y(), dz = storage.z() - rp.z();
        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) > 64)
            throw refusal("Moving fixture is not near the recorded storage rotation point");
        Quaternion q = frame.orientation();
        Vector3d world = new Quaterniond(q.x(), q.y(), q.z(), q.w())
                .transform(new Vector3d(dx * scale.x(), dy * scale.y(), dz * scale.z()));
        world.add(frame.position().x(), frame.position().y(), frame.position().z());
        return new Vec3(world.x, world.y, world.z);
    }

    /** Diagnostic/static-sanity helper. Returns off-viewport x/y, but refuses near/far/behind-camera points. */
    public static ProjectedVertex projectWorld(FrameSnapshot frame, Vec3 world) {
        return new Projector(frame).project(world);
    }

    /** Build independent, bounded software depth buffers and mask expectations. No image argument exists. */
    public static FrameGeometry map(FrameSnapshot frame, Fixture fixture) {
        Objects.requireNonNull(frame); Objects.requireNonNull(fixture);
        Projector projector = new Projector(frame);
        List<FaceProjection> movingFaces = exteriorFaces(frame, projector, fixture.moving(), Space.MOVING_STORAGE);
        List<FaceProjection> stationaryFaces = exteriorFaces(frame, projector, fixture.stationary(), Space.STATIONARY_WORLD);
        RasterBudget budget = new RasterBudget();
        DepthLayer moving = rasterize(movingFaces, budget), stationary = rasterize(stationaryFaces, budget);
        EnumMap<Marker, BitSet> unoccluded = markerBits(), visible = markerBits(), wallHidden = markerBits(), otherHidden = markerBits();
        BitSet wallBits = new BitSet(PIXELS), landmarkBits = new BitSet(PIXELS);
        for (int i = 0; i < PIXELS; i++) {
            Material m = moving.owner[i], s = stationary.owner[i];
            if (m != null && s != null && Math.abs(moving.depth[i] - stationary.depth[i]) <= DEPTH_EPSILON)
                throw refusal("Moving/static surfaces have ambiguous coplanar depth at pixel " + i);
            boolean movingInFront = m != null && (s == null || moving.depth[i] < stationary.depth[i]);
            Marker marker = asMarker(m);
            if (marker != null) {
                unoccluded.get(marker).set(i);
                if (movingInFront) visible.get(marker).set(i);
                else if (s == Material.MAGENTA_WALL) wallHidden.get(marker).set(i);
                else otherHidden.get(marker).set(i);
            }
            if (s != null && !movingInFront) {
                if (s == Material.MAGENTA_WALL) wallBits.set(i);
                if (s == Material.CYAN_LANDMARK) landmarkBits.set(i);
            }
        }
        PixelMask wallVisible = new PixelMask(wallBits), wallInterior = wallVisible.inset(WALL_INSET);
        EnumMap<Marker, MarkerProjection> result = new EnumMap<>(Marker.class);
        BitSet allHiddenInterior = new BitSet(PIXELS);
        for (Marker marker : Marker.values()) {
            PixelMask beforeWall = new PixelMask(unoccluded.get(marker));
            PixelMask visibleMask = new PixelMask(visible.get(marker));
            BitSet interiorBehindWall = (BitSet) beforeWall.inset(VISIBLE_INSET).bits.clone();
            interiorBehindWall.and(wallInterior.bits);
            interiorBehindWall.and(wallHidden.get(marker));
            allHiddenInterior.or(interiorBehindWall);
            List<FaceProjection> markerFaces = movingFaces.stream().filter(f -> asMarker(f.material()) == marker).toList();
            Cell cell = markerCell(fixture.moving(), marker);
            result.put(marker, new MarkerProjection(marker, projector.project(localToWorld(frame, center(cell))), markerFaces,
                    beforeWall, visibleMask, visibleMask.inset(VISIBLE_INSET), new PixelMask(wallHidden.get(marker)),
                    new PixelMask(interiorBehindWall), new PixelMask(otherHidden.get(marker))));
        }
        Cell landmark = new Cell(-4, 83, -6);
        PixelMask landmarkMask = new PixelMask(landmarkBits);
        List<FaceProjection> allFaces = new ArrayList<>(movingFaces); allFaces.addAll(stationaryFaces);
        return new FrameGeometry(frame.frameId(), result, allFaces, wallVisible, wallInterior,
                new PixelMask(allHiddenInterior), new LandmarkProjection(landmark, projector.project(center(landmark)),
                landmarkMask, landmarkMask.inset(VISIBLE_INSET)), budget.visits);
    }

    /** Shortest signed difference, for observed-vs-expected and stage-to-stage basis angles. */
    public static double angleDifferenceDegrees(double from, double to) {
        requireFinite(from, to);
        return Math.IEEEremainder(to - from, 360);
    }

    private record Face(String name, int dx, int dy, int dz, int[] corners) { }
    private static final List<Face> FACES = List.of(
            new Face("+X", 1, 0, 0, new int[]{1, 3, 7, 5}),
            new Face("-X", -1, 0, 0, new int[]{4, 6, 2, 0}),
            new Face("+Y", 0, 1, 0, new int[]{2, 6, 7, 3}),
            new Face("-Y", 0, -1, 0, new int[]{4, 0, 1, 5}),
            new Face("+Z", 0, 0, 1, new int[]{5, 7, 6, 4}),
            new Face("-Z", 0, 0, -1, new int[]{0, 2, 3, 1}));

    private static List<FaceProjection> exteriorFaces(FrameSnapshot frame, Projector projector, List<Cube> cubes, Space space) {
        Map<Cell, Material> occupancy = cellMap(cubes);
        List<FaceProjection> result = new ArrayList<>();
        for (Cube cube : cubes) {
            ProjectedVertex[] corners = new ProjectedVertex[8];
            for (int i = 0; i < 8; i++) {
                Vec3 point = new Vec3(cube.cell().x() + (i & 1), cube.cell().y() + ((i >> 1) & 1),
                        cube.cell().z() + ((i >> 2) & 1));
                if (space == Space.MOVING_STORAGE) point = localToWorld(frame, point);
                // Validate EVERY corner, including back-facing/neighbor-removed faces. No near-plane clipping loophole.
                corners[i] = projector.project(point);
            }
            for (Face face : FACES) {
                if (occupancy.containsKey(cube.cell().offset(face.dx(), face.dy(), face.dz()))) continue;
                List<ProjectedVertex> polygon = new ArrayList<>(4);
                for (int corner : face.corners()) polygon.add(corners[corner]);
                // Outward winding is CCW before Screenshot.flipY(), hence negative area in top-left PNG coordinates.
                if (signedAreaTwice(polygon) >= -1e-8) continue;
                polygon = clipViewport(polygon);
                if (polygon.size() < 3 || Math.abs(signedAreaTwice(polygon)) < 1e-8) continue;
                result.add(new FaceProjection(space, cube.cell(), cube.material(), face.name(), polygon));
            }
        }
        return List.copyOf(result);
    }

    private static final class Projector {
        private final FrameSnapshot frame;
        private final Matrix4f modelView, projection;
        Projector(FrameSnapshot frame) {
            this.frame = frame;
            // .set(float[]) imports column-major JOML arrays; no manual row-major transpose.
            modelView = new Matrix4f().set(frame.modelViewColumnMajor);
            projection = new Matrix4f().set(frame.projectionColumnMajor);
            float mdet = modelView.determinant(), pdet = projection.determinant();
            if (!Float.isFinite(mdet) || Math.abs(mdet - 1) > 1e-4 || !Float.isFinite(pdet) || Math.abs(pdet) < 1e-10)
                throw refusal("Invalid rotation model-view or singular actual projection");
            float[] m = frame.modelViewColumnMajor;
            if (Math.abs(m[3]) > 1e-5 || Math.abs(m[7]) > 1e-5 || Math.abs(m[11]) > 1e-5
                    || Math.abs(m[12]) > 1e-5 || Math.abs(m[13]) > 1e-5 || Math.abs(m[14]) > 1e-5
                    || Math.abs(m[15] - 1) > 1e-5)
                throw refusal("Pinned frame model-view must contain camera rotation only");
            for (int column = 0; column < 3; column++) {
                int c = column * 4;
                double norm = (double)m[c] * m[c] + (double)m[c + 1] * m[c + 1] + (double)m[c + 2] * m[c + 2];
                if (Math.abs(norm - 1) > 1e-4) throw refusal("Model-view rotation column is not unit length");
                for (int other = column + 1; other < 3; other++) {
                    int o = other * 4;
                    double dot = (double)m[c] * m[o] + (double)m[c + 1] * m[o + 1] + (double)m[c + 2] * m[o + 2];
                    if (Math.abs(dot) > 1e-4) throw refusal("Model-view columns are not orthogonal");
                }
            }
        }
        ProjectedVertex project(Vec3 world) {
            double x = world.x() - frame.camera().x(), y = world.y() - frame.camera().y(), z = world.z() - frame.camera().z();
            requireFinite(x, y, z);
            if (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) > MAX_CAMERA_RELATIVE_COORDINATE)
                throw refusal("Fixture point outside camera-relative bound");
            Vector4f clip = new Vector4f((float)x, (float)y, (float)z, 1);
            modelView.transform(clip); projection.transform(clip);
            requireFinite(clip.x, clip.y, clip.z, clip.w);
            if (clip.w <= 1e-5) throw refusal("Fixture point is behind the camera or at the eye plane");
            double ndcZ = (double)clip.z / clip.w;
            if (ndcZ <= -1 + 1e-5 || ndcZ >= 1 - 1e-5)
                throw refusal("Fixture intersects or lies outside the near/far clip domain");
            double px = ((double)clip.x / clip.w + 1) * WIDTH / 2;
            double py = (1 - (double)clip.y / clip.w) * HEIGHT / 2;
            requireFinite(px, py, ndcZ);
            if (Math.max(Math.abs(px), Math.abs(py)) > 1e6) throw refusal("Unbounded projected coordinate");
            return new ProjectedVertex(px, py, ndcZ, clip.w);
        }
    }

    private static List<ProjectedVertex> clipViewport(List<ProjectedVertex> polygon) {
        List<ProjectedVertex> result = polygon;
        for (int edge = 0; edge < 4 && !result.isEmpty(); edge++) {
            List<ProjectedVertex> output = new ArrayList<>(8);
            ProjectedVertex previous = result.get(result.size() - 1);
            double previousDistance = viewportDistance(previous, edge);
            for (ProjectedVertex current : result) {
                double distance = viewportDistance(current, edge);
                if ((distance >= 0) != (previousDistance >= 0)) {
                    double t = previousDistance / (previousDistance - distance);
                    output.add(interpolate(previous, current, t));
                }
                if (distance >= 0) output.add(current);
                previous = current; previousDistance = distance;
            }
            result = output;
        }
        if (result.size() > 8) throw refusal("Unexpected clipped convex-face size");
        return result;
    }
    private static double viewportDistance(ProjectedVertex v, int edge) {
        return switch (edge) { case 0 -> v.x(); case 1 -> WIDTH - v.x(); case 2 -> v.y(); default -> HEIGHT - v.y(); };
    }
    private static ProjectedVertex interpolate(ProjectedVertex a, ProjectedVertex b, double t) {
        // NDC depth and reciprocal clipW are affine along a projected edge; clipW is diagnostic only.
        return new ProjectedVertex(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t,
                a.ndcDepth() + (b.ndcDepth() - a.ndcDepth()) * t, 1 / ((1 - t) / a.clipW() + t / b.clipW()));
    }
    private static double signedAreaTwice(List<ProjectedVertex> polygon) {
        double area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            ProjectedVertex a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            area += a.x() * b.y() - b.x() * a.y();
        }
        return area;
    }

    private static final class DepthLayer {
        final double[] depth = new double[PIXELS];
        final Material[] owner = new Material[PIXELS];
        DepthLayer() { Arrays.fill(depth, Double.POSITIVE_INFINITY); }
        void write(int pixel, double z, Material material) {
            requireFinite(z);
            if (owner[pixel] == null || z < depth[pixel] - DEPTH_EPSILON) { depth[pixel] = z; owner[pixel] = material; }
            else if (Math.abs(z - depth[pixel]) <= DEPTH_EPSILON && owner[pixel] != material)
                throw refusal("Distinct moving/static materials have ambiguous coplanar raster depth at pixel " + pixel);
        }
    }
    private static final class RasterBudget {
        long visits;
        void add(long candidates) {
            visits += candidates;
            if (visits > MAX_RASTER_VISITS) throw refusal("Per-frame raster work bound exceeded");
        }
    }
    private static DepthLayer rasterize(List<FaceProjection> faces, RasterBudget budget) {
        DepthLayer result = new DepthLayer();
        for (FaceProjection face : faces) {
            ProjectedVertex a = face.vertices().get(0);
            for (int i = 1; i + 1 < face.vertices().size(); i++)
                rasterTriangle(result, budget, a, face.vertices().get(i), face.vertices().get(i + 1), face.material());
        }
        return result;
    }
    private static void rasterTriangle(DepthLayer layer, RasterBudget budget,
            ProjectedVertex a, ProjectedVertex b, ProjectedVertex c, Material material) {
        double area = edge(a, b, c.x(), c.y());
        if (Math.abs(area) < 1e-8) return;
        if (area < 0) { ProjectedVertex swap = b; b = c; c = swap; area = -area; }
        int minX = Math.max(0, (int)Math.ceil(Math.min(a.x(), Math.min(b.x(), c.x())) - .5));
        int maxX = Math.min(WIDTH - 1, (int)Math.floor(Math.max(a.x(), Math.max(b.x(), c.x())) - .5));
        int minY = Math.max(0, (int)Math.ceil(Math.min(a.y(), Math.min(b.y(), c.y())) - .5));
        int maxY = Math.min(HEIGHT - 1, (int)Math.floor(Math.max(a.y(), Math.max(b.y(), c.y())) - .5));
        if (minX > maxX || minY > maxY) return;
        budget.add((long)(maxX - minX + 1) * (maxY - minY + 1));
        boolean abTopLeft = topLeft(a, b), bcTopLeft = topLeft(b, c), caTopLeft = topLeft(c, a);
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            double px = x + .5, py = y + .5;
            double ab = edge(a, b, px, py), bc = edge(b, c, px, py), ca = edge(c, a, px, py);
            if (!inside(ab, abTopLeft) || !inside(bc, bcTopLeft) || !inside(ca, caTopLeft)) continue;
            double z = (bc * a.ndcDepth() + ca * b.ndcDepth() + ab * c.ndcDepth()) / area;
            layer.write(y * WIDTH + x, z, material);
        }
    }
    private static double edge(ProjectedVertex a, ProjectedVertex b, double x, double y) {
        return (b.x() - a.x()) * (y - a.y()) - (b.y() - a.y()) * (x - a.x());
    }
    private static boolean topLeft(ProjectedVertex a, ProjectedVertex b) {
        return b.y() < a.y() || (b.y() == a.y() && b.x() > a.x());
    }
    private static boolean inside(double edge, boolean topLeft) { return edge > 0 || (edge == 0 && topLeft); }

    private static List<Cube> expectedMoving(Cell origin) {
        List<Cube> cubes = new ArrayList<>(10);
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
            Material material = x == 0 && z == 0 ? Material.RED : x == 2 && z == 0 ? Material.BLUE
                    : x == 0 && z == 2 ? Material.LIME : x == 2 && z == 2 ? Material.YELLOW : Material.WHITE;
            cubes.add(new Cube(origin.offset(x, 0, z), material));
        }
        cubes.add(new Cube(origin.offset(1, 1, 1), Material.SEA_LANTERN));
        return List.copyOf(cubes);
    }
    private static List<Cube> expectedStationary() {
        List<Cube> cubes = new ArrayList<>(129);
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++)
            cubes.add(new Cube(new Cell(x, 80, z), Material.NEUTRAL_OPAQUE));
        for (int x = 2; x <= 2; x++) for (int y = 84; y <= 90; y++)
            cubes.add(new Cube(new Cell(x, y, -4), Material.MAGENTA_WALL));
        cubes.add(new Cube(new Cell(-4, 83, -6), Material.CYAN_LANDMARK));
        return List.copyOf(cubes);
    }
    private static Map<Cell, Material> cellMap(List<Cube> cubes) {
        Map<Cell, Material> result = new HashMap<>();
        for (Cube cube : cubes) if (result.put(cube.cell(), cube.material()) != null)
            throw refusal("Duplicate fixture cell");
        return result;
    }
    private static Cell markerCell(List<Cube> cubes, Marker marker) {
        return cubes.stream().filter(cube -> asMarker(cube.material()) == marker).findFirst().orElseThrow().cell();
    }
    private static Vec3 center(Cell cell) { return new Vec3(cell.x() + .5, cell.y() + .5, cell.z() + .5); }
    private static Marker asMarker(Material material) {
        if (material == null) return null;
        return switch (material) { case RED -> Marker.RED; case BLUE -> Marker.BLUE; case LIME -> Marker.LIME;
            case YELLOW -> Marker.YELLOW; default -> null; };
    }
    private static EnumMap<Marker, BitSet> markerBits() {
        EnumMap<Marker, BitSet> result = new EnumMap<>(Marker.class);
        for (Marker marker : Marker.values()) result.put(marker, new BitSet(PIXELS));
        return result;
    }
    private static float[] copyMatrix(float[] input) {
        if (input == null || input.length != 16) throw refusal("Expected a complete 16-float matrix");
        float[] copy = input.clone();
        for (float value : copy) if (!Float.isFinite(value)) throw refusal("Nonfinite matrix element");
        return copy;
    }
    private static void requireFinite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) throw refusal("Nonfinite geometry component");
    }
    private static IllegalArgumentException refusal(String message) {
        return new IllegalArgumentException("GEOMETRY_REFUSED: " + message);
    }
}
