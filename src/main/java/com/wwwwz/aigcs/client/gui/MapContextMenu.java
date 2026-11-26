package com.wwwwz.aigcs.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.wwwwz.aigcs.client.map.MapViewport;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Small right click menu of the world map canvas.
 * <p>
 * It is intentionally not a vanilla widget: the menu has to appear at the mouse
 * position, may contain disabled entries and is drawn above the map overlays.
 * Clicks outside of the menu close it without starting a map drag.
 */
@SuppressWarnings("null")
public final class MapContextMenu {
    public record Entry(Component label, Runnable action, boolean enabled) {
        public Entry(Component label, Runnable action) {
            this(label, action, true);
        }
    }

    private static final int ROW_HEIGHT = 12;
    private static final int PADDING = 3;
    private static final int MIN_WIDTH = 96;

    private final List<Entry> entries = new ArrayList<>();
    private boolean open;
    private int x;
    private int y;
    private int width;
    private int height;

    public boolean isOpen() {
        return this.open;
    }

    public void close() {
        this.open = false;
        this.entries.clear();
    }

    /** Opens the menu next to the mouse, keeping it inside the viewport. */
    public void open(double mouseX, double mouseY, List<Entry> items, MapViewport viewport) {
        this.entries.clear();
        this.entries.addAll(items);
        if (this.entries.isEmpty()) {
            this.close();
            return;
        }
        Font font = Minecraft.getInstance().font;
        int textWidth = 0;
        for (Entry entry : this.entries) {
            textWidth = Math.max(textWidth, font.width(entry.label()));
        }
        this.width = Math.max(MIN_WIDTH, textWidth + PADDING * 2 + 8);
        this.height = this.entries.size() * ROW_HEIGHT + PADDING * 2;
        int wantedX = (int) Math.round(mouseX);
        int wantedY = (int) Math.round(mouseY);
        if (wantedX + this.width > viewport.x() + viewport.width()) {
            wantedX = viewport.x() + viewport.width() - this.width;
        }
        if (wantedY + this.height > viewport.y() + viewport.height()) {
            wantedY = viewport.y() + viewport.height() - this.height;
        }
        this.x = Mth.clamp(wantedX, viewport.x(), Math.max(viewport.x(),
                viewport.x() + viewport.width() - this.width));
        this.y = Mth.clamp(wantedY, viewport.y(), Math.max(viewport.y(),
                viewport.y() + viewport.height() - this.height));
        this.open = true;
    }

    /**
     * Handles a click.
     *
     * @return {@code true} when the click belonged to the menu (or closed it)
     */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (!this.open) {
            return false;
        }
        int row = (int) Math.floor((mouseY - this.y - PADDING) / ROW_HEIGHT);
        boolean inside = mouseX >= this.x && mouseX < this.x + this.width
                && mouseY >= this.y && mouseY < this.y + this.height;
        if (!inside || row < 0 || row >= this.entries.size()) {
            this.close();
            return true;
        }
        Entry entry = this.entries.get(row);
        this.close();
        if (entry.enabled()) {
            entry.action().run();
        }
        return true;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!this.open) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        graphics.fill(this.x - 1, this.y - 1, this.x + this.width + 1, this.y + this.height + 1, 0xFF000000);
        graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, 0xF0181818);
        graphics.fill(this.x, this.y, this.x + this.width, this.y + 1, 0xFF8B8B8B);
        graphics.fill(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, 0xFF3C3C3C);

        int rowY = this.y + PADDING;
        for (int index = 0; index < this.entries.size(); index++) {
            Entry entry = this.entries.get(index);
            boolean hovered = mouseX >= this.x && mouseX < this.x + this.width
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (hovered && entry.enabled()) {
                graphics.fill(this.x + 1, rowY, this.x + this.width - 1, rowY + ROW_HEIGHT, 0xFF2E4A7A);
            }
            int color = entry.enabled() ? (hovered ? 0xFFFFFFA0 : 0xFFFFFFFF) : 0xFF707070;
            graphics.drawString(font, entry.label(), this.x + PADDING + 3, rowY + 2, color, false);
            rowY += ROW_HEIGHT;
        }
    }
}
