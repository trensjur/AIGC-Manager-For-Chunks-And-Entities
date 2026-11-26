package com.wwwwz.aigcs.client.map;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * The two map layers the world map can display.
 * <p>
 * The config stores the enum name, the GUI only renders the translation key so
 * both layers can be localised without touching the sampling code.
 */
public enum MapLayer implements TranslatableEnum {
    SURFACE("screen.m4cne.layer.surface"),
    CAVE("screen.m4cne.layer.cave");

    private final String translationKey;

    MapLayer(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }

    public MapLayer next() {
        return this == SURFACE ? CAVE : SURFACE;
    }
}
