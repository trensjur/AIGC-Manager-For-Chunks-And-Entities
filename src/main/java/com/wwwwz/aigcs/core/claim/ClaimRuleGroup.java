package com.wwwwz.aigcs.core.claim;

/**
 * The entity rules of one claimed chunk group.
 * <p>
 * A group is identified by a chunk id (see {@link ClaimChunk#displayId()}):
 * every chunk that carries the same id shares the rules of the group. Renaming
 * chunks moves their rules to the new id when the old id disappears, merging
 * chunks keeps only the rules of the lexicographically smallest id of the
 * merged group.
 *
 * @param id    chunk id of the group, never {@code null}
 * @param rules rules of the group, never {@code null}
 */
public record ClaimRuleGroup(String id, EntityRuleSet rules) {
    public ClaimRuleGroup {
        id = id == null ? "" : id.trim();
        rules = rules == null ? EntityRuleSet.EMPTY : rules;
    }

    public boolean isEmpty() {
        return this.id.isEmpty() || this.rules.isEmpty();
    }
}
