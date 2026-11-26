package com.wwwwz.aigcs.core.claim;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity control settings of one claim owner.
 * <p>
 * The owner wide flags are applied to every claimed chunk:
 * <ul>
 * <li>{@code enabled} switches the whole feature off; nothing is blocked or
 * cleaned while it is {@code false}</li>
 * <li>{@code allowSpawn} allows new living entities inside the claimed
 * chunks</li>
 * <li>{@code allowAggro} allows mobs to target the owner while the owner stands
 * inside their own claim</li>
 * <li>{@code allowEnter} / {@code allowLeave} allow living entities to cross
 * the claim boundary</li>
 * <li>{@code forceLoadAllowSpawn} applies to force loaded chunks only: such a
 * chunk may additionally refuse all spawns</li>
 * </ul>
 * The per type {@link EntityRule} list overrides the spawn / aggro behaviour
 * and limits the population of a single type inside one chunk. Every rule with
 * a {@code maxCount > 0} is one entry of the mob population limit list: while
 * the chunk holds that many entities of the type every further spawn is
 * refused and the excess entities are removed automatically.
 */
public record EntityRuleSet(boolean enabled, boolean allowSpawn, boolean allowAggro, boolean allowEnter,
        boolean allowLeave, boolean forceLoadAllowSpawn, List<EntityRule> rules) {
    /** Hard cap of stored rules per owner, also used by the network codec. */
    public static final int MAX_RULES = 256;

    public static final EntityRuleSet DEFAULT = new EntityRuleSet(false, true, true, true, true, true,
            List.of());

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
        return new EntityRuleSet(this.enabled, this.allowSpawn, this.allowAggro, this.allowEnter,
                this.allowLeave, this.forceLoadAllowSpawn, next);
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
        return new EntityRuleSet(this.enabled, this.allowSpawn, this.allowAggro, this.allowEnter,
                this.allowLeave, this.forceLoadAllowSpawn, next);
    }

    /**
     * Every rule that takes part in the mob population limit list, i.e. every
     * rule with a {@code maxCount} greater than zero.
     */
    public List<EntityRule> limitedRules() {
        List<EntityRule> limited = new ArrayList<>(this.rules.size());
        for (EntityRule rule : this.rules) {
            if (rule.maxCount() > 0) {
                limited.add(rule);
            }
        }
        return limited;
    }

    /**
     * Adds or updates the population limit of one entity type. The remaining
     * spawn / aggro flags are kept; a limit of {@code 0} removes the limit only.
     */
    public EntityRuleSet withLimit(String type, int maxCount) {
        EntityRule existing = this.rule(type);
        EntityRule next = existing == null
                ? new EntityRule(type, maxCount, true, true)
                : existing.withMaxCount(maxCount);
        // A rule that neither blocks spawns nor limits the population and does
        // not change aggression is dropped instead of piling up empty entries.
        if (next.maxCount() <= 0 && next.allowSpawn() && next.allowAggro()) {
            return this.withoutRule(type);
        }
        return this.withRule(next);
    }

    public EntityRuleSet withEnabled(boolean value) {
        return new EntityRuleSet(value, this.allowSpawn, this.allowAggro, this.allowEnter, this.allowLeave,
                this.forceLoadAllowSpawn, this.rules);
    }

    public EntityRuleSet withAllowSpawn(boolean value) {
        return new EntityRuleSet(this.enabled, value, this.allowAggro, this.allowEnter, this.allowLeave,
                this.forceLoadAllowSpawn, this.rules);
    }

    public EntityRuleSet withAllowAggro(boolean value) {
        return new EntityRuleSet(this.enabled, this.allowSpawn, value, this.allowEnter, this.allowLeave,
                this.forceLoadAllowSpawn, this.rules);
    }

    public EntityRuleSet withAllowEnter(boolean value) {
        return new EntityRuleSet(this.enabled, this.allowSpawn, this.allowAggro, value, this.allowLeave,
                this.forceLoadAllowSpawn, this.rules);
    }

    public EntityRuleSet withAllowLeave(boolean value) {
        return new EntityRuleSet(this.enabled, this.allowSpawn, this.allowAggro, this.allowEnter, value,
                this.forceLoadAllowSpawn, this.rules);
    }

    public EntityRuleSet withForceLoadAllowSpawn(boolean value) {
        return new EntityRuleSet(this.enabled, this.allowSpawn, this.allowAggro, this.allowEnter,
                this.allowLeave, value, this.rules);
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
        return new EntityRuleSet(this.enabled, this.allowSpawn, this.allowAggro, this.allowEnter,
                this.allowLeave, this.forceLoadAllowSpawn, next);
    }

    /** True when the rule set cannot influence any chunk at all. */
    public boolean inert() {
        return !this.enabled;
    }
}
