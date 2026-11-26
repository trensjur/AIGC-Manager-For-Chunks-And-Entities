package com.wwwwz.aigcs.core.claim;

/**
 * One claimed chunk.
 * <p>
 * Claims are identified by their dimension and chunk coordinates only, so the
 * client and the server can exchange them without any block level detail. The
 * force load flag makes the server keep the chunk loaded with a vanilla forced
 * chunk ticket.
 * <p>
 * Every chunk also carries a player editable id. The id is used by the chunk
 * list, by the rename feature, by the merge feature (merged chunks share the
 * lexicographically smallest id of the merged group) and by the "entered a
 * chunk" hint. The boundary flag draws a vanilla like boundary wall around the
 * outer edge of the chunk and the enter message flag shows a message whenever
 * a player walks into the chunk.
 */
public record ClaimChunk(String dimension, int x, int z, boolean forceLoad, String id,
        boolean boundary, boolean enterMessage) {
    /** Upper bound of a chunk id, shared by the network codec and the editor. */
    public static final int MAX_ID_LENGTH = 48;

    /** Claim without an explicit id; the id is derived from the coordinates. */
    public ClaimChunk(String dimension, int x, int z, boolean forceLoad) {
        this(dimension, x, z, forceLoad, defaultId(x, z), false, false);
    }

    /** Default id of a chunk that was never renamed. */
    public static String defaultId(int x, int z) {
        return "c" + x + "_" + z;
    }

    /** Id used for display and sorting; never blank. */
    public String displayId() {
        return this.id == null || this.id.isBlank() ? defaultId(this.x, this.z) : this.id;
    }

    /** Centre of the chunk in block coordinates. */
    public int centerX() {
        return this.x * 16 + 8;
    }

    /** Centre of the chunk in block coordinates. */
    public int centerZ() {
        return this.z * 16 + 8;
    }

    /**
     * Normalises a typed id: trims it, cuts it to {@link #MAX_ID_LENGTH} and
     * falls back to the coordinate based default when it is blank.
     */
    public static String sanitizeId(String value, int x, int z) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty()) {
            return defaultId(x, z);
        }
        return id.length() > MAX_ID_LENGTH ? id.substring(0, MAX_ID_LENGTH) : id;
    }

    public ClaimChunk withForceLoad(boolean value) {
        return new ClaimChunk(this.dimension, this.x, this.z, value, this.id, this.boundary,
                this.enterMessage);
    }

    public ClaimChunk withId(String value) {
        return new ClaimChunk(this.dimension, this.x, this.z, this.forceLoad,
                sanitizeId(value, this.x, this.z), this.boundary, this.enterMessage);
    }

    public ClaimChunk withBoundary(boolean value) {
        return new ClaimChunk(this.dimension, this.x, this.z, this.forceLoad, this.id, value,
                this.enterMessage);
    }

    public ClaimChunk withEnterMessage(boolean value) {
        return new ClaimChunk(this.dimension, this.x, this.z, this.forceLoad, this.id, this.boundary,
                value);
    }
}
