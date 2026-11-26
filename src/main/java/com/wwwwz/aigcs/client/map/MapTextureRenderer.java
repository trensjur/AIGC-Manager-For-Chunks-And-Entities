package com.wwwwz.aigcs.client.map;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.blaze3d.platform.NativeImage;
import com.wwwwz.aigcs.client.ClientChunkStore;
import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.core.ChunkTile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

/**
 * Builds and draws the world map canvas.
 * <p>
 * The canvas is one dynamic texture that covers the visible area plus a
 * configurable margin. The texture is pinned to the world coordinates it was
 * built for, so panning and zooming only scales the existing texture on the GPU
 * instead of rasterising every visible chunk again. A rebuild is scheduled once
 * the camera rests, when the recorded data changed or when the visual settings
 * changed.
 * <p>
 * Chunk colours are taken from {@link ChunkColorCache}, which means a rebuild
 * only copies integers instead of re-evaluating shading and light per pixel.
 */
@SuppressWarnings("null")
public final class MapTextureRenderer {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final int MAX_TEXTURE_SIDE = 2048;
    private static final long MAX_TEXTURE_PIXELS = 4_000_000L;
    /** Camera drift in GUI pixels that forces a rebuild after the camera rests. */
    private static final double DRIFT_PIXELS = 10.0D;

    private final Minecraft minecraft = Minecraft.getInstance();

    /** Minimaps rebuild while the player moves; the world map only when idle. */
    private boolean allowMotionRebuild;

    private DynamicTexture texture;
    private ResourceLocation textureId;
    private int textureWidth;
    private int textureHeight;
    private int[] buffer = new int[0];
    private boolean dirty = true;
    private boolean smooth;
    private long lastBuildAt;
    private long lastStoreVersion = Long.MIN_VALUE;
    private int lastStyle = -1;
    private MapLayer lastLayer;
    private int lastSliceY = Integer.MIN_VALUE;
    private ResourceLocation lastDimension;

    private boolean hasSnapshot;
    private double builtCenterX;
    private double builtCenterZ;
    private double builtZoom = 1.0D;
    private double builtMinX;
    private double builtMinZ;
    private double builtMaxX;
    private double builtMaxZ;

    private boolean hasLastView;
    private double lastViewX;
    private double lastViewZ;
    private double lastViewZoom = 1.0D;
    private long lastViewChangeAt;

    public void markDirty() {
        this.dirty = true;
    }

    /** Allows rebuilds while the camera is still moving (used by the minimap). */
    public void setAllowMotionRebuild(boolean allow) {
        this.allowMotionRebuild = allow;
    }

    /**
     * Rebuilds the texture when needed and draws it, pinned to the world area it
     * was sampled for.
     */
    public void draw(GuiGraphics graphics, ClientLevel level, ResourceLocation dimension, MapCamera camera,
            int x, int y, int width, int height, MapLayer layer, int sliceY) {
        this.updateViewState(camera);
        this.ensureBuilt(level, dimension, camera, width, height, layer, sliceY);
        if (!this.hasSnapshot || this.textureId == null) {
            return;
        }
        double zoom = camera.zoom();
        double centerScreenX = x + width / 2.0D;
        double centerScreenZ = y + height / 2.0D;
        int destX = (int) Math.floor(centerScreenX + (this.builtMinX - camera.centerX()) * zoom);
        int destY = (int) Math.floor(centerScreenZ + (this.builtMinZ - camera.centerZ()) * zoom);
        int destWidth = (int) Math.ceil((this.builtMaxX - this.builtMinX) * zoom);
        int destHeight = (int) Math.ceil((this.builtMaxZ - this.builtMinZ) * zoom);
        if (destWidth <= 0 || destHeight <= 0) {
            return;
        }
        // Full texture into the destination rectangle: the explicit texture size
        // keeps the UV range at 0..1, so the map can never tile into quadrants.
        graphics.blit(this.textureId, destX, destY, destWidth, destHeight, 0.0F, 0.0F,
                this.textureWidth, this.textureHeight, this.textureWidth, this.textureHeight);
    }

    // --------------------------------------------------------------- building

    private void updateViewState(MapCamera camera) {
        double zoom = Math.max(camera.zoom(), 1.0E-4D);
        long now = System.currentTimeMillis();
        if (!this.hasLastView
                || Math.abs(camera.centerX() - this.lastViewX) * zoom > 1.0D
                || Math.abs(camera.centerZ() - this.lastViewZ) * zoom > 1.0D
                || Math.abs(zoom / this.lastViewZoom - 1.0D) > 1.0E-4D) {
            this.hasLastView = true;
            this.lastViewX = camera.centerX();
            this.lastViewZ = camera.centerZ();
            this.lastViewZoom = zoom;
            this.lastViewChangeAt = now;
        }
    }

    private void ensureBuilt(ClientLevel level, ResourceLocation dimension, MapCamera camera,
            int viewWidth, int viewHeight, MapLayer layer, int sliceY) {
        double zoom = Math.max(camera.zoom(), 1.0E-4D);
        double renderScale = ClientConfig.MAP_RENDER_SCALE.get();
        double overscan = ClientConfig.MAP_OVERSCAN.get();
        double worldWidth = viewWidth / zoom * (1.0D + overscan * 2.0D);
        double worldHeight = viewHeight / zoom * (1.0D + overscan * 2.0D);

        int wantedWidth = Mth.clamp((int) Math.ceil(worldWidth * zoom * renderScale), 1, MAX_TEXTURE_SIDE);
        int wantedHeight = Mth.clamp((int) Math.ceil(worldHeight * zoom * renderScale), 1, MAX_TEXTURE_SIDE);
        long pixelCount = (long) wantedWidth * wantedHeight;
        if (pixelCount > MAX_TEXTURE_PIXELS) {
            double factor = Math.sqrt(MAX_TEXTURE_PIXELS / (double) pixelCount);
            wantedWidth = Math.max(1, (int) Math.floor(wantedWidth * factor));
            wantedHeight = Math.max(1, (int) Math.floor(wantedHeight * factor));
        }
        if (wantedWidth != this.textureWidth || wantedHeight != this.textureHeight) {
            this.createTexture(wantedWidth, wantedHeight);
        }

        boolean smoothNow = ClientConfig.MAP_SMOOTH_TEXTURE.getAsBoolean();
        if (this.texture != null && smoothNow != this.smooth) {
            this.texture.setFilter(smoothNow, false);
            this.smooth = smoothNow;
        }

        long storeVersion = ClientChunkStore.get().version();
        if (storeVersion != this.lastStoreVersion) {
            this.lastStoreVersion = storeVersion;
            this.dirty = true;
        }
        int style = ChunkColorCache.get().styleVersion();
        if (style != this.lastStyle) {
            this.lastStyle = style;
            this.dirty = true;
        }
        if (layer != this.lastLayer || sliceY != this.lastSliceY) {
            this.lastLayer = layer;
            this.lastSliceY = sliceY;
            this.dirty = true;
        }
        if (!dimension.equals(this.lastDimension)) {
            this.lastDimension = dimension;
            this.dirty = true;
            this.hasSnapshot = false;
        }

        long now = System.currentTimeMillis();
        boolean settled = now - this.lastViewChangeAt >= ClientConfig.MAP_SETTLE_MS.getAsInt();
        boolean viewDrift = !this.hasSnapshot
                || Math.abs(this.builtCenterX - camera.centerX()) * zoom > DRIFT_PIXELS
                || Math.abs(this.builtCenterZ - camera.centerZ()) * zoom > DRIFT_PIXELS
                || Math.abs(this.builtZoom / zoom - 1.0D) > 0.04D;
        if (!this.dirty && !viewDrift) {
            return;
        }
        if (this.hasSnapshot && !settled && !this.allowMotionRebuild) {
            // Keep dragging and zooming perfectly smooth: the current snapshot is
            // just scaled until the camera comes to rest.
            return;
        }
        if (now - this.lastBuildAt < ClientConfig.MAP_REBUILD_INTERVAL_MS.getAsInt()) {
            return;
        }
        this.lastBuildAt = now;
        this.dirty = false;
        this.rebuild(level, dimension, camera, worldWidth, worldHeight, layer, sliceY);
    }

    private void createTexture(int width, int height) {
        this.releaseTexture();
        this.textureWidth = width;
        this.textureHeight = height;
        this.buffer = new int[width * height];
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        this.texture = new DynamicTexture(image);
        this.textureId = this.minecraft.getTextureManager().register(
                "m4cne/world_map_" + NEXT_ID.incrementAndGet(), this.texture);
        this.smooth = ClientConfig.MAP_SMOOTH_TEXTURE.getAsBoolean();
        this.texture.setFilter(this.smooth, false);
        this.dirty = true;
        this.hasSnapshot = false;
    }

    private void rebuild(ClientLevel level, ResourceLocation dimension, MapCamera camera,
            double worldWidth, double worldHeight, MapLayer layer, int sliceY) {
        Arrays.fill(this.buffer, 0);

        double minX = camera.centerX() - worldWidth / 2.0D;
        double minZ = camera.centerZ() - worldHeight / 2.0D;
        double maxX = minX + worldWidth;
        double maxZ = minZ + worldHeight;
        double pixelsPerBlockX = this.textureWidth / worldWidth;
        double pixelsPerBlockZ = this.textureHeight / worldHeight;
        int width = this.textureWidth;
        int height = this.textureHeight;

        // Block coordinate of every output pixel. Computing them once per rebuild
        // removes two divisions and a floor from the per-chunk inner loops.
        int[] blockXByColumn = new int[width];
        double inversePixelsPerBlockX = 1.0D / pixelsPerBlockX;
        for (int pixelX = 0; pixelX < width; pixelX++) {
            blockXByColumn[pixelX] = (int) Math.floor(minX + (pixelX + 0.5D) * inversePixelsPerBlockX);
        }
        int[] blockZByRow = new int[height];
        double inversePixelsPerBlockZ = 1.0D / pixelsPerBlockZ;
        for (int pixelZ = 0; pixelZ < height; pixelZ++) {
            blockZByRow[pixelZ] = (int) Math.floor(minZ + (pixelZ + 0.5D) * inversePixelsPerBlockZ);
        }

        int minChunkX = Mth.floor(minX / 16.0D);
        int maxChunkX = Mth.floor(maxX / 16.0D);
        int minChunkZ = Mth.floor(minZ / 16.0D);
        int maxChunkZ = Mth.floor(maxZ / 16.0D);
        boolean caveLayer = layer == MapLayer.CAVE;
        double chunkPixelsX = pixelsPerBlockX * 16.0D;
        double chunkPixelsZ = pixelsPerBlockZ * 16.0D;

        if (chunkPixelsX < 1.0D || chunkPixelsZ < 1.0D) {
            // Far zoom: many cached chunks share a single output pixel. Sampling
            // the texture pixels keeps the cost independent of the cache size.
            this.drawByPixel(dimension, blockXByColumn, blockZByRow, width, height, caveLayer, sliceY);
        } else {
            ChunkColorCache colorCache = ChunkColorCache.get();
            int[] buffer = this.buffer;
            // Only walk the 32x32 chunk regions that overlap the texture instead
            // of every chunk ever sampled.
            ClientChunkStore.get().forEachRegion(dimension, minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                    (key, entry) -> {
                        int chunkX = ChunkPos.getX(key);
                        int chunkZ = ChunkPos.getZ(key);
                        ChunkTile tile = caveLayer
                                ? CaveTileCache.get().get(dimension, chunkX, chunkZ, sliceY)
                                : entry.tile();
                        if (tile == null) {
                            return;
                        }
                        double chunkMinX = chunkX * 16.0D;
                        double chunkMinZ = chunkZ * 16.0D;

                        int x0 = (int) Math.floor((chunkMinX - minX) * pixelsPerBlockX);
                        int x1 = (int) Math.ceil((chunkMinX + 16.0D - minX) * pixelsPerBlockX) - 1;
                        int z0 = (int) Math.floor((chunkMinZ - minZ) * pixelsPerBlockZ);
                        int z1 = (int) Math.ceil((chunkMinZ + 16.0D - minZ) * pixelsPerBlockZ) - 1;
                        if (x1 < x0) {
                            x1 = x0;
                        }
                        if (z1 < z0) {
                            z1 = z0;
                        }
                        if (x1 < 0 || z1 < 0 || x0 >= width || z0 >= height) {
                            return;
                        }
                        x0 = Mth.clamp(x0, 0, width - 1);
                        x1 = Mth.clamp(x1, 0, width - 1);
                        z0 = Mth.clamp(z0, 0, height - 1);
                        z1 = Mth.clamp(z1, 0, height - 1);

                        int[] colors = caveLayer ? colorCache.colors(tile)
                                : colorCache.colors(tile, entry.tileFingerprint());
                        int baseX = chunkX << 4;
                        int baseZ = chunkZ << 4;
                        for (int pixelZ = z0; pixelZ <= z1; pixelZ++) {
                            int localZ = blockZByRow[pixelZ] - baseZ;
                            if (localZ < 0) {
                                localZ = 0;
                            } else if (localZ > 15) {
                                localZ = 15;
                            }
                            int rowOffset = pixelZ * width;
                            for (int pixelX = x0; pixelX <= x1; pixelX++) {
                                int localX = blockXByColumn[pixelX] - baseX;
                                if (localX < 0) {
                                    localX = 0;
                                } else if (localX > 15) {
                                    localX = 15;
                                }
                                int color = colors[(localX << 4) + localZ];
                                if (color != 0) {
                                    buffer[rowOffset + pixelX] = color;
                                }
                            }
                        }
                    });
        }

        this.builtCenterX = camera.centerX();
        this.builtCenterZ = camera.centerZ();
        this.builtZoom = camera.zoom();
        this.builtMinX = minX;
        this.builtMinZ = minZ;
        this.builtMaxX = minX + worldWidth;
        this.builtMaxZ = minZ + worldHeight;
        this.hasSnapshot = true;
        this.upload();
    }

    /**
     * Fallback for very low zoom levels where one output pixel covers many
     * chunks. Instead of iterating every cached chunk, each texture pixel looks
     * up the chunk at its block coordinate and uses the chunk's averaged colour.
     * The work is therefore bounded by the texture size, not by the (unlimited)
     * chunk cache.
     */
    private void drawByPixel(ResourceLocation dimension, int[] blockXByColumn, int[] blockZByRow,
            int width, int height, boolean caveLayer, int sliceY) {
        ClientChunkStore store = ClientChunkStore.get();
        ChunkColorCache colorCache = ChunkColorCache.get();
        int[] buffer = this.buffer;
        for (int pixelZ = 0; pixelZ < height; pixelZ++) {
            int chunkZ = blockZByRow[pixelZ] >> 4;
            int rowOffset = pixelZ * width;
            int lastChunkX = Integer.MIN_VALUE;
            int color = 0;
            for (int pixelX = 0; pixelX < width; pixelX++) {
                int chunkX = blockXByColumn[pixelX] >> 4;
                if (chunkX != lastChunkX) {
                    lastChunkX = chunkX;
                    color = this.colorAt(store, colorCache, dimension, chunkX, chunkZ, caveLayer, sliceY);
                }
                if (color != 0) {
                    buffer[rowOffset + pixelX] = color;
                }
            }
        }
    }

    private int colorAt(ClientChunkStore store, ChunkColorCache colorCache, ResourceLocation dimension,
            int chunkX, int chunkZ, boolean caveLayer, int sliceY) {
        if (caveLayer) {
            ChunkTile caveTile = CaveTileCache.get().get(dimension, chunkX, chunkZ, sliceY);
            return caveTile != null ? colorCache.averageColor(caveTile, caveTile.fingerprint()) : 0;
        }
        ClientChunkStore.Entry entry = store.get(dimension, chunkX, chunkZ);
        return entry != null ? colorCache.averageColor(entry.tile(), entry.tileFingerprint()) : 0;
    }

    private void upload() {
        if (this.texture == null) {
            return;
        }
        NativeImage image = this.texture.getPixels();
        if (image == null) {
            return;
        }
        int index = 0;
        for (int y = 0; y < this.textureHeight; y++) {
            for (int x = 0; x < this.textureWidth; x++) {
                image.setPixelRGBA(x, y, this.buffer[index++]);
            }
        }
        this.texture.upload();
    }

    /**
     * Samples a few missing cave slices inside the current view.
     *
     * @return {@code true} when the map data changed
     */
    public boolean prefetchCave(ClientLevel level, ResourceLocation dimension, MapCamera camera,
            int viewWidth, int viewHeight, int sliceY, int budget) {
        if (budget <= 0) {
            return false;
        }
        double zoom = camera.zoom();
        double viewMinX = camera.centerX() - viewWidth / 2.0D / zoom - 16.0D;
        double viewMaxX = camera.centerX() + viewWidth / 2.0D / zoom + 16.0D;
        double viewMinZ = camera.centerZ() - viewHeight / 2.0D / zoom - 16.0D;
        double viewMaxZ = camera.centerZ() + viewHeight / 2.0D / zoom + 16.0D;
        int[] remaining = { budget };
        boolean[] changed = { false };
        int minChunkX = Mth.floor(viewMinX / 16.0D);
        int maxChunkX = Mth.floor(viewMaxX / 16.0D);
        int minChunkZ = Mth.floor(viewMinZ / 16.0D);
        int maxChunkZ = Mth.floor(viewMaxZ / 16.0D);

        ClientChunkStore.get().forEachRegion(dimension, minChunkX, minChunkZ, maxChunkX, maxChunkZ, (key, entry) -> {
            if (remaining[0] <= 0) {
                return;
            }
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                return;
            }
            if (CaveTileCache.get().get(dimension, chunkX, chunkZ, sliceY) != null) {
                return;
            }
            remaining[0]--;
            if (CaveTileCache.get().sample(level, dimension, chunkX, chunkZ, sliceY)) {
                changed[0] = true;
            }
        });
        if (changed[0]) {
            this.dirty = true;
        }
        return changed[0];
    }

    public int textureWidth() {
        return this.textureWidth;
    }

    public int textureHeight() {
        return this.textureHeight;
    }

    public void close() {
        this.releaseTexture();
        this.buffer = new int[0];
        this.textureWidth = 0;
        this.textureHeight = 0;
        this.hasSnapshot = false;
        this.hasLastView = false;
        this.dirty = true;
        this.lastStoreVersion = Long.MIN_VALUE;
        this.lastStyle = -1;
        this.lastDimension = null;
    }

    private void releaseTexture() {
        if (this.textureId != null) {
            this.minecraft.getTextureManager().release(this.textureId);
            this.textureId = null;
            this.texture = null;
        }
    }
}
