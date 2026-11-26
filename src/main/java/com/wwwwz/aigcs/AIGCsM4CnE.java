package com.wwwwz.aigcs;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wwwwz.aigcs.command.M4CnECommand;
import com.wwwwz.aigcs.network.ModNetwork;
import com.wwwwz.aigcs.server.ServerChunkStore;
import com.wwwwz.aigcs.server.ServerClaimManager;
import com.wwwwz.aigcs.server.ServerWaypointStore;
import com.wwwwz.aigcs.server.claim.ServerEntityControl;
import com.wwwwz.aigcs.server.preload.ServerPreloadManager;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Common entry point.
 * <p>
 * The mod records chunks on the client and stores them per save game on the
 * server. This class only wires up the common side: server config, payload
 * registration, the per-save chunk repository lifecycle and its commands.
 */
@Mod(AIGCsM4CnE.MODID)
public class AIGCsM4CnE {
    public static final String MODID = "m4cne";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AIGCsM4CnE(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        modEventBus.addListener(ModNetwork::register);

        NeoForge.EVENT_BUS.addListener(ServerChunkStore::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerChunkStore::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerChunkStore::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerChunkStore::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(ServerPreloadManager::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerPreloadManager::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerPreloadManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerPreloadManager::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(ServerPreloadManager::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(ServerWaypointStore::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerWaypointStore::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerWaypointStore::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(ServerClaimManager::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerClaimManager::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerClaimManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerClaimManager::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(ServerClaimManager::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(ServerEntityControl::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerEntityControl::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerEntityControl::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(ServerEntityControl::onLivingChangeTarget);
        NeoForge.EVENT_BUS.addListener(ServerEntityControl::onEntityTick);
        NeoForge.EVENT_BUS.addListener(M4CnECommand::register);
    }
}
