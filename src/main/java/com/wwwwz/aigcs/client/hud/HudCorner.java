package com.wwwwz.aigcs.client.hud;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * The four screen corners the minimap can be anchored to.
 * <p>
 * The corner is not only the placement anchor: it is also the centre of the
 * minimap scale, so growing the widget never pushes it past the screen border
 * it touches.
 */
@SuppressWarnings("null")
public enum HudCorner implements TranslatableEnum {
    TOP_LEFT("screen.m4cne.hud_corner.top_left", true, true),
    TOP_RIGHT("screen.m4cne.hud_corner.top_right", false, true),
    BOTTOM_LEFT("screen.m4cne.hud_corner.bottom_left", true, false),
    BOTTOM_RIGHT("screen.m4cne.hud_corner.bottom_right", false, false);

    private final String translationKey;
    private final boolean left;
    private final boolean top;

    HudCorner(String translationKey, boolean left, boolean top) {
        this.translationKey = translationKey;
        this.left = left;
        this.top = top;
    }

    public String translationKey() {
        return this.translationKey;
    }

    /** @return {@code true} when the widget hugs the left half of the screen */
    public boolean isLeft() {
        return this.left;
    }

    /** @return {@code true} when the widget hugs the top half of the screen */
    public boolean isTop() {
        return this.top;
    }

    /** Cycles through the four corners in display order. */
    public HudCorner next() {
        HudCorner[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    /** Resolves the corner of a screen quadrant. */
    public static HudCorner of(boolean left, boolean top) {
        if (top) {
            return left ? TOP_LEFT : TOP_RIGHT;
        }
        return left ? BOTTOM_LEFT : BOTTOM_RIGHT;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }
}
