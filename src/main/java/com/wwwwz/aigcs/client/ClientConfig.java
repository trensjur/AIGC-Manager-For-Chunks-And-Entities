package com.wwwwz.aigcs.client;

import com.wwwwz.aigcs.client.claim.ClaimMessageMode;
import com.wwwwz.aigcs.client.hud.HudCorner;
import com.wwwwz.aigcs.client.hud.HudMode;
import com.wwwwz.aigcs.client.hud.HudRenderLayer;
import com.wwwwz.aigcs.client.map.EntityDisplayMode;
import com.wwwwz.aigcs.client.map.EntityMarkerStyle;
import com.wwwwz.aigcs.client.map.LayerSwitchMode;
import com.wwwwz.aigcs.client.map.MapLayer;
import com.wwwwz.aigcs.client.preload.PreloadCenterMode;
import com.wwwwz.aigcs.client.preload.PreloadImportMode;
import com.wwwwz.aigcs.client.preload.PreloadLimits;
import com.wwwwz.aigcs.core.preload.PreloadPriority;
import com.wwwwz.aigcs.core.preload.PreloadRegion;
import com.wwwwz.aigcs.core.preload.PreloadShape;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client side configuration of the chunk recorder.
 * <p>
 * The recorder only ever runs on the client thread and never touches save
 * files, so all throttling that protects the local game lives here.
 */
public final class ClientConfig {
        private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

        public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
                        .comment("Record loaded chunks and synchronise changes to the server.")
                        .translation("m4cne.configuration.recording.enabled")
                        .define("recording.enabled", true);

        public static final ModConfigSpec.IntValue UPDATE_BUDGET_PER_TICK = BUILDER
                        .comment("How many chunks are sampled per client tick.")
                        .translation("m4cne.configuration.recording.chunksPerTick")
                        .defineInRange("recording.chunksPerTick", 4, 1, 64);

        public static final ModConfigSpec.IntValue RESCAN_INTERVAL_SECONDS = BUILDER
                        .comment("How often an already recorded chunk is sampled again.")
                        .translation("m4cne.configuration.recording.rescanSeconds")
                        .defineInRange("recording.rescanSeconds", 10, 2, 300);

        public static final ModConfigSpec.BooleanValue RECORD_ENTITIES = BUILDER
                        .comment("Count the entities visible in each recorded chunk.")
                        .translation("m4cne.configuration.recording.entities")
                        .define("recording.entities", true);

        public static final ModConfigSpec.BooleanValue RECORD_PLAYERS = BUILDER
                        .comment("Include players in the entity census.")
                        .translation("m4cne.configuration.recording.players")
                        .define("recording.players", true);

        public static final ModConfigSpec.BooleanValue RECORD_NON_LIVING = BUILDER
                        .comment("Include items, projectiles, boats and other non-living entities in the census.")
                        .translation("m4cne.configuration.recording.nonLivingEntities")
                        .define("recording.nonLivingEntities", true);

        // ------------------------------------------------------------ world map

        public static final ModConfigSpec.BooleanValue MAP_SHADING = BUILDER
                        .comment("Apply the sampled relief shading to the world map.")
                        .translation("m4cne.configuration.map.shading")
                        .define("map.shading", true);

        public static final ModConfigSpec.BooleanValue MAP_DAY_NIGHT = BUILDER
                        .comment("Dim the world map according to the current day/night cycle and sampled light.")
                        .translation("m4cne.configuration.map.dayNightLighting")
                        .define("map.dayNightLighting", true);

        public static final ModConfigSpec.BooleanValue MAP_HEIGHT_COLOR = BUILDER
                        .comment("Blend the sampled surface height into the map colour.")
                        .translation("m4cne.configuration.map.heightColor")
                        .define("map.heightColor", true);

        public static final ModConfigSpec.BooleanValue MAP_SMOOTH_TEXTURE = BUILDER
                        .comment("Use linear texture filtering for the map canvas instead of crisp pixels.")
                        .translation("m4cne.configuration.map.smoothTexture")
                        .define("map.smoothTexture", false);

        public static final ModConfigSpec.DoubleValue MAP_SHADING_STRENGTH = BUILDER
                        .comment("Strength of the relief shading, 0 = off, 1 = sampled slope.")
                        .translation("m4cne.configuration.map.shadingStrength")
                        .defineInRange("map.shadingStrength", 1.0D, 0.0D, 2.0D);

        public static final ModConfigSpec.DoubleValue MAP_HEIGHT_BLEND = BUILDER
                        .comment("How strongly the surface height changes the map brightness.")
                        .translation("m4cne.configuration.map.heightBlend")
                        .defineInRange("map.heightBlend", 0.35D, 0.0D, 1.0D);

        public static final ModConfigSpec.DoubleValue MAP_LIGHT_INTENSITY = BUILDER
                        .comment("How strongly sky light and block light affect the rendered map.")
                        .translation("m4cne.configuration.map.lightIntensity")
                        .defineInRange("map.lightIntensity", 0.75D, 0.0D, 1.0D);

        public static final ModConfigSpec.DoubleValue MAP_RENDER_SCALE = BUILDER
                        .comment("World map texture resolution relative to the on screen size.")
                        .translation("m4cne.configuration.map.renderScale")
                        .defineInRange("map.renderScale", 0.75D, 0.25D, 1.0D);

        public static final ModConfigSpec.IntValue MAP_REBUILD_INTERVAL_MS = BUILDER
                        .comment("Minimum time between two map texture rebuilds while panning or zooming.")
                        .translation("m4cne.configuration.map.rebuildIntervalMs")
                        .defineInRange("map.rebuildIntervalMs", 90, 30, 1000);

        public static final ModConfigSpec.DoubleValue MAP_OVERSCAN = BUILDER
                        .comment("Extra area sampled around the visible canvas so panning stays smooth.")
                        .translation("m4cne.configuration.map.overscan")
                        .defineInRange("map.overscan", 0.35D, 0.0D, 1.0D);

        public static final ModConfigSpec.IntValue MAP_SETTLE_MS = BUILDER
                        .comment("How long the camera has to rest before the map texture is rebuilt.")
                        .translation("m4cne.configuration.map.settleMs")
                        .defineInRange("map.settleMs", 160, 40, 1000);

        public static final ModConfigSpec.BooleanValue MAP_INVERT_WHEEL_ZOOM = BUILDER
                        .comment("Inverts the world map wheel zoom direction. Off means wheel up zooms in.")
                        .translation("m4cne.configuration.map.invertWheelZoom")
                        .define("map.invertWheelZoom", false);

        public static final ModConfigSpec.EnumValue<LayerSwitchMode> MAP_LAYER_MODE = BUILDER
                        .comment("AUTO switches to the cave layer while underground, MANUAL uses map.manualLayer.")
                        .translation("m4cne.configuration.map.layerSwitchMode")
                        .defineEnum("map.layerSwitchMode", LayerSwitchMode.AUTO);

        public static final ModConfigSpec.EnumValue<MapLayer> MAP_MANUAL_LAYER = BUILDER
                        .comment("Layer used in MANUAL switch mode.")
                        .translation("m4cne.configuration.map.manualLayer")
                        .defineEnum("map.manualLayer", MapLayer.SURFACE);

        public static final ModConfigSpec.IntValue MAP_CAVE_SLICE_OFFSET = BUILDER
                        .comment("Vertical offset of the cave slice relative to the player, in blocks.")
                        .translation("m4cne.configuration.map.caveSliceOffset")
                        .defineInRange("map.caveSliceOffset", 0, -64, 64);

        public static final ModConfigSpec.IntValue MAP_AUTO_CAVE_DEPTH = BUILDER
                        .comment("Distance below the surface at which AUTO mode switches to the cave layer.")
                        .translation("m4cne.configuration.map.autoCaveDepth")
                        .defineInRange("map.autoCaveDepth", 6, 1, 64);

        public static final ModConfigSpec.IntValue MAP_CAVE_CACHE_CHUNKS = BUILDER
                        .comment("Maximum number of cached cave slices.")
                        .translation("m4cne.configuration.map.caveCacheChunks")
                        .defineInRange("map.caveCacheChunks", 4096, 256, 32768);

        public static final ModConfigSpec.IntValue MAP_CAVE_CACHE_SECONDS = BUILDER
                        .comment("Lifetime of a cached cave slice before it is sampled again.")
                        .translation("m4cne.configuration.map.caveCacheSeconds")
                        .defineInRange("map.caveCacheSeconds", 30, 5, 300);

        // -------------------------------------------------------------- preload

        public static final ModConfigSpec.IntValue PRELOAD_RADIUS_CHUNKS = BUILDER
                        .comment("Radius of a preload task in chunks. The feature allows at most 400 chunks.")
                        .translation("m4cne.configuration.preload.radiusChunks")
                        .defineInRange("preload.radiusChunks", 128, 1, PreloadRegion.MAX_RADIUS);

        public static final ModConfigSpec.EnumValue<PreloadShape> PRELOAD_SHAPE = BUILDER
                        .comment("Outline of the preload region, SQUARE or CIRCLE.")
                        .translation("m4cne.configuration.preload.shape")
                        .defineEnum("preload.shape", PreloadShape.SQUARE);

        public static final ModConfigSpec.EnumValue<PreloadCenterMode> PRELOAD_CENTER_MODE = BUILDER
                        .comment("Where the preload centre comes from: SPAWN, PLAYER or the map MARKER.")
                        .translation("m4cne.configuration.preload.centerMode")
                        .defineEnum("preload.centerMode", PreloadCenterMode.PLAYER);

        public static final ModConfigSpec.IntValue PRELOAD_THREADS = BUILDER
                        .comment("Parallel chunk requests of a preload task, capped at the hardware thread count.")
                        .translation("m4cne.configuration.preload.threads")
                        .defineInRange("preload.threads", PreloadLimits.defaultThreads(), 1, 1024);

        public static final ModConfigSpec.EnumValue<PreloadPriority> PRELOAD_PRIORITY = BUILDER
                        .comment("Thread priority of the preload worker, five steps from LOWEST to HIGHEST.")
                        .translation("m4cne.configuration.preload.priority")
                        .defineEnum("preload.priority", PreloadPriority.NORMAL);

        public static final ModConfigSpec.IntValue PRELOAD_MEMORY_MB = BUILDER
                        .comment("Memory budget of a preload task in MiB. The GUI caps this at 90% of the game heap.")
                        .translation("m4cne.configuration.preload.memoryMb")
                        .defineInRange("preload.memoryMb", PreloadLimits.defaultMemoryMb(), 256, 65536);

        public static final ModConfigSpec.BooleanValue PRELOAD_FREEZE_TICKS = BUILDER
                        .comment("Freeze the game ticks with /tick freeze while a preload task runs.")
                        .translation("m4cne.configuration.preload.freezeTicks")
                        .define("preload.freezeTicks", true);

        public static final ModConfigSpec.EnumValue<PreloadImportMode> PRELOAD_IMPORT_MODE = BUILDER
                        .comment("Import source of the preloaded chunks: OFF, CHUNKY or M4CNE (default).",
                                        "M4CNE syncs the tiles the server cached while preloading, once the task finished.")
                        .translation("m4cne.configuration.preload.importMode")
                        .defineEnum("preload.importMode", PreloadImportMode.M4CNE);

        public static final ModConfigSpec.IntValue PRELOAD_IMPORT_BUDGET = BUILDER
                        .comment("How many preloaded chunks are imported per game tick.")
                        .translation("m4cne.configuration.preload.importChunksPerTick")
                        .defineInRange("preload.importChunksPerTick", 8, 1, 64);

        public static final ModConfigSpec.BooleanValue PRELOAD_IMPORT_AUTO = BUILDER
                        .comment("Run the M4CNE import automatically once a preload task finished.")
                        .translation("m4cne.configuration.preload.autoImportOnFinish")
                        .define("preload.autoImportOnFinish", true);

        public static final ModConfigSpec.BooleanValue PRELOAD_SHOW_RANGE = BUILDER
                        .comment("Draw the preload region outline and progress on the world map.")
                        .translation("m4cne.configuration.preload.showRange")
                        .define("preload.showRange", true);

        // ------------------------------------------------------------------ HUD

        public static final ModConfigSpec.BooleanValue HUD_ENABLED = BUILDER
                        .comment("Show the information HUD in the top left corner of the world map.")
                        .translation("m4cne.configuration.hud.enabled")
                        .define("hud.enabled", true);

        public static final ModConfigSpec.BooleanValue HUD_SHOW_COORDINATES = BUILDER
                        .comment("Show the player coordinates in the world map overlay.")
                        .translation("m4cne.configuration.hud.showCoordinates")
                        .define("hud.showCoordinates", true);

        public static final ModConfigSpec.BooleanValue HUD_SHOW_CHUNK_COORDINATES = BUILDER
                        .comment("Show the player chunk coordinates in the world map overlay.")
                        .translation("m4cne.configuration.hud.showChunkCoordinates")
                        .define("hud.showChunkCoordinates", true);

        public static final ModConfigSpec.BooleanValue HUD_SHOW_SCALE = BUILDER
                        .comment("Show the current map scale in the world map overlay.")
                        .translation("m4cne.configuration.hud.showScale")
                        .define("hud.showScale", true);

        public static final ModConfigSpec.BooleanValue HUD_SHOW_ENTITY_TOTALS = BUILDER
                        .comment("Show the number of visible entities in the world map overlay.")
                        .translation("m4cne.configuration.hud.showEntityTotals")
                        .define("hud.showEntityTotals", true);

        public static final ModConfigSpec.DoubleValue HUD_INFO_SCALE = BUILDER
                        .comment("Scale of the world map HUD text.")
                        .translation("m4cne.configuration.hud.infoScale")
                        .defineInRange("hud.infoScale", 1.0D, 0.5D, 2.0D);

        // ---------------------------------------------------------------- radar

        public static final ModConfigSpec.BooleanValue RADAR_ENABLED = BUILDER
                        .comment("Draw entity markers on the world map.")
                        .translation("m4cne.configuration.radar.enabled")
                        .define("radar.enabled", true);

        public static final ModConfigSpec.BooleanValue RADAR_PLAYERS = BUILDER
                        .comment("Include players in the map radar.")
                        .translation("m4cne.configuration.radar.players")
                        .define("radar.players", true);

        public static final ModConfigSpec.BooleanValue RADAR_HOSTILE = BUILDER
                        .comment("Include hostile mobs in the map radar.")
                        .translation("m4cne.configuration.radar.hostile")
                        .define("radar.hostile", true);

        public static final ModConfigSpec.BooleanValue RADAR_PASSIVE = BUILDER
                        .comment("Include animals and other passive mobs in the map radar.")
                        .translation("m4cne.configuration.radar.passive")
                        .define("radar.passive", true);

        public static final ModConfigSpec.BooleanValue RADAR_ITEMS = BUILDER
                        .comment("Include items, projectiles and other non-living entities in the map radar.")
                        .translation("m4cne.configuration.radar.items")
                        .define("radar.items", false);

        public static final ModConfigSpec.DoubleValue RADAR_MARKER_SCALE = BUILDER
                        .comment("Size of the entity markers.")
                        .translation("m4cne.configuration.radar.markerScale")
                        .defineInRange("radar.markerScale", 1.0D, 0.5D, 2.0D);

        public static final ModConfigSpec.BooleanValue RADAR_SHOW_COUNTS = BUILDER
                        .comment("Show how many entities are inside a radar marker.")
                        .translation("m4cne.configuration.radar.showCounts")
                        .define("radar.showCounts", true);

        public static final ModConfigSpec.EnumValue<EntityDisplayMode> RADAR_DISPLAY_MODE = BUILDER
                        .comment("MERGED adds the entities of a chunk to one marker, INDIVIDUAL draws every sampled entity.")
                        .translation("m4cne.configuration.radar.displayMode")
                        .defineEnum("radar.displayMode", EntityDisplayMode.MERGED);

        public static final ModConfigSpec.EnumValue<EntityMarkerStyle> RADAR_MARKER_STYLE = BUILDER
                        .comment("Style of the independent entity markers.")
                        .translation("m4cne.configuration.radar.markerStyle")
                        .defineEnum("radar.markerStyle", EntityMarkerStyle.ICON);

        public static final ModConfigSpec.DoubleValue RADAR_ICON_SCALE = BUILDER
                        .comment("Scale of the independent entity icons.")
                        .translation("m4cne.configuration.radar.iconScale")
                        .defineInRange("radar.iconScale", 1.0D, 0.25D, 3.0D);

        public static final ModConfigSpec.IntValue RADAR_MAX_MARKERS = BUILDER
                        .comment("Maximum number of independent entity markers drawn per frame.")
                        .translation("m4cne.configuration.radar.maxMarkers")
                        .defineInRange("radar.maxMarkers", 1500, 100, 8000);

        public static final ModConfigSpec.IntValue RADAR_SAMPLE_INTERVAL_MS = BUILDER
                        .comment("How often the visible entities are sampled for the independent display.")
                        .translation("m4cne.configuration.radar.sampleIntervalMs")
                        .defineInRange("radar.sampleIntervalMs", 250, 100, 5000);

        public static final ModConfigSpec.BooleanValue RADAR_CLICK_INFO = BUILDER
                        .comment("Left clicking an entity marker shows its name and coordinates.")
                        .translation("m4cne.configuration.radar.clickInfo")
                        .define("radar.clickInfo", true);

        // ------------------------------------------------------------ waypoints

        public static final ModConfigSpec.BooleanValue WAYPOINT_SHOW = BUILDER
                        .comment("Draw waypoints on the world map.")
                        .translation("m4cne.configuration.waypoints.show")
                        .define("waypoints.show", true);

        public static final ModConfigSpec.BooleanValue WAYPOINT_SHOW_NAMES = BUILDER
                        .comment("Draw waypoint names on the world map.")
                        .translation("m4cne.configuration.waypoints.showNames")
                        .define("waypoints.showNames", true);

        public static final ModConfigSpec.DoubleValue WAYPOINT_MARKER_SCALE = BUILDER
                        .comment("Size of the waypoint markers.")
                        .translation("m4cne.configuration.waypoints.markerScale")
                        .defineInRange("waypoints.markerScale", 1.0D, 0.5D, 2.0D);

        public static final ModConfigSpec.IntValue WAYPOINT_SYNC_INTERVAL_SECONDS = BUILDER
                        .comment("Real time interval between two automatic waypoint synchronisations, in seconds.")
                        .translation("m4cne.configuration.waypoints.syncIntervalSeconds")
                        .defineInRange("waypoints.syncIntervalSeconds", 300, 30, 86400);

        public static final ModConfigSpec.IntValue WAYPOINT_TRASH_AUTO_CLEAR_SECONDS = BUILDER
                        .comment("Real time in seconds after which a waypoint in the recycle bin is removed permanently. The timer pauses while the bin is empty.")
                        .translation("m4cne.configuration.waypoints.trashAutoClearSeconds")
                        .defineInRange("waypoints.trashAutoClearSeconds", 1800, 10, 86400);

        // ------------------------------------------------------------- overlays

        public static final ModConfigSpec.BooleanValue OVERLAY_CHUNK_GRID = BUILDER
                        .comment("Draw the chunk grid on the world map.")
                        .translation("m4cne.configuration.overlay.chunkGrid")
                        .define("overlay.chunkGrid", false);

        public static final ModConfigSpec.BooleanValue OVERLAY_CHUNK_COORDINATES = BUILDER
                        .comment("Draw chunk coordinates on the world map.")
                        .translation("m4cne.configuration.overlay.chunkCoordinates")
                        .define("overlay.chunkCoordinates", false);

        public static final ModConfigSpec.BooleanValue OVERLAY_HEIGHTS = BUILDER
                        .comment("Draw the sampled surface height of every visible chunk.")
                        .translation("m4cne.configuration.overlay.heights")
                        .define("overlay.heights", false);

        public static final ModConfigSpec.BooleanValue OVERLAY_HIGHLIGHT_HOVERED = BUILDER
                        .comment("Highlight the chunk under the mouse cursor.")
                        .translation("m4cne.configuration.overlay.highlightHovered")
                        .define("overlay.highlightHovered", true);

        public static final ModConfigSpec.DoubleValue OVERLAY_OPACITY = BUILDER
                        .comment("Opacity of the chunk information overlays.")
                        .translation("m4cne.configuration.overlay.opacity")
                        .defineInRange("overlay.opacity", 0.65D, 0.10D, 1.0D);

        public static final ModConfigSpec.BooleanValue OVERLAY_BIOME = BUILDER
                        .comment("Tint every recorded chunk with its biome colour.")
                        .translation("m4cne.configuration.overlay.biome")
                        .define("overlay.biome", false);

        public static final ModConfigSpec.DoubleValue OVERLAY_BIOME_OPACITY = BUILDER
                        .comment("Opacity of the biome overlay.")
                        .translation("m4cne.configuration.overlay.biomeOpacity")
                        .defineInRange("overlay.biomeOpacity", 0.45D, 0.0D, 1.0D);

        public static final ModConfigSpec.BooleanValue OVERLAY_SLIME_CHUNKS = BUILDER
                        .comment("Mark slime chunks on the world map.")
                        .translation("m4cne.configuration.overlay.slimeChunks")
                        .define("overlay.slimeChunks", false);

        public static final ModConfigSpec.DoubleValue OVERLAY_SLIME_OPACITY = BUILDER
                        .comment("Opacity of the slime chunk overlay.")
                        .translation("m4cne.configuration.overlay.slimeOpacity")
                        .defineInRange("overlay.slimeOpacity", 0.60D, 0.0D, 1.0D);

        public static final ModConfigSpec.BooleanValue OVERLAY_ORE_VEINS = BUILDER
                        .comment("Show the icon of the most common ore of the visible chunks.")
                        .translation("m4cne.configuration.overlay.oreVeins")
                        .define("overlay.oreVeins", false);

        public static final ModConfigSpec.BooleanValue OVERLAY_QUERY_HIGHLIGHT = BUILDER
                        .comment("Highlight the chunk selected by the chunk info query.")
                        .translation("m4cne.configuration.overlay.queryHighlight")
                        .define("overlay.queryHighlight", true);

        public static final ModConfigSpec.IntValue OVERLAY_ORE_SCAN_BUDGET = BUILDER
                        .comment("How many chunks may be scanned for ore counts per client tick.")
                        .translation("m4cne.configuration.overlay.oreScanBudget")
                        .defineInRange("overlay.oreScanBudget", 1, 1, 8);

        // ------------------------------------------------------ minimap / compass

        public static final ModConfigSpec.BooleanValue MINIMAP_ENABLED = BUILDER
                        .comment("Show the minimap / compass HUD while playing.")
                        .translation("m4cne.configuration.minimap.enabled")
                        .define("minimap.enabled", false);

        public static final ModConfigSpec.EnumValue<HudMode> MINIMAP_MODE = BUILDER
                        .comment("HUD widget: MINIMAP draws the nearby terrain, COMPASS draws the heading strip.")
                        .translation("m4cne.configuration.minimap.mode")
                        .defineEnum("minimap.mode", HudMode.MINIMAP);

        public static final ModConfigSpec.EnumValue<HudCorner> MINIMAP_CORNER = BUILDER
                        .comment("Screen corner the minimap is anchored to. Scaling grows away from that corner.")
                        .translation("m4cne.configuration.minimap.corner")
                        .defineEnum("minimap.corner", HudCorner.TOP_LEFT);

        public static final ModConfigSpec.DoubleValue MINIMAP_SCALE = BUILDER
                        .comment("Scale of the minimap HUD, anchored at its screen corner.")
                        .translation("m4cne.configuration.minimap.scale")
                        .defineInRange("minimap.scale", 1.0D, 0.5D, 2.0D);

        public static final ModConfigSpec.IntValue MINIMAP_MARGIN = BUILDER
                        .comment("Distance between the minimap and the screen borders in GUI pixels.")
                        .translation("m4cne.configuration.minimap.margin")
                        .defineInRange("minimap.margin", 4, 0, 64);

        public static final ModConfigSpec.EnumValue<HudRenderLayer> MINIMAP_RENDER_LAYER = BUILDER
                        .comment("Draws the HUD after (above) or before (below) the vanilla and modded GUI layers.")
                        .translation("m4cne.configuration.minimap.renderLayer")
                        .defineEnum("minimap.renderLayer", HudRenderLayer.ABOVE);

        public static final ModConfigSpec.BooleanValue MINIMAP_HIDE_IN_SCREENS = BUILDER
                        .comment("Hide the HUD while any screen (inventory, chest, JEI / EMI, ...) is open.")
                        .translation("m4cne.configuration.minimap.hideInScreens")
                        .define("minimap.hideInScreens", true);

        public static final ModConfigSpec.IntValue MINIMAP_SIZE = BUILDER
                        .comment("Edge length of the square minimap in GUI pixels.")
                        .translation("m4cne.configuration.minimap.size")
                        .defineInRange("minimap.size", 88, 48, 192);

        public static final ModConfigSpec.DoubleValue MINIMAP_ZOOM = BUILDER
                        .comment("Minimap zoom in GUI pixels per block.")
                        .translation("m4cne.configuration.minimap.zoom")
                        .defineInRange("minimap.zoom", 1.0D, 0.25D, 4.0D);

        public static final ModConfigSpec.BooleanValue MINIMAP_BORDER = BUILDER
                        .comment("Draw a frame around the minimap / compass HUD.")
                        .translation("m4cne.configuration.minimap.border")
                        .define("minimap.border", true);

        public static final ModConfigSpec.BooleanValue MINIMAP_SHOW_COORDINATES = BUILDER
                        .comment("Show the player coordinates below the HUD.")
                        .translation("m4cne.configuration.minimap.showCoordinates")
                        .define("minimap.showCoordinates", true);

        public static final ModConfigSpec.BooleanValue MINIMAP_SHOW_BIOME = BUILDER
                        .comment("Show the biome the player is standing in.")
                        .translation("m4cne.configuration.minimap.showBiome")
                        .define("minimap.showBiome", true);

        public static final ModConfigSpec.BooleanValue MINIMAP_SHOW_DIMENSION = BUILDER
                        .comment("Show the current dimension.")
                        .translation("m4cne.configuration.minimap.showDimension")
                        .define("minimap.showDimension", true);

        public static final ModConfigSpec.BooleanValue MINIMAP_SHOW_CHUNK = BUILDER
                        .comment("Show the current chunk including slime chunk state.")
                        .translation("m4cne.configuration.minimap.showChunk")
                        .define("minimap.showChunk", true);

        public static final ModConfigSpec.DoubleValue MINIMAP_INFO_SCALE = BUILDER
                        .comment("Text scale of the minimap / compass HUD information lines.")
                        .translation("m4cne.configuration.minimap.infoScale")
                        .defineInRange("minimap.infoScale", 1.0D, 0.5D, 2.0D);

        // ------------------------------------------------------------- compass

        public static final ModConfigSpec.IntValue COMPASS_POS_X = BUILDER
                        .comment("Horizontal offset of the compass centre from the screen centre line in GUI pixels.")
                        .translation("m4cne.configuration.compass.posX")
                        .defineInRange("compass.posX", 0, -8192, 8192);

        public static final ModConfigSpec.IntValue COMPASS_POS_Y = BUILDER
                        .comment("Vertical position of the compass centre, measured from the top of the window.")
                        .translation("m4cne.configuration.compass.posY")
                        .defineInRange("compass.posY", 28, 0, 8192);

        public static final ModConfigSpec.IntValue COMPASS_WIDTH = BUILDER
                        .comment("Width of the compass strip in GUI pixels, before the compass scale is applied.")
                        .translation("m4cne.configuration.compass.width")
                        .defineInRange("compass.width", 180, 80, 480);

        public static final ModConfigSpec.DoubleValue COMPASS_SCALE = BUILDER
                        .comment("Scale of the compass HUD; it grows around its own centre point.")
                        .translation("m4cne.configuration.compass.scale")
                        .defineInRange("compass.scale", 1.0D, 0.5D, 2.0D);

        public static final ModConfigSpec.DoubleValue COMPASS_ICON_SCALE = BUILDER
                        .comment("Scale of the entity icons drawn on the compass strip.")
                        .translation("m4cne.configuration.compass.iconScale")
                        .defineInRange("compass.iconScale", 1.5D, 0.5D, 3.0D);

        public static final ModConfigSpec.DoubleValue COMPASS_BACKGROUND_OPACITY = BUILDER
                        .comment("Opacity of the compass strip background, 0 is fully transparent.")
                        .translation("m4cne.configuration.compass.backgroundOpacity")
                        .defineInRange("compass.backgroundOpacity", 0.75D, 0.0D, 1.0D);

        public static final ModConfigSpec.BooleanValue COMPASS_ENTITIES = BUILDER
                        .comment("Draw entity icons on the compass strip.")
                        .translation("m4cne.configuration.compass.entities")
                        .define("compass.entities", true);

        public static final ModConfigSpec.IntValue COMPASS_ENTITY_RANGE = BUILDER
                        .comment("Entities farther away than this (in blocks) are hidden on the compass.")
                        .translation("m4cne.configuration.compass.entityRange")
                        .defineInRange("compass.entityRange", 64, 4, 512);

        public static final ModConfigSpec.IntValue COMPASS_MERGE_PIXELS = BUILDER
                        .comment("Entity icons closer than this many pixels are merged into one marker.")
                        .translation("m4cne.configuration.compass.mergePixels")
                        .defineInRange("compass.mergePixels", 10, 2, 48);

        public static final ModConfigSpec.BooleanValue COMPASS_WAYPOINTS = BUILDER
                        .comment("Draw waypoint markers on the compass strip.")
                        .translation("m4cne.configuration.compass.waypoints")
                        .define("compass.waypoints", true);

        public static final ModConfigSpec.IntValue COMPASS_WAYPOINT_RANGE = BUILDER
                        .comment("Waypoints farther away than this (in blocks) are hidden on the compass.")
                        .translation("m4cne.configuration.compass.waypointRange")
                        .defineInRange("compass.waypointRange", 256, 8, 4096);

        public static final ModConfigSpec.BooleanValue MINIMAP_ENTITIES = BUILDER
                        .comment("Draw entity icons on the minimap.")
                        .translation("m4cne.configuration.minimap.entities")
                        .define("minimap.entities", true);

        public static final ModConfigSpec.IntValue MINIMAP_ENTITY_RANGE = BUILDER
                        .comment("Entities farther away than this (in blocks) are hidden on the minimap.")
                        .translation("m4cne.configuration.minimap.entityRange")
                        .defineInRange("minimap.entityRange", 64, 4, 512);

        public static final ModConfigSpec.DoubleValue MINIMAP_ICON_SCALE = BUILDER
                        .comment("Scale of the entity and waypoint icons drawn on the minimap.")
                        .translation("m4cne.configuration.minimap.iconScale")
                        .defineInRange("minimap.iconScale", 1.0D, 0.5D, 3.0D);

        public static final ModConfigSpec.BooleanValue MINIMAP_WAYPOINTS = BUILDER
                        .comment("Draw waypoint markers on the minimap.")
                        .translation("m4cne.configuration.minimap.waypoints")
                        .define("minimap.waypoints", true);

        public static final ModConfigSpec.IntValue MINIMAP_WAYPOINT_RANGE = BUILDER
                        .comment("Waypoints farther away than this (in blocks) are hidden on the minimap.")
                        .translation("m4cne.configuration.minimap.waypointRange")
                        .defineInRange("minimap.waypointRange", 256, 8, 4096);

        // --------------------------------------------------------------- claims

        public static final ModConfigSpec.BooleanValue CLAIM_SHOW = BUILDER
                        .comment("Draw the claimed chunks of the world map as coloured stripes.")
                        .translation("m4cne.configuration.claims.show")
                        .define("claims.show", true);

        public static final ModConfigSpec.BooleanValue CLAIM_SHOW_OTHERS = BUILDER
                        .comment("Include the claims of other players; the own claims are always drawn.")
                        .translation("m4cne.configuration.claims.showOthers")
                        .define("claims.showOthers", true);

        public static final ModConfigSpec.BooleanValue CLAIM_SHOW_FORCE_LOAD = BUILDER
                        .comment("Mark force loaded chunks on the world map.")
                        .translation("m4cne.configuration.claims.showForceLoad")
                        .define("claims.showForceLoad", true);

        public static final ModConfigSpec.BooleanValue CLAIM_SELECT_LEFT_DRAG = BUILDER
                        .comment("Hold the left mouse button and drag to box select chunks for the claim menu.",
                                        "Disabled by default: the left mouse button pans the map, the middle mouse",
                                        "button always pans and the claim pages offer selection helpers instead.")
                        .translation("m4cne.configuration.claims.selectWithLeftDrag")
                        .define("claims.selectWithLeftDrag", false);

        public static final ModConfigSpec.BooleanValue CLAIM_NOTIFY = BUILDER
                        .comment("Print a chat message when entering or leaving the claimed chunks of another player.")
                        .translation("m4cne.configuration.claims.notify")
                        .define("claims.notify", true);

        public static final ModConfigSpec.EnumValue<ClaimMessageMode> CLAIM_ENTER_MESSAGE_MODE = BUILDER
                        .comment("Where the \"entered this chunk\" hint of the per chunk claim setting is shown.",
                                        "ABOVE_HOTBAR = overlay line above the hot bar, CHAT = chat message, OFF = hidden.")
                        .translation("m4cne.configuration.claims.enterMessageMode")
                        .defineEnum("claims.enterMessageMode", ClaimMessageMode.ABOVE_HOTBAR);

        public static final ModConfigSpec.DoubleValue CLAIM_OPACITY = BUILDER
                        .comment("Opacity of the claim overlay stripes.")
                        .translation("m4cne.configuration.claims.opacity")
                        .defineInRange("claims.opacity", 0.55D, 0.0D, 1.0D);

        public static final ModConfigSpec SPEC = BUILDER.build();

        private ClientConfig() {
        }
}
