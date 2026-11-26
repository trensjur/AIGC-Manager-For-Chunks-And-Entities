package com.wwwwz.aigcs.client.gui.widget;

import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Vanilla slider with a localisable label and a custom value formatter.
 * <p>
 * The slider works on normalised values internally and maps them onto the
 * configured minimum/maximum, so every setting keeps its real range.
 */
@SuppressWarnings("null")
public final class MapSlider extends AbstractSliderButton {
    private final Component label;
    private final double minimum;
    private final double maximum;
    private final double step;
    private final Function<Double, Component> formatter;
    private final Consumer<Double> setter;
    private final Runnable changed;

    public MapSlider(int x, int y, int width, int height, Component label, double minimum, double maximum,
            double step, double initial, Function<Double, Component> formatter, Consumer<Double> setter,
            Runnable changed) {
        super(x, y, width, height, Component.empty(),
                maximum - minimum <= 0.0D ? 0.0D
                        : (Mth.clamp(initial, minimum, maximum) - minimum) / (maximum - minimum));
        this.label = label;
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = step;
        this.formatter = formatter;
        this.setter = setter;
        this.changed = changed;
        this.updateMessage();
    }

    public double currentValue() {
        double raw = this.minimum + (this.maximum - this.minimum) * this.value;
        if (this.step > 0.0D) {
            raw = Math.round(raw / this.step) * this.step;
        }
        return Mth.clamp(raw, this.minimum, this.maximum);
    }

    @Override
    protected void updateMessage() {
        this.setMessage(Component.empty()
                .append(this.label)
                .append(": ")
                .append(this.formatter.apply(this.currentValue())));
    }

    @Override
    protected void applyValue() {
        double value = this.currentValue();
        this.setter.accept(value);
        this.updateMessage();
        this.changed.run();
    }
}
