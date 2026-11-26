package com.wwwwz.aigcs.client.map;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/** Marker style used by {@link EntityDisplayMode#INDIVIDUAL}. */
public enum EntityMarkerStyle implements TranslatableEnum {
    DOT("screen.m4cne.entity_style.dot"),
    ICON("screen.m4cne.entity_style.icon");

    private final String translationKey;

    EntityMarkerStyle(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }

    public EntityMarkerStyle next() {
        return this == DOT ? ICON : DOT;
    }
}
