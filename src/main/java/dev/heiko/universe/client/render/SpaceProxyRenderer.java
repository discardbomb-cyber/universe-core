package dev.heiko.universe.client.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/** Fixed visual landmarks for the space prototype, not physical or authoritative planets. */
@EventBusSubscriber(value = Dist.CLIENT, modid = "universe")
public final class SpaceProxyRenderer {
    private static final ResourceLocation SPACE = ResourceLocation.fromNamespaceAndPath("universe", "space");
    private static final int LATITUDES = 12;
    private static final int LONGITUDES = 24;
    // One bounded immutable unit mesh; no generation or world reads in the render loop.
    private static final float[] UNIT_QUADS = createUnitSphere();
    private static final Proxy[] PROXIES = {
        new Proxy(160, 128, -240, 36, 0.20f, 0.55f, 0.90f),
        new Proxy(-220, 180, 80, 24, 0.85f, 0.36f, 0.18f),
        new Proxy(40, 240, 300, 18, 1.00f, 0.88f, 0.42f)
    };

    private SpaceProxyRenderer() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !SPACE.equals(minecraft.level.dimension().location())) return;
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f matrix = event.getModelViewMatrix();
        // Own transient buffer: never flush another renderer's shared buffers, retain no GPU resources.
        try (ByteBufferBuilder storage = new ByteBufferBuilder(65536)) {
            MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(storage);
            VertexConsumer vertices = null;
            for (Proxy proxy : PROXIES) {
                if (!event.getFrustum().isVisible(proxy.bounds())) continue;
                // Fixed local scene: do not submit far geometry beyond useful vanilla visibility.
                if (camera.distanceToSqr(proxy.x, proxy.y, proxy.z) > 1024.0 * 1024.0) continue;
                if (vertices == null) vertices = buffers.getBuffer(RenderType.debugQuads());
                emit(vertices, matrix, camera, proxy);
            }
            if (vertices != null) buffers.endBatch(RenderType.debugQuads());
        }
    }

    private static void emit(VertexConsumer vertices, Matrix4f matrix, Vec3 camera, Proxy proxy) {
        double x = proxy.x - camera.x;
        double y = proxy.y - camera.y;
        double z = proxy.z - camera.z;
        for (int i = 0; i < UNIT_QUADS.length; i += 3) {
            float nx = UNIT_QUADS[i];
            float ny = UNIT_QUADS[i + 1];
            float nz = UNIT_QUADS[i + 2];
            float shade = 0.35f + 0.65f * Math.max(0, nx * 0.36f + ny * 0.80f + nz * 0.48f);
            vertices.addVertex(matrix, (float) (x + nx * proxy.radius),
                    (float) (y + ny * proxy.radius), (float) (z + nz * proxy.radius))
                    .setColor(proxy.red * shade, proxy.green * shade, proxy.blue * shade, 1.0f);
        }
    }

    private static float[] createUnitSphere() {
        float[] mesh = new float[LATITUDES * LONGITUDES * 4 * 3];
        int offset = 0;
        for (int latitude = 0; latitude < LATITUDES; latitude++) {
            for (int longitude = 0; longitude < LONGITUDES; longitude++) {
                offset = point(mesh, offset, latitude, longitude);
                offset = point(mesh, offset, latitude + 1, longitude);
                offset = point(mesh, offset, latitude + 1, longitude + 1);
                offset = point(mesh, offset, latitude, longitude + 1);
            }
        }
        return mesh;
    }

    private static int point(float[] mesh, int offset, int latitude, int longitude) {
        double elevation = -Math.PI / 2 + Math.PI * latitude / LATITUDES;
        double azimuth = 2 * Math.PI * longitude / LONGITUDES;
        mesh[offset++] = (float) (Math.cos(elevation) * Math.cos(azimuth));
        mesh[offset++] = (float) Math.sin(elevation);
        mesh[offset++] = (float) (Math.cos(elevation) * Math.sin(azimuth));
        return offset;
    }

    private record Proxy(double x, double y, double z, float radius, float red, float green, float blue) {
        AABB bounds() {
            return new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        }
    }
}
