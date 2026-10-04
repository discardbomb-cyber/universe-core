package dev.heiko.universe.client.map;

import dev.heiko.universe.core.Realm;
import dev.heiko.universe.network.SectorSnapshotCache;
import dev.heiko.universe.network.UniverseNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Demonstration layout; intentionally has no level access or catalog expansion. */
public final class UniverseMapScreen extends Screen {
    private final MapViewState state = new MapViewState();
    // Shared across screen instances: reopening cannot reuse an old reply ID or burst requests.
    private static int nextRequestId;
    private static long lastRequestNanos;
    private boolean pendingRequest = true;
    private int expectedRequestId = -1;
    private long sentAt;
    private SectorSnapshotCache.Snapshot snapshot;
    private boolean timedOut;

    public UniverseMapScreen() { super(Component.translatable("map.universe.title")); }

    @Override
    protected void init() {
        int gap = 4;
        int galaxyWidth = (width - 24 - gap * 2) / 3;
        for (Realm realm : Realm.values()) {
            addRenderableWidget(Button.builder(Component.translatable("map.universe.galaxy." + realm.galaxyId()),
                    button -> selectRealm(realm)).bounds(12 + realm.ordinal() * (galaxyWidth + gap), 48, galaxyWidth, 20).build());
        }
        int scaleWidth = (width - 24 - gap * 4) / 5;
        for (MapViewState.Scale scale : MapViewState.Scale.values()) {
            addRenderableWidget(Button.builder(Component.translatable(scale.translationKey()),
                    button -> state.selectScale(scale)).bounds(12 + scale.ordinal() * (scaleWidth + gap), 74, scaleWidth, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    private void selectRealm(Realm realm) {
        if (state.realm() == realm) return;
        state.selectRealm(realm);
        // Invalidate immediately, even when the next send is debounced.
        expectedRequestId = -1;
        snapshot = null;
        timedOut = false;
        pendingRequest = true;
    }

    @Override
    public void tick() {
        long now = System.nanoTime();
        if (pendingRequest && minecraft != null && minecraft.getConnection() != null
                && (lastRequestNanos == 0 || now - lastRequestNanos >= 250_000_000L)) {
            if (nextRequestId == Integer.MAX_VALUE) { timedOut = true; pendingRequest = false; return; }
            expectedRequestId = nextRequestId++;
            sentAt = now;
            lastRequestNanos = now;
            pendingRequest = false;
            UniverseNetwork.requestSector(expectedRequestId, state.realm().id(), 0, 0, 0);
        }
        if (expectedRequestId >= 0 && snapshot == null) {
            for (var candidate : SectorSnapshotCache.snapshots()) {
                if (MapSectorSelection.matches(candidate, expectedRequestId, state.realm())) {
                    snapshot = candidate;
                    timedOut = false;
                    break;
                }
            }
            if (snapshot == null && now - sentAt >= 3_000_000_000L) timedOut = true;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xF0101726);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("map.universe.catalog_notice"), width / 2, 27, 0xFFC76B);
        int left = 12, top = 98, right = width - 12, bottom = height - 60;
        graphics.fill(left, top, right, bottom, 0xFF182438);
        // Pure decorative grid: no symbols are presented as discoveries or real bodies.
        int spacing = 12 + state.scale().ordinal() * 8;
        for (int x = left; x < right; x += spacing) graphics.vLine(x, top, bottom - 1, 0xFF26374E);
        for (int y = top; y < bottom; y += spacing) graphics.hLine(left, right - 1, y, 0xFF26374E);
        graphics.drawString(font, Component.translatable(state.galaxyTranslationKey()), left + 8, top + 8, 0xFFFFFF);
        graphics.drawString(font, Component.translatable(state.scale().translationKey()), left + 8, top + 20, 0xB9D8FF);
        graphics.drawString(font, Component.translatable(state.statusTranslationKey()), left + 8, top + 32, 0xFFC76B);
        Component summary = snapshot != null
                ? Component.translatable("map.universe.catalog_counts", snapshot.systemCount(), snapshot.bodyCount())
                : Component.translatable(timedOut ? "map.universe.catalog_timeout" : "map.universe.catalog_waiting");
        graphics.drawWordWrap(font, summary, left + 8, top + 46, Math.max(1, right - left - 16), 0xCDD5DF);
        graphics.drawWordWrap(font, Component.translatable("map.universe.controls"), 12, height - 54, width - 24, 0xCDD5DF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        state.stepScale(scrollY > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_5) {
            state.selectScale(MapViewState.Scale.values()[keyCode - GLFW.GLFW_KEY_1]);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            state.stepScale(keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_G) {
            Realm[] realms = Realm.values();
            selectRealm(realms[Math.floorMod(state.realm().ordinal() + (hasShiftDown() ? -1 : 1), realms.length)]);
            return true;
        }
        if (MapKeyBindings.OPEN_MAP.matches(keyCode, scanCode)) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
