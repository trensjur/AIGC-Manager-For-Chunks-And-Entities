package com.wwwwz.aigcs;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server side configuration.
 * <p>
 * These values control what the server accepts from clients and how the
 * per-save chunk repository is kept on disk / in memory.
 */
public final class Config {
        private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

        public static final ModConfigSpec.BooleanValue ACCEPT_CLIENT_DATA = BUILDER
                        .comment("Accept chunk tiles and entity censuses sent by clients.")
                        .translation("m4cne.configuration.sync.acceptClientData")
                        .define("sync.acceptClientData", true);

        public static final ModConfigSpec.IntValue MAX_SYNC_DISTANCE_CHUNKS = BUILDER
                        .comment("Maximum distance in chunks between the player and a chunk the client may record.")
                        .translation("m4cne.configuration.sync.maxDistanceChunks")
                        .defineInRange("sync.maxDistanceChunks", 48, 1, 512);

        public static final ModConfigSpec.IntValue MAX_CHUNKS_PER_SECOND_PER_PLAYER = BUILDER
                        .comment("Upper bound of chunk updates accepted per player per second.")
                        .translation("m4cne.configuration.sync.maxChunksPerSecondPerPlayer")
                        .defineInRange("sync.maxChunksPerSecondPerPlayer", 160, 1, 20000);

        public static final ModConfigSpec.BooleanValue RECORD_ENTITIES = BUILDER
                        .comment("Store the entity census reported by clients.")
                        .translation("m4cne.configuration.entities.enabled")
                        .define("entities.enabled", true);

        public static final ModConfigSpec.IntValue MAX_ENTITY_TYPES_PER_CHUNK = BUILDER
                        .comment("Maximum number of distinct entity types stored per chunk.")
                        .translation("m4cne.configuration.entities.maxTypesPerChunk")
                        .defineInRange("entities.maxTypesPerChunk", 64, 1, 256);

        public static final ModConfigSpec.IntValue AUTOSAVE_SECONDS = BUILDER
                        .comment("How often dirty regions are written to disk, in seconds.")
                        .translation("m4cne.configuration.storage.autosaveSeconds")
                        .defineInRange("storage.autosaveSeconds", 120, 10, 3600);

        public static final ModConfigSpec.IntValue MAX_LOADED_CHUNKS = BUILDER
                        .comment("Maximum number of chunk records kept in memory before clean regions are evicted.")
                        .translation("m4cne.configuration.storage.maxLoadedChunks")
                        .defineInRange("storage.maxLoadedChunks", 40000, 1024, 1000000);

        public static final ModConfigSpec.IntValue PRELOAD_REQUIRED_PERMISSION_LEVEL = BUILDER
                        .comment("Vanilla permission level a player needs to use the chunk preload feature.",
                                        "0 = every player, 2 = operators/admins (default), 4 = server owners only.",
                                        "The single player host is always allowed, and the control packets are",
                                        "validated again on the server, so a modified client cannot bypass this.")
                        .translation("m4cne.configuration.preload.requiredPermissionLevel")
                        .defineInRange("preload.requiredPermissionLevel", 2, 0, 4);

        public static final ModConfigSpec.IntValue CLAIM_SCAN_INTERVAL_SECONDS = BUILDER
                        .comment("How often the server asks every client to upload its chunk claims, in seconds.",
                                        "The default of 600 seconds equals the ten minute scan interval; an admin",
                                        "can also change it at runtime from the chunk claim page.")
                        .translation("m4cne.configuration.claims.scanIntervalSeconds")
                        .defineInRange("claims.scanIntervalSeconds", 600, 30, 86400);

        public static final ModConfigSpec.IntValue CLAIM_MAX_PER_PLAYER = BUILDER
                        .comment("Maximum number of chunks one player may claim.")
                        .translation("m4cne.configuration.claims.maxClaimsPerPlayer")
                        .defineInRange("claims.maxClaimsPerPlayer", 10000, 1, 200000);

        public static final ModConfigSpec.BooleanValue CLAIM_FORCE_LOAD = BUILDER
                        .comment("Allow players to force load their claimed chunks so they are never unloaded.")
                        .translation("m4cne.configuration.claims.forceLoadEnabled")
                        .define("claims.forceLoadEnabled", true);

        public static final ModConfigSpec.IntValue CLAIM_MAX_FORCED_PER_PLAYER = BUILDER
                        .comment("Maximum number of force loaded chunks one player may keep loaded.")
                        .translation("m4cne.configuration.claims.maxForcedChunksPerPlayer")
                        .defineInRange("claims.maxForcedChunksPerPlayer", 256, 0, 100000);

        public static final ModConfigSpec.IntValue CLAIM_ADMIN_PERMISSION_LEVEL = BUILDER
                        .comment("Vanilla permission level needed to manage the claims of other players,",
                                        "to trigger a manual claim scan and to change the scan interval.",
                                        "0 = every player, 2 = operators/admins (default), 4 = server owners only.")
                        .translation("m4cne.configuration.claims.adminPermissionLevel")
                        .defineInRange("claims.adminPermissionLevel", 2, 0, 4);

        public static final ModConfigSpec.BooleanValue CLAIM_ENTITY_CONTROL = BUILDER
                        .comment("Enable the per claim entity control: chunk owners can limit spawns and",
                                        "populations of living entities, disable mob aggression and lock the",
                                        "claim boundary for entities.")
                        .translation("m4cne.configuration.claims.entityControlEnabled")
                        .define("claims.entityControlEnabled", true);

        public static final ModConfigSpec.IntValue CLAIM_MAX_ENTITY_RULES = BUILDER
                        .comment("Maximum number of per entity type rules one claim owner may store.")
                        .translation("m4cne.configuration.claims.maxEntityRulesPerOwner")
                        .defineInRange("claims.maxEntityRulesPerOwner", 64, 1, 256);

        public static final ModConfigSpec.IntValue CLAIM_ENTITY_CLEANUP_INTERVAL_TICKS = BUILDER
                        .comment("How often the server checks controlled chunks for excess entities, in ticks.")
                        .translation("m4cne.configuration.claims.entityCleanupIntervalTicks")
                        .defineInRange("claims.entityCleanupIntervalTicks", 20, 5, 200);

        public static final ModConfigSpec.IntValue CLAIM_ENTITY_CLEANUP_CHUNKS = BUILDER
                        .comment("How many controlled chunks are checked per cleanup pass. Higher values",
                                        "remove excess entities faster but cost more server time.")
                        .translation("m4cne.configuration.claims.entityCleanupChunksPerPass")
                        .defineInRange("claims.entityCleanupChunksPerPass", 32, 1, 512);

        public static final ModConfigSpec SPEC = BUILDER.build();

        private Config() {
        }
}
