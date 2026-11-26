package com.wwwwz.aigcs.core.claim;

import java.util.ArrayList;
import java.util.List;

/**
 * The entity rules of one claimed chunk group.
 * <p>
 * A group is the set of chunks that share one chunk id (see
 * {@link ClaimChunk#displayId()}); merging chunks therefore merges their rule
 * groups as well. Only the rules of the lexicographically smallest id survive a
 * merge (要求补充-15 item 7).
 * <p>
 * Every rule is independent: it carries its own enabled / spawn / isolation /
 * aggro flags and its own buff list, and the server only applies rules that are
 * enabled.
 */
public record EntityRuleSet(List<EntityRule> rules) {
    /** Hard cap of stored rules per group, also used by the network codec. */
    public static final int MAX_RULES = 256;

    /** Empty rule set; used for groups without any rule. */
    public static final EntityRuleSet EMPTY = new EntityRuleSet(List.of());

    public EntityRuleSet {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** Rule of one entity type, or {@code null} when the type is not listed. */
    public EntityRule rule(String type) {
        if (type == null) {
            return null;
        }
        for (EntityRule rule : this.rules) {
            if (rule.type().equals(type)) {
                return rule;
            }
        }
        return null;
    }

    public boolean has(String type) {
        return this.rule(type) != null;
    }

    /** Replaces or appends one type rule. Invalid types are ignored. */
    public EntityRuleSet withRule(EntityRule rule) {
        if (rule == null || !rule.valid()) {
            return this;
        }
        List<EntityRule> next = new ArrayList<>(this.rules.size() + 1);
        boolean replaced = false;
        for (EntityRule existing : this.rules) {
            if (existing.type().equals(rule.type())) {
                next.add(rule);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced && next.size() < MAX_RULES) {
            next.add(rule);
        }
        return new EntityRuleSet(next);
    }

    public EntityRuleSet withoutRule(String type) {
        if (type == null) {
            return this;
        }
        List<EntityRule> next = new ArrayList<>(this.rules.size());
        for (EntityRule existing : this.rules) {
            if (!existing.type().equals(type)) {
                next.add(existing);
            }
        }
        return new EntityRuleSet(next);
    }

    /** Every rule with a positive population limit. */
    public List<EntityRule> limitedRules() {
        List<EntityRule> limited = new ArrayList<>(this.rules.size());
        for (EntityRule rule : this.rules) {
            if (rule.maxCount() > 0) {
                limited.add(rule);
            }
        }
        return limited;
    }

    /** Every enabled rule, i.e. every rule the server actually applies. */
    public List<EntityRule> enabledRules() {
        List<EntityRule> active = new ArrayList<>(this.rules.size());
        for (EntityRule rule : this.rules) {
            if (rule.enabled()) {
                active.add(rule);
            }
        }
        return active;
    }

    /**
     * Adds or updates the population limit of one entity type. The remaining
     * flags are kept; a limit of {@code 0} removes the limit only.
     */
    public EntityRuleSet withLimit(String type, int maxCount) {
        EntityRule next = this.rule(type) == null ? EntityRule.defaults(type) : this.rule(type);
        next = next.withMaxCount(maxCount);
        if (!next.hasLimit() && next.allowNaturalSpawn() && next.allowUnnaturalSpawn()
                && next.allowAggro() && !next.hasBuffs() && !next.isolated()) {
            return this.withoutRule(type);
        }
        return this.withRule(next);
    }

    /**
     * Drops invalid / duplicated rules and clamps the list size. The server runs
     * this on every update, so a modified client cannot flood the claim file.
     */
    public EntityRuleSet sanitize(int maxRules) {
        int limit = Math.max(1, Math.min(MAX_RULES, maxRules));
        List<EntityRule> next = new ArrayList<>(Math.min(this.rules.size(), limit));
        for (EntityRule rule : this.rules) {
            if (rule == null || !rule.valid() || next.size() >= limit) {
                continue;
            }
            boolean duplicate = false;
            for (EntityRule existing : next) {
                if (existing.type().equals(rule.type())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                next.add(rule);
            }
        }
        return new EntityRuleSet(next);
    }

    public boolean isEmpty() {
        return this.rules.isEmpty();
    }
}
