package com.wwwwz.aigcs.core.claim;

import net.minecraft.resources.ResourceLocation;

/**
 * One per entity type rule inside a claimed chunk.
 * <p>
 * {@code maxCount} is the maximum number of living entities of that type the
 * owner allows inside a single claimed chunk. A value of {@code 0} means "no
 * limit". {@code allowSpawn} decides whether new entities of the type may be
 * created at all (natural spawns, spawn eggs, spawners and commands alike) and
 * {@code allowAggro} decides whether the type may target the owner while the
 * owner stands inside their own claim.
 */
public record EntityRule(String type, int maxCount, boolean allowSpawn, boolean allowAggro) {
    /** Upper bound of {@link #maxCount()}; keeps the UI and the packets sane. */
    public static final int MAX_COUNT = 9999;
    /** Limit a newly added population rule starts with (see 要求补充-13). */
    public static final int DEFAULT_LIMIT = 3;

    public EntityRule {
        ResourceLocation id = ResourceLocation.tryParse(type == null ? "" : type.trim());
        type = id == null ? "" : id.toString();
        maxCount = Math.max(0, Math.min(MAX_COUNT, maxCount));
    }

    /** Rule with the default values: no limit, spawn and aggro allowed. */
    public static EntityRule defaults(String type) {
        return new EntityRule(type, 0, true, true);
    }

    /** Rule with the default population limit of {@link #DEFAULT_LIMIT}. */
    public static EntityRule limited(String type) {
        return new EntityRule(type, DEFAULT_LIMIT, true, true);
    }

    /** True when the rule takes part in the population limit list. */
    public boolean hasLimit() {
        return this.maxCount > 0;
    }

    public EntityRule withMaxCount(int value) {
        return new EntityRule(this.type, value, this.allowSpawn, this.allowAggro);
    }

    public EntityRule withAllowSpawn(boolean value) {
        return new EntityRule(this.type, this.maxCount, value, this.allowAggro);
    }

    public EntityRule withAllowAggro(boolean value) {
        return new EntityRule(this.type, this.maxCount, this.allowSpawn, value);
    }

    public boolean valid() {
        return !this.type.isEmpty();
    }
}
