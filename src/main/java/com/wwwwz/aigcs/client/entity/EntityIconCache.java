package com.wwwwz.aigcs.client.entity;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Static icon per entity type.
 * <p>
 * Building an icon is expensive (spawn eggs have to be looked up in the item
 * registry), so every entity type - including all modded ones - is sampled once
 * when a world is entered. Rendering then only does a map lookup and draws the
 * cached {@link ItemStack}, which keeps per frame cost independent of the
 * number of entity types.
 */
@SuppressWarnings("null")
public final class EntityIconCache {
    /** @param color fallback marker colour when no icon is available */
    public record Icon(ItemStack stack, int color) {
    }

    private static final EntityIconCache INSTANCE = new EntityIconCache();
    private static final Icon FALLBACK = new Icon(new ItemStack(Items.SPAWNER), 0xFFFFFF);

    private final Map<EntityType<?>, Icon> icons = new IdentityHashMap<>();
    private String worldKey = "";
    private long sampledAt;

    private EntityIconCache() {
    }

    public static EntityIconCache get() {
        return INSTANCE;
    }

    public int sampledTypes() {
        return this.icons.size();
    }

    /** Re-samples every registered entity type when the world changed. */
    public void ensureWorld(String currentWorldKey) {
        if (currentWorldKey.equals(this.worldKey) && !this.icons.isEmpty()) {
            return;
        }
        this.worldKey = currentWorldKey;
        this.icons.clear();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            this.icons.put(type, createIcon(type));
        }
        this.sampledAt = System.currentTimeMillis();
    }

    public Icon icon(EntityType<?> type) {
        Icon icon = type != null ? this.icons.get(type) : null;
        return icon != null ? icon : FALLBACK;
    }

    public long sampledAt() {
        return this.sampledAt;
    }

    public void clear() {
        this.icons.clear();
        this.worldKey = "";
        this.sampledAt = 0L;
    }

    private static Icon createIcon(EntityType<?> type) {
        int color = colorFor(type);
        if (type == EntityType.PLAYER) {
            return new Icon(new ItemStack(Items.PLAYER_HEAD), color);
        }
        SpawnEggItem egg;
        try {
            egg = SpawnEggItem.byId(type);
        } catch (RuntimeException exception) {
            egg = null;
        }
        return new Icon(egg != null ? new ItemStack(egg) : new ItemStack(Items.SPAWNER), color);
    }

    private static int colorFor(EntityType<?> type) {
        if (type == EntityType.PLAYER) {
            return 0xFFFFFF;
        }
        MobCategory category = type.getCategory();
        return switch (category) {
            case MONSTER -> 0xFF5555;
            case CREATURE, AXOLOTLS -> 0x55FF55;
            case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE -> 0x55AAFF;
            case AMBIENT -> 0xAA55FF;
            default -> 0xFFD755;
        };
    }
}
