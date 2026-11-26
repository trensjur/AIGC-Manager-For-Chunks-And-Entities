package com.wwwwz.aigcs.client.gui.widget;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Vanilla styled on/off button that renders the current state in its label. */
@SuppressWarnings("null")
public final class MapToggleButton extends Button {
    private final Component label;
    private final BooleanSupplier getter;
    private final Consumer<Boolean> setter;
    private final Runnable changed;

    public MapToggleButton(int x, int y, int width, int height, Component label, BooleanSupplier getter,
            Consumer<Boolean> setter, Runnable changed) {
        super(x, y, width, height, message(label, getter.getAsBoolean()),
                button -> ((MapToggleButton) button).toggle(), DEFAULT_NARRATION);
        this.label = label;
        this.getter = getter;
        this.setter = setter;
        this.changed = changed;
    }

    private void toggle() {
        boolean next = !this.getter.getAsBoolean();
        this.setter.accept(next);
        this.setMessage(message(this.label, next));
        this.changed.run();
    }

    private static Component message(Component label, boolean value) {
        return Component.empty()
                .append(label)
                .append(": ")
                .append(Component.translatable(value ? "screen.m4cne.value.on" : "screen.m4cne.value.off"));
    }
}
