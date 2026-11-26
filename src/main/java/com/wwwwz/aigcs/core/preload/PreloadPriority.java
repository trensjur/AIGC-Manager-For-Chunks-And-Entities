package com.wwwwz.aigcs.core.preload;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * Five step priority scale for the threads a preload task uses.
 * <p>
 * The value maps onto {@link Thread} priorities, so the server can apply it to
 * its own worker thread without knowing the GUI.
 */
public enum PreloadPriority implements TranslatableEnum {
    LOWEST(Thread.MIN_PRIORITY),
    LOW(3),
    NORMAL(Thread.NORM_PRIORITY),
    HIGH(7),
    HIGHEST(Thread.MAX_PRIORITY);

    private final int threadPriority;

    PreloadPriority(int threadPriority) {
        this.threadPriority = threadPriority;
    }

    public int threadPriority() {
        return this.threadPriority;
    }

    public String translationKey() {
        return "screen.m4cne.preload.priority." + this.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(this.translationKey());
    }

    public PreloadPriority next() {
        PreloadPriority[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static PreloadPriority byOrdinal(int ordinal) {
        PreloadPriority[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NORMAL;
    }
}
