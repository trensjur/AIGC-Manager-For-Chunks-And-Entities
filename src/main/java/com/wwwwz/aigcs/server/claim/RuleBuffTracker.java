package com.wwwwz.aigcs.server.claim;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.wwwwz.aigcs.AIGCsM4CnE;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * Applies the buffs of entity rules and remembers which effects the rules
 * granted.
 * <p>
 * A buff is only refreshed while the entity stays inside the chunks of the rule
 * group; as soon as the rule no longer applies (entity left the chunk, the rule
 * was disabled / deleted or the buff was removed from the rule) the effect is
 * removed again. Only effects that were granted by a rule are tracked, so a
 * potion the player drank on their own is never touched (要求补充-15 item 5).
 */
@SuppressWarnings("null")
final class RuleBuffTracker {
    /**
     * Duration of one granted effect instance, refreshed while the rule applies.
     */
    private static final int REFRESH_TICKS = 120;
    /** How often the effect instance of an active rule is refreshed. */
    private static final int REAPPLY_INTERVAL = 20;

    private static final Map<UUID, Granted> GRANTED = new HashMap<>();
    private static final Map<String, Optional<Holder<MobEffect>>> HOLDERS = new HashMap<>();

    private RuleBuffTracker() {
    }

    /** Tracks the effects one rule granted to one entity. */
    private static final class Granted {
        private final WeakReference<LivingEntity> entity;
        private final Set<Holder<MobEffect>> effects = new LinkedHashSet<>();
        private int lastApply = Integer.MIN_VALUE;

        private Granted(LivingEntity entity) {
            this.entity = new WeakReference<>(entity);
        }
    }

    /** Resolves the registry holders of the buff ids of one rule. */
    static Set<Holder<MobEffect>> resolve(List<String> buffIds) {
        Set<Holder<MobEffect>> holders = new LinkedHashSet<>();
        if (buffIds == null) {
            return holders;
        }
        for (String buffId : buffIds) {
            Optional<Holder<MobEffect>> holder = HOLDERS.computeIfAbsent(buffId == null ? "" : buffId,
                    key -> {
                        ResourceLocation id = ResourceLocation.tryParse(key);
                        if (id == null) {
                            return Optional.empty();
                        }
                        return BuiltInRegistries.MOB_EFFECT
                                .getHolder(ResourceKey.create(Registries.MOB_EFFECT, id))
                                .map(found -> (Holder<MobEffect>) found);
                    });
            holder.ifPresent(holders::add);
        }
        return holders;
    }

    /** Applies (and refreshes) the buffs of one rule to the entity. */
    static void apply(LivingEntity entity, List<String> buffIds) {
        Set<Holder<MobEffect>> desired = resolve(buffIds);
        if (desired.isEmpty()) {
            clear(entity);
            return;
        }
        UUID id = entity.getUUID();
        Granted granted = GRANTED.get(id);
        boolean newEntity = granted == null || granted.entity.get() != entity;
        if (newEntity) {
            granted = new Granted(entity);
            GRANTED.put(id, granted);
        }
        boolean setChanged = !granted.effects.equals(desired);
        if (!newEntity && !setChanged && entity.tickCount - granted.lastApply < REAPPLY_INTERVAL) {
            return;
        }
        granted.lastApply = entity.tickCount;
        // Effects that were granted earlier but are no longer part of the rule.
        List<Holder<MobEffect>> stale = new ArrayList<>(granted.effects);
        stale.removeAll(desired);
        for (Holder<MobEffect> holder : stale) {
            removeGranted(entity, holder);
        }
        granted.effects.removeAll(stale);
        for (Holder<MobEffect> holder : desired) {
            MobEffectInstance current = entity.getEffect(holder);
            if (current != null && !granted.effects.contains(holder)
                    && current.getDuration() > REFRESH_TICKS) {
                // The entity already has a longer lasting effect of its own that
                // was not granted by a rule: leave it alone.
                continue;
            }
            if (entity.addEffect(new MobEffectInstance(holder, REFRESH_TICKS, 0, false, false, true))) {
                granted.effects.add(holder);
            }
        }
    }

    /** Removes every effect of this entity that was granted by a rule. */
    static void clear(LivingEntity entity) {
        Granted granted = GRANTED.remove(entity.getUUID());
        if (granted == null || granted.entity.get() != entity) {
            return;
        }
        for (Holder<MobEffect> holder : granted.effects) {
            removeGranted(entity, holder);
        }
        granted.effects.clear();
    }

    /** Removes an effect only when it still looks like the instance we granted. */
    private static void removeGranted(LivingEntity entity, Holder<MobEffect> holder) {
        MobEffectInstance current = entity.getEffect(holder);
        if (current == null) {
            return;
        }
        if (current.getAmplifier() > 0) {
            // A stronger effect was applied meanwhile; it is not ours any more.
            return;
        }
        entity.removeEffect(holder);
        AIGCsM4CnE.LOGGER.debug("Removed rule buff {} from {}", holder, entity.getUUID());
    }

    /** Drops tracking entries of entities that no longer exist. */
    static void sweep() {
        GRANTED.values().removeIf(granted -> {
            LivingEntity entity = granted.entity.get();
            return entity == null || entity.isRemoved();
        });
    }

    static void reset() {
        GRANTED.clear();
    }
}
