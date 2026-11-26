package com.wwwwz.aigcs.core.claim;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;

/**
 * One entity rule of a claimed chunk group.
 * <p>
 * A rule always refers to one entity type (a mob or {@code minecraft:player})
 * and is applied to every chunk that shares the group id of the rule (see
 * {@link ClaimRuleGroup}).
 * <p>
 * Flag semantics (要求补充-15):
 * <ul>
 * <li>{@code enabled} - a disabled rule is stored but has no effect at all</li>
 * <li>{@code allowNaturalSpawn} - natural spawns (chunk generation, natural
 * spawner, spawners, breeding, structure spawns, ...) may create the type</li>
 * <li>{@code allowUnnaturalSpawn} - spawn eggs and the {@code /summon} command
 * may create the type; every other spawn path counts as natural, exactly as
 * defined by the requirement</li>
 * <li>{@code isolated} - mobs of the type may not leave the chunks of the
 * group while they stand inside one; the pathfinder treats the outside as
 * unreachable</li>
 * <li>{@code allowAggro} - mobs of the type may target players while they stay
 * inside the controlled chunks</li>
 * <li>{@code buffs} - mob effect ids granted to entities of the type while they
 * stay inside the chunks of the group; the effect is removed again when the
 * entity leaves the chunk or when the rule changes</li>
 * <li>{@code maxCount} - optional population limit per chunk, {@code 0} means
 * unlimited (kept from 要求补充-13)</li>
 * </ul>
 * Player rules only use {@code enabled}, {@code buffs} and the target type; all
 * spawn / isolation / aggro flags are fixed to their permissive defaults.
 */
@SuppressWarnings("null")
public record EntityRule(String type, boolean enabled, int maxCount, boolean allowNaturalSpawn,
        boolean allowUnnaturalSpawn, boolean allowAggro, boolean isolated, List<String> buffs) {
    /** Upper bound of {@link #maxCount()}; keeps the UI and the packets sane. */
    public static final int MAX_COUNT = 9999;
    /** Upper bound of buffs of one rule, shared by the UI and the codec. */
    public static final int MAX_BUFFS = 16;
    /** Registry id of the player entity type; the only non mob rule target. */
    public static final String PLAYER_TYPE = "minecraft:player";
    /** Limit a newly added rule starts with in the editor. */
    public static final int DEFAULT_LIMIT = 3;

    public EntityRule {
        ResourceLocation id = ResourceLocation.tryParse(type == null ? "" : type.trim());
        type = id == null ? "" : id.toString();
        maxCount = Math.max(0, Math.min(MAX_COUNT, maxCount));
        buffs = sanitizeBuffs(buffs);
        if (PLAYER_TYPE.equals(type)) {
            // Player rules never limit spawns and cannot be isolated or made
            // non aggressive: the requirement restricts those flags to mobs.
            maxCount = 0;
            allowNaturalSpawn = true;
            allowUnnaturalSpawn = true;
            allowAggro = true;
            isolated = false;
        }
    }

    /** Rule with the permissive defaults: enabled, every spawn allowed. */
    public static EntityRule defaults(String type) {
        return new EntityRule(type, true, 0, true, true, true, false, List.of());
    }

    /** Rule with the default population limit of {@link #DEFAULT_LIMIT}. */
    public static EntityRule limited(String type) {
        return new EntityRule(type, true, DEFAULT_LIMIT, true, true, true, false, List.of());
    }

    /** Player rule with no buffs yet. */
    public static EntityRule player() {
        return defaults(PLAYER_TYPE);
    }

    private static List<String> sanitizeBuffs(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> next = new ArrayList<>(Math.min(values.size(), MAX_BUFFS));
        for (String value : values) {
            ResourceLocation id = ResourceLocation.tryParse(value == null ? "" : value.trim());
            if (id == null || next.size() >= MAX_BUFFS) {
                continue;
            }
            String key = id.toString();
            if (!next.contains(key)) {
                next.add(key);
            }
        }
        return List.copyOf(next);
    }

    public boolean isPlayer() {
        return PLAYER_TYPE.equals(this.type);
    }

    /** True when the rule takes part in the per chunk population limit list. */
    public boolean hasLimit() {
        return this.maxCount > 0;
    }

    public boolean hasBuffs() {
        return !this.buffs.isEmpty();
    }

    /** True when the rule can influence anything (disabled rules are inert). */
    public boolean active() {
        return this.enabled;
    }

    public EntityRule withType(String value) {
        return new EntityRule(value, this.enabled, this.maxCount, this.allowNaturalSpawn,
                this.allowUnnaturalSpawn, this.allowAggro, this.isolated, this.buffs);
    }

    public EntityRule withEnabled(boolean value) {
        return new EntityRule(this.type, value, this.maxCount, this.allowNaturalSpawn,
                this.allowUnnaturalSpawn, this.allowAggro, this.isolated, this.buffs);
    }

    public EntityRule withMaxCount(int value) {
        return new EntityRule(this.type, this.enabled, value, this.allowNaturalSpawn,
                this.allowUnnaturalSpawn, this.allowAggro, this.isolated, this.buffs);
    }

    public EntityRule withAllowNaturalSpawn(boolean value) {
        return new EntityRule(this.type, this.enabled, this.maxCount, value,
                this.allowUnnaturalSpawn, this.allowAggro, this.isolated, this.buffs);
    }

    public EntityRule withAllowUnnaturalSpawn(boolean value) {
        return new EntityRule(this.type, this.enabled, this.maxCount, this.allowNaturalSpawn,
                value, this.allowAggro, this.isolated, this.buffs);
    }

    public EntityRule withAllowAggro(boolean value) {
        return new EntityRule(this.type, this.enabled, this.maxCount, this.allowNaturalSpawn,
                this.allowUnnaturalSpawn, value, this.isolated, this.buffs);
    }

    public EntityRule withIsolated(boolean value) {
        return new EntityRule(this.type, this.enabled, this.maxCount, this.allowNaturalSpawn,
                this.allowUnnaturalSpawn, this.allowAggro, value, this.buffs);
    }

    public EntityRule withBuffs(List<String> value) {
        return new EntityRule(this.type, this.enabled, this.maxCount, this.allowNaturalSpawn,
                this.allowUnnaturalSpawn, this.allowAggro, this.isolated, value);
    }

    /** Adds or removes one buff id and returns the updated rule. */
    public EntityRule toggleBuff(String buffId) {
        ResourceLocation id = ResourceLocation.tryParse(buffId == null ? "" : buffId.trim());
        if (id == null) {
            return this;
        }
        String key = id.toString();
        List<String> next = new ArrayList<>(this.buffs.size() + 1);
        boolean removed = false;
        for (String existing : this.buffs) {
            if (existing.equals(key)) {
                removed = true;
            } else {
                next.add(existing);
            }
        }
        if (!removed && next.size() < MAX_BUFFS) {
            next.add(key);
        }
        return this.withBuffs(next);
    }

    public boolean hasBuff(String buffId) {
        return buffId != null && this.buffs.contains(buffId);
    }

    public boolean valid() {
        return !this.type.isEmpty();
    }
}
