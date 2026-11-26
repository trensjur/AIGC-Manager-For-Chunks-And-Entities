package com.wwwwz.aigcs.core.claim;

import java.util.List;

/**
 * One player together with every chunk the player claimed.
 * <p>
 * The colour and the boundary flag are owner wide settings: the world map uses
 * the colour for the striped overlay and the boundary flag decides whether the
 * outline of the claimed area is drawn. The {@link EntityRuleSet} carries the
 * entity control settings of the owner and is applied by the server to every
 * claimed chunk of the player.
 */
public record ClaimOwner(String id, String name, int color, boolean boundary, List<ClaimChunk> chunks,
        EntityRuleSet entityRules) {
    public int forceLoadCount() {
        int count = 0;
        for (ClaimChunk chunk : this.chunks) {
            if (chunk.forceLoad()) {
                count++;
            }
        }
        return count;
    }
}
