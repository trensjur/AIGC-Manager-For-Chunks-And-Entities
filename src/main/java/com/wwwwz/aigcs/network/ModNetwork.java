package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.AIGCsM4CnE;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers all custom payloads on the mod event bus. */
@SuppressWarnings("null")
public final class ModNetwork {
        /** Bump whenever the payload layout changes. */
        public static final String PROTOCOL_VERSION = "12";

        private ModNetwork() {
        }

        public static void register(RegisterPayloadHandlersEvent event) {
                event.registrar(PROTOCOL_VERSION)
                                .optional()
                                .playToServer(ChunkSyncPayload.TYPE, ChunkSyncPayload.STREAM_CODEC,
                                                ChunkSyncPayload::handle)
                                .playToServer(SeedRequestPayload.TYPE, SeedRequestPayload.STREAM_CODEC,
                                                SeedRequestPayload::handle)
                                .playToServer(PreloadControlPayload.TYPE, PreloadControlPayload.STREAM_CODEC,
                                                PreloadControlPayload::handle)
                                .playToServer(WaypointSyncPayload.TYPE, WaypointSyncPayload.STREAM_CODEC,
                                                WaypointSyncPayload::handle)
                                .playToServer(StructureScanPayload.TYPE, StructureScanPayload.STREAM_CODEC,
                                                StructureScanPayload::handle)
                                .playToServer(TeleportRequestPayload.TYPE, TeleportRequestPayload.STREAM_CODEC,
                                                TeleportRequestPayload::handle)
                                .playToServer(ClaimSyncPayload.TYPE, ClaimSyncPayload.STREAM_CODEC,
                                                ClaimSyncPayload::handle)
                                .playToServer(ClaimActionPayload.TYPE, ClaimActionPayload.STREAM_CODEC,
                                                ClaimActionPayload::handle)
                                .playToServer(ClaimRulePayload.TYPE, ClaimRulePayload.STREAM_CODEC,
                                                ClaimRulePayload::handle)
                                .playToClient(WorldSeedPayload.TYPE, WorldSeedPayload.STREAM_CODEC,
                                                WorldSeedPayload::handle)
                                .playToClient(PreloadStatusPayload.TYPE, PreloadStatusPayload.STREAM_CODEC,
                                                PreloadStatusPayload::handle)
                                .playToClient(PreloadChunkPayload.TYPE, PreloadChunkPayload.STREAM_CODEC,
                                                PreloadChunkPayload::handle)
                                .playToClient(WaypointListPayload.TYPE, WaypointListPayload.STREAM_CODEC,
                                                WaypointListPayload::handle)
                                .playToClient(WaypointForceSyncPayload.TYPE,
                                                WaypointForceSyncPayload.STREAM_CODEC,
                                                WaypointForceSyncPayload::handle)
                                .playToClient(StructureScanResultPayload.TYPE,
                                                StructureScanResultPayload.STREAM_CODEC,
                                                StructureScanResultPayload::handle)
                                .playToClient(ClaimListPayload.TYPE, ClaimListPayload.STREAM_CODEC,
                                                ClaimListPayload::handle)
                                .playToClient(ClaimScanPayload.TYPE, ClaimScanPayload.STREAM_CODEC,
                                                ClaimScanPayload::handle);
                AIGCsM4CnE.LOGGER.debug("Registered m4cne payloads");
        }
}
