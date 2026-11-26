package com.wwwwz.aigcs.core.preload;

/**
 * Geometry of a preload region: centre chunk, radius and outline.
 * <p>
 * Local coordinates are offsets inside the squared bounding box of the region,
 * {@code 0 .. width-1} on both axes. The pair is packed into one {@code int}
 * ({@code localX << 16 | localZ}) so the ordered chunk list of a radius 400
 * region only needs 2.5 MiB - and that list is rebuilt deterministically every
 * time a task starts, so resuming never depends on it being persisted.
 */
public final class PreloadRegion {
    /** Radius limit of the preload feature, in chunks. */
    public static final int MAX_RADIUS = 400;

    private final int centerChunkX;
    private final int centerChunkZ;
    private final int radius;
    private final PreloadShape shape;
    private int total = -1;

    public PreloadRegion(int centerChunkX, int centerChunkZ, int radius, PreloadShape shape) {
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.radius = Math.max(0, Math.min(MAX_RADIUS, radius));
        this.shape = shape != null ? shape : PreloadShape.SQUARE;
    }

    public int centerChunkX() {
        return this.centerChunkX;
    }

    public int centerChunkZ() {
        return this.centerChunkZ;
    }

    public int radius() {
        return this.radius;
    }

    public PreloadShape shape() {
        return this.shape;
    }

    /** Width and height of the bounding box, in chunks. */
    public int width() {
        return this.radius * 2 + 1;
    }

    /** Chunk coordinate of local index {@code 0}. */
    public int minChunkX() {
        return this.centerChunkX - this.radius;
    }

    public int minChunkZ() {
        return this.centerChunkZ - this.radius;
    }

    public int chunkX(int localX) {
        return this.minChunkX() + localX;
    }

    public int chunkZ(int localZ) {
        return this.minChunkZ() + localZ;
    }

    public int localX(int chunkX) {
        return chunkX - this.minChunkX();
    }

    public int localZ(int chunkZ) {
        return chunkZ - this.minChunkZ();
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        int deltaX = chunkX - this.centerChunkX;
        int deltaZ = chunkZ - this.centerChunkZ;
        return this.shape.accepts(deltaX, deltaZ, this.radius);
    }

    /** Number of chunks inside the outline. */
    public int total() {
        if (this.total < 0) {
            int count = 0;
            int width = this.width();
            for (int localX = 0; localX < width; localX++) {
                for (int localZ = 0; localZ < width; localZ++) {
                    if (this.containsChunk(this.chunkX(localX), this.chunkZ(localZ))) {
                        count++;
                    }
                }
            }
            this.total = count;
        }
        return this.total;
    }

    /**
     * Builds the chunk list ordered by distance rings, so a running preload
     * expands outwards from the centre.
     */
    public int[] buildOrder() {
        int width = this.width();
        int[] order = new int[this.total()];
        int index = 0;
        for (int ring = 0; ring <= this.radius; ring++) {
            for (int localX = 0; localX < width; localX++) {
                int deltaX = localX - this.radius;
                for (int localZ = 0; localZ < width; localZ++) {
                    int deltaZ = localZ - this.radius;
                    if (Math.max(Math.abs(deltaX), Math.abs(deltaZ)) != ring) {
                        continue;
                    }
                    if (!this.shape.accepts(deltaX, deltaZ, this.radius)) {
                        continue;
                    }
                    order[index++] = packLocal(localX, localZ);
                }
            }
        }
        return order;
    }

    public static int packLocal(int localX, int localZ) {
        return (localX << 16) | (localZ & 0xFFFF);
    }

    public static int unpackLocalX(int packed) {
        return packed >>> 16;
    }

    public static int unpackLocalZ(int packed) {
        return packed & 0xFFFF;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PreloadRegion region)) {
            return false;
        }
        return this.centerChunkX == region.centerChunkX
                && this.centerChunkZ == region.centerChunkZ
                && this.radius == region.radius
                && this.shape == region.shape;
    }

    @Override
    public int hashCode() {
        return (this.centerChunkX * 31 + this.centerChunkZ) * 31 + this.radius * 2 + this.shape.ordinal();
    }
}
