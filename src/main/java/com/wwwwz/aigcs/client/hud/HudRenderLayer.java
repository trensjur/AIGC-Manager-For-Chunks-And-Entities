package com.wwwwz.aigcs.client.hud;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * Render order of the minimap / compass HUD.
 * <p>
 * {@link #ABOVE} draws after the vanilla HUD (and after most mod overlays),
 * {@link #BELOW} draws before the vanilla HUD so other mods can put their own
 * overlays on top of the minimap.
 */
public enum HudRenderLayer implements TranslatableEnum {
    ABOVE("screen.m4cne.hud_layer.above"),
    BELOW("screen.m4cne.hud_layer.below");

    private final String translationKey;

    HudRenderLayer(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }

    public HudRenderLayer next() {
        return this == ABOVE ? BELOW : ABOVE;
    }
}
