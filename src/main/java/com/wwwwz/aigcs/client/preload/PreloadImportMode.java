package com.wwwwz.aigcs.client.preload;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * Where the "import preloaded chunks and draw the map" feature reads from.
 * <p>
 * {@link #CHUNKY} samples the chunks chunky generated in the vanilla save,
 * {@link #M4CNE} streams the tiles this mod recorded during its own preload
 * task. Both feed the normal client chunk cache, so imported chunks are drawn
 * by the world map even though they are far outside the render distance.
 */
public enum PreloadImportMode implements TranslatableEnum {
    OFF("screen.m4cne.preload.import.off", 0),
    CHUNKY("screen.m4cne.preload.import.chunky", 1),
    M4CNE("screen.m4cne.preload.import.m4cne", 2);

    private final String translationKey;
    private final int protocolValue;

    PreloadImportMode(String translationKey, int protocolValue) {
        this.translationKey = translationKey;
        this.protocolValue = protocolValue;
    }

    public String translationKey() {
        return this.translationKey;
    }

    public int protocolValue() {
        return this.protocolValue;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }

    public boolean isEnabled() {
        return this != OFF;
    }

    public PreloadImportMode next() {
        PreloadImportMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
