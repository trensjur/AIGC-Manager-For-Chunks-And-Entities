package com.wwwwz.aigcs.client.claim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.wwwwz.aigcs.core.claim.EntityRule;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

/**
 * Quick list of every mob the current game can spawn.
 * <p>
 * The list is built once from the entity type registry, sorted by the
 * translated name and used by the chunk claim entity control page. It contains
 * every registered type that is a {@link Mob} and not a technical entity
 * ({@link MobCategory#MISC}), so modded mobs show up automatically.
 */
@SuppressWarnings("null")
public final class EntityRuleCatalog {
    private static List<Entry> cached;

    /** One selectable mob: registry id, translation key and translated name. */
    public record Entry(EntityType<?> type, String id, String name) {
    }

    /** One selectable buff: registry id and translated name. */
    public record Buff(String id, String name) {
    }

    private EntityRuleCatalog() {
    }

    /** The player target entry, always shown first in the editor. */
    public static Entry player() {
        return new Entry(EntityType.PLAYER, EntityRule.PLAYER_TYPE,
                EntityType.PLAYER.getDescription().getString());
    }

    /** Every registered mob effect, sorted by name. */
    public static List<Buff> buffs() {
        List<Buff> list = new ArrayList<>();
        for (Holder<MobEffect> holder : BuiltInRegistries.MOB_EFFECT.holders().toList()) {
            ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(holder.value());
            if (id == null) {
                continue;
            }
            list.add(new Buff(id.toString(), holder.value().getDisplayName().getString()));
        }
        list.sort(Comparator.comparing(Buff::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Buff::id));
        return List.copyOf(list);
    }

    /** Buffs whose name or id contains the filter text. */
    public static List<Buff> filteredBuffs(String filter) {
        List<Buff> all = buffs();
        if (filter == null || filter.isBlank()) {
            return all;
        }
        String needle = filter.trim().toLowerCase(java.util.Locale.ROOT);
        List<Buff> result = new ArrayList<>();
        for (Buff buff : all) {
            if (buff.name().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || buff.id().contains(needle)) {
                result.add(buff);
            }
        }
        return result;
    }

    public static List<Entry> entries() {
        if (cached != null) {
            return cached;
        }
        List<Entry> list = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type == null || type == EntityType.PLAYER || type.getCategory() == MobCategory.MISC) {
                continue;
            }
            Class<? extends net.minecraft.world.entity.Entity> baseClass = type.getBaseClass();
            if (baseClass == null || !Mob.class.isAssignableFrom(baseClass)) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null || id.getNamespace().equals("minecraft") && id.getPath().equals("player")) {
                continue;
            }
            list.add(new Entry(type, id.toString(), type.getDescription().getString()));
        }
        list.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::id));
        cached = List.copyOf(list);
        return cached;
    }

    /** Entries whose name or id contains the (lower case) filter text. */
    public static List<Entry> filtered(String filter) {
        List<Entry> all = entries();
        if (filter == null || filter.isBlank()) {
            return all;
        }
        String needle = filter.trim().toLowerCase(java.util.Locale.ROOT);
        List<Entry> result = new ArrayList<>();
        for (Entry entry : all) {
            if (entry.name().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || entry.id().contains(needle)) {
                result.add(entry);
            }
        }
        return result;
    }
}
