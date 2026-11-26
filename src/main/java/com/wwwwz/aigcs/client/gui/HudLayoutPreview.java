package com.wwwwz.aigcs.client.gui;

import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.hud.ClientHudOverlay;
import com.wwwwz.aigcs.client.hud.HudCorner;
import com.wwwwz.aigcs.client.hud.HudMode;
import com.wwwwz.aigcs.client.map.MapViewport;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Preview of the game window used by the third level HUD layout pages.
 * <p>
 * The preview keeps the aspect ratio of the real game window and draws the
 * minimap or the compass through the exact same layout code as the in game
 * HUD, so every change of the settings is visible immediately. Left clicking
 * inside the window picks the matching screen corner for the minimap, or moves
 * the centre of the compass to the clicked spot.
 */
@SuppressWarnings("null")
public final class HudLayoutPreview {
    private static final int PADDING = 12;

    private int windowX;
    private int windowY;
    private int windowWidth;
    private int windowHeight;
    private float previewScale = 1.0F;
    private int guiWidth;
    private int guiHeight;

    public void render(GuiGraphics graphics, MapViewport viewport, Minecraft minecraft, HudMode previewMode) {
        this.guiWidth = Math.max(1, minecraft.getWindow().getGuiScaledWidth());
        this.guiHeight = Math.max(1, minecraft.getWindow().getGuiScaledHeight());
        int availableWidth = Math.max(16, viewport.width() - PADDING * 2);
        int availableHeight = Math.max(16, viewport.height() - PADDING * 2);
        double aspect = this.guiWidth / (double) this.guiHeight;
        int width;
        int height;
        if (availableWidth / (double) availableHeight > aspect) {
            height = availableHeight;
            width = (int) Math.round(height * aspect);
        } else {
            width = availableWidth;
            height = (int) Math.round(width / aspect);
        }
        this.windowWidth = Math.max(1, width);
        this.windowHeight = Math.max(1, height);
        this.windowX = viewport.x() + (viewport.width() - this.windowWidth) / 2;
        this.windowY = viewport.y() + (viewport.height() - this.windowHeight) / 2;
        this.previewScale = this.windowWidth / (float) this.guiWidth;

        int left = this.windowX;
        int top = this.windowY;
        int right = left + this.windowWidth;
        int bottom = top + this.windowHeight;
        graphics.fill(left, top, right, bottom, 0xF01A1A1A);
        // Faint grid so the widget position is easy to judge.
        int gridColor = 0x18FFFFFF;
        int step = Math.max(16, this.windowWidth / 12);
        for (int x = left + step; x < right; x += step) {
            graphics.fill(x, top, x + 1, bottom, gridColor);
        }
        for (int y = top + step; y < bottom; y += step) {
            graphics.fill(left, y, right, y + 1, gridColor);
        }
        this.drawCrosshair(graphics, (left + right) / 2, (top + bottom) / 2);
        this.drawHotbar(graphics, (left + right) / 2, bottom - Math.max(14, this.windowHeight / 8));
        if (previewMode == HudMode.COMPASS) {
            // The compass layout refers to the vertical centre line of the screen.
            this.drawCentreAxis(graphics, left, top, right, bottom);
        } else {
            this.drawCornerGuides(graphics, left, top, right, bottom);
        }

        ClientHudOverlay.get().renderPreview(graphics, minecraft, this.guiWidth, this.guiHeight,
                this.previewScale, left, top, previewMode);

        graphics.fill(left - 1, top - 1, right + 1, top, 0xFF8B8B8B);
        graphics.fill(left - 1, bottom, right + 1, bottom + 1, 0xFF4C4C4C);
        graphics.fill(left - 1, top, left, bottom, 0xFF8B8B8B);
        graphics.fill(right, top, right + 1, bottom, 0xFF4C4C4C);

        Font font = Minecraft.getInstance().font;
        Component caption = Component.translatable("screen.m4cne.hud_layout.window", this.guiWidth, this.guiHeight);
        graphics.drawString(font, caption, left + 4, top + 4, 0xFFC0C0C0, true);
        Component hint = Component.translatable(previewMode == HudMode.COMPASS
                ? "screen.m4cne.hud_layout.compass_click_hint"
                : "screen.m4cne.hud_layout.minimap_click_hint");
        graphics.drawString(font, hint, left + 4, bottom - 12, 0xFF9A9A9A, true);
    }

    /** Marks the vertical centre line and the horizontal centre of the window. */
    private void drawCentreAxis(GuiGraphics graphics, int left, int top, int right, int bottom) {
        int centerX = (left + right) / 2;
        for (int y = top + 2; y < bottom; y += 6) {
            graphics.fill(centerX, y, centerX + 1, Math.min(y + 3, bottom), 0x60FFD050);
        }
        int centerY = (top + bottom) / 2;
        for (int x = left + 2; x < right; x += 6) {
            graphics.fill(x, centerY, Math.min(x + 3, right), centerY + 1, 0x28FFFFFF);
        }
    }

    /** Shows the four quadrants and highlights the active minimap corner. */
    private void drawCornerGuides(GuiGraphics graphics, int left, int top, int right, int bottom) {
        int centerX = (left + right) / 2;
        int centerY = (top + bottom) / 2;
        graphics.fill(centerX, top, centerX + 1, bottom, 0x28FFFFFF);
        graphics.fill(left, centerY, right, centerY + 1, 0x28FFFFFF);
        int size = Math.max(8, Math.min(this.windowWidth, this.windowHeight) / 8);
        HudCorner active = ClientConfig.MINIMAP_CORNER.get();
        this.drawCornerBracket(graphics, left, top, size, true, true, active == HudCorner.TOP_LEFT);
        this.drawCornerBracket(graphics, right, top, size, false, true, active == HudCorner.TOP_RIGHT);
        this.drawCornerBracket(graphics, left, bottom, size, true, false, active == HudCorner.BOTTOM_LEFT);
        this.drawCornerBracket(graphics, right, bottom, size, false, false, active == HudCorner.BOTTOM_RIGHT);
    }

    private void drawCornerBracket(GuiGraphics graphics, int x, int y, int size, boolean leftSide,
            boolean topSide, boolean active) {
        int color = active ? 0xFFFFD050 : 0x60FFFFFF;
        int horizontalStart = leftSide ? x : x - size;
        int horizontalEnd = leftSide ? x + size : x;
        int horizontalY = topSide ? y : y - 1;
        graphics.fill(horizontalStart, horizontalY, horizontalEnd, horizontalY + 1, color);
        int verticalStart = topSide ? y : y - size;
        int verticalEnd = topSide ? y + size : y;
        int verticalX = leftSide ? x : x - 1;
        graphics.fill(verticalX, verticalStart, verticalX + 1, verticalEnd, color);
    }

    /** Draws a simple hotbar placeholder so the widget has a size reference. */
    private void drawHotbar(GuiGraphics graphics, int centerX, int y) {
        int slot = Math.max(8, Math.min(20, this.windowHeight / 12));
        int barWidth = slot * 9;
        int left = centerX - barWidth / 2;
        graphics.fill(left - 2, y - 2, left + barWidth + 2, y + slot + 2, 0xC0000000);
        for (int index = 0; index < 9; index++) {
            int x = left + index * slot;
            graphics.fill(x, y, x + slot - 1, y + slot - 1, 0x40FFFFFF);
            graphics.fill(x + 1, y + 1, x + slot - 2, y + slot - 2, 0x30000000);
        }
    }

    private void drawCrosshair(GuiGraphics graphics, int centerX, int centerY) {
        graphics.fill(centerX - 5, centerY, centerX + 5, centerY + 1, 0x80FFFFFF);
        graphics.fill(centerX, centerY - 5, centerX + 1, centerY + 5, 0x80FFFFFF);
    }

    /**
     * Applies a click inside the simulated window.
     *
     * @param previewMode widget whose layout page is open
     * @return {@code true} when the click landed inside the simulated window
     */
    public boolean placeAt(double mouseX, double mouseY, HudMode previewMode) {
        if (this.previewScale <= 0.0F || mouseX < this.windowX || mouseX >= this.windowX + this.windowWidth
                || mouseY < this.windowY || mouseY >= this.windowY + this.windowHeight) {
            return false;
        }
        int x = Mth.floor((mouseX - this.windowX) / this.previewScale);
        int y = Mth.floor((mouseY - this.windowY) / this.previewScale);
        if (previewMode == HudMode.COMPASS) {
            // The compass centre moves to the clicked spot: the horizontal
            // value is the offset from the centre line of the screen.
            ClientConfig.COMPASS_POS_X.set(Mth.clamp(x - this.guiWidth / 2, -8192, 8192));
            ClientConfig.COMPASS_POS_Y.set(Mth.clamp(y, 0, 8192));
        } else {
            // The minimap snaps to the quadrant that was clicked.
            ClientConfig.MINIMAP_CORNER.set(HudCorner.of(x < this.guiWidth / 2, y < this.guiHeight / 2));
        }
        return true;
    }
}
