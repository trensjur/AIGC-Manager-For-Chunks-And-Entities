package com.wwwwz.aigcs.client.gui.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Small colour swatch of the 64 colour palettes.
 * <p>
 * Vanilla buttons always draw the widget background, so the palette needs a
 * minimal widget that only paints the colour plus a selection frame.
 */
public final class MapColorButton extends AbstractWidget {
    private final int color;
    private final boolean selected;
    private final Runnable action;

    public MapColorButton(int x, int y, int width, int height, int color, boolean selected, Runnable action) {
        super(x, y, width, height, Component.literal(String.format("#%06X", color & 0xFFFFFF)));
        this.color = color & 0xFFFFFF;
        this.selected = selected;
        this.action = action;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int border = this.selected ? 0xFFFFFFFF : 0xFF1A1A1A;
        graphics.fill(this.getX() - 1, this.getY() - 1, this.getX() + this.width + 1,
                this.getY() + this.height + 1, border);
        graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height,
                0xFF000000 | this.color);
        if (this.isHovered()) {
            graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height,
                    0x40FFFFFF);
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (this.active) {
            this.action.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
    }
}
