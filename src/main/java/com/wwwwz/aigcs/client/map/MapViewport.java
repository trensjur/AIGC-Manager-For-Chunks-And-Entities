package com.wwwwz.aigcs.client.map;

import net.minecraft.util.Mth;

/** Pixel rectangle of the map canvas inside the world map screen. */
public record MapViewport(int x, int y, int width, int height) {
    public int centerX() {
        return this.x + this.width / 2;
    }

    public int centerY() {
        return this.y + this.height / 2;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.width
                && mouseY >= this.y && mouseY < this.y + this.height;
    }

    public double screenToWorldX(MapCamera camera, double screenX) {
        return camera.centerX() + (screenX - this.centerX()) / camera.zoom();
    }

    public double screenToWorldZ(MapCamera camera, double screenY) {
        return camera.centerZ() + (screenY - this.centerY()) / camera.zoom();
    }

    public double worldToScreenX(MapCamera camera, double worldX) {
        return this.centerX() + (worldX - camera.centerX()) * camera.zoom();
    }

    public double worldToScreenZ(MapCamera camera, double worldZ) {
        return this.centerY() + (worldZ - camera.centerZ()) * camera.zoom();
    }

    public int clampX(double x) {
        return Mth.clamp((int) Math.round(x), this.x, this.x + this.width);
    }

    public int clampY(double y) {
        return Mth.clamp((int) Math.round(y), this.y, this.y + this.height);
    }
}
