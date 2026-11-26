package com.wwwwz.aigcs.client.gui;

import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.map.MapCamera;
import com.wwwwz.aigcs.client.map.MapViewport;
import com.wwwwz.aigcs.client.preload.PreloadController;
import com.wwwwz.aigcs.client.preload.PreloadMarkerStore;
import com.wwwwz.aigcs.core.preload.PreloadRegion;
import com.wwwwz.aigcs.core.preload.PreloadShape;
import com.wwwwz.aigcs.core.preload.PreloadState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws the preload visualisation on the world map: the outline of the region,
 * the progress label and the bright centre markers the player placed with the
 * right click menu.
 */
@SuppressWarnings("null")
public final class PreloadOverlay {
    private static final double MARKER_PICK_PIXELS = 7.0D;
    private static final int ACTIVE_COLOR = 0xFFFFD070;
    private static final int DONE_COLOR = 0xFF6CFF6C;
    private static final int PAUSED_COLOR = 0xFFB0B0B0;

    /** Draws the outline (and a very light fill) of the active preload region. */
    public void drawRange(GuiGraphics graphics, MapCamera camera, MapViewport viewport,
            PreloadController.Status status) {
        if (!ClientConfig.PRELOAD_SHOW_RANGE.getAsBoolean() || status == null) {
            return;
        }
        PreloadState state = status.state();
        if (state == PreloadState.IDLE || status.total() <= 0) {
            return;
        }
        PreloadRegion region = status.region();
        int color = switch (state) {
            case DONE, CANCELLED -> DONE_COLOR;
            case PAUSED, FAILED -> PAUSED_COLOR;
            default -> ACTIVE_COLOR;
        };

        double minWorldX = region.minChunkX() * 16.0D;
        double minWorldZ = region.minChunkZ() * 16.0D;
        double maxWorldX = (region.minChunkX() + region.width()) * 16.0D;
        double maxWorldZ = (region.minChunkZ() + region.width()) * 16.0D;

        int left = Mth.clamp((int) Math.floor(viewport.worldToScreenX(camera, minWorldX)),
                viewport.x(), viewport.x() + viewport.width());
        int top = Mth.clamp((int) Math.floor(viewport.worldToScreenZ(camera, minWorldZ)),
                viewport.y(), viewport.y() + viewport.height());
        int right = Mth.clamp((int) Math.ceil(viewport.worldToScreenX(camera, maxWorldX)),
                viewport.x(), viewport.x() + viewport.width());
        int bottom = Mth.clamp((int) Math.ceil(viewport.worldToScreenZ(camera, maxWorldZ)),
                viewport.y(), viewport.y() + viewport.height());
        if (right <= left || bottom <= top) {
            return;
        }

        int fill = (color & 0xFFFFFF) | (0x1A << 24);
        if (region.shape() == PreloadShape.SQUARE) {
            graphics.fill(left, top, right, bottom, fill);
            drawFrame(graphics, left, top, right, bottom, color);
        } else {
            drawCircle(graphics, camera, viewport, region, color);
        }

        // The centre cross makes it obvious which chunk the task started at.
        int centerX = (int) Math.round(viewport.worldToScreenX(camera, (region.centerChunkX() + 0.5D) * 16.0D));
        int centerZ = (int) Math.round(viewport.worldToScreenZ(camera, (region.centerChunkZ() + 0.5D) * 16.0D));
        if (viewport.contains(centerX, centerZ)) {
            graphics.fill(centerX - 5, centerZ, centerX + 6, centerZ + 1, color);
            graphics.fill(centerX, centerZ - 5, centerX + 1, centerZ + 6, color);
        }

        drawProgressLabel(graphics, viewport, left, top, status, color);
    }

    private static void drawFrame(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, bottom - 1, right, bottom, color);
        graphics.fill(left, top, left + 1, bottom, color);
        graphics.fill(right - 1, top, right, bottom, color);
    }

    private void drawCircle(GuiGraphics graphics, MapCamera camera, MapViewport viewport, PreloadRegion region,
            int color) {
        double radiusBlocks = (region.radius() + 0.5D) * 16.0D;
        double centerWorldX = (region.centerChunkX() + 0.5D) * 16.0D;
        double centerWorldZ = (region.centerChunkZ() + 0.5D) * 16.0D;
        int steps = 240;
        int previousX = Integer.MIN_VALUE;
        int previousY = Integer.MIN_VALUE;
        for (int step = 0; step <= steps; step++) {
            double angle = Math.PI * 2.0D * step / steps;
            double worldX = centerWorldX + Math.cos(angle) * radiusBlocks;
            double worldZ = centerWorldZ + Math.sin(angle) * radiusBlocks;
            int x = (int) Math.round(viewport.worldToScreenX(camera, worldX));
            int y = (int) Math.round(viewport.worldToScreenZ(camera, worldZ));
            if (previousX != Integer.MIN_VALUE) {
                drawLine(graphics, viewport, previousX, previousY, x, y, color);
            }
            previousX = x;
            previousY = y;
        }
    }

    /** Bresenham style 1px line, clipped to the viewport. */
    private void drawLine(GuiGraphics graphics, MapViewport viewport, int x0, int y0, int x1, int y1, int color) {
        int deltaX = Math.abs(x1 - x0);
        int deltaY = Math.abs(y1 - y0);
        int stepX = x0 < x1 ? 1 : -1;
        int stepY = y0 < y1 ? 1 : -1;
        int error = deltaX - deltaY;
        int x = x0;
        int y = y0;
        int guard = 0;
        while (guard++ < 4096) {
            if (x >= viewport.x() && x < viewport.x() + viewport.width()
                    && y >= viewport.y() && y < viewport.y() + viewport.height()) {
                graphics.fill(x, y, x + 1, y + 1, color);
            }
            if (x == x1 && y == y1) {
                return;
            }
            int doubled = error * 2;
            if (doubled > -deltaY) {
                error -= deltaY;
                x += stepX;
            }
            if (doubled < deltaX) {
                error += deltaX;
                y += stepY;
            }
        }
    }

    private void drawProgressLabel(GuiGraphics graphics, MapViewport viewport, int left, int top,
            PreloadController.Status status, int color) {
        Font font = Minecraft.getInstance().font;
        int percent = (int) Math.round(status.progress() * 100.0D);
        Component text = Component.translatable("screen.m4cne.preload.map_label", percent, status.completed(),
                status.total());
        int width = font.width(text) + 6;
        int x = Mth.clamp(left + 2, viewport.x() + 1, Math.max(viewport.x() + 1,
                viewport.x() + viewport.width() - width - 1));
        int y = Mth.clamp(top + 2, viewport.y() + 1, Math.max(viewport.y() + 1,
                viewport.y() + viewport.height() - 11));
        graphics.fill(x, y, x + width, y + 10, 0xA0000000);
        graphics.drawString(font, text, x + 3, y + 1, color, false);
    }

    /** Draws every bright centre marker of the dimension. */
    public void drawMarkers(GuiGraphics graphics, MapCamera camera, MapViewport viewport, ResourceLocation dimension) {
        PreloadMarkerStore store = PreloadMarkerStore.get();
        PreloadMarkerStore.Marker active = store.active(dimension);
        for (PreloadMarkerStore.Marker marker : store.forDimension(dimension)) {
            double screenX = viewport.worldToScreenX(camera, marker.x() + 0.5D);
            double screenZ = viewport.worldToScreenZ(camera, marker.z() + 0.5D);
            if (!viewport.contains(screenX, screenZ)) {
                continue;
            }
            int x = (int) Math.round(screenX);
            int y = (int) Math.round(screenZ);
            boolean isActive = active != null && active.id() == marker.id();
            int radius = isActive ? 4 : 3;
            graphics.fill(x - radius - 1, y - radius - 1, x + radius + 2, y + radius + 2, 0xFF000000);
            // Bright dot: a warm core surrounded by a lighter halo so it never
            // disappears on a bright terrain colour.
            graphics.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, 0xFFFFF2A0);
            graphics.fill(x - radius + 1, y - radius + 1, x + radius, y + radius, 0xFFFFFFFF);
            if (isActive) {
                graphics.fill(x - radius - 3, y - radius - 3, x + radius + 4, y - radius - 2, 0xFFFFD070);
                graphics.fill(x - radius - 3, y + radius + 3, x + radius + 4, y + radius + 4, 0xFFFFD070);
                graphics.fill(x - radius - 3, y - radius - 3, x - radius - 2, y + radius + 4, 0xFFFFD070);
                graphics.fill(x + radius + 3, y - radius - 3, x + radius + 4, y + radius + 4, 0xFFFFD070);
            }
        }
    }

    /** @return the marker under the cursor, {@code null} when there is none */
    public PreloadMarkerStore.Marker pickMarker(MapCamera camera, MapViewport viewport, ResourceLocation dimension,
            double mouseX, double mouseY) {
        PreloadMarkerStore.Marker best = null;
        double bestDistance = MARKER_PICK_PIXELS * MARKER_PICK_PIXELS;
        for (PreloadMarkerStore.Marker marker : PreloadMarkerStore.get().forDimension(dimension)) {
            double screenX = viewport.worldToScreenX(camera, marker.x() + 0.5D);
            double screenZ = viewport.worldToScreenZ(camera, marker.z() + 0.5D);
            double deltaX = screenX - mouseX;
            double deltaZ = screenZ - mouseY;
            double distance = deltaX * deltaX + deltaZ * deltaZ;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = marker;
            }
        }
        return best;
    }
}
