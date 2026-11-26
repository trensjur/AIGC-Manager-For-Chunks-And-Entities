package com.wwwwz.aigcs.client.map;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * How the entity radar renders the recorded entities on the world map.
 * <p>
 * {@link #MERGED} keeps the classic behaviour: every chunk gets one marker that
 * aggregates all entities inside it. {@link #INDIVIDUAL} splits the chunk data
 * and draws one marker per sampled entity, either as a dot or as a static icon.
 */
public enum EntityDisplayMode implements TranslatableEnum {
    MERGED("screen.m4cne.entity_mode.merged"),
    INDIVIDUAL("screen.m4cne.entity_mode.individual");

    private final String translationKey;

    EntityDisplayMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }

    public EntityDisplayMode next() {
        return this == MERGED ? INDIVIDUAL : MERGED;
    }
}
