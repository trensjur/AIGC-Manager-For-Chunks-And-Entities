package com.wwwwz.aigcs.client.claim;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * Where the "entered a claimed chunk" hint is shown.
 * <p>
 * The hint is triggered by the per chunk flag of the claim list, this enum only
 * decides how it reaches the player. {@link #ABOVE_HOTBAR} uses the vanilla
 * overlay message (the line above the hot bar), {@link #CHAT} prints the hint
 * into the chat and {@link #OFF} hides it completely.
 */
public enum ClaimMessageMode implements TranslatableEnum {
    ABOVE_HOTBAR("screen.m4cne.claims.message_mode.hotbar", 0),
    CHAT("screen.m4cne.claims.message_mode.chat", 1),
    OFF("screen.m4cne.claims.message_mode.off", 2);

    private final String translationKey;
    private final int protocolValue;

    ClaimMessageMode(String translationKey, int protocolValue) {
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

    public ClaimMessageMode next() {
        ClaimMessageMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
