package com.wwwwz.aigcs.client.gui.widget;

import java.util.function.IntConsumer;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Compact label plus numeric text field used by the settings panel.
 * <p>
 * The widget replaces the old integer sliders: the label is drawn on the left
 * and a normal {@link EditBox} on the right. Pressing Enter applies the typed
 * value; moving the focus elsewhere commits it as well. Values are clamped to
 * the configured range and invalid text simply restores the last valid value.
 */
@SuppressWarnings("null")
public final class MapNumberField extends AbstractWidget {
    private static final int INPUT_HEIGHT = 16;

    private final EditBox input;
    private final int minimum;
    private final int maximum;
    private final IntConsumer setter;
    private final Runnable changed;
    private int value;
    private boolean committing;

    public MapNumberField(int x, int y, int width, int height, Component label, int minimum, int maximum,
            int initial, IntConsumer setter, Runnable changed) {
        super(x, y, width, height, label);
        this.minimum = minimum;
        this.maximum = maximum;
        this.setter = setter;
        this.changed = changed;
        this.value = Mth.clamp(initial, minimum, maximum);

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int inputWidth = Mth.clamp(width / 3, 40, 72);
        this.input = new EditBox(font,
                x + width - inputWidth - 3,
                y + (height - INPUT_HEIGHT) / 2,
                inputWidth, INPUT_HEIGHT, label);
        this.input.setMaxLength(10);
        this.input.setValue(Integer.toString(this.value));
        this.input.setFilter(this::isValidText);
        this.input.setTextShadow(false);
    }

    /** @return the last committed value */
    public int value() {
        return this.value;
    }

    private boolean isValidText(String text) {
        if (text.isEmpty()) {
            return true;
        }
        if ("-".equals(text)) {
            return this.minimum < 0;
        }
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /** Applies the typed value; called by Enter and when the field loses focus. */
    public void commit() {
        if (this.committing) {
            return;
        }
        this.committing = true;
        try {
            int parsed = this.value;
            try {
                parsed = Integer.parseInt(this.input.getValue().trim());
            } catch (NumberFormatException ignored) {
                // Empty or incomplete input: fall back to the last valid value.
            }
            int clamped = Mth.clamp(parsed, this.minimum, this.maximum);
            // Rewrite the field so it always shows the accepted value.
            this.input.setValue(Integer.toString(clamped));
            if (clamped != this.value) {
                this.value = clamped;
                this.setter.accept(clamped);
                this.changed.run();
            }
        } finally {
            this.committing = false;
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.input.active = this.active;
        this.input.visible = true;
        this.input.setEditable(this.active);

        int left = this.getX();
        int top = this.getY();
        int right = left + this.getWidth();
        int bottom = top + this.getHeight();
        graphics.fill(left, top, right, bottom, this.active ? 0xA0000000 : 0x60000000);
        graphics.fill(left, top, right, top + 1, this.active ? 0xFF707070 : 0xFF404040);
        graphics.fill(left, bottom - 1, right, bottom, 0xFF303030);
        graphics.fill(left, top, left + 1, bottom, 0xFF707070);
        graphics.fill(right - 1, top, right, bottom, 0xFF303030);

        Font font = Minecraft.getInstance().font;
        Component label = this.getMessage();
        int labelWidth = Math.max(0, this.input.getX() - left - 8);
        int textWidth = font.width(label);
        float scale = textWidth > labelWidth && textWidth > 0 ? labelWidth / (float) textWidth : 1.0F;
        scale = Math.max(0.5F, scale);
        int color = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
        graphics.pose().pushPose();
        graphics.pose().translate(left + 4, top + (this.getHeight() - 8.0F * scale) / 2.0F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, label, 0, 0, color, false);
        graphics.pose().popPose();

        this.input.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible || button != 0) {
            return false;
        }
        if (this.input.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.clicked(mouseX, mouseY)) {
            this.input.setFocused(true);
            this.input.setCursorPosition(this.input.getValue().length());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.input.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return this.input.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.active || !this.visible) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.commit();
            return true;
        }
        return this.input.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.active || !this.visible) {
            return false;
        }
        return this.input.charTyped(codePoint, modifiers);
    }

    @Override
    public void setFocused(boolean focused) {
        boolean wasFocused = this.isFocused();
        super.setFocused(focused);
        this.input.setFocused(focused);
        if (wasFocused && !focused) {
            this.commit();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
