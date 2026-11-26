package com.wwwwz.aigcs.server;

import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;

/**
 * Server side record of one chunk, mutable so updates can be merged in place.
 */
public final class StoredChunk {
    public final int chunkX;
    public final int chunkZ;
    /**
     * Surface sample last reported by a client, {@code null} when only entity
     * data has been received so far.
     */
    public ChunkTile tile;
    /** Entity census last reported by a client, never {@code null}. */
    public EntityCensus entities = EntityCensus.empty();
    /** Wall clock time of the last update. */
    public long updatedAt;
    /** Monotonically increasing server side revision. */
    public long revision;

    StoredChunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }
}
