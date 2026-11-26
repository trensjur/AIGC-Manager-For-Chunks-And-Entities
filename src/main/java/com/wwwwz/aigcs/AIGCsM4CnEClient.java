package com.wwwwz.aigcs;

import com.wwwwz.aigcs.client.ClientChunkRecorder;
import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.ClientGameEvents;
import com.wwwwz.aigcs.client.ClientKeyBindings;
import com.wwwwz.aigcs.client.claim.ClaimBoundaryRenderer;
import com.wwwwz.aigcs.client.claim.ClaimStore;
import com.wwwwz.aigcs.client.claim.ClaimSyncManager;
import com.wwwwz.aigcs.client.hud.ClientHudOverlay;
import com.wwwwz.aigcs.client.map.StructureScanController;
import com.wwwwz.aigcs.client.map.WaypointSyncManager;
import com.wwwwz.aigcs.client.preload.PreloadController;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client entry point.
 * <p>
 * The client side is responsible for reading the chunks it has loaded,
 * detecting
 * changes and reporting them to the server. It never writes save data itself.
 */
@Mod(value = AIGCsM4CnE.MODID, dist = Dist.CLIENT)
public class AIGCsM4CnEClient {
    public AIGCsM4CnEClient(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modEventBus.addListener(ClientKeyBindings::register);

        NeoForge.EVENT_BUS.addListener(ClientChunkRecorder::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientChunkRecorder::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientKeyBindings::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientGameEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientGameEvents::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(WaypointSyncManager::onClientTick);
        NeoForge.EVENT_BUS.addListener(WaypointSyncManager::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClaimSyncManager::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClaimSyncManager::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClaimStore::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClaimBoundaryRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(StructureScanController::onClientTick);
        NeoForge.EVENT_BUS.addListener(PreloadController::onClientTick);
        NeoForge.EVENT_BUS.addListener(PreloadController::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientHudOverlay::onRenderGuiPre);
        NeoForge.EVENT_BUS.addListener(ClientHudOverlay::onRenderGuiPost);
    }
}
