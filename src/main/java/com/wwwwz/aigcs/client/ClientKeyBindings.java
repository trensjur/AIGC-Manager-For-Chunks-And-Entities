package com.wwwwz.aigcs.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.wwwwz.aigcs.client.gui.WorldMapScreen;
import com.wwwwz.aigcs.client.hud.HudMode;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * Key bindings of the client side map.
 * <p>
 * The binding is a normal, remappable Minecraft key binding (Options ->
 * Controls
 * -> Key Binds) and defaults to M. Pressing it again closes the world map,
 * while
 * ESC always performs the vanilla "close screen" action.
 */
@SuppressWarnings("null")
public final class ClientKeyBindings {
    public static final String CATEGORY = "key.categories.m4cne";
    public static final KeyMapping OPEN_MAP = new KeyMapping(
            "key.m4cne.open_map",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            CATEGORY);

    /** Shows or hides the minimap / compass HUD. */
    public static final KeyMapping TOGGLE_HUD = new KeyMapping(
            "key.m4cne.toggle_hud",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            CATEGORY);

    /** Switches the HUD between minimap and compass. */
    public static final KeyMapping CYCLE_HUD = new KeyMapping(
            "key.m4cne.cycle_hud",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            CATEGORY);

    /** Opens the "new waypoint" editor at the player position. */
    public static final KeyMapping NEW_WAYPOINT = new KeyMapping(
            "key.m4cne.new_waypoint",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            CATEGORY);

    private ClientKeyBindings() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MAP);
        event.register(TOGGLE_HUD);
        event.register(CYCLE_HUD);
        event.register(NEW_WAYPOINT);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN_MAP.consumeClick()) {
            if (minecraft.screen instanceof WorldMapScreen screen) {
                screen.onClose();
            } else if (minecraft.screen == null) {
                minecraft.setScreen(new WorldMapScreen());
            }
        }
        while (TOGGLE_HUD.consumeClick()) {
            boolean next = !ClientConfig.MINIMAP_ENABLED.getAsBoolean();
            ClientConfig.MINIMAP_ENABLED.set(next);
            ClientConfig.SPEC.save();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable(next ? "screen.m4cne.hud.toggled_on" : "screen.m4cne.hud.toggled_off"),
                        true);
            }
        }
        while (CYCLE_HUD.consumeClick()) {
            HudMode next = ClientConfig.MINIMAP_MODE.get().next();
            ClientConfig.MINIMAP_MODE.set(next);
            ClientConfig.SPEC.save();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("screen.m4cne.hud.mode_changed",
                                Component.translatable(next.translationKey())),
                        true);
            }
        }
        while (NEW_WAYPOINT.consumeClick()) {
            if (minecraft.screen == null && minecraft.player != null) {
                // The editor needs a parent screen to return to, so the world
                // map is opened first and replaced by the editor immediately.
                WorldMapScreen screen = new WorldMapScreen();
                minecraft.setScreen(screen);
                screen.openWaypointEditorAtPlayer();
            }
        }
    }
}
