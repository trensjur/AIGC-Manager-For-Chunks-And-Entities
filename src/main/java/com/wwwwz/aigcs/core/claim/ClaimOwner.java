package com.wwwwz.aigcs.core.claim;

import java.util.List;

/**
 * One player together with every chunk the player claimed.
 * <p>
 * The colour and the boundary flag are owner wide settings: the world map uses
 * the colour for the striped overlay and the boundary flag decides whether the
 * outline of the claimed area is drawn.
 * <p>
 * Entity rules are stored per chunk group ({@link ClaimRuleGroup}): every group
 * is keyed by a chunk id and applies to all chunks that carry that id, so two
 * groups of the same player can use completely different rules.
 */
public record ClaimOwner(String id, String name, int color, boolean boundary, List<ClaimChunk> chunks,
        List<ClaimRuleGroup> ruleGroups) {
    public ClaimOwner {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        ruleGroups = ruleGroups == null ? List.of() : List.copyOf(ruleGroups);
    }

    public int forceLoadCount() {
        int count = 0;
        for (ClaimChunk chunk : this.chunks) {
            if (chunk.forceLoad()) {
                count++;
            }
        }
        return count;
    }

    /** Rules of one chunk group, never {@code null}. */
    public EntityRuleSet ruleSet(String groupId) {
        if (groupId != null) {
            for (ClaimRuleGroup group : this.ruleGroups) {
                if (group.id().equals(groupId)) {
                    return group.rules();
                }
            }
        }
        return EntityRuleSet.EMPTY;
    }
}
