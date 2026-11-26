package com.wwwwz.aigcs.client.map;

import net.minecraft.util.Mth;

/**
 * Centre position and zoom of the world map view.
 * <p>
 * All values are plain world coordinates. The zoom is expressed in GUI pixels
 * per block, so the map texture builder can convert between both spaces without
 * knowing anything about the screen layout.
 */
public final class MapCamera {
    public static final double MIN_ZOOM = 0.02D;
    public static final double MAX_ZOOM = 8.0D;
    private static final double DEFAULT_ZOOM = 0.5D;

    private double centerX;
    private double centerZ;
    private double zoom = DEFAULT_ZOOM;

    public double centerX() {
        return this.centerX;
    }

    public double centerZ() {
        return this.centerZ;
    }

    public double zoom() {
        return this.zoom;
    }

    public void centerOn(double x, double z) {
        this.centerX = x;
        this.centerZ = z;
    }

    public void resetZoom() {
        this.zoom = DEFAULT_ZOOM;
    }

    /** Moves the view by a number of GUI pixels. */
    public void panPixels(double deltaX, double deltaY) {
        if (this.zoom <= 0.0D) {
            return;
        }
        this.centerX -= deltaX / this.zoom;
        this.centerZ -= deltaY / this.zoom;
    }

    /**
     * Zooms around a fixed screen point so the block under the cursor stays in
     * place.
     *
     * @param scroll        positive to zoom in, negative to zoom out
     * @param anchorWorldX  world x coordinate under the cursor
     * @param anchorWorldZ  world z coordinate under the cursor
     * @param anchorScreenX screen x of the cursor relative to the map centre
     * @param anchorScreenY screen y of the cursor relative to the map centre
     */
    public void zoomAt(double scroll, double anchorWorldX, double anchorWorldZ,
            double anchorScreenX, double anchorScreenY) {
        double oldZoom = this.zoom;
        double factor = Math.pow(1.22D, scroll);
        this.zoom = Mth.clamp(oldZoom * factor, MIN_ZOOM, MAX_ZOOM);
        if (Math.abs(this.zoom - oldZoom) < 1.0E-6D) {
            return;
        }
        this.centerX = anchorWorldX - anchorScreenX / this.zoom;
        this.centerZ = anchorWorldZ - anchorScreenY / this.zoom;
    }

    public void setZoom(double zoom) {
        this.zoom = Mth.clamp(zoom, MIN_ZOOM, MAX_ZOOM);
    }
}
