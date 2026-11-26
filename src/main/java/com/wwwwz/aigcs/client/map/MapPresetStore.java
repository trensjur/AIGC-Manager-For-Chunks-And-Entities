package com.wwwwz.aigcs.client.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wwwwz.aigcs.AIGCsM4CnE;
import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.hud.HudCorner;
import com.wwwwz.aigcs.client.hud.HudMode;
import com.wwwwz.aigcs.client.hud.HudRenderLayer;
import com.wwwwz.aigcs.client.preload.PreloadCenterMode;
import com.wwwwz.aigcs.client.preload.PreloadImportMode;
import com.wwwwz.aigcs.core.preload.PreloadPriority;
import com.wwwwz.aigcs.core.preload.PreloadShape;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Named configuration presets for the world map settings.
 * <p>
 * Only the settings exposed by the world map menus are captured. Presets are
 * stored in {@code config/m4cne/presets.json} and are global (not per world),
 * which matches the behaviour of a normal options preset.
 */
@SuppressWarnings("null")
public final class MapPresetStore {
    public static final int SLOTS = 10;

    private record Setting(String id, Supplier<String> getter, Consumer<String> setter, Supplier<String> defaultValue) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, MapPresetStore> INSTANCES = new LinkedHashMap<>();

    private static final java.util.List<Setting> SETTINGS = java.util.List.of(
            bool("map.shading", ClientConfig.MAP_SHADING),
            bool("map.dayNightLighting", ClientConfig.MAP_DAY_NIGHT),
            bool("map.heightColor", ClientConfig.MAP_HEIGHT_COLOR),
            bool("map.smoothTexture", ClientConfig.MAP_SMOOTH_TEXTURE),
            decimal("map.shadingStrength", ClientConfig.MAP_SHADING_STRENGTH),
            decimal("map.heightBlend", ClientConfig.MAP_HEIGHT_BLEND),
            decimal("map.lightIntensity", ClientConfig.MAP_LIGHT_INTENSITY),
            decimal("map.renderScale", ClientConfig.MAP_RENDER_SCALE),
            integer("map.rebuildIntervalMs", ClientConfig.MAP_REBUILD_INTERVAL_MS),
            decimal("map.overscan", ClientConfig.MAP_OVERSCAN),
            integer("map.settleMs", ClientConfig.MAP_SETTLE_MS),
            bool("map.invertWheelZoom", ClientConfig.MAP_INVERT_WHEEL_ZOOM),
            enumeration("map.layerSwitchMode", ClientConfig.MAP_LAYER_MODE, LayerSwitchMode.class),
            enumeration("map.manualLayer", ClientConfig.MAP_MANUAL_LAYER, MapLayer.class),
            integer("map.caveSliceOffset", ClientConfig.MAP_CAVE_SLICE_OFFSET),
            integer("map.autoCaveDepth", ClientConfig.MAP_AUTO_CAVE_DEPTH),
            integer("map.caveCacheChunks", ClientConfig.MAP_CAVE_CACHE_CHUNKS),
            integer("map.caveCacheSeconds", ClientConfig.MAP_CAVE_CACHE_SECONDS),
            bool("preload.freezeTicks", ClientConfig.PRELOAD_FREEZE_TICKS),
            integer("preload.radiusChunks", ClientConfig.PRELOAD_RADIUS_CHUNKS),
            enumeration("preload.shape", ClientConfig.PRELOAD_SHAPE, PreloadShape.class),
            enumeration("preload.centerMode", ClientConfig.PRELOAD_CENTER_MODE, PreloadCenterMode.class),
            integer("preload.threads", ClientConfig.PRELOAD_THREADS),
            enumeration("preload.priority", ClientConfig.PRELOAD_PRIORITY, PreloadPriority.class),
            integer("preload.memoryMb", ClientConfig.PRELOAD_MEMORY_MB),
            enumeration("preload.importMode", ClientConfig.PRELOAD_IMPORT_MODE, PreloadImportMode.class),
            integer("preload.importChunksPerTick", ClientConfig.PRELOAD_IMPORT_BUDGET),
            bool("preload.autoImportOnFinish", ClientConfig.PRELOAD_IMPORT_AUTO),
            bool("preload.showRange", ClientConfig.PRELOAD_SHOW_RANGE),
            bool("hud.enabled", ClientConfig.HUD_ENABLED),
            bool("hud.showCoordinates", ClientConfig.HUD_SHOW_COORDINATES),
            bool("hud.showChunkCoordinates", ClientConfig.HUD_SHOW_CHUNK_COORDINATES),
            bool("hud.showScale", ClientConfig.HUD_SHOW_SCALE),
            bool("hud.showEntityTotals", ClientConfig.HUD_SHOW_ENTITY_TOTALS),
            decimal("hud.infoScale", ClientConfig.HUD_INFO_SCALE),
            bool("radar.enabled", ClientConfig.RADAR_ENABLED),
            bool("radar.players", ClientConfig.RADAR_PLAYERS),
            bool("radar.hostile", ClientConfig.RADAR_HOSTILE),
            bool("radar.passive", ClientConfig.RADAR_PASSIVE),
            bool("radar.items", ClientConfig.RADAR_ITEMS),
            decimal("radar.markerScale", ClientConfig.RADAR_MARKER_SCALE),
            bool("radar.showCounts", ClientConfig.RADAR_SHOW_COUNTS),
            enumeration("radar.displayMode", ClientConfig.RADAR_DISPLAY_MODE, EntityDisplayMode.class),
            enumeration("radar.markerStyle", ClientConfig.RADAR_MARKER_STYLE, EntityMarkerStyle.class),
            decimal("radar.iconScale", ClientConfig.RADAR_ICON_SCALE),
            integer("radar.maxMarkers", ClientConfig.RADAR_MAX_MARKERS),
            integer("radar.sampleIntervalMs", ClientConfig.RADAR_SAMPLE_INTERVAL_MS),
            bool("radar.clickInfo", ClientConfig.RADAR_CLICK_INFO),
            bool("waypoints.show", ClientConfig.WAYPOINT_SHOW),
            bool("waypoints.showNames", ClientConfig.WAYPOINT_SHOW_NAMES),
            decimal("waypoints.markerScale", ClientConfig.WAYPOINT_MARKER_SCALE),
            integer("waypoints.syncIntervalSeconds", ClientConfig.WAYPOINT_SYNC_INTERVAL_SECONDS),
            integer("waypoints.trashAutoClearSeconds", ClientConfig.WAYPOINT_TRASH_AUTO_CLEAR_SECONDS),
            bool("overlay.chunkGrid", ClientConfig.OVERLAY_CHUNK_GRID),
            bool("overlay.chunkCoordinates", ClientConfig.OVERLAY_CHUNK_COORDINATES),
            bool("overlay.heights", ClientConfig.OVERLAY_HEIGHTS),
            bool("overlay.highlightHovered", ClientConfig.OVERLAY_HIGHLIGHT_HOVERED),
            decimal("overlay.opacity", ClientConfig.OVERLAY_OPACITY),
            bool("overlay.biome", ClientConfig.OVERLAY_BIOME),
            decimal("overlay.biomeOpacity", ClientConfig.OVERLAY_BIOME_OPACITY),
            bool("overlay.slimeChunks", ClientConfig.OVERLAY_SLIME_CHUNKS),
            decimal("overlay.slimeOpacity", ClientConfig.OVERLAY_SLIME_OPACITY),
            bool("overlay.oreVeins", ClientConfig.OVERLAY_ORE_VEINS),
            bool("overlay.queryHighlight", ClientConfig.OVERLAY_QUERY_HIGHLIGHT),
            integer("overlay.oreScanBudget", ClientConfig.OVERLAY_ORE_SCAN_BUDGET),
            bool("minimap.enabled", ClientConfig.MINIMAP_ENABLED),
            enumeration("minimap.mode", ClientConfig.MINIMAP_MODE, HudMode.class),
            enumeration("minimap.corner", ClientConfig.MINIMAP_CORNER, HudCorner.class),
            decimal("minimap.scale", ClientConfig.MINIMAP_SCALE),
            integer("minimap.margin", ClientConfig.MINIMAP_MARGIN),
            enumeration("minimap.renderLayer", ClientConfig.MINIMAP_RENDER_LAYER, HudRenderLayer.class),
            bool("minimap.hideInScreens", ClientConfig.MINIMAP_HIDE_IN_SCREENS),
            integer("minimap.size", ClientConfig.MINIMAP_SIZE),
            decimal("minimap.zoom", ClientConfig.MINIMAP_ZOOM),
            bool("minimap.border", ClientConfig.MINIMAP_BORDER),
            bool("minimap.showCoordinates", ClientConfig.MINIMAP_SHOW_COORDINATES),
            bool("minimap.showBiome", ClientConfig.MINIMAP_SHOW_BIOME),
            bool("minimap.showDimension", ClientConfig.MINIMAP_SHOW_DIMENSION),
            bool("minimap.showChunk", ClientConfig.MINIMAP_SHOW_CHUNK),
            decimal("minimap.infoScale", ClientConfig.MINIMAP_INFO_SCALE),
            bool("minimap.entities", ClientConfig.MINIMAP_ENTITIES),
            integer("minimap.entityRange", ClientConfig.MINIMAP_ENTITY_RANGE),
            decimal("minimap.iconScale", ClientConfig.MINIMAP_ICON_SCALE),
            bool("minimap.waypoints", ClientConfig.MINIMAP_WAYPOINTS),
            integer("minimap.waypointRange", ClientConfig.MINIMAP_WAYPOINT_RANGE),
            integer("compass.posX", ClientConfig.COMPASS_POS_X),
            integer("compass.posY", ClientConfig.COMPASS_POS_Y),
            integer("compass.width", ClientConfig.COMPASS_WIDTH),
            decimal("compass.scale", ClientConfig.COMPASS_SCALE),
            decimal("compass.iconScale", ClientConfig.COMPASS_ICON_SCALE),
            decimal("compass.backgroundOpacity", ClientConfig.COMPASS_BACKGROUND_OPACITY),
            bool("compass.entities", ClientConfig.COMPASS_ENTITIES),
            integer("compass.entityRange", ClientConfig.COMPASS_ENTITY_RANGE),
            integer("compass.mergePixels", ClientConfig.COMPASS_MERGE_PIXELS),
            bool("compass.waypoints", ClientConfig.COMPASS_WAYPOINTS),
            integer("compass.waypointRange", ClientConfig.COMPASS_WAYPOINT_RANGE),
            bool("claims.show", ClientConfig.CLAIM_SHOW),
            bool("claims.showOthers", ClientConfig.CLAIM_SHOW_OTHERS),
            bool("claims.showForceLoad", ClientConfig.CLAIM_SHOW_FORCE_LOAD),
            bool("claims.selectWithLeftDrag", ClientConfig.CLAIM_SELECT_LEFT_DRAG),
            bool("claims.notify", ClientConfig.CLAIM_NOTIFY),
            enumeration("claims.enterMessageMode", ClientConfig.CLAIM_ENTER_MESSAGE_MODE,
                    com.wwwwz.aigcs.client.claim.ClaimMessageMode.class),
            decimal("claims.opacity", ClientConfig.CLAIM_OPACITY));

    private final Path file;
    private final Map<String, Map<String, String>> slots = new LinkedHashMap<>();
    private boolean loaded;

    private MapPresetStore(Path file) {
        this.file = file;
    }

    public static MapPresetStore client() {
        String key = "client";
        return INSTANCES.computeIfAbsent(key,
                ignored -> new MapPresetStore(FMLPaths.CONFIGDIR.get().resolve("m4cne").resolve("presets.json")));
    }

    private void ensureLoaded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try {
            String json = Files.readString(this.file, StandardCharsets.UTF_8);
            PresetFile decoded = GSON.fromJson(json, PresetFile.class);
            if (decoded != null && decoded.presets != null) {
                this.slots.putAll(decoded.presets);
            }
        } catch (Exception exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to read map presets from {}", this.file, exception);
        }
    }

    public boolean has(int slot) {
        this.ensureLoaded();
        Map<String, String> values = this.slots.get(Integer.toString(slot));
        return values != null && !values.isEmpty();
    }

    public boolean save(int slot) {
        this.ensureLoaded();
        Map<String, String> captured = new LinkedHashMap<>();
        for (Setting setting : SETTINGS) {
            captured.put(setting.id(), setting.getter().get());
        }
        this.slots.put(Integer.toString(slot), captured);
        this.write();
        return true;
    }

    public boolean apply(int slot) {
        this.ensureLoaded();
        Map<String, String> values = this.slots.get(Integer.toString(slot));
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (Setting setting : SETTINGS) {
            String value = values.get(setting.id());
            if (value == null) {
                continue;
            }
            try {
                setting.setter().accept(value);
            } catch (RuntimeException exception) {
                AIGCsM4CnE.LOGGER.warn("Skipping invalid preset value {}={}", setting.id(), value);
            }
        }
        ClientConfig.SPEC.save();
        return true;
    }

    public boolean delete(int slot) {
        this.ensureLoaded();
        boolean removed = this.slots.remove(Integer.toString(slot)) != null;
        if (removed) {
            this.write();
        }
        return removed;
    }

    public void resetDefaults() {
        for (Setting setting : SETTINGS) {
            setting.setter().accept(setting.defaultValue().get());
        }
        ClientConfig.SPEC.save();
    }

    private void write() {
        PresetFile data = new PresetFile();
        data.presets.putAll(this.slots);
        try {
            Files.createDirectories(this.file.getParent());
            Files.writeString(this.file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            AIGCsM4CnE.LOGGER.warn("Failed to write map presets to {}", this.file, exception);
        }
    }

    private static Setting bool(String id, ModConfigSpec.BooleanValue value) {
        return new Setting(id, () -> Boolean.toString(value.get()),
                parsed -> value.set(Boolean.parseBoolean(parsed)),
                () -> Boolean.toString(value.getDefault()));
    }

    private static Setting integer(String id, ModConfigSpec.IntValue value) {
        return new Setting(id, () -> Integer.toString(value.get()),
                parsed -> value.set(Integer.parseInt(parsed)),
                () -> Integer.toString(value.getDefault()));
    }

    private static Setting decimal(String id, ModConfigSpec.DoubleValue value) {
        return new Setting(id, () -> Double.toString(value.get()),
                parsed -> value.set(Double.parseDouble(parsed)),
                () -> Double.toString(value.getDefault()));
    }

    private static <T extends Enum<T>> Setting enumeration(String id, ModConfigSpec.EnumValue<T> value, Class<T> type) {
        return new Setting(id, () -> value.get().name(),
                parsed -> value.set(Enum.valueOf(type, parsed)),
                () -> value.getDefault().name());
    }

    private static final class PresetFile {
        private Map<String, Map<String, String>> presets = new LinkedHashMap<>();
    }
}
