package com.wwwwz.aigcs.client;

import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Stable identifier of the world the client is currently playing.
 * <p>
 * The server keeps the authoritative data in the save folder, but the client
 * also needs to know when it switched worlds so its own caches never mix data
 * of two different saves.
 */
@SuppressWarnings("null")
public final class ClientWorldKey {
    private static String cached = "none";
    private static long cachedAt;

    private ClientWorldKey() {
    }

    /** Forces the next {@link #current()} call to recompute the key. */
    public static void invalidate() {
        cachedAt = 0L;
    }

    public static String current() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < 1000L) {
            return cached;
        }
        cachedAt = now;
        cached = compute();
        return cached;
    }

    private static String compute() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return "none";
        }
        try {
            if (!minecraft.hasSingleplayerServer()) {
                ServerData server = minecraft.getCurrentServer();
                return "mp:" + (server != null ? server.ip : "unknown");
            }
            MinecraftServer integrated = minecraft.getSingleplayerServer();
            if (integrated != null) {
                Path root = integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                return "sp:" + integrated.getWorldData().getLevelName() + ":"
                        + Integer.toHexString(root.toString().hashCode());
            }
        } catch (Exception ignored) {
            // The level may be torn down while the key is queried; fall through.
        }
        return "sp:unknown";
    }
}
