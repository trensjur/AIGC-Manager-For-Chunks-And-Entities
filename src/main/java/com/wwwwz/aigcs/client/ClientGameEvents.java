package com.wwwwz.aigcs.client;

import java.util.HashSet;
import java.util.Set;

import com.wwwwz.aigcs.client.chunk.ChunkQueryService;
import com.wwwwz.aigcs.client.entity.ClientEntityMarkerCache;
import com.wwwwz.aigcs.client.entity.EntityIconCache;
import com.wwwwz.aigcs.client.map.ChunkColorCache;
import com.wwwwz.aigcs.client.map.HighlightedPlayers;
import com.wwwwz.aigcs.client.map.OreScanCache;
import com.wwwwz.aigcs.client.map.SlimeChunkCache;
import com.wwwwz.aigcs.network.SeedRequestPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per tick housekeeping of the client side caches.
 * <p>
 * It also fetches the level seed once per dimension, because the vanilla
 * protocol never sends it to the client and the slime chunk overlay needs it.
 * In single player the seed is read straight from the integrated server.
 */
@SuppressWarnings("null")
public final class ClientGameEvents {
    private static final long SEED_REQUEST_INTERVAL_MS = 2000L;

    private static final Set<ResourceLocation> SEED_REQUESTED = new HashSet<>();
    private static String worldKey = "";
    private static long lastSeedRequestAt;

    private ClientGameEvents() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            ClientEntityMarkerCache.get().clear();
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        String world = ClientWorldKey.current();
        if (!world.equals(worldKey)) {
            worldKey = world;
            SEED_REQUESTED.clear();
        }

        SlimeChunkCache.get().ensureSingleplayerSeed();
        requestSeedIfNeeded(minecraft, dimension);

        EntityIconCache.get().ensureWorld(world);
        HighlightedPlayers.ensureWorld(world);
        ClientEntityMarkerCache.get().sample(level, world);
        OreScanCache.get().tick(level, dimension);
    }

    private static void requestSeedIfNeeded(Minecraft minecraft, ResourceLocation dimension) {
        if (minecraft.hasSingleplayerServer() || SlimeChunkCache.get().hasSeed(dimension)) {
            return;
        }
        if (!SEED_REQUESTED.add(dimension)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSeedRequestAt < SEED_REQUEST_INTERVAL_MS) {
            SEED_REQUESTED.remove(dimension);
            return;
        }
        lastSeedRequestAt = now;
        PacketDistributor.sendToServer(new SeedRequestPayload());
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        worldKey = "";
        SEED_REQUESTED.clear();
        ClientEntityMarkerCache.get().clear();
        EntityIconCache.get().clear();
        HighlightedPlayers.clear();
        OreScanCache.get().clear();
        ChunkQueryService.get().clear();
        SlimeChunkCache.get().clear();
        ChunkColorCache.get().clear();
    }
}
