package com.wwwwz.aigcs.client.gui;

import com.mojang.math.Axis;
import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.claim.ClaimStore;
import com.wwwwz.aigcs.client.map.MapCamera;
import com.wwwwz.aigcs.client.map.MapViewport;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

/**
 * Draws the chunk claim overlay of the world map.
 * <p>
 * Every claimed chunk is filled with its owner colour and, once the chunk is
 * large enough on screen, with diagonal stripes (white by default). The
 * boundary pass then outlines claimed areas, and force loaded chunks get a
 * small marker in their centre.
 */
@SuppressWarnings("null")
public final class ClaimOverlay {
    /** Above this chunk size (GUI pixels) the fill uses stripes. */
    private static final double STRIPE_MIN_PIXELS = 7.0D;
    /** Frame safety net; the viewport only covers a limited number of chunks. */
    private static final int MAX_CHUNKS = 40_000;
    /** Striped chunks per frame, the plain fill is much cheaper. */
    private static final int MAX_STRIPED_CHUNKS = 2048;
    /** Selection outline limit per frame. */
    private static final int MAX_SELECTION_OUTLINES = 4096;

    /** Reused by {@link #rect} so drawing thousands of chunks allocates nothing. */
    private final int[] rectBuffer = new int[4];

    private int dragStartX;
    private int dragStartY;
    private int dragEndX;
    private int dragEndY;
    private boolean dragging;

    /** Starts the visual drag rectangle. */
    public void beginDrag(int startX, int startY, int currentX, int currentY) {
        this.dragging = true;
        this.dragStartX = startX;
        this.dragStartY = startY;
        this.dragEndX = currentX;
        this.dragEndY = currentY;
    }

    public void updateDrag(int currentX, int currentY) {
        this.dragEndX = currentX;
        this.dragEndY = currentY;
    }

    public void endDrag() {
        this.dragging = false;
    }

    public boolean isDragging() {
        return this.dragging;
    }

    public int dragStartX() {
        return this.dragStartX;
    }

    public int dragStartY() {
        return this.dragStartY;
    }

    public int dragEndX() {
        return this.dragEndX;
    }

    public int dragEndY() {
        return this.dragEndY;
    }

    /** @return {@code true} when the drag moved far enough to be a rectangle */
    public boolean isDragArea() {
        return Math.abs(this.dragEndX - this.dragStartX) >= 3 || Math.abs(this.dragEndY - this.dragStartY) >= 3;
    }

    // ----------------------------------------------------------------- claims

    public void drawClaims(GuiGraphics graphics, MapCamera camera, MapViewport viewport,
            ResourceLocation dimension) {
        if (!ClientConfig.CLAIM_SHOW.getAsBoolean() || dimension == null) {
            return;
        }
        ClaimStore store = ClaimStore.get();
        String key = dimension.toString();
        int minChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x()) / 16.0D);
        int maxChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x() + viewport.width()) / 16.0D);
        int minChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y()) / 16.0D);
        int maxChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y() + viewport.height()) / 16.0D);
        long total = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        if (total <= 0L || total > MAX_CHUNKS) {
            return;
        }
        int alpha = (int) Math.round(ClientConfig.CLAIM_OPACITY.get() * 170.0D);
        if (alpha <= 0) {
            return;
        }
        boolean showOthers = ClientConfig.CLAIM_SHOW_OTHERS.getAsBoolean();
        boolean showForce = ClientConfig.CLAIM_SHOW_FORCE_LOAD.getAsBoolean();
        String selfId = store.selfId();
        double chunkPixels = camera.zoom() * 16.0D;
        boolean stripes = chunkPixels >= STRIPE_MIN_PIXELS;
        int striped = 0;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ClaimStore.Ref ref = store.refAt(key, chunkX, chunkZ);
                if (ref == null) {
                    continue;
                }
                boolean self = ref.owner().id().equals(selfId);
                if (!self && (!showOthers || store.isHidden(ref.owner().id()))) {
                    continue;
                }
                int[] rect = this.rect(camera, viewport, chunkX, chunkZ);
                if (rect == null) {
                    continue;
                }
                int left = rect[0];
                int top = rect[1];
                int right = rect[2];
                int bottom = rect[3];
                int color = ref.owner().color() & 0xFFFFFF;
                graphics.fill(left, top, right, bottom, (Math.max(10, alpha / 4) << 24) | color);
                if (stripes && striped < MAX_STRIPED_CHUNKS) {
                    fillStripes(graphics, left, top, right, bottom, (alpha << 24) | color);
                    striped++;
                }
                if (showForce && ref.forceLoad()) {
                    this.drawForceMarker(graphics, left, top, right, bottom);
                }
                if (ref.owner().boundary()) {
                    this.drawBoundaries(graphics, store, key, chunkX, chunkZ, left, top, right, bottom,
                            color, selfId, showOthers);
                }
            }
        }
    }

    private void drawBoundaries(GuiGraphics graphics, ClaimStore store, String dimension, int chunkX,
            int chunkZ, int left, int top, int right, int bottom, int color, String selfId,
            boolean showOthers) {
        ClaimStore.Ref selfRef = store.refAt(dimension, chunkX, chunkZ);
        if (selfRef == null) {
            return;
        }
        int line = (0xC0 << 24) | brighten(color);
        int thickness = Math.max(1, (right - left) / 12);
        ClaimStore.Ref leftRef = store.refAt(dimension, chunkX - 1, chunkZ);
        ClaimStore.Ref rightRef = store.refAt(dimension, chunkX + 1, chunkZ);
        ClaimStore.Ref upRef = store.refAt(dimension, chunkX, chunkZ - 1);
        ClaimStore.Ref downRef = store.refAt(dimension, chunkX, chunkZ + 1);
        String owner = selfRef.owner().id();
        if (!sameOwner(leftRef, owner, selfId, showOthers)) {
            graphics.fill(left, top, left + thickness, bottom, line);
        }
        if (!sameOwner(rightRef, owner, selfId, showOthers)) {
            graphics.fill(right - thickness, top, right, bottom, line);
        }
        if (!sameOwner(upRef, owner, selfId, showOthers)) {
            graphics.fill(left, top, right, top + thickness, line);
        }
        if (!sameOwner(downRef, owner, selfId, showOthers)) {
            graphics.fill(left, bottom - thickness, right, bottom, line);
        }
    }

    private static boolean sameOwner(ClaimStore.Ref ref, String owner, String selfId, boolean showOthers) {
        if (ref == null) {
            return false;
        }
        if (ref.owner().id().equals(selfId)) {
            // The own claims are always drawn, so they count as neighbours.
            return ref.owner().id().equals(owner);
        }
        return showOthers && ref.owner().id().equals(owner);
    }

    /** Small pin in the middle of a force loaded chunk. */
    private void drawForceMarker(GuiGraphics graphics, int left, int top, int right, int bottom) {
        int centerX = (left + right) / 2;
        int centerY = (top + bottom) / 2;
        int size = Mth.clamp((right - left) / 5, 2, 5);
        graphics.fill(centerX - size - 1, centerY - size - 1, centerX + size + 1, centerY + size + 1,
                0xFF101010);
        graphics.fill(centerX - size, centerY - size, centerX + size, centerY + size, 0xFFFFFFFF);
        graphics.fill(centerX - size + 1, centerY - size + 1, centerX + size - 1, centerY + size - 1,
                0xFF3A6EA5);
    }

    // -------------------------------------------------------------- selection

    /** Highlights every selected chunk and the drag rectangle. */
    public void drawSelection(GuiGraphics graphics, MapCamera camera, MapViewport viewport,
            ClaimSelection selection) {
        if (selection == null) {
            return;
        }
        int outline = 0xE0FFD040;
        int fill = 0x28FFD040;
        int drawn = 0;
        double chunkPixels = camera.zoom() * 16.0D;
        for (long key : selection.chunks()) {
            if (drawn++ >= MAX_SELECTION_OUTLINES) {
                break;
            }
            int[] rect = this.rect(camera, viewport, ChunkPos.getX(key), ChunkPos.getZ(key));
            if (rect == null) {
                continue;
            }
            if (chunkPixels >= 12.0D) {
                graphics.fill(rect[0], rect[1], rect[2], rect[3], fill);
            }
            graphics.fill(rect[0], rect[1], rect[2], rect[1] + 1, outline);
            graphics.fill(rect[0], rect[3] - 1, rect[2], rect[3], outline);
            graphics.fill(rect[0], rect[1], rect[0] + 1, rect[3], outline);
            graphics.fill(rect[2] - 1, rect[1], rect[2], rect[3], outline);
        }
        if (this.dragging) {
            int left = Math.max(viewport.x(), Math.min(this.dragStartX, this.dragEndX));
            int top = Math.max(viewport.y(), Math.min(this.dragStartY, this.dragEndY));
            int right = Math.min(viewport.x() + viewport.width(), Math.max(this.dragStartX, this.dragEndX));
            int bottom = Math.min(viewport.y() + viewport.height(), Math.max(this.dragStartY, this.dragEndY));
            if (right > left && bottom > top) {
                graphics.fill(left, top, right, bottom, 0x20FFFFFF);
                graphics.fill(left, top, right, top + 1, 0xFFFFFFFF);
                graphics.fill(left, bottom - 1, right, bottom, 0xFFFFFFFF);
                graphics.fill(left, top, left + 1, bottom, 0xFFFFFFFF);
                graphics.fill(right - 1, top, right, bottom, 0xFFFFFFFF);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private int[] rect(MapCamera camera, MapViewport viewport, int chunkX, int chunkZ) {
        int left = (int) Math.floor(viewport.worldToScreenX(camera, chunkX * 16.0D));
        int top = (int) Math.floor(viewport.worldToScreenZ(camera, chunkZ * 16.0D));
        int right = (int) Math.ceil(viewport.worldToScreenX(camera, chunkX * 16.0D + 16.0D));
        int bottom = (int) Math.ceil(viewport.worldToScreenZ(camera, chunkZ * 16.0D + 16.0D));
        left = Mth.clamp(left, viewport.x(), viewport.x() + viewport.width());
        top = Mth.clamp(top, viewport.y(), viewport.y() + viewport.height());
        right = Mth.clamp(right, left, viewport.x() + viewport.width());
        bottom = Mth.clamp(bottom, top, viewport.y() + viewport.height());
        if (right <= left || bottom <= top) {
            return null;
        }
        this.rectBuffer[0] = left;
        this.rectBuffer[1] = top;
        this.rectBuffer[2] = right;
        this.rectBuffer[3] = bottom;
        return this.rectBuffer;
    }

    /**
     * Fills a rectangle with diagonal stripes. The quads are rotated around the
     * rectangle centre and clipped with its scissor, mirroring the slime chunk
     * overlay so both patterns look alike.
     */
    private static void fillStripes(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 2 || height <= 2) {
            graphics.fill(left, top, right, bottom, color);
            return;
        }
        int thickness = Math.max(1, Math.round(Math.min(width, height) * 0.18F));
        int period = Math.max(thickness + 2, Math.round(Math.min(width, height) * 0.45F));
        int extent = Math.max(width, height);
        graphics.enableScissor(left, top, right, bottom);
        graphics.pose().pushPose();
        graphics.pose().translate((left + right) / 2.0F, (top + bottom) / 2.0F, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(45.0F));
        for (int offset = -extent; offset <= extent; offset += period) {
            graphics.fill(-extent, offset, extent, offset + thickness, color);
        }
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    private static int brighten(int rgb) {
        int red = Math.min(255, ((rgb >> 16) & 0xFF) + 90);
        int green = Math.min(255, ((rgb >> 8) & 0xFF) + 90);
        int blue = Math.min(255, (rgb & 0xFF) + 90);
        return (red << 16) | (green << 8) | blue;
    }
}
