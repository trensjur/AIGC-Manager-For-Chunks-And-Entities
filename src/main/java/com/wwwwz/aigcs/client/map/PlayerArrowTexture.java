package com.wwwwz.aigcs.client.map;

import org.joml.Quaternionf;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Baked, anti aliased player arrow.
 * <p>
 * The arrow is rasterised once into a 64x64 dynamic texture with 4x4 super
 * sampling, so its outline stays smooth while the icon is rotated on the map.
 * Drawing only rotates and blits that texture, which keeps per frame cost low.
 */
@SuppressWarnings("null")
public final class PlayerArrowTexture {
    private static final int SIZE = 64;
    private static final int SUPERSAMPLE = 4;
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("m4cne", "player_arrow");

    /** Outer dart: tip, right wing, notch, left wing. */
    private static final double[] OUTER_X = { 32.0D, 59.0D, 32.0D, 5.0D };
    private static final double[] OUTER_Y = { 2.0D, 58.0D, 44.0D, 58.0D };
    /** Inner white dart, inset from {@link #OUTER_X} so a black border remains. */
    private static final double[] INNER_X = { 32.0D, 50.0D, 32.0D, 14.0D };
    private static final double[] INNER_Y = { 13.0D, 50.0D, 41.0D, 50.0D };

    private static final PlayerArrowTexture INSTANCE = new PlayerArrowTexture();

    private DynamicTexture texture;

    private PlayerArrowTexture() {
    }

    public static PlayerArrowTexture get() {
        return INSTANCE;
    }

    /**
     * Draws the arrow centred on the given GUI position.
     *
     * @param size       edge length of the square icon in GUI pixels
     * @param yawDegrees player yaw, the arrow points into the facing direction
     */
    public void draw(GuiGraphics graphics, float centerX, float centerY, float size, float yawDegrees) {
        this.ensureTexture();
        if (this.texture == null) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0F);
        // The baked arrow points up (-Z / north); yaw 0 faces +Z, so the icon
        // has to be rotated by yaw + 180 degrees.
        graphics.pose().mulPose(new Quaternionf().rotateZ((float) Math.toRadians(yawDegrees + 180.0F)));
        int extent = Math.max(4, Math.round(size));
        int offset = -extent / 2;
        graphics.blit(ID, offset, offset, extent, extent, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);
        graphics.pose().popPose();
    }

    private void ensureTexture() {
        Minecraft minecraft = Minecraft.getInstance();
        if (this.texture != null && minecraft.getTextureManager().getTexture(ID, null) != null) {
            return;
        }
        NativeImage image = bake();
        DynamicTexture baked = new DynamicTexture(image);
        this.texture = baked;
        minecraft.getTextureManager().register(ID, baked);
        // Linear filtering keeps the rotated silhouette smooth.
        baked.setFilter(true, false);
        baked.upload();
    }

    /** Rasterises the white dart with its black border and anti aliased edges. */
    private static NativeImage bake() {
        // The image must be zero filled: NativeImage only allocates uninitialised
        // memory unless useCalloc is set, which showed up as a rectangular block
        // of garbage pixels around the arrow.
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, true);
        int samples = SUPERSAMPLE * SUPERSAMPLE;
        for (int pixelY = 0; pixelY < SIZE; pixelY++) {
            for (int pixelX = 0; pixelX < SIZE; pixelX++) {
                int covered = 0;
                int red = 0;
                int green = 0;
                int blue = 0;
                for (int subY = 0; subY < SUPERSAMPLE; subY++) {
                    for (int subX = 0; subX < SUPERSAMPLE; subX++) {
                        double sampleX = pixelX + (subX + 0.5D) / SUPERSAMPLE;
                        double sampleY = pixelY + (subY + 0.5D) / SUPERSAMPLE;
                        if (!inside(sampleX, sampleY, INNER_X, INNER_Y)) {
                            if (!inside(sampleX, sampleY, OUTER_X, OUTER_Y)) {
                                continue;
                            }
                            // Black outline.
                            covered++;
                            continue;
                        }
                        covered++;
                        red += 255;
                        green += 255;
                        blue += 255;
                    }
                }
                if (covered == 0) {
                    continue;
                }
                int alpha = Math.round(255.0F * covered / samples);
                int r = red / covered;
                int g = green / covered;
                int b = blue / covered;
                // NativeImage#setPixelRGBA expects ABGR bytes (red in the low byte).
                image.setPixelRGBA(pixelX, pixelY, (alpha << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return image;
    }

    /** Even-odd point in polygon test, the dart is concave. */
    private static boolean inside(double x, double y, double[] xs, double[] ys) {
        boolean result = false;
        for (int index = 0, previous = xs.length - 1; index < xs.length; previous = index++) {
            double currentX = xs[index];
            double currentY = ys[index];
            double previousX = xs[previous];
            double previousY = ys[previous];
            if ((currentY > y) != (previousY > y)
                    && x < (previousX - currentX) * (y - currentY) / (previousY - currentY) + currentX) {
                result = !result;
            }
        }
        return result;
    }
}
