package com.wwwwz.aigcs.client.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wwwwz.aigcs.client.ClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;

/**
 * Snapshot of every entity the client can currently see.
 * <p>
 * The entity census travels only as per chunk counts, which is enough for the
 * merged radar but cannot feed an independent marker per entity. This cache
 * samples the actual entity positions on a budget and keeps them until the next
 * sample, so rendering never walks the entity list itself.
 */
@SuppressWarnings("null")
public final class ClientEntityMarkerCache {
    public record Marker(UUID id, EntityType<?> type, MobCategory category, Component name, double x, double y,
            double z) {
    }

    private static final ClientEntityMarkerCache INSTANCE = new ClientEntityMarkerCache();

    private final List<Marker> markers = new ArrayList<>();
    private String worldKey = "";
    private ResourceLocation dimension;
    private long lastSampleAt;
    private long version;

    private ClientEntityMarkerCache() {
    }

    public static ClientEntityMarkerCache get() {
        return INSTANCE;
    }

    public List<Marker> markers() {
        return this.markers;
    }

    public long version() {
        return this.version;
    }

    public void clear() {
        this.markers.clear();
        this.worldKey = "";
        this.dimension = null;
        this.lastSampleAt = 0L;
        this.version++;
    }

    /**
     * Re-samples the visible entities when the world, the dimension or the
     * sample interval changed.
     */
    public void sample(ClientLevel level, String currentWorldKey) {
        if (level == null) {
            this.clear();
            return;
        }
        ResourceLocation currentDimension = level.dimension().location();
        long now = System.currentTimeMillis();
        boolean worldChanged = !currentWorldKey.equals(this.worldKey) || !currentDimension.equals(this.dimension);
        if (!worldChanged && now - this.lastSampleAt < ClientConfig.RADAR_SAMPLE_INTERVAL_MS.getAsInt()) {
            return;
        }
        this.worldKey = currentWorldKey;
        this.dimension = currentDimension;
        this.lastSampleAt = now;
        this.markers.clear();

        int maximum = ClientConfig.RADAR_MAX_MARKERS.getAsInt();
        Player localPlayer = Minecraft.getInstance().player;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == localPlayer || entity.isRemoved()) {
                continue;
            }
            this.markers.add(new Marker(entity.getUUID(), entity.getType(), entity.getType().getCategory(),
                    entity.getDisplayName().copy(), entity.getX(), entity.getY(), entity.getZ()));
            if (this.markers.size() >= maximum) {
                break;
            }
        }
        this.version++;
    }
}
