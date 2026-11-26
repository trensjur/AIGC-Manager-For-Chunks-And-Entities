package com.wwwwz.aigcs.client.hud;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/** Which widget the in game HUD overlay shows. */
public enum HudMode implements TranslatableEnum {
    MINIMAP("screen.m4cne.hud_mode.minimap"),
    COMPASS("screen.m4cne.hud_mode.compass");

    private final String translationKey;

    HudMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }

    public HudMode next() {
        return this == MINIMAP ? COMPASS : MINIMAP;
    }
}
