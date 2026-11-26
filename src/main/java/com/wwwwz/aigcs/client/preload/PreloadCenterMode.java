package com.wwwwz.aigcs.client.preload;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * Selects where the centre of a preload region comes from.
 * <p>
 * {@link #MARKER} uses the bright marker the player placed on the world map by
 * right clicking, which is why the map keeps a marker list per world.
 */
public enum PreloadCenterMode implements TranslatableEnum {
    SPAWN("screen.m4cne.preload.center.spawn"),
    PLAYER("screen.m4cne.preload.center.player"),
    MARKER("screen.m4cne.preload.center.marker");

    private final String translationKey;

    PreloadCenterMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }

    public PreloadCenterMode next() {
        PreloadCenterMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
