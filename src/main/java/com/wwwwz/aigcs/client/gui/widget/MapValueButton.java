package com.wwwwz.aigcs.client.gui.widget;

import java.util.function.Supplier;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Vanilla styled button that shows and cycles an enumerated value. The current
 * value is rendered after the label so the button always reflects the config.
 */
@SuppressWarnings("null")
public final class MapValueButton extends Button {
    private final Component label;
    private final Supplier<Component> value;
    private final Runnable action;
    private final Runnable changed;

    public MapValueButton(int x, int y, int width, int height, Component label, Supplier<Component> value,
            Runnable action, Runnable changed) {
        super(x, y, width, height, message(label, value.get()),
                button -> ((MapValueButton) button).cycle(), DEFAULT_NARRATION);
        this.label = label;
        this.value = value;
        this.action = action;
        this.changed = changed;
    }

    private void cycle() {
        this.action.run();
        this.setMessage(message(this.label, this.value.get()));
        this.changed.run();
    }

    private static Component message(Component label, Component value) {
        return Component.empty().append(label).append(": ").append(value);
    }
}
