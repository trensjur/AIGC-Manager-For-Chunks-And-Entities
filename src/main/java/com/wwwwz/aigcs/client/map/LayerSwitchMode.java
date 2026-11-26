package com.wwwwz.aigcs.client.map;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/** How the world map decides between the surface and the cave layer. */
public enum LayerSwitchMode implements TranslatableEnum {
    /** Picks the cave layer automatically while the player is underground. */
    AUTO("screen.m4cne.layer.mode.auto"),
    /** Always uses {@link com.wwwwz.aigcs.client.ClientConfig#MAP_MANUAL_LAYER}. */
    MANUAL("screen.m4cne.layer.mode.manual");

    private final String translationKey;

    LayerSwitchMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }

    public LayerSwitchMode next() {
        return this == AUTO ? MANUAL : AUTO;
    }
}
