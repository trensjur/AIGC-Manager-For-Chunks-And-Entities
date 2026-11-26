package com.wwwwz.aigcs.core.preload;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * Outline of a preload region.
 * <p>
 * The shape is part of the region geometry and therefore lives in the common
 * part of the mod: the server uses it to compute which chunks belong to the
 * task, the client uses it to draw the wireframe on the world map.
 */
public enum PreloadShape implements TranslatableEnum {
    SQUARE("screen.m4cne.preload.shape.square"),
    CIRCLE("screen.m4cne.preload.shape.circle");

    private final String translationKey;

    PreloadShape(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }

    public PreloadShape next() {
        return this == SQUARE ? CIRCLE : SQUARE;
    }

    /**
     * @param deltaX chunk distance to the centre
     * @param deltaZ chunk distance to the centre
     * @param radius radius of the region in chunks
     */
    public boolean accepts(int deltaX, int deltaZ, int radius) {
        if (Math.abs(deltaX) > radius || Math.abs(deltaZ) > radius) {
            return false;
        }
        if (this == CIRCLE) {
            return (long) deltaX * deltaX + (long) deltaZ * deltaZ <= (long) radius * radius;
        }
        return true;
    }

    public static PreloadShape byOrdinal(int ordinal) {
        PreloadShape[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : SQUARE;
    }
}
