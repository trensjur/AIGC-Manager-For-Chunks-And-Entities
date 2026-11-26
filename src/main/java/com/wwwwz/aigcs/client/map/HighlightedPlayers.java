package com.wwwwz.aigcs.client.map;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.chat.Component;

/**
 * Players the user highlighted through the right click menu of the world map.
 * <p>
 * Highlighted players are exempt from the distance limit of the compass HUD and
 * are pinned to the border of the minimap when they leave its area. The set is
 * session scoped and reset when the world changes.
 */
public final class HighlightedPlayers {
    private static final Map<UUID, Component> PLAYERS = new LinkedHashMap<>();
    private static String worldKey = "";

    private HighlightedPlayers() {
    }

    /** Drops every highlight when the client joins another world. */
    public static void ensureWorld(String currentWorldKey) {
        if (currentWorldKey.equals(worldKey)) {
            return;
        }
        worldKey = currentWorldKey;
        PLAYERS.clear();
    }

    public static boolean isHighlighted(UUID id) {
        return id != null && PLAYERS.containsKey(id);
    }

    /**
     * Adds or removes the highlight of one player.
     *
     * @return {@code true} when the player is highlighted afterwards
     */
    public static boolean toggle(UUID id, Component name) {
        if (id == null) {
            return false;
        }
        if (PLAYERS.remove(id) != null) {
            return false;
        }
        PLAYERS.put(id, name != null ? name : Component.empty());
        return true;
    }

    public static boolean remove(UUID id) {
        return id != null && PLAYERS.remove(id) != null;
    }

    public static int count() {
        return PLAYERS.size();
    }

    public static void clear() {
        PLAYERS.clear();
    }
}
