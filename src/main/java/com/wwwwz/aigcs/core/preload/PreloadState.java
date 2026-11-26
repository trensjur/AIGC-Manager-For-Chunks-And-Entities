package com.wwwwz.aigcs.core.preload;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * Life cycle of a preload task.
 * <p>
 * The server owns the state; the client only mirrors what the last status
 * payload reported. {@link #DONE} and {@link #CANCELLED} are terminal, both
 * release the region tickets held by the server.
 */
public enum PreloadState implements TranslatableEnum {
    IDLE("screen.m4cne.preload.state.idle"),
    RUNNING("screen.m4cne.preload.state.running"),
    PAUSED("screen.m4cne.preload.state.paused"),
    DONE("screen.m4cne.preload.state.done"),
    CANCELLED("screen.m4cne.preload.state.cancelled"),
    FAILED("screen.m4cne.preload.state.failed");

    private final String translationKey;

    PreloadState(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey);
    }

    /** A task that still owns a progress record. */
    public boolean hasTask() {
        return this == RUNNING || this == PAUSED || this == FAILED;
    }

    public boolean isRunning() {
        return this == RUNNING;
    }

    public static PreloadState byOrdinal(int ordinal) {
        PreloadState[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IDLE;
    }
}
