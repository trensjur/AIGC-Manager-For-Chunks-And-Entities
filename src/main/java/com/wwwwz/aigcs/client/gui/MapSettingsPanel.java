package com.wwwwz.aigcs.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.ClientKeyBindings;
import com.wwwwz.aigcs.client.claim.ClaimColors;
import com.wwwwz.aigcs.client.claim.ClaimStore;
import com.wwwwz.aigcs.client.claim.ClaimSyncManager;
import com.wwwwz.aigcs.client.claim.EntityRuleCatalog;
import com.wwwwz.aigcs.client.chunk.ChunkQueryService;
import com.wwwwz.aigcs.client.gui.widget.MapColorButton;
import com.wwwwz.aigcs.client.gui.widget.MapNumberField;
import com.wwwwz.aigcs.client.gui.widget.MapSlider;
import com.wwwwz.aigcs.client.gui.widget.MapToggleButton;
import com.wwwwz.aigcs.client.gui.widget.MapValueButton;
import com.wwwwz.aigcs.client.hud.HudMode;
import com.wwwwz.aigcs.client.hud.HudRenderLayer;
import com.wwwwz.aigcs.client.map.EntityDisplayMode;
import com.wwwwz.aigcs.client.map.EntityMarkerStyle;
import com.wwwwz.aigcs.client.map.LayerSwitchMode;
import com.wwwwz.aigcs.client.map.MapLayer;
import com.wwwwz.aigcs.client.map.MapPresetStore;
import com.wwwwz.aigcs.client.map.OreScanCache;
import com.wwwwz.aigcs.client.map.StructureScanCatalog;
import com.wwwwz.aigcs.client.map.StructureScanController;
import com.wwwwz.aigcs.client.map.WaypointStore;
import com.wwwwz.aigcs.client.map.WaypointSyncManager;
import com.wwwwz.aigcs.network.StructureScanResultPayload;
import com.wwwwz.aigcs.client.preload.PreloadCenterMode;
import com.wwwwz.aigcs.client.preload.PreloadController;
import com.wwwwz.aigcs.client.preload.PreloadImportMode;
import com.wwwwz.aigcs.client.preload.PreloadLimits;
import com.wwwwz.aigcs.client.preload.PreloadMarkerStore;
import com.wwwwz.aigcs.core.claim.ClaimChunk;
import com.wwwwz.aigcs.core.claim.EntityRule;
import com.wwwwz.aigcs.core.claim.EntityRuleSet;
import com.wwwwz.aigcs.core.preload.PreloadRegion;
import com.wwwwz.aigcs.core.preload.PreloadState;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Right hand settings column of the world map.
 * <p>
 * All entries are vanilla widgets. The panel switches between the first level
 * menu and the second level pages in place, shows the full menu path and offers
 * a back button. ESC remains reserved for closing the whole world map. Every
 * widget carries a localised tooltip that explains what it does.
 */
@SuppressWarnings("null")
public final class MapSettingsPanel {
    private record Heading(Component text, int y, int x) {
    }

    private record LabelLine(Component text, int y, int color, int x) {
    }

    private static final int ENTRY_HEIGHT = 20;
    private static final int ENTRY_GAP = 2;
    private static final int HEADER_HEIGHT = 32;
    private static final int FOOTER_HEIGHT = 18;
    /** Mob buttons per page of the entity rule quick list. */
    private static final int ENTITY_RULE_PAGE_SIZE = 10;
    /** How long the cancel button stays armed for the second confirmation. */
    private static final long CANCEL_CONFIRM_MS = 4000L;
    /** Change callback for settings that do not affect the baked map colours. */
    private static final Runnable NO_MAP_CHANGE = () -> {
    };

    private final WorldMapScreen screen;
    private final List<Heading> headings = new ArrayList<>();
    private final List<LabelLine> lines = new ArrayList<>();

    private MapSettingsPage page = MapSettingsPage.MAIN;
    private int x;
    private int y;
    private int width;
    private int height;
    private int contentTop;
    private int contentBottom;
    private int scrollTop;
    private int cursor;
    private double scroll;
    private double contentHeight;
    private double maxScroll;

    /** Text of the chunk query fields, kept across panel rebuilds. */
    private String queryX = "";
    private String queryZ = "";
    /** Selection of the waypoint manager / recycle bin lists. */
    private String selectedWaypointId = "";
    private String selectedTrashId = "";
    /** Selected player of the chunk claim list. */
    private String selectedClaimOwnerId = "";
    /**
     * Selected chunks of the chunk list page, keyed by dimension + packed chunk.
     */
    private final java.util.LinkedHashSet<String> chunkSelection = new java.util.LinkedHashSet<>();
    /**
     * {@code true} while the "multi select to merge" mode of the chunk list is
     * active.
     */
    private boolean claimMergeMode;
    /**
     * List tab of the chunk list page: 0 = claimed chunks, 1 = force loaded chunks.
     */
    private int claimListTab;
    private int claimListPage;
    private int claimForcePage;
    /** Free text filter of the entity rule quick list. */
    private String entityRuleFilter = "";
    /** Page of the entity rule quick list. */
    private int entityRulePage;
    /** Entity type whose rule row is expanded on the entity control page. */
    private String expandedEntityRule = "";
    /** Free text filter of the population limit quick list. */
    private String limitRuleFilter = "";
    /** Page of the population limit quick list. */
    private int limitRulePage;
    /** Entity type whose population limit row is expanded. */
    private String expandedLimitRule = "";
    /** Free text filter of the structure scan page. */
    private String structureQuery = "";
    /** Scan interval typed by an administrator on the claim page. */
    private String claimInterval = "";
    /** Timestamp until which the cancel button waits for its confirmation. */
    private long cancelArmedUntil;
    /**
     * When set, every widget built for the current page is disabled and shows
     * this tooltip key instead of its own one. Used by pages that need a
     * permission the server did not grant.
     */
    private String lockedTooltipKey;

    public MapSettingsPanel(WorldMapScreen screen) {
        this.screen = screen;
    }

    public MapSettingsPage page() {
        return this.page;
    }

    /**
     * The waypoint manager, the recycle bin, the claim player list and the
     * entity control page are drawn above the world map: their panel spans the
     * whole window and the map rendering is switched off.
     */
    public boolean hidesMap() {
        return this.page == MapSettingsPage.WAYPOINT_MANAGE || this.page == MapSettingsPage.WAYPOINT_TRASH
                || this.page == MapSettingsPage.CLAIM_CHUNKS
                || this.page == MapSettingsPage.CLAIM_ENTITY_RULES;
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.contentTop = y + HEADER_HEIGHT;
        this.contentBottom = y + height - FOOTER_HEIGHT;
        this.rebuild();
    }

    public void rebuild() {
        this.screen.clearSidebarWidgets();
        this.headings.clear();
        this.lines.clear();
        this.lockedTooltipKey = null;
        if (this.page == MapSettingsPage.CLAIM_CHUNKS) {
            // The chunk list is a fixed two column page: the list takes the
            // left two thirds of the window, the action buttons the right
            // third. It does not scroll as a whole, the lists are paginated.
            this.scroll = 0.0D;
            this.scrollTop = this.contentTop;
            this.maxScroll = 0.0D;
            this.contentHeight = this.contentBottom - this.scrollTop;
            this.buildClaimChunksPage();
            return;
        }
        int entryWidth = this.width - 12;
        if (this.hidesMap()) {
            // The list pages span the whole window; keep their rows readable.
            entryWidth = Math.min(entryWidth, 480);
        }
        this.cursor = this.contentTop - (int) Math.round(this.scroll);

        if (this.page.isMain()) {
            this.scrollTop = this.contentTop;
            this.cursor = this.scrollTop - (int) Math.round(this.scroll) + 2;
            for (MapSettingsPage entry : MapSettingsPage.values()) {
                // Third level pages are reached through their parent page
                // instead of being listed in the root menu.
                if (entry.isMain() || entry.parent() != MapSettingsPage.MAIN) {
                    continue;
                }
                MapSettingsPage target = entry;
                this.addButton(Component.translatable(entry.titleKey()), entryWidth,
                        () -> this.open(target), entry.titleKey() + ".tooltip");
            }
        } else {
            this.addFixedButton(Component.translatable("screen.m4cne.back"), entryWidth, this.contentTop,
                    this::back, "screen.m4cne.back.tooltip");
            // The back button lives outside the scrolling area, so scrolled
            // content can never overlap it.
            this.scrollTop = this.contentTop + ENTRY_HEIGHT + 6;
            this.cursor = this.scrollTop - (int) Math.round(this.scroll);
            switch (this.page) {
                case WORLD_MAP -> this.buildWorldMap(entryWidth);
                case RADAR -> this.buildRadar(entryWidth);
                case WAYPOINTS -> this.buildWaypoints(entryWidth);
                case WAYPOINT_MANAGE -> this.buildWaypointManage(entryWidth);
                case WAYPOINT_TRASH -> this.buildWaypointTrash(entryWidth);
                case STRUCTURE_SCAN -> this.buildStructureScan(entryWidth);
                case CLAIMS -> this.buildClaims(entryWidth);
                case CLAIM_ENTITY_RULES -> this.buildClaimEntityRules(entryWidth);
                case PRELOAD -> this.buildPreload(entryWidth);
                case OVERLAYS -> this.buildOverlays(entryWidth);
                case MINIMAP_HUD -> this.buildMinimapHud(entryWidth);
                case MINIMAP_LAYOUT -> this.buildMinimapLayout(entryWidth);
                case COMPASS_LAYOUT -> this.buildCompassLayout(entryWidth);
                case PRESETS -> this.buildPresets(entryWidth);
                case MAIN -> {
                    // handled above
                }
            }
        }

        this.contentHeight = this.cursor + this.scroll - this.scrollTop;
        double visible = this.contentBottom - this.scrollTop;
        this.maxScroll = Math.max(0.0D, this.contentHeight - visible);
        if (this.scroll > this.maxScroll) {
            // The widgets of this frame were placed with the old offset; the
            // next rebuild (requested by the next scroll or page change) uses
            // the corrected offset. Avoid recursive rebuilds here.
            this.scroll = this.maxScroll;
        }
    }

    private void open(MapSettingsPage target) {
        this.page = target;
        this.scroll = 0.0D;
        this.screen.requestSidebarRebuild();
    }

    private void back() {
        this.page = this.page.parent();
        this.scroll = 0.0D;
        this.screen.requestSidebarRebuild();
    }

    // --------------------------------------------------------------- entries

    private void buildWorldMap(int entryWidth) {
        this.section("screen.m4cne.section.render");
        this.toggle("screen.m4cne.setting.map_shading", ClientConfig.MAP_SHADING);
        this.toggle("screen.m4cne.setting.map_day_night", ClientConfig.MAP_DAY_NIGHT);
        this.toggle("screen.m4cne.setting.map_height_color", ClientConfig.MAP_HEIGHT_COLOR);
        this.toggle("screen.m4cne.setting.map_smooth", ClientConfig.MAP_SMOOTH_TEXTURE);
        this.toggle("screen.m4cne.setting.invert_scroll", ClientConfig.MAP_INVERT_WHEEL_ZOOM);
        this.doubleSlider("screen.m4cne.setting.overscan", ClientConfig.MAP_OVERSCAN,
                0.0D, 1.0D, 0.05D, MapSettingsPanel::percent);

        this.section("screen.m4cne.section.shading");
        this.doubleSlider("screen.m4cne.setting.shading_strength", ClientConfig.MAP_SHADING_STRENGTH,
                0.0D, 2.0D, 0.05D, MapSettingsPanel::percent);
        this.doubleSlider("screen.m4cne.setting.height_blend", ClientConfig.MAP_HEIGHT_BLEND,
                0.0D, 1.0D, 0.05D, MapSettingsPanel::percent);
        this.doubleSlider("screen.m4cne.setting.light_intensity", ClientConfig.MAP_LIGHT_INTENSITY,
                0.0D, 1.0D, 0.05D, MapSettingsPanel::percent);

        this.section("screen.m4cne.section.performance");
        this.intField("screen.m4cne.setting.chunks_per_tick", ClientConfig.UPDATE_BUDGET_PER_TICK, 1, 32);
        this.intField("screen.m4cne.setting.rescan_seconds", ClientConfig.RESCAN_INTERVAL_SECONDS, 2, 120);
        this.doubleSlider("screen.m4cne.setting.render_scale", ClientConfig.MAP_RENDER_SCALE,
                0.25D, 1.0D, 0.05D, MapSettingsPanel::percent);
        this.intField("screen.m4cne.setting.rebuild_interval", ClientConfig.MAP_REBUILD_INTERVAL_MS, 30, 1000);
        this.intField("screen.m4cne.setting.settle_ms", ClientConfig.MAP_SETTLE_MS, 40, 1000);

        this.section("screen.m4cne.section.layer");
        this.valueButton("screen.m4cne.setting.layer_mode", entryWidth,
                () -> Component.translatable(ClientConfig.MAP_LAYER_MODE.get().translationKey()),
                () -> ClientConfig.MAP_LAYER_MODE.set(ClientConfig.MAP_LAYER_MODE.get().next()));
        this.valueButton("screen.m4cne.setting.manual_layer", entryWidth,
                () -> Component.translatable(ClientConfig.MAP_MANUAL_LAYER.get().translationKey()),
                () -> ClientConfig.MAP_MANUAL_LAYER.set(ClientConfig.MAP_MANUAL_LAYER.get().next()));
        this.intField("screen.m4cne.setting.cave_offset", ClientConfig.MAP_CAVE_SLICE_OFFSET, -64, 64);
        this.intField("screen.m4cne.setting.auto_cave_depth", ClientConfig.MAP_AUTO_CAVE_DEPTH, 1, 64);

        // The former "HUD 设置" page was merged into this page.
        this.section("screen.m4cne.section.hud_info");
        this.toggle("screen.m4cne.setting.hud_enabled", ClientConfig.HUD_ENABLED);
        this.toggle("screen.m4cne.setting.hud_coordinates", ClientConfig.HUD_SHOW_COORDINATES);
        this.toggle("screen.m4cne.setting.hud_chunk_coordinates", ClientConfig.HUD_SHOW_CHUNK_COORDINATES);
        this.toggle("screen.m4cne.setting.hud_scale", ClientConfig.HUD_SHOW_SCALE);
        this.toggle("screen.m4cne.setting.hud_entity_totals", ClientConfig.HUD_SHOW_ENTITY_TOTALS);
        this.doubleSlider("screen.m4cne.setting.hud_info_scale", ClientConfig.HUD_INFO_SCALE,
                0.5D, 2.0D, 0.05D, MapSettingsPanel::percent);

        this.section("screen.m4cne.section.actions");
        this.addButton(Component.translatable("screen.m4cne.action.refresh_map"), entryWidth, () -> {
            this.screen.markMapDirty();
            this.screen.showStatus(Component.translatable("screen.m4cne.status.map_refreshed"));
        }, "screen.m4cne.action.refresh_map.tooltip");
        this.addButton(Component.translatable("screen.m4cne.action.clear_cache"), entryWidth, () -> {
            this.screen.clearMapCache();
            this.screen.showStatus(Component.translatable("screen.m4cne.status.cache_cleared"));
            this.screen.requestSidebarRebuild();
        }, "screen.m4cne.action.clear_cache.tooltip");
        this.label(Component.translatable("screen.m4cne.label.cave_cache", this.screen.caveCacheSize()));
    }

    private void buildRadar(int entryWidth) {
        this.toggle("screen.m4cne.setting.radar_enabled", ClientConfig.RADAR_ENABLED);
        this.section("screen.m4cne.section.radar_display");
        this.valueButton("screen.m4cne.setting.radar_mode", entryWidth,
                () -> Component.translatable(ClientConfig.RADAR_DISPLAY_MODE.get().translationKey()),
                () -> ClientConfig.RADAR_DISPLAY_MODE.set(ClientConfig.RADAR_DISPLAY_MODE.get().next()));
        this.valueButton("screen.m4cne.setting.radar_style", entryWidth,
                () -> Component.translatable(ClientConfig.RADAR_MARKER_STYLE.get().translationKey()),
                () -> ClientConfig.RADAR_MARKER_STYLE.set(ClientConfig.RADAR_MARKER_STYLE.get().next()));
        this.doubleSlider("screen.m4cne.setting.radar_icon_scale", ClientConfig.RADAR_ICON_SCALE,
                0.25D, 3.0D, 0.05D, MapSettingsPanel::percent);
        this.intField("screen.m4cne.setting.radar_max_markers", ClientConfig.RADAR_MAX_MARKERS, 100, 8000);
        this.intField("screen.m4cne.setting.radar_sample_interval", ClientConfig.RADAR_SAMPLE_INTERVAL_MS, 100, 5000);

        this.section("screen.m4cne.section.radar_targets");
        this.toggle("screen.m4cne.setting.radar_players", ClientConfig.RADAR_PLAYERS);
        this.toggle("screen.m4cne.setting.radar_hostile", ClientConfig.RADAR_HOSTILE);
        this.toggle("screen.m4cne.setting.radar_passive", ClientConfig.RADAR_PASSIVE);
        this.toggle("screen.m4cne.setting.radar_items", ClientConfig.RADAR_ITEMS);
        this.toggle("screen.m4cne.setting.radar_click_info", ClientConfig.RADAR_CLICK_INFO);

        this.section("screen.m4cne.section.radar_render");
        this.doubleSlider("screen.m4cne.setting.radar_scale", ClientConfig.RADAR_MARKER_SCALE,
                0.5D, 2.0D, 0.05D, MapSettingsPanel::percent);
        this.toggle("screen.m4cne.setting.radar_counts", ClientConfig.RADAR_SHOW_COUNTS);
        this.label(Component.translatable("screen.m4cne.label.radar_hint"));
    }

    private void buildWaypoints(int entryWidth) {
        WaypointStore store = WaypointStore.get();
        this.section("screen.m4cne.section.waypoint_manager");
        this.label(Component.translatable("screen.m4cne.label.waypoint_count", store.count()));
        this.label(Component.translatable("screen.m4cne.label.waypoint_trash_count", store.trashCount()));
        this.addButton(Component.translatable("screen.m4cne.action.waypoint_manage"), entryWidth,
                () -> this.open(MapSettingsPage.WAYPOINT_MANAGE),
                "screen.m4cne.action.waypoint_manage.tooltip");
        this.addButton(Component.translatable("screen.m4cne.action.waypoint_trash"), entryWidth,
                () -> this.open(MapSettingsPage.WAYPOINT_TRASH),
                "screen.m4cne.action.waypoint_trash.tooltip");
        this.addButton(Component.translatable("screen.m4cne.action.structure_scan"), entryWidth,
                () -> this.open(MapSettingsPage.STRUCTURE_SCAN),
                "screen.m4cne.action.structure_scan.tooltip");

        this.section("screen.m4cne.section.waypoint_display");
        this.toggle("screen.m4cne.setting.waypoint_show", ClientConfig.WAYPOINT_SHOW);
        this.toggle("screen.m4cne.setting.waypoint_names", ClientConfig.WAYPOINT_SHOW_NAMES);
        this.doubleSlider("screen.m4cne.setting.waypoint_scale", ClientConfig.WAYPOINT_MARKER_SCALE,
                0.5D, 2.0D, 0.05D, MapSettingsPanel::percent);
        this.label(Component.translatable("screen.m4cne.label.waypoint_hint"));
    }

    /**
     * Third level "waypoint management" page: a selectable list of the current
     * dimension plus the actions for the selected waypoint.
     */
    private void buildWaypointManage(int entryWidth) {
        WaypointStore store = WaypointStore.get();
        this.section("screen.m4cne.section.waypoint_manage_list");
        List<WaypointStore.Waypoint> list = this.visibleWaypoints();
        if (list.isEmpty()) {
            this.label(Component.translatable("screen.m4cne.label.waypoint_none"));
        } else {
            this.label(Component.translatable("screen.m4cne.label.waypoint_select_hint"));
            for (WaypointStore.Waypoint waypoint : list) {
                boolean selected = waypoint.id().equals(this.selectedWaypointId);
                Component tooltip = Component.translatable("screen.m4cne.waypoint.row_tooltip",
                        Component.translatable(waypoint.category().translationKey()),
                        waypoint.x(), waypoint.y(), waypoint.z(),
                        Component.translatable(waypoint.sync() ? "screen.m4cne.state.enabled"
                                : "screen.m4cne.state.disabled"));
                this.selectRow(entryWidth, this.waypointRowLabel(waypoint, selected), tooltip, () -> {
                    this.selectedWaypointId = waypoint.id();
                    this.screen.requestSidebarRebuild();
                });
            }
        }

        this.section("screen.m4cne.section.waypoint_manage_actions");
        this.addButton(Component.translatable("screen.m4cne.action.waypoint_new"), entryWidth,
                () -> this.screen.openWaypointEditorAtPlayer(),
                "screen.m4cne.action.waypoint_new.tooltip");

        WaypointStore.Waypoint selected = this.selectedWaypoint();
        boolean hasSelection = selected != null;
        this.addButton(Component.translatable("screen.m4cne.action.waypoint_delete"), entryWidth,
                hasSelection, () -> {
                    WaypointStore.Waypoint target = this.selectedWaypoint();
                    if (target == null) {
                        return;
                    }
                    store.moveToTrash(target.id());
                    this.selectedWaypointId = "";
                    this.screen.markMapDirty();
                    this.screen.showStatus(Component.translatable(
                            "screen.m4cne.status.waypoint_trashed", target.name()));
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.action.waypoint_delete.tooltip");

        this.addButton(Component.translatable("screen.m4cne.action.waypoint_teleport"), entryWidth,
                hasSelection, () -> {
                    WaypointStore.Waypoint target = this.selectedWaypoint();
                    if (target != null) {
                        this.screen.teleportToWaypoint(target);
                    }
                }, "screen.m4cne.action.waypoint_teleport.tooltip");

        this.addButton(Component.translatable("screen.m4cne.action.waypoint_edit"), entryWidth,
                hasSelection, () -> {
                    WaypointStore.Waypoint target = this.selectedWaypoint();
                    if (target != null) {
                        this.screen.openWaypointEditor(target);
                    }
                }, "screen.m4cne.action.waypoint_edit.tooltip");

        this.addButton(Component.translatable("screen.m4cne.action.waypoint_sync_selected"), entryWidth,
                hasSelection, () -> {
                    WaypointStore.Waypoint target = this.selectedWaypoint();
                    if (target == null) {
                        return;
                    }
                    boolean sent = WaypointSyncManager.get().syncWaypoint(target);
                    this.screen.showStatus(sent
                            ? Component.translatable("screen.m4cne.status.waypoint_sync_requested",
                                    target.name())
                            : Component.translatable("screen.m4cne.status.waypoint_sync_unavailable"));
                }, "screen.m4cne.action.waypoint_sync_selected.tooltip");

        this.addButton(Component.translatable("screen.m4cne.action.waypoint_toggle_sync",
                Component.translatable(!hasSelection || selected.sync()
                        ? "screen.m4cne.state.enabled"
                        : "screen.m4cne.state.disabled")),
                entryWidth, hasSelection, () -> {
                    WaypointStore.Waypoint target = this.selectedWaypoint();
                    if (target != null) {
                        store.setSync(target.id(), !target.sync());
                        this.screen.requestSidebarRebuild();
                    }
                }, "screen.m4cne.action.waypoint_toggle_sync.tooltip");

        this.section("screen.m4cne.section.waypoint_sync");
        this.intField("screen.m4cne.setting.waypoint_sync_interval",
                ClientConfig.WAYPOINT_SYNC_INTERVAL_SECONDS, 30, 86400);
        if (WaypointSyncManager.get().isRunning()) {
            this.label(Component.translatable("screen.m4cne.label.waypoint_sync_active"));
        } else {
            this.label(Component.translatable("screen.m4cne.label.waypoint_sync_status",
                    WaypointSyncManager.get().secondsUntilNextSync()));
        }
        this.label(Component.translatable("screen.m4cne.label.waypoint_sync_hint"));
    }

    /**
     * Third level recycle bin page. Deleted waypoints keep their countdown and
     * are purged automatically once the timer elapses.
     */
    private void buildWaypointTrash(int entryWidth) {
        WaypointStore store = WaypointStore.get();
        this.section("screen.m4cne.section.waypoint_trash_list");
        List<WaypointStore.TrashEntry> entries = store.trash();
        if (entries.isEmpty()) {
            this.label(Component.translatable("screen.m4cne.label.waypoint_trash_empty"));
        } else {
            this.label(Component.translatable("screen.m4cne.label.waypoint_select_hint"));
            for (WaypointStore.TrashEntry entry : entries) {
                WaypointStore.Waypoint waypoint = entry.waypoint();
                boolean selected = waypoint.id().equals(this.selectedTrashId);
                long remaining = store.trashRemainingSeconds(entry);
                Component label = Component.empty()
                        .append(Component.literal(selected ? "\u25B6 " : "\u25CB "))
                        .append(Component.literal(waypoint.name())
                                .withStyle(style -> style.withColor(waypoint.color())))
                        .append(Component.literal(" "))
                        .append(Component.translatable("screen.m4cne.waypoint.trash_remaining", remaining)
                                .withStyle(ChatFormatting.GRAY));
                Component tooltip = Component.translatable("screen.m4cne.waypoint.trash_tooltip",
                        waypoint.x(), waypoint.y(), waypoint.z(), remaining);
                this.selectRow(entryWidth, label, tooltip, () -> {
                    this.selectedTrashId = waypoint.id();
                    this.screen.requestSidebarRebuild();
                });
            }
        }

        this.section("screen.m4cne.section.waypoint_trash_actions");
        boolean hasSelection = this.selectedTrashEntry() != null;
        this.addButton(Component.translatable("screen.m4cne.action.trash_restore"), entryWidth,
                hasSelection, () -> {
                    WaypointStore.TrashEntry target = this.selectedTrashEntry();
                    if (target == null) {
                        return;
                    }
                    store.restore(target.waypoint().id());
                    this.selectedTrashId = "";
                    this.screen.markMapDirty();
                    this.screen.showStatus(Component.translatable(
                            "screen.m4cne.status.waypoint_restored", target.waypoint().name()));
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.action.trash_restore.tooltip");

        this.addButton(Component.translatable("screen.m4cne.action.trash_purge"), entryWidth,
                hasSelection, () -> {
                    WaypointStore.TrashEntry target = this.selectedTrashEntry();
                    if (target == null) {
                        return;
                    }
                    store.purge(target.waypoint().id());
                    this.selectedTrashId = "";
                    this.screen.showStatus(Component.translatable(
                            "screen.m4cne.status.waypoint_purged"));
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.action.trash_purge.tooltip");

        this.addButton(Component.translatable("screen.m4cne.action.trash_clear"), entryWidth,
                !entries.isEmpty(), () -> {
                    int removed = store.clearTrash();
                    this.selectedTrashId = "";
                    this.screen.showStatus(Component.translatable(
                            "screen.m4cne.status.waypoint_trash_cleared", removed));
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.action.trash_clear.tooltip");

        this.section("screen.m4cne.section.waypoint_trash_timer");
        this.intField("screen.m4cne.setting.waypoint_trash_auto_clear",
                ClientConfig.WAYPOINT_TRASH_AUTO_CLEAR_SECONDS, 10, 86400);
        this.label(Component.translatable("screen.m4cne.label.waypoint_trash_note"));
        if (entries.isEmpty()) {
            this.label(Component.translatable("screen.m4cne.label.waypoint_trash_paused"));
        }
    }

    /**
     * Third level "structure scan" page. The text field either filters the
     * quick list or is resolved by name, then the server searches the nearest
     * structure through the seed based structure placement.
     */
    private void buildStructureScan(int entryWidth) {
        StructureScanController controller = StructureScanController.get();
        this.section("screen.m4cne.section.structure_scan");
        this.label(Component.translatable("screen.m4cne.label.structure_scan_hint"));
        this.structureQueryField(entryWidth);
        this.addButton(Component.translatable("screen.m4cne.action.structure_scan_text"), entryWidth,
                !controller.isPending(), this::scanTypedStructure,
                "screen.m4cne.action.structure_scan_text.tooltip");

        this.structureScanStatus(controller);

        this.section("screen.m4cne.section.structure_list");
        List<StructureScanCatalog.Entry> entries = StructureScanCatalog
                .forDimension(this.currentDimension());
        if (entries.isEmpty()) {
            this.label(Component.translatable("screen.m4cne.label.structure_none"));
        } else {
            for (StructureScanCatalog.Entry entry : entries) {
                Component label = Component.translatable("screen.m4cne.structure.row",
                        Component.translatable(entry.translationKey()),
                        Component.translatable(entry.category().translationKey()));
                this.addButton(label, entryWidth, !controller.isPending(), () -> {
                    StructureScanController.get().scan(entry);
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.action.structure_scan_entry.tooltip");
            }
        }
        this.label(Component.translatable("screen.m4cne.label.structure_scan_note"));
    }

    /** Resolves the text field and starts the scan; also used by the Enter key. */
    public void scanTypedStructure() {
        if (this.structureQuery.isBlank()) {
            this.screen.showStatus(Component.translatable(
                    "screen.m4cne.status.structure_name_required"));
            return;
        }
        StructureScanCatalog.Entry entry = StructureScanCatalog.search(this.structureQuery,
                this.currentDimension());
        if (entry == null) {
            this.screen.showStatus(Component.translatable(
                    "screen.m4cne.status.structure_name_unknown", this.structureQuery));
            return;
        }
        StructureScanController.get().scan(entry);
        this.screen.requestSidebarRebuild();
    }

    private void structureQueryField(int entryWidth) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            EditBox field = new EditBox(Minecraft.getInstance().font, this.x + 6, widgetY, entryWidth,
                    ENTRY_HEIGHT, Component.translatable("screen.m4cne.structure.search"));
            field.setMaxLength(48);
            field.setHint(Component.translatable("screen.m4cne.structure.search_hint"));
            field.setValue(this.structureQuery);
            field.setResponder(value -> this.structureQuery = value);
            field.setTooltip(Tooltip.create(
                    Component.translatable("screen.m4cne.structure.search.tooltip")));
            this.screen.addSidebarWidget(field);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    /** Draws the pending / result lines of the structure scan page. */
    private void structureScanStatus(StructureScanController controller) {
        if (!controller.isSupported()) {
            this.label(Component.translatable("screen.m4cne.label.structure_scan_unsupported"));
            return;
        }
        if (controller.isPending()) {
            StructureScanCatalog.Entry entry = StructureScanCatalog.byId(controller.pendingId());
            this.label(entry != null
                    ? Component.translatable("screen.m4cne.label.structure_scan_pending",
                            Component.translatable(entry.translationKey()))
                    : Component.translatable("screen.m4cne.label.structure_scan_pending_unknown"));
            return;
        }
        StructureScanResultPayload result = controller.result();
        if (result == null) {
            return;
        }
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null || !result.dimension().equals(dimension)) {
            return;
        }
        StructureScanCatalog.Entry entry = StructureScanCatalog.byId(result.structureId());
        Component name = entry != null ? Component.translatable(entry.translationKey())
                : Component.literal(result.structureId());
        if (result.found()) {
            this.label(Component.translatable("screen.m4cne.label.structure_scan_found", name,
                    result.x(), result.y(), result.z(), result.distance()));
        } else {
            this.label(Component.translatable("screen.m4cne.label.structure_scan_not_found", name));
        }
    }

    /** Waypoints of the dimension currently displayed on the map. */
    private List<WaypointStore.Waypoint> visibleWaypoints() {
        ResourceLocation dimension = this.currentDimension();
        return dimension != null ? WaypointStore.get().forDimension(dimension)
                : WaypointStore.get().all();
    }

    private WaypointStore.Waypoint selectedWaypoint() {
        WaypointStore.Waypoint waypoint = WaypointStore.get().get(this.selectedWaypointId);
        ResourceLocation dimension = this.currentDimension();
        if (waypoint == null || (dimension != null && !waypoint.dimensionId().equals(dimension))) {
            return null;
        }
        return waypoint;
    }

    private WaypointStore.TrashEntry selectedTrashEntry() {
        for (WaypointStore.TrashEntry entry : WaypointStore.get().trash()) {
            if (entry.waypoint().id().equals(this.selectedTrashId)) {
                return entry;
            }
        }
        return null;
    }

    /** One selectable list row with a coloured waypoint name. */
    private Component waypointRowLabel(WaypointStore.Waypoint waypoint, boolean selected) {
        Component label = Component.empty()
                .append(Component.literal(selected ? "\u25B6 " : "\u25CB "))
                .append(Component.literal(waypoint.name())
                        .withStyle(style -> style.withColor(waypoint.color())))
                .append(Component.literal(" (" + waypoint.x() + ", " + waypoint.z() + ")"));
        if (!waypoint.sync()) {
            label = label.copy().append(Component
                    .translatable("screen.m4cne.waypoint.row_no_sync")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return label;
    }

    private void selectRow(int entryWidth, Component label, Component tooltip, Runnable action) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            Button button = Button.builder(label, ignored -> action.run())
                    .bounds(this.x + 6, widgetY, entryWidth, ENTRY_HEIGHT)
                    .build();
            if (this.lockedTooltipKey != null) {
                button.active = false;
                tip(button, this.lockedTooltipKey);
            } else {
                button.setTooltip(Tooltip.create(tooltip));
            }
            this.screen.addSidebarWidget(button);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    /**
     * Second level "chunk claim management" page.
     * <p>
     * It combines the overlay switches, the box selection helpers, the 64 colour
     * palette of the own claims and the quick actions that also appear in the
     * right click menu of the map canvas.
     */
    private void buildClaims(int entryWidth) {
        ClaimStore store = ClaimStore.get();
        ClaimSyncManager sync = ClaimSyncManager.get();
        ClaimStore.Owner self = store.self();
        int own = self != null ? self.count() : 0;
        int selected = this.screen.claimSelectionSize();

        this.section("screen.m4cne.section.claims_state");
        this.label(Component.translatable("screen.m4cne.claims.state_line", own, sync.maxClaims()));
        this.label(Component.translatable("screen.m4cne.claims.selected_line", selected));
        this.label(Component.translatable("screen.m4cne.claims.scan_line", sync.scanIntervalSeconds()));
        if (!sync.supported()) {
            this.label(Component.translatable("screen.m4cne.claims.unsupported"));
        }
        if (sync.isAdmin()) {
            this.label(Component.translatable("screen.m4cne.claims.admin_mode"));
        }
        if (sync.isRunning()) {
            this.label(Component.translatable("screen.m4cne.claims.syncing"));
        }

        this.section("screen.m4cne.section.claims_display");
        this.toggle("screen.m4cne.setting.claims_show", ClientConfig.CLAIM_SHOW);
        this.toggle("screen.m4cne.setting.claims_others", ClientConfig.CLAIM_SHOW_OTHERS);
        this.toggle("screen.m4cne.setting.claims_force", ClientConfig.CLAIM_SHOW_FORCE_LOAD);
        this.toggle("screen.m4cne.setting.claims_notify", ClientConfig.CLAIM_NOTIFY);
        this.toggle("screen.m4cne.setting.claims_left_drag", ClientConfig.CLAIM_SELECT_LEFT_DRAG);
        this.doubleSlider("screen.m4cne.setting.claims_opacity", ClientConfig.CLAIM_OPACITY,
                0.0D, 1.0D, 0.05D, MapSettingsPanel::percent);
        boolean boundary = self != null && self.boundary();
        this.addButton(Component.translatable("screen.m4cne.claims.action.boundary",
                Component.translatable(boundary ? "screen.m4cne.state.enabled" : "screen.m4cne.state.disabled")),
                entryWidth, () -> this.screen.toggleOwnClaimBoundary(), "screen.m4cne.claims.action.boundary.tooltip");

        this.section("screen.m4cne.section.claims_select");
        this.label(Component.translatable("screen.m4cne.claims.select_hint"));
        this.addButton(Component.translatable("screen.m4cne.claims.action.select_view"), entryWidth,
                () -> this.screen.selectVisibleChunks(), "screen.m4cne.claims.action.select_view.tooltip");
        this.addButton(Component.translatable("screen.m4cne.claims.action.select_player"), entryWidth,
                () -> this.screen.selectPlayerChunks(), "screen.m4cne.claims.action.select_player.tooltip");
        this.addButton(Component.translatable("screen.m4cne.claims.action.clear_selection"), entryWidth,
                () -> this.screen.clearClaimSelection(), "screen.m4cne.claims.action.clear_selection.tooltip");

        this.section("screen.m4cne.section.claims_color");
        this.label(Component.translatable("screen.m4cne.claims.color_hint"));
        this.paletteGrid(entryWidth);

        this.section("screen.m4cne.section.claims_actions");
        boolean hasSelection = selected > 0;
        this.addButton(Component.translatable("screen.m4cne.claims.action.claim"), entryWidth, hasSelection,
                () -> this.screen.claimSelectedChunks(), "screen.m4cne.claims.action.claim.tooltip");
        this.addButton(Component.translatable("screen.m4cne.claims.action.unclaim"), entryWidth, hasSelection,
                () -> this.screen.unclaimSelectedChunks(), "screen.m4cne.claims.action.unclaim.tooltip");
        this.addButton(Component.translatable("screen.m4cne.claims.action.force"), entryWidth, hasSelection,
                () -> this.screen.forceLoadSelectedChunks(), "screen.m4cne.claims.action.force.tooltip");
        this.addButton(Component.translatable("screen.m4cne.claims.action.unforce"), entryWidth, hasSelection,
                () -> this.screen.clearSelectedForceLoad(), "screen.m4cne.claims.action.unforce.tooltip");
        this.addButton(Component.translatable("screen.m4cne.claims.action.clear_dimension"), entryWidth,
                own > 0, () -> this.screen.clearOwnClaimsInDimension(),
                "screen.m4cne.claims.action.clear_dimension.tooltip");

        this.section("screen.m4cne.section.claims_server");
        this.addButton(Component.translatable("screen.m4cne.claims.action.sync_now"), entryWidth,
                () -> this.screen.syncClaimsNow(), "screen.m4cne.claims.action.sync_now.tooltip");
        this.addButton(Component.translatable("screen.m4cne.claims.action.players"), entryWidth,
                () -> this.open(MapSettingsPage.CLAIM_CHUNKS), "screen.m4cne.claims.action.players.tooltip");
        this.addButton(Component.translatable("screen.m4cne.claims.action.entity_rules"), entryWidth,
                () -> this.open(MapSettingsPage.CLAIM_ENTITY_RULES),
                "screen.m4cne.claims.action.entity_rules.tooltip");
        if (sync.isAdmin()) {
            this.addButton(Component.translatable("screen.m4cne.claims.action.scan"), entryWidth,
                    () -> this.screen.requestClaimScan(), "screen.m4cne.claims.action.scan.tooltip");
            this.claimIntervalField(entryWidth);
            this.addButton(Component.translatable("screen.m4cne.claims.action.interval_apply"), entryWidth,
                    () -> this.applyClaimInterval(),
                    "screen.m4cne.claims.action.interval_apply.tooltip");
        }
    }

    /**
     * Entity control page of the chunk claim management.
     * <p>
     * The owner wide switches decide whether the feature is active at all, which
     * spawns, aggression and boundary crossings are allowed for the claimed
     * chunks, and whether force loaded chunks may spawn mobs. Below them the
     * population limit list keeps one editable rule per mob type (the old single
     * force load cap); the per mob rule list then exposes the spawn / aggro
     * flags of every rule and the quick list adds any mob the game knows about.
     */
    private void buildClaimEntityRules(int entryWidth) {
        ClaimStore store = ClaimStore.get();
        ClaimSyncManager sync = ClaimSyncManager.get();
        if (!sync.isAdmin()) {
            // Ordinary players only edit their own rules; the player list
            // selection is an administrator tool.
            this.selectedClaimOwnerId = store.selfId();
        }
        ClaimStore.Owner target = this.entityRuleTarget();
        if (target == null) {
            this.label(Component.translatable("screen.m4cne.claims.entity_no_target"));
            return;
        }
        boolean mayEdit = sync.isAdmin() || target.id().equals(store.selfId());
        if (!mayEdit) {
            this.lockedTooltipKey = "screen.m4cne.claims.entity_no_permission";
        }
        if (!sync.entityControlSupported()) {
            this.lockedTooltipKey = "screen.m4cne.claims.entity_disabled.tooltip";
        }
        EntityRuleSet rules = target.entityRules();

        this.section("screen.m4cne.section.claims_entity_target");
        this.label(Component.translatable("screen.m4cne.claims.entity_target", target.name(),
                rules.rules().size()));
        if (!sync.entityControlSupported()) {
            this.label(Component.translatable("screen.m4cne.claims.entity_disabled"));
        }
        if (sync.isAdmin()) {
            this.addButton(Component.translatable("screen.m4cne.claims.entity_switch_target"), entryWidth,
                    () -> this.cycleEntityRuleTarget(),
                    "screen.m4cne.claims.entity_switch_target.tooltip");
        }
        if (!sync.supported()) {
            this.label(Component.translatable("screen.m4cne.claims.unsupported"));
        } else if (!sync.available()) {
            this.label(Component.translatable("screen.m4cne.claims.entity_waiting"));
        }

        this.section("screen.m4cne.section.claims_entity_global");
        this.ruleToggle("screen.m4cne.claims.entity_enabled", rules.enabled(),
                value -> this.applyEntityRules(target, rules.withEnabled(value)), entryWidth);
        this.ruleToggle("screen.m4cne.claims.entity_allow_spawn", rules.allowSpawn(),
                value -> this.applyEntityRules(target, rules.withAllowSpawn(value)), entryWidth);
        this.ruleToggle("screen.m4cne.claims.entity_allow_aggro", rules.allowAggro(),
                value -> this.applyEntityRules(target, rules.withAllowAggro(value)), entryWidth);
        this.ruleToggle("screen.m4cne.claims.entity_allow_enter", rules.allowEnter(),
                value -> this.applyEntityRules(target, rules.withAllowEnter(value)), entryWidth);
        this.ruleToggle("screen.m4cne.claims.entity_allow_leave", rules.allowLeave(),
                value -> this.applyEntityRules(target, rules.withAllowLeave(value)), entryWidth);

        this.section("screen.m4cne.section.claims_entity_force");
        this.label(Component.translatable("screen.m4cne.claims.entity_force_hint"));
        this.ruleToggle("screen.m4cne.claims.entity_force_spawn", rules.forceLoadAllowSpawn(),
                value -> this.applyEntityRules(target, rules.withForceLoadAllowSpawn(value)), entryWidth);

        // Population limit list: one rule per mob type, e.g. minecraft:zombie
        // with a limit of 3 keeps at most three zombies inside every claimed
        // chunk and closes the spawn mechanism of the chunk while it is full.
        this.section("screen.m4cne.section.claims_entity_limits");
        this.label(Component.translatable("screen.m4cne.claims.entity_limits_hint"));
        List<EntityRule> limited = rules.limitedRules();
        if (limited.isEmpty()) {
            this.label(Component.translatable("screen.m4cne.claims.entity_limits_empty"));
        }
        for (EntityRule rule : limited) {
            boolean expanded = rule.type().equals(this.expandedLimitRule);
            Component name = entityName(rule.type());
            Component row = Component.empty()
                    .append(Component.literal(expanded ? "\u25BC " : "\u25B6 ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(name)
                    .append(Component.literal("  " + rule.type()).withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("  \u2264 " + rule.maxCount())
                            .withStyle(ChatFormatting.GOLD));
            this.selectRow(entryWidth, row,
                    Component.translatable("screen.m4cne.claims.entity_limit_row_tooltip", name,
                            rule.maxCount()),
                    () -> {
                        this.expandedLimitRule = expanded ? "" : rule.type();
                        this.screen.requestSidebarRebuild();
                    });
            if (!expanded) {
                continue;
            }
            this.ruleNumberField("screen.m4cne.claims.entity_limit_count", 1, EntityRule.MAX_COUNT,
                    rule.maxCount(),
                    value -> this.applyEntityRules(target, rules.withRule(rule.withMaxCount(value))),
                    entryWidth);
            this.addButton(Component.translatable("screen.m4cne.claims.entity_limit_remove"), entryWidth,
                    () -> {
                        this.expandedLimitRule = "";
                        this.applyEntityRules(target, rules.withLimit(rule.type(), 0));
                    }, "screen.m4cne.claims.entity_limit_remove.tooltip");
        }
        this.label(Component.translatable("screen.m4cne.claims.entity_limits_add_hint"));
        this.mobCatalog(entryWidth, true, rules, target);

        this.section("screen.m4cne.section.claims_entity_rules");
        if (rules.rules().isEmpty()) {
            this.label(Component.translatable("screen.m4cne.claims.entity_rules_empty"));
        }
        for (EntityRule rule : rules.rules()) {
            boolean expanded = rule.type().equals(this.expandedEntityRule);
            Component name = entityName(rule.type());
            Component row = Component.empty()
                    .append(Component.literal(expanded ? "\u25BC " : "\u25B6 ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(name)
                    .append(Component.literal("  " + rule.type()).withStyle(ChatFormatting.DARK_GRAY));
            Component tooltip = Component.translatable("screen.m4cne.claims.entity_rule_tooltip",
                    name,
                    rule.maxCount() <= 0
                            ? Component.translatable("screen.m4cne.claims.entity_unlimited")
                            : Component.literal(Integer.toString(rule.maxCount())),
                    state(rule.allowSpawn()), state(rule.allowAggro()));
            this.selectRow(entryWidth, row, tooltip, () -> {
                this.expandedEntityRule = expanded ? "" : rule.type();
                this.screen.requestSidebarRebuild();
            });
            if (!expanded) {
                continue;
            }
            this.ruleToggle("screen.m4cne.claims.entity_rule_allow_spawn", rule.allowSpawn(),
                    value -> this.applyEntityRules(target, rules.withRule(rule.withAllowSpawn(value))),
                    entryWidth);
            this.ruleToggle("screen.m4cne.claims.entity_rule_allow_aggro", rule.allowAggro(),
                    value -> this.applyEntityRules(target, rules.withRule(rule.withAllowAggro(value))),
                    entryWidth);
            this.addButton(Component.translatable("screen.m4cne.claims.entity_rule_remove"), entryWidth,
                    () -> {
                        this.expandedEntityRule = "";
                        this.applyEntityRules(target, rules.withoutRule(rule.type()));
                    }, "screen.m4cne.claims.entity_rule_remove.tooltip");
        }

        this.section("screen.m4cne.section.claims_entity_catalog");
        this.label(Component.translatable("screen.m4cne.claims.entity_catalog_hint"));
        this.mobCatalog(entryWidth, false, rules, target);
    }

    /**
     * Owner whose entity rules the page edits: the selected player or the local
     * player.
     */
    private ClaimStore.Owner entityRuleTarget() {
        ClaimStore store = ClaimStore.get();
        ClaimStore.Owner target = store.owner(this.selectedClaimOwnerId);
        return target != null ? target : store.selfOrCreate();
    }

    /**
     * Switches the edited owner to the next player with claims (administrators
     * only).
     */
    private void cycleEntityRuleTarget() {
        ClaimStore store = ClaimStore.get();
        List<ClaimStore.Owner> owners = store.sortedOwners();
        if (owners.isEmpty()) {
            return;
        }
        int next = 0;
        for (int index = 0; index < owners.size(); index++) {
            if (owners.get(index).id().equals(this.selectedClaimOwnerId)) {
                next = (index + 1) % owners.size();
                break;
            }
        }
        this.selectedClaimOwnerId = owners.get(next).id();
        this.expandedEntityRule = "";
        this.expandedLimitRule = "";
        this.screen.requestSidebarRebuild();
    }

    /** Applies one rule set locally and uploads it to the server. */
    private void applyEntityRules(ClaimStore.Owner owner, EntityRuleSet rules) {
        if (owner == null || rules == null) {
            return;
        }
        ClaimStore.get().setOwnerRules(owner.id(), rules);
        boolean sent = ClaimSyncManager.get().setEntityRules(owner.id(), rules);
        if (!sent) {
            this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.unavailable"));
        }
        this.screen.requestSidebarRebuild();
    }

    /** On/off button bound to one entity rule flag. */
    private void ruleToggle(String labelKey, boolean value, Consumer<Boolean> setter, int entryWidth) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            MapToggleButton button = new MapToggleButton(
                    this.x + 6, widgetY, entryWidth, ENTRY_HEIGHT,
                    Component.translatable(labelKey), () -> value,
                    next -> setter.accept(next), NO_MAP_CHANGE);
            this.applyLock(button, labelKey + ".tooltip");
            this.screen.addSidebarWidget(button);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    /** Numeric input bound to one entity rule field. */
    private void ruleNumberField(String labelKey, int minimum, int maximum, int initial,
            IntConsumer setter, int entryWidth) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            MapNumberField field = new MapNumberField(
                    this.x + 6, widgetY, entryWidth, ENTRY_HEIGHT,
                    Component.translatable(labelKey), minimum, maximum, initial,
                    setter, NO_MAP_CHANGE);
            if (this.lockedTooltipKey != null) {
                field.active = false;
                tip(field, this.lockedTooltipKey);
            } else {
                tip(field, labelKey + ".tooltip", "screen.m4cne.input.enter_confirm");
            }
            this.screen.addSidebarWidget(field);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    /** Filter input of one mob quick list; the apply button commits the keyword. */
    private void mobCatalogFilterField(int entryWidth, boolean limitMode) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            EditBox field = new EditBox(Minecraft.getInstance().font, this.x + 6, widgetY, entryWidth,
                    ENTRY_HEIGHT, Component.translatable("screen.m4cne.claims.entity_catalog_filter"));
            field.setMaxLength(64);
            field.setHint(Component.translatable("screen.m4cne.claims.entity_catalog_filter_hint"));
            field.setValue(limitMode ? this.limitRuleFilter : this.entityRuleFilter);
            field.setResponder(value -> {
                if (limitMode) {
                    this.limitRuleFilter = value;
                } else {
                    this.entityRuleFilter = value;
                }
            });
            if (this.lockedTooltipKey != null) {
                field.active = false;
                tip(field, this.lockedTooltipKey);
            } else {
                tip(field, "screen.m4cne.claims.entity_catalog_filter.tooltip");
            }
            this.screen.addSidebarWidget(field);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    /**
     * Quick list of every registered mob, shared by the population limit list
     * and the per mob spawn / aggro rule list.
     * <p>
     * In limit mode a click appends the mob to the population limit list (or
     * raises the limit back to the default), otherwise a plain rule is added.
     */
    private void mobCatalog(int entryWidth, boolean limitMode, EntityRuleSet rules,
            ClaimStore.Owner target) {
        this.mobCatalogFilterField(entryWidth, limitMode);
        this.addButton(Component.translatable("screen.m4cne.claims.entity_catalog_apply"), entryWidth,
                () -> {
                    if (limitMode) {
                        this.limitRulePage = 0;
                    } else {
                        this.entityRulePage = 0;
                    }
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.claims.entity_catalog_apply.tooltip");
        List<EntityRuleCatalog.Entry> entries = EntityRuleCatalog
                .filtered(limitMode ? this.limitRuleFilter : this.entityRuleFilter);
        int pages = Math.max(1,
                (entries.size() + ENTITY_RULE_PAGE_SIZE - 1) / ENTITY_RULE_PAGE_SIZE);
        int page = Mth.clamp(limitMode ? this.limitRulePage : this.entityRulePage, 0, pages - 1);
        if (limitMode) {
            this.limitRulePage = page;
        } else {
            this.entityRulePage = page;
        }
        this.label(Component.translatable("screen.m4cne.claims.entity_catalog_page", page + 1, pages,
                entries.size()));
        this.addButton(Component.translatable("screen.m4cne.claims.entity_catalog_prev"), entryWidth,
                page > 0, () -> {
                    if (limitMode) {
                        this.limitRulePage = page - 1;
                    } else {
                        this.entityRulePage = page - 1;
                    }
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.claims.entity_catalog_prev.tooltip");
        this.addButton(Component.translatable("screen.m4cne.claims.entity_catalog_next"), entryWidth,
                page + 1 < pages, () -> {
                    if (limitMode) {
                        this.limitRulePage = page + 1;
                    } else {
                        this.entityRulePage = page + 1;
                    }
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.claims.entity_catalog_next.tooltip");
        if (entries.isEmpty()) {
            this.label(Component.translatable("screen.m4cne.claims.entity_catalog_empty"));
        }
        int start = page * ENTITY_RULE_PAGE_SIZE;
        int end = Math.min(start + ENTITY_RULE_PAGE_SIZE, entries.size());
        for (int index = start; index < end; index++) {
            EntityRuleCatalog.Entry entry = entries.get(index);
            if (limitMode) {
                EntityRule existing = rules.rule(entry.id());
                boolean limitedRow = existing != null && existing.hasLimit();
                this.addButton(Component.translatable(
                        limitedRow ? "screen.m4cne.claims.entity_limit_added"
                                : "screen.m4cne.claims.entity_limit_add",
                        entry.name()), entryWidth, !limitedRow, () -> {
                            this.expandedLimitRule = entry.id();
                            this.applyEntityRules(target,
                                    rules.withLimit(entry.id(), EntityRule.DEFAULT_LIMIT));
                        }, limitedRow ? "screen.m4cne.claims.entity_limit_added.tooltip"
                                : "screen.m4cne.claims.entity_limit_add.tooltip");
                continue;
            }
            boolean added = rules.has(entry.id());
            this.addButton(Component.translatable(
                    added ? "screen.m4cne.claims.entity_catalog_added"
                            : "screen.m4cne.claims.entity_catalog_add",
                    entry.name()), entryWidth, !added, () -> {
                        this.expandedEntityRule = entry.id();
                        this.applyEntityRules(target,
                                rules.withRule(EntityRule.defaults(entry.id())));
                    }, added ? "screen.m4cne.claims.entity_catalog_added.tooltip"
                            : "screen.m4cne.claims.entity_catalog_add.tooltip");
        }
    }

    private static Component entityName(String type) {
        ResourceLocation id = ResourceLocation.tryParse(type);
        EntityType<?> entityType = id == null ? null : BuiltInRegistries.ENTITY_TYPE.get(id);
        return entityType == null ? Component.literal(type) : entityType.getDescription();
    }

    private static Component state(boolean value) {
        return Component.translatable(value ? "screen.m4cne.state.enabled" : "screen.m4cne.state.disabled");
    }

    /** Editable scan interval of the server; administrators only. */
    private void claimIntervalField(int entryWidth) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            EditBox field = new EditBox(Minecraft.getInstance().font, this.x + 6, widgetY, entryWidth,
                    ENTRY_HEIGHT, Component.translatable("screen.m4cne.claims.interval"));
            field.setMaxLength(6);
            field.setHint(Component.translatable("screen.m4cne.claims.interval_hint",
                    ClaimSyncManager.get().scanIntervalSeconds()));
            field.setValue(this.claimInterval);
            field.setResponder(value -> this.claimInterval = value);
            field.setTooltip(Tooltip.create(
                    Component.translatable("screen.m4cne.claims.interval.tooltip")));
            this.screen.addSidebarWidget(field);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    private void applyClaimInterval() {
        try {
            int seconds = Integer.parseInt(this.claimInterval.trim());
            if (ClaimSyncManager.get().setScanInterval(seconds)) {
                this.screen.showStatus(Component.translatable(
                        "screen.m4cne.status.claims.interval_set", Math.max(30, seconds)));
            } else {
                this.screen.showStatus(Component.translatable(
                        "screen.m4cne.status.claims.unavailable"));
            }
        } catch (NumberFormatException exception) {
            this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.interval_invalid"));
        }
    }

    /** 8x8 palette of the claim colours. */
    private void paletteGrid(int entryWidth) {
        int cell = Math.max(10, Math.min(18, (entryWidth - 7) / ClaimColors.GRID));
        int step = cell + 1;
        ClaimStore.Owner self = ClaimStore.get().self();
        int current = ClaimColors.indexOf(self != null ? self.color() : ClaimColors.defaultColor());
        for (int row = 0; row < ClaimColors.GRID; row++) {
            int rowY = this.cursor;
            if (this.isVisible(rowY, cell)) {
                for (int column = 0; column < ClaimColors.GRID; column++) {
                    int index = row * ClaimColors.GRID + column;
                    int color = ClaimColors.at(index);
                    MapColorButton button = new MapColorButton(this.x + 6 + column * step, rowY, cell, cell,
                            color, index == current, () -> this.screen.setOwnClaimColor(color));
                    button.setTooltip(Tooltip.create(
                            Component.translatable("screen.m4cne.claims.color_cell",
                                    String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF))));
                    this.screen.addSidebarWidget(button);
                }
            }
            this.cursor += cell + 1;
        }
        this.cursor += 4;
    }

    /**
     * Third level chunk list page.
     * <p>
     * Layout follows the request: the list occupies the left two thirds of the
     * window, the action buttons sit in the right third. Claimed chunks and
     * force loaded chunks are two independent tabs; every row shows the chunk
     * id, the player and the centre of the chunk in block coordinates. The
     * selection supports rename / merge / delete, the per chunk boundary wall
     * and the per chunk enter message.
     */
    private void buildClaimChunksPage() {
        this.addFixedButton(Component.translatable("screen.m4cne.back"), 90, this.contentTop,
                this::back, "screen.m4cne.back.tooltip");
        int listX = this.x + 6;
        int listWidth = Math.max(120, (int) Math.round((this.width - 20) * 2.0D / 3.0D));
        int rightX = listX + listWidth + 8;
        int rightWidth = Math.max(80, this.x + this.width - 6 - rightX);
        int top = this.contentTop + ENTRY_HEIGHT + 8;

        ClaimStore store = ClaimStore.get();
        List<ClaimStore.Entry> all = store.entries();
        int claimedCount = all.size();
        int forceCount = 0;
        for (ClaimStore.Entry entry : all) {
            if (entry.forceLoad()) {
                forceCount++;
            }
        }

        boolean claimedTab = this.claimListTab == 0;
        Component claimedLabel = Component.translatable("screen.m4cne.claims.chunk.tab_claimed",
                claimedCount);
        if (claimedTab) {
            claimedLabel = Component.literal("\u25B6 ").append(claimedLabel);
        }
        Component forceLabel = Component.translatable("screen.m4cne.claims.chunk.tab_forced", forceCount);
        if (!claimedTab) {
            forceLabel = Component.literal("\u25B6 ").append(forceLabel);
        }
        int tabWidth = Math.max(60, (listWidth - 2) / 2);
        this.addButtonAt(claimedLabel, listX, top, tabWidth, true, () -> {
            if (this.claimListTab != 0) {
                this.claimListTab = 0;
                this.chunkSelection.clear();
                this.claimMergeMode = false;
                this.screen.requestSidebarRebuild();
            }
        }, "screen.m4cne.claims.chunk.tab_claimed.tooltip");
        this.addButtonAt(forceLabel, listX + listWidth - tabWidth, top, tabWidth, true, () -> {
            if (this.claimListTab != 1) {
                this.claimListTab = 1;
                this.chunkSelection.clear();
                this.claimMergeMode = false;
                this.screen.requestSidebarRebuild();
            }
        }, "screen.m4cne.claims.chunk.tab_forced.tooltip");
        top += ENTRY_HEIGHT + 4;
        this.labelAt(listX + 1, top,
                Component.translatable(this.claimMergeMode
                        ? "screen.m4cne.claims.chunk.merge_hint"
                        : "screen.m4cne.claims.chunk.list_hint"));
        top += 13;

        List<ClaimStore.Entry> list = new ArrayList<>();
        for (ClaimStore.Entry entry : all) {
            if (claimedTab || entry.forceLoad()) {
                list.add(entry);
            }
        }
        list.sort((left, right) -> {
            int dimension = left.dimension().compareTo(right.dimension());
            if (dimension != 0) {
                return dimension;
            }
            int x = Integer.compare(left.x(), right.x());
            return x != 0 ? x : Integer.compare(left.z(), right.z());
        });

        int navY = this.contentBottom - ENTRY_HEIGHT;
        int pageSize = Math.max(1, (navY - 6 - top) / (ENTRY_HEIGHT + ENTRY_GAP));
        int pages = Math.max(1, (list.size() + pageSize - 1) / pageSize);
        int pageIndex = Mth.clamp(claimedTab ? this.claimListPage : this.claimForcePage, 0, pages - 1);
        if (claimedTab) {
            this.claimListPage = pageIndex;
        } else {
            this.claimForcePage = pageIndex;
        }
        int rowY = top;
        if (list.isEmpty()) {
            this.labelAt(listX + 1, rowY, Component.translatable(claimedTab
                    ? "screen.m4cne.claims.chunk.empty_claimed"
                    : "screen.m4cne.claims.chunk.empty_forced"));
        }
        int start = pageIndex * pageSize;
        int end = Math.min(list.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            ClaimStore.Entry entry = list.get(index);
            boolean selected = this.chunkSelection.contains(entry.key());
            Button row = Button.builder(chunkRowLabel(entry, selected),
                    button -> this.toggleChunkSelection(entry))
                    .bounds(listX, rowY, listWidth, ENTRY_HEIGHT)
                    .build();
            row.setTooltip(Tooltip.create(this.chunkRowTooltip(entry)));
            this.screen.addSidebarWidget(row);
            rowY += ENTRY_HEIGHT + ENTRY_GAP;
        }

        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.prev"), listX, navY, 70,
                pageIndex > 0, () -> {
                    if (claimedTab) {
                        this.claimListPage = Math.max(0, this.claimListPage - 1);
                    } else {
                        this.claimForcePage = Math.max(0, this.claimForcePage - 1);
                    }
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.claims.chunk.prev.tooltip");
        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.next"),
                listX + listWidth - 70, navY, 70, pageIndex + 1 < pages, () -> {
                    if (claimedTab) {
                        this.claimListPage++;
                    } else {
                        this.claimForcePage++;
                    }
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.claims.chunk.next.tooltip");
        this.labelAt(listX + 78, navY + 6,
                Component.translatable("screen.m4cne.claims.chunk.page", pageIndex + 1, pages,
                        list.size()));

        // ------------------------------------------------------ right column
        int rightY = this.contentTop;
        this.headingAt(rightX, rightY, Component.translatable("screen.m4cne.section.claims_chunk_actions"));
        rightY += 15;
        List<ClaimStore.Entry> selected = this.selectedChunkEntries();
        List<ClaimStore.Entry> own = this.ownSelectedChunks();
        boolean hasSelection = !selected.isEmpty();
        boolean hasOwn = !own.isEmpty();
        boolean allBoundary = hasSelection;
        boolean allMessage = hasSelection;
        for (ClaimStore.Entry entry : selected) {
            if (!entry.boundary()) {
                allBoundary = false;
            }
            if (!entry.enterMessage()) {
                allMessage = false;
            }
        }

        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.rename"), rightX, rightY,
                rightWidth, hasOwn, this::renameSelectedChunks,
                "screen.m4cne.claims.chunk.rename.tooltip");
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.merge"), rightX, rightY,
                rightWidth, !this.claimMergeMode, () -> {
                    this.claimMergeMode = true;
                    this.chunkSelection.clear();
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.claims.chunk.merge.tooltip");
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.merge_confirm"), rightX,
                rightY, rightWidth, this.claimMergeMode && hasSelection, this::mergeSelectedChunks,
                "screen.m4cne.claims.chunk.merge_confirm.tooltip");
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.merge_cancel"), rightX,
                rightY, rightWidth, this.claimMergeMode, () -> {
                    this.claimMergeMode = false;
                    this.chunkSelection.clear();
                    this.screen.showStatus(
                            Component.translatable("screen.m4cne.status.claims.merge_cancelled"));
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.claims.chunk.merge_cancel.tooltip");
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.delete"), rightX, rightY,
                rightWidth, hasSelection, this::deleteSelectedChunks,
                "screen.m4cne.claims.chunk.delete.tooltip");
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.boundary",
                Component.translatable(allBoundary ? "screen.m4cne.state.disabled"
                        : "screen.m4cne.state.enabled")),
                rightX, rightY, rightWidth, hasSelection, this::toggleSelectedChunkBoundary,
                "screen.m4cne.claims.chunk.boundary.tooltip");
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.message",
                Component.translatable(allMessage ? "screen.m4cne.state.disabled"
                        : "screen.m4cne.state.enabled")),
                rightX, rightY, rightWidth, hasSelection, this::toggleSelectedChunkMessage,
                "screen.m4cne.claims.chunk.message.tooltip");
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        MapValueButton messageMode = new MapValueButton(rightX, rightY, rightWidth, ENTRY_HEIGHT,
                Component.translatable("screen.m4cne.claims.message_mode"),
                () -> ClientConfig.CLAIM_ENTER_MESSAGE_MODE.get().getTranslatedName(),
                () -> ClientConfig.CLAIM_ENTER_MESSAGE_MODE
                        .set(ClientConfig.CLAIM_ENTER_MESSAGE_MODE.get().next()),
                NO_MAP_CHANGE);
        tip(messageMode, "screen.m4cne.claims.message_mode.tooltip");
        this.screen.addSidebarWidget(messageMode);
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.clear"), rightX, rightY,
                rightWidth, hasSelection || this.claimMergeMode, () -> {
                    this.chunkSelection.clear();
                    this.claimMergeMode = false;
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.claims.chunk.clear.tooltip");
        rightY += ENTRY_HEIGHT + 6;

        this.headingAt(rightX, rightY, Component.translatable("screen.m4cne.section.claims_chunk_player"));
        rightY += 15;
        this.addButtonAt(Component.translatable("screen.m4cne.claims.chunk.rules"), rightX, rightY,
                rightWidth, hasSelection, () -> {
                    ClaimStore.Entry first = this.selectedChunkEntries().stream().findFirst().orElse(null);
                    if (first == null) {
                        return;
                    }
                    this.selectedClaimOwnerId = first.owner().id();
                    this.open(MapSettingsPage.CLAIM_ENTITY_RULES);
                }, "screen.m4cne.claims.chunk.rules.tooltip");
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        String ownerId = hasSelection ? selected.get(0).owner().id() : "";
        boolean visible = hasSelection && !store.isHidden(ownerId);
        this.addButtonAt(Component.translatable("screen.m4cne.claims.action.toggle_visible",
                Component.translatable(visible ? "screen.m4cne.state.enabled"
                        : "screen.m4cne.state.disabled")),
                rightX, rightY, rightWidth, hasSelection, () -> {
                    ClaimStore.Entry first = this.selectedChunkEntries().stream().findFirst().orElse(null);
                    if (first == null) {
                        return;
                    }
                    ClaimStore.get().setHidden(first.owner().id(),
                            !ClaimStore.get().isHidden(first.owner().id()));
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.claims.action.toggle_visible.tooltip");
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        this.addButtonAt(Component.translatable("screen.m4cne.claims.action.entity_rules_selected"),
                rightX, rightY, rightWidth, hasSelection, () -> {
                    ClaimStore.Entry first = this.selectedChunkEntries().stream().findFirst().orElse(null);
                    if (first != null) {
                        this.selectedClaimOwnerId = first.owner().id();
                        this.open(MapSettingsPage.CLAIM_ENTITY_RULES);
                    }
                }, "screen.m4cne.claims.action.entity_rules_selected.tooltip");
        rightY += ENTRY_HEIGHT + 6;

        this.headingAt(rightX, rightY, Component.translatable("screen.m4cne.section.claims_server"));
        rightY += 15;
        this.addButtonAt(Component.translatable("screen.m4cne.claims.action.sync_now"), rightX, rightY,
                rightWidth, true, this.screen::syncClaimsNow,
                "screen.m4cne.claims.action.sync_now.tooltip");
        rightY += ENTRY_HEIGHT + ENTRY_GAP;
        if (ClaimSyncManager.get().isAdmin()) {
            this.addButtonAt(Component.translatable("screen.m4cne.claims.action.scan"), rightX, rightY,
                    rightWidth, true, this.screen::requestClaimScan,
                    "screen.m4cne.claims.action.scan.tooltip");
            rightY += ENTRY_HEIGHT + ENTRY_GAP;
        }

        this.labelAt(rightX, this.contentBottom - 13,
                Component.translatable("screen.m4cne.claims.chunk.summary", claimedCount, forceCount,
                        this.chunkSelection.size()));
    }

    /** One row label of the chunk list: marker, id, player and chunk centre. */
    private Component chunkRowLabel(ClaimStore.Entry entry, boolean selected) {
        String marker = selected ? "\u25B6 " : (this.claimMergeMode ? "\u25A1 " : "\u25CB ");
        String ownerId = entry.owner().id();
        String shortId = ownerId.length() > 8 ? ownerId.substring(0, 8) : ownerId;
        return Component.empty()
                .append(Component.literal(marker).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(entry.displayId())
                        .withStyle(style -> style.withColor(entry.owner().color())))
                .append(Component.literal(" | " + entry.owner().name()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" [" + shortId + "]").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(" | (" + entry.centerX() + ", " + entry.centerZ() + ")")
                        .withStyle(ChatFormatting.GRAY));
    }

    /** Full row information: id, player id, centre, dimension and all flags. */
    private Component chunkRowTooltip(ClaimStore.Entry entry) {
        return Component.translatable("screen.m4cne.claims.chunk.row_tooltip",
                entry.displayId(), entry.owner().name(), entry.owner().id(), entry.dimension(),
                entry.centerX(), entry.centerZ(), state(entry.forceLoad()), state(entry.boundary()),
                state(entry.enterMessage()));
    }

    /** Resolves the selection keys of the chunk list against the current mirror. */
    private List<ClaimStore.Entry> selectedChunkEntries() {
        if (this.chunkSelection.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, ClaimStore.Entry> byKey = new java.util.HashMap<>();
        for (ClaimStore.Entry entry : ClaimStore.get().entries()) {
            byKey.put(entry.key(), entry);
        }
        List<ClaimStore.Entry> selected = new ArrayList<>(this.chunkSelection.size());
        for (String key : this.chunkSelection) {
            ClaimStore.Entry entry = byKey.get(key);
            if (entry != null) {
                selected.add(entry);
            }
        }
        return selected;
    }

    /** Selected chunks owned by the local player. */
    private List<ClaimStore.Entry> ownSelectedChunks() {
        String selfId = ClaimStore.get().selfId();
        List<ClaimStore.Entry> own = new ArrayList<>();
        for (ClaimStore.Entry entry : this.selectedChunkEntries()) {
            if (entry.owner().id().equals(selfId)) {
                own.add(entry);
            }
        }
        return own;
    }

    private void toggleChunkSelection(ClaimStore.Entry entry) {
        String key = entry.key();
        if (this.claimMergeMode) {
            if (!this.chunkSelection.remove(key)) {
                this.chunkSelection.add(key);
            }
        } else if (Screen.hasShiftDown() || Screen.hasControlDown()) {
            if (!this.chunkSelection.remove(key)) {
                this.chunkSelection.add(key);
            }
        } else {
            boolean already = this.chunkSelection.size() == 1 && this.chunkSelection.contains(key);
            this.chunkSelection.clear();
            if (!already) {
                this.chunkSelection.add(key);
            }
        }
        this.selectedClaimOwnerId = entry.owner().id();
        this.screen.requestSidebarRebuild();
    }

    private static java.util.Map<String, List<ClaimStore.Entry>> groupByDimension(
            List<ClaimStore.Entry> entries) {
        java.util.Map<String, List<ClaimStore.Entry>> grouped = new java.util.LinkedHashMap<>();
        for (ClaimStore.Entry entry : entries) {
            grouped.computeIfAbsent(entry.dimension(), ignored -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    private static java.util.Map<String, List<ClaimStore.Entry>> groupByOwner(
            List<ClaimStore.Entry> entries) {
        java.util.Map<String, List<ClaimStore.Entry>> grouped = new java.util.LinkedHashMap<>();
        for (ClaimStore.Entry entry : entries) {
            grouped.computeIfAbsent(entry.owner().id(), ignored -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    /** Opens the rename editor for the own selected chunks. */
    private void renameSelectedChunks() {
        List<ClaimStore.Entry> own = this.ownSelectedChunks();
        if (own.isEmpty()) {
            this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.chunk_not_own"));
            return;
        }
        List<ClaimChunk> chunks = new ArrayList<>(own.size());
        for (ClaimStore.Entry entry : own) {
            chunks.add(new ClaimChunk(entry.dimension(), entry.x(), entry.z(), entry.forceLoad(),
                    entry.displayId(), entry.boundary(), entry.enterMessage()));
        }
        Minecraft.getInstance().setScreen(new ClaimRenameScreen(this.screen, chunks));
    }

    /** Merges the selected own chunks into the smallest id of the group. */
    private void mergeSelectedChunks() {
        List<ClaimStore.Entry> selected = this.selectedChunkEntries();
        List<ClaimStore.Entry> own = this.ownSelectedChunks();
        if (selected.size() < 2) {
            this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.merge_need_two"));
            return;
        }
        if (own.size() != selected.size()) {
            this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.merge_only_own"));
            return;
        }
        String merged = null;
        int count = 0;
        for (Map.Entry<String, List<ClaimStore.Entry>> group : groupByDimension(own).entrySet()) {
            List<Long> keys = new ArrayList<>(group.getValue().size());
            for (ClaimStore.Entry entry : group.getValue()) {
                keys.add(ChunkPos.asLong(entry.x(), entry.z()));
            }
            String result = ClaimStore.get().mergeChunks(group.getKey(), keys);
            if (result != null) {
                if (merged == null || result.compareTo(merged) < 0) {
                    merged = result;
                }
                count += group.getValue().size();
            }
        }
        this.claimMergeMode = false;
        this.chunkSelection.clear();
        if (merged != null) {
            this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.merged", merged,
                    count));
        }
        this.screen.requestSidebarRebuild();
    }

    /** Deletes every selected chunk (own chunks always, foreign ones as admin). */
    private void deleteSelectedChunks() {
        List<ClaimStore.Entry> selected = this.selectedChunkEntries();
        if (selected.isEmpty()) {
            this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.no_selection"));
            return;
        }
        ClaimStore store = ClaimStore.get();
        String selfId = store.selfId();
        boolean admin = ClaimSyncManager.get().isAdmin();
        int deleted = 0;
        for (Map.Entry<String, List<ClaimStore.Entry>> group : groupByDimension(selected).entrySet()) {
            List<Long> ownKeys = new ArrayList<>();
            java.util.Map<String, List<ClaimChunk>> foreign = new java.util.LinkedHashMap<>();
            for (ClaimStore.Entry entry : group.getValue()) {
                if (entry.owner().id().equals(selfId)) {
                    ownKeys.add(ChunkPos.asLong(entry.x(), entry.z()));
                } else if (admin) {
                    foreign.computeIfAbsent(entry.owner().id(), ignored -> new ArrayList<>())
                            .add(new ClaimChunk(entry.dimension(), entry.x(), entry.z(),
                                    entry.forceLoad(), entry.displayId(), entry.boundary(),
                                    entry.enterMessage()));
                }
            }
            deleted += store.unclaim(group.getKey(), ownKeys);
            for (Map.Entry<String, List<ClaimChunk>> target : foreign.entrySet()) {
                if (ClaimSyncManager.get().deleteClaims(target.getKey(), target.getValue())) {
                    deleted += target.getValue().size();
                }
            }
        }
        this.claimMergeMode = false;
        this.chunkSelection.clear();
        this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.chunks_deleted", deleted));
        this.screen.requestSidebarRebuild();
    }

    /** Toggles the boundary wall of the selected chunks. */
    private void toggleSelectedChunkBoundary() {
        List<ClaimStore.Entry> selected = this.selectedChunkEntries();
        if (selected.isEmpty()) {
            this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.no_selection"));
            return;
        }
        boolean enable = false;
        for (ClaimStore.Entry entry : selected) {
            if (!entry.boundary()) {
                enable = true;
                break;
            }
        }
        int changed = this.applyChunkFlag(selected, enable, true);
        this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.chunk_boundary",
                state(enable), changed));
        this.screen.requestSidebarRebuild();
    }

    /** Toggles the enter message of the selected chunks. */
    private void toggleSelectedChunkMessage() {
        List<ClaimStore.Entry> selected = this.selectedChunkEntries();
        if (selected.isEmpty()) {
            this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.no_selection"));
            return;
        }
        boolean enable = false;
        for (ClaimStore.Entry entry : selected) {
            if (!entry.enterMessage()) {
                enable = true;
                break;
            }
        }
        int changed = this.applyChunkFlag(selected, enable, false);
        this.screen.showStatus(Component.translatable("screen.m4cne.status.claims.chunk_message",
                state(enable), changed));
        this.screen.requestSidebarRebuild();
    }

    /**
     * Applies the boundary / message flag to own chunks locally and to foreign
     * chunks through the action payload when the player is an administrator.
     */
    private int applyChunkFlag(List<ClaimStore.Entry> selected, boolean value, boolean boundary) {
        ClaimStore store = ClaimStore.get();
        String selfId = store.selfId();
        boolean admin = ClaimSyncManager.get().isAdmin();
        int changed = 0;
        for (Map.Entry<String, List<ClaimStore.Entry>> group : groupByDimension(selected).entrySet()) {
            List<Long> ownKeys = new ArrayList<>();
            java.util.Map<String, List<ClaimChunk>> foreign = new java.util.LinkedHashMap<>();
            for (ClaimStore.Entry entry : group.getValue()) {
                if (entry.owner().id().equals(selfId)) {
                    ownKeys.add(ChunkPos.asLong(entry.x(), entry.z()));
                } else if (admin) {
                    foreign.computeIfAbsent(entry.owner().id(), ignored -> new ArrayList<>())
                            .add(new ClaimChunk(entry.dimension(), entry.x(), entry.z(),
                                    entry.forceLoad(), entry.displayId(), entry.boundary(),
                                    entry.enterMessage()));
                }
            }
            changed += boundary
                    ? store.setChunkBoundary(group.getKey(), ownKeys, value)
                    : store.setChunkEnterMessage(group.getKey(), ownKeys, value);
            for (Map.Entry<String, List<ClaimChunk>> target : foreign.entrySet()) {
                boolean sent = boundary
                        ? ClaimSyncManager.get().setChunkBoundary(target.getKey(), target.getValue(), value)
                        : ClaimSyncManager.get().setChunkMessage(target.getKey(), target.getValue(), value);
                if (sent) {
                    changed += target.getValue().size();
                }
            }
        }
        return changed;
    }

    private ClaimStore.Owner selectedClaimOwner() {
        return ClaimStore.get().owner(this.selectedClaimOwnerId);
    }

    /**
     * Chunk preload page.
     * <p>
     * The page was fully reworked: the old sampling radius entries are gone and
     * the group now controls a real, server driven preload task plus the map
     * import of its result.
     */
    private void buildPreload(int entryWidth) {
        PreloadController controller = PreloadController.get();
        PreloadController.Status status = controller.status();
        // Only the server owner / an admin may use the feature. Without the
        // permission every widget of the page is built disabled and explains
        // why in its tooltip.
        boolean supported = controller.supported();
        boolean permitted = status.permitted();
        this.lockedTooltipKey = !supported ? "screen.m4cne.preload.unsupported"
                : (!permitted ? "screen.m4cne.preload.no_permission.tooltip" : null);

        this.section("screen.m4cne.section.preload_state");
        this.label(Component.translatable("screen.m4cne.preload.state_line",
                Component.translatable(status.state().translationKey()),
                Component.translatable(status.shape().translationKey())));
        if (status.total() > 0) {
            this.label(Component.translatable("screen.m4cne.preload.progress_line",
                    status.completed(), status.total(), Math.round(status.progress() * 100.0D)));
            this.label(Component.translatable("screen.m4cne.preload.center_line",
                    status.centerChunkX(), status.centerChunkZ()));
        }
        if (status.importTotal() > 0) {
            this.label(Component.translatable("screen.m4cne.preload.import_line", status.imported(),
                    status.importTotal(), Math.round(status.importProgress() * 100.0D)));
        }
        if (status.skipped() > 0 || controller.skippedThisSession() > 0) {
            this.label(Component.translatable("screen.m4cne.preload.skip_line", status.skipped()));
            this.label(Component.translatable("screen.m4cne.preload.session_skip_line",
                    controller.skippedThisSession()));
        }
        this.label(Component.translatable("screen.m4cne.preload.session_line", controller.importedThisSession()));
        if (status.c2me()) {
            this.label(Component.translatable("screen.m4cne.preload.c2me_detected"));
        }
        if (status.chunky()) {
            this.label(Component.translatable("screen.m4cne.preload.chunky_detected"));
        }
        if (!supported) {
            this.label(Component.translatable("screen.m4cne.preload.unsupported"));
        } else if (!permitted) {
            this.label(Component.translatable("screen.m4cne.preload.no_permission", status.permissionLevel()));
        }
        if (status.hint() != 0) {
            this.label(Component.translatable("screen.m4cne.preload.hint." + status.hint()));
        }

        this.section("screen.m4cne.section.preload_area");
        this.intField("screen.m4cne.setting.preload_radius", ClientConfig.PRELOAD_RADIUS_CHUNKS,
                1, PreloadRegion.MAX_RADIUS);
        this.valueButton("screen.m4cne.setting.preload_shape", entryWidth,
                () -> Component.translatable(ClientConfig.PRELOAD_SHAPE.get().translationKey()),
                () -> ClientConfig.PRELOAD_SHAPE.set(ClientConfig.PRELOAD_SHAPE.get().next()));
        this.valueButton("screen.m4cne.setting.preload_center", entryWidth,
                () -> Component.translatable(ClientConfig.PRELOAD_CENTER_MODE.get().translationKey()),
                () -> ClientConfig.PRELOAD_CENTER_MODE.set(ClientConfig.PRELOAD_CENTER_MODE.get().next()));
        this.label(this.preloadCenterDescription());
        this.toggle("screen.m4cne.setting.preload_freeze", ClientConfig.PRELOAD_FREEZE_TICKS);
        this.toggle("screen.m4cne.setting.preload_show_range", ClientConfig.PRELOAD_SHOW_RANGE);

        this.section("screen.m4cne.section.preload_performance");
        this.intField("screen.m4cne.setting.preload_threads", ClientConfig.PRELOAD_THREADS,
                1, Math.max(2, PreloadLimits.maxThreads()));
        this.valueButton("screen.m4cne.setting.preload_priority", entryWidth,
                () -> ClientConfig.PRELOAD_PRIORITY.get().getTranslatedName(),
                () -> ClientConfig.PRELOAD_PRIORITY.set(ClientConfig.PRELOAD_PRIORITY.get().next()));
        this.intField("screen.m4cne.setting.preload_memory", ClientConfig.PRELOAD_MEMORY_MB,
                256, Math.max(512, PreloadLimits.maxMemoryMb()));
        this.label(Component.translatable("screen.m4cne.preload.memory_note", PreloadLimits.heapMb()));

        this.section("screen.m4cne.section.preload_task");
        PreloadState state = status.state();
        boolean running = state == PreloadState.RUNNING;
        boolean paused = state == PreloadState.PAUSED;
        this.addButton(Component.translatable(paused ? "screen.m4cne.preload.action.resume"
                : "screen.m4cne.preload.action.start"), entryWidth, !running,
                () -> {
                    if (paused) {
                        this.screen.resumePreload();
                    } else {
                        this.screen.startPreload();
                    }
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.preload.action.start.tooltip");
        this.addButton(Component.translatable("screen.m4cne.preload.action.pause"), entryWidth, running,
                () -> {
                    this.screen.pausePreload();
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.preload.action.pause.tooltip");

        boolean armed = System.currentTimeMillis() < this.cancelArmedUntil;
        this.addButton(Component.translatable(armed ? "screen.m4cne.preload.action.cancel_confirm"
                : "screen.m4cne.preload.action.cancel"), entryWidth, state.hasTask() || state == PreloadState.DONE,
                () -> {
                    if (!armed) {
                        // First click only arms the button, a second click within
                        // the time window really cancels the task.
                        this.cancelArmedUntil = System.currentTimeMillis() + CANCEL_CONFIRM_MS;
                        this.screen.requestSidebarRebuild();
                        return;
                    }
                    this.cancelArmedUntil = 0L;
                    this.screen.cancelPreload();
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.preload.action.cancel.tooltip");

        this.section("screen.m4cne.section.preload_import");
        this.valueButton("screen.m4cne.setting.preload_import_mode", entryWidth,
                () -> Component.translatable(ClientConfig.PRELOAD_IMPORT_MODE.get().translationKey()),
                () -> ClientConfig.PRELOAD_IMPORT_MODE.set(ClientConfig.PRELOAD_IMPORT_MODE.get().next()));
        this.intField("screen.m4cne.setting.preload_import_budget", ClientConfig.PRELOAD_IMPORT_BUDGET, 1, 64);
        this.toggle("screen.m4cne.setting.preload_import_auto", ClientConfig.PRELOAD_IMPORT_AUTO);
        PreloadImportMode importMode = ClientConfig.PRELOAD_IMPORT_MODE.get();
        // The m4cne sync only runs after the server finished caching the whole
        // region, so the manual import waits for the DONE state as well.
        boolean importReady = importMode.isEnabled()
                && (importMode != PreloadImportMode.M4CNE || state == PreloadState.DONE);
        this.addButton(Component.translatable("screen.m4cne.preload.action.import"), entryWidth,
                importReady,
                () -> {
                    this.screen.importPreload();
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.preload.action.import.tooltip");
        this.label(Component.translatable("screen.m4cne.preload.import_note"));
        this.label(Component.translatable("screen.m4cne.preload.marker_count",
                PreloadMarkerStore.get().forDimension(this.currentDimension()).size()));
        this.addButton(Component.translatable("screen.m4cne.preload.action.clear_markers"), entryWidth,
                () -> {
                    this.screen.clearPreloadMarkers();
                    this.screen.requestSidebarRebuild();
                }, "screen.m4cne.preload.action.clear_markers.tooltip");
        this.lockedTooltipKey = null;
    }

    /** Describes the currently configured preload centre. */
    private Component preloadCenterDescription() {
        Minecraft minecraft = Minecraft.getInstance();
        PreloadCenterMode mode = ClientConfig.PRELOAD_CENTER_MODE.get();
        ResourceLocation dimension = this.currentDimension();
        return switch (mode) {
            case SPAWN -> {
                if (minecraft.level == null) {
                    yield Component.translatable("screen.m4cne.no_world");
                }
                BlockPos spawn = minecraft.level.getSharedSpawnPos();
                yield Component.translatable("screen.m4cne.preload.center_at", spawn.getX() >> 4, spawn.getZ() >> 4);
            }
            case PLAYER -> {
                if (minecraft.player == null) {
                    yield Component.translatable("screen.m4cne.no_world");
                }
                ChunkPos chunk = minecraft.player.chunkPosition();
                yield Component.translatable("screen.m4cne.preload.center_at", chunk.x, chunk.z);
            }
            case MARKER -> {
                PreloadMarkerStore.Marker marker = dimension != null
                        ? PreloadMarkerStore.get().active(dimension)
                        : null;
                yield marker != null
                        ? Component.translatable("screen.m4cne.preload.center_at", marker.x() >> 4, marker.z() >> 4)
                        : Component.translatable("screen.m4cne.preload.center_missing");
            }
        };
    }

    private ResourceLocation currentDimension() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null ? level.dimension().location() : null;
    }

    private void buildOverlays(int entryWidth) {
        this.section("screen.m4cne.section.overlay_elements");
        this.toggle("screen.m4cne.setting.overlay_grid", ClientConfig.OVERLAY_CHUNK_GRID);
        this.toggle("screen.m4cne.setting.overlay_coordinates", ClientConfig.OVERLAY_CHUNK_COORDINATES);
        this.toggle("screen.m4cne.setting.overlay_heights", ClientConfig.OVERLAY_HEIGHTS);
        this.toggle("screen.m4cne.setting.overlay_biome", ClientConfig.OVERLAY_BIOME);
        this.doubleSlider("screen.m4cne.setting.overlay_biome_opacity", ClientConfig.OVERLAY_BIOME_OPACITY,
                0.0D, 1.0D, 0.05D, MapSettingsPanel::percent);
        this.toggle("screen.m4cne.setting.overlay_slime", ClientConfig.OVERLAY_SLIME_CHUNKS);
        this.doubleSlider("screen.m4cne.setting.overlay_slime_opacity", ClientConfig.OVERLAY_SLIME_OPACITY,
                0.0D, 1.0D, 0.05D, MapSettingsPanel::percent);
        this.toggle("screen.m4cne.setting.overlay_ores", ClientConfig.OVERLAY_ORE_VEINS);
        this.intField("screen.m4cne.setting.overlay_ore_budget", ClientConfig.OVERLAY_ORE_SCAN_BUDGET, 1, 8);
        this.toggle("screen.m4cne.setting.overlay_highlight", ClientConfig.OVERLAY_HIGHLIGHT_HOVERED);

        this.section("screen.m4cne.section.overlay_style");
        this.doubleSlider("screen.m4cne.setting.overlay_opacity", ClientConfig.OVERLAY_OPACITY,
                0.10D, 1.0D, 0.05D, MapSettingsPanel::percent);

        this.section("screen.m4cne.section.chunk_query");
        this.queryFields(entryWidth);
        this.addButton(Component.translatable("screen.m4cne.action.query_run"), entryWidth,
                () -> this.screen.queryChunkFromText(this.queryX, this.queryZ),
                "screen.m4cne.action.query_run.tooltip");
        this.addButton(Component.translatable("screen.m4cne.action.query_player"), entryWidth,
                () -> this.screen.queryPlayerChunk(), "screen.m4cne.action.query_player.tooltip");
        this.addButton(Component.translatable("screen.m4cne.action.query_center"), entryWidth,
                () -> this.screen.queryCenterChunk(), "screen.m4cne.action.query_center.tooltip");
        this.addButton(Component.translatable("screen.m4cne.action.query_focus"), entryWidth,
                () -> this.screen.focusQueriedChunk(), "screen.m4cne.action.query_focus.tooltip");
        this.addButton(Component.translatable("screen.m4cne.action.query_clear"), entryWidth,
                () -> this.screen.clearQuery(), "screen.m4cne.action.query_clear.tooltip");
        this.toggle("screen.m4cne.setting.query_highlight", ClientConfig.OVERLAY_QUERY_HIGHLIGHT);
        this.queryResultLines();
    }

    private void buildMinimapHud(int entryWidth) {
        this.section("screen.m4cne.section.hud_widget");
        this.toggle("screen.m4cne.setting.minimap_enabled", ClientConfig.MINIMAP_ENABLED);
        this.valueButton("screen.m4cne.setting.minimap_mode", entryWidth,
                () -> Component.translatable(ClientConfig.MINIMAP_MODE.get().translationKey()),
                () -> ClientConfig.MINIMAP_MODE.set(ClientConfig.MINIMAP_MODE.get().next()));
        // Minimap and compass are placed independently, so each widget has its
        // own third level preview page.
        this.addButton(Component.translatable("screen.m4cne.action.minimap_layout"), entryWidth,
                () -> this.open(MapSettingsPage.MINIMAP_LAYOUT), "screen.m4cne.action.minimap_layout.tooltip");
        this.addButton(Component.translatable("screen.m4cne.action.compass_layout"), entryWidth,
                () -> this.open(MapSettingsPage.COMPASS_LAYOUT), "screen.m4cne.action.compass_layout.tooltip");
        this.valueButton("screen.m4cne.setting.minimap_render_layer", entryWidth,
                () -> Component.translatable(ClientConfig.MINIMAP_RENDER_LAYER.get().translationKey()),
                () -> ClientConfig.MINIMAP_RENDER_LAYER.set(ClientConfig.MINIMAP_RENDER_LAYER.get().next()));
        this.toggle("screen.m4cne.setting.minimap_hide_in_screens", ClientConfig.MINIMAP_HIDE_IN_SCREENS);
        this.intField("screen.m4cne.setting.minimap_size", ClientConfig.MINIMAP_SIZE, 48, 192);
        this.doubleSlider("screen.m4cne.setting.minimap_zoom", ClientConfig.MINIMAP_ZOOM,
                0.25D, 4.0D, 0.05D,
                value -> Component.translatable("screen.m4cne.unit.pixels_per_block",
                        String.format(java.util.Locale.ROOT, "%.2f", value)));
        this.toggle("screen.m4cne.setting.minimap_border", ClientConfig.MINIMAP_BORDER);

        this.section("screen.m4cne.section.minimap_info");
        this.toggle("screen.m4cne.setting.minimap_coordinates", ClientConfig.MINIMAP_SHOW_COORDINATES);
        this.toggle("screen.m4cne.setting.minimap_biome", ClientConfig.MINIMAP_SHOW_BIOME);
        this.toggle("screen.m4cne.setting.minimap_dimension", ClientConfig.MINIMAP_SHOW_DIMENSION);
        this.toggle("screen.m4cne.setting.minimap_chunk", ClientConfig.MINIMAP_SHOW_CHUNK);
        this.doubleSlider("screen.m4cne.setting.minimap_info_scale", ClientConfig.MINIMAP_INFO_SCALE,
                0.5D, 2.0D, 0.05D, MapSettingsPanel::percent);

        this.section("screen.m4cne.section.hotkeys");
        this.label(Component.translatable("screen.m4cne.label.hud_hotkeys",
                ClientKeyBindings.TOGGLE_HUD.getTranslatedKeyMessage(),
                ClientKeyBindings.CYCLE_HUD.getTranslatedKeyMessage(),
                ClientKeyBindings.NEW_WAYPOINT.getTranslatedKeyMessage()));
        this.label(Component.translatable("screen.m4cne.label.hud_extra_hint"));
    }

    /**
     * Third level "minimap layout" page. The left canvas previews the game
     * window with the minimap in its configured corner, so the corner, the
     * scale and the screen margin can be judged directly.
     */
    private void buildMinimapLayout(int entryWidth) {
        this.section("screen.m4cne.section.minimap_layout_corner");
        this.label(Component.translatable("screen.m4cne.label.minimap_layout_hint"));
        this.valueButton("screen.m4cne.setting.minimap_corner", entryWidth,
                () -> Component.translatable(ClientConfig.MINIMAP_CORNER.get().translationKey()),
                () -> ClientConfig.MINIMAP_CORNER.set(ClientConfig.MINIMAP_CORNER.get().next()));
        this.doubleSlider("screen.m4cne.setting.minimap_scale", ClientConfig.MINIMAP_SCALE,
                0.5D, 2.0D, 0.05D, MapSettingsPanel::percent);
        this.intField("screen.m4cne.setting.minimap_margin", ClientConfig.MINIMAP_MARGIN, 0, 64);
        this.label(Component.translatable("screen.m4cne.label.minimap_layout_scale_hint"));

        // The minimap now carries the same marker information the compass
        // offers: entity icons, waypoint icons and the nearest target details.
        this.section("screen.m4cne.section.minimap_entities");
        this.toggle("screen.m4cne.setting.minimap_entities", ClientConfig.MINIMAP_ENTITIES);
        this.intField("screen.m4cne.setting.minimap_entity_range", ClientConfig.MINIMAP_ENTITY_RANGE, 4, 512);
        this.doubleSlider("screen.m4cne.setting.minimap_icon_scale", ClientConfig.MINIMAP_ICON_SCALE,
                0.5D, 3.0D, 0.05D, MapSettingsPanel::percent);

        this.section("screen.m4cne.section.minimap_waypoints");
        this.toggle("screen.m4cne.setting.minimap_waypoints", ClientConfig.MINIMAP_WAYPOINTS);
        this.intField("screen.m4cne.setting.minimap_waypoint_range", ClientConfig.MINIMAP_WAYPOINT_RANGE,
                8, 4096);
        this.label(Component.translatable("screen.m4cne.label.minimap_marker_hint"));
    }

    /**
     * Third level "compass layout" page. The compass is positioned relative to
     * the vertical centre line of the screen and always scales around its own
     * centre point.
     */
    private void buildCompassLayout(int entryWidth) {
        this.section("screen.m4cne.section.compass_layout_position");
        this.label(Component.translatable("screen.m4cne.label.compass_layout_hint"));
        this.intField("screen.m4cne.setting.compass_pos_x", ClientConfig.COMPASS_POS_X, -8192, 8192);
        this.intField("screen.m4cne.setting.compass_pos_y", ClientConfig.COMPASS_POS_Y, 0, 8192);

        this.section("screen.m4cne.section.compass_layout_size");
        this.doubleSlider("screen.m4cne.setting.compass_scale", ClientConfig.COMPASS_SCALE,
                0.5D, 2.0D, 0.05D, MapSettingsPanel::percent);
        this.intField("screen.m4cne.setting.compass_width", ClientConfig.COMPASS_WIDTH, 80, 480);
        this.doubleSlider("screen.m4cne.setting.compass_icon_scale", ClientConfig.COMPASS_ICON_SCALE,
                0.5D, 3.0D, 0.05D, MapSettingsPanel::percent);
        this.doubleSlider("screen.m4cne.setting.compass_background_opacity",
                ClientConfig.COMPASS_BACKGROUND_OPACITY, 0.0D, 1.0D, 0.05D, MapSettingsPanel::percent);
        this.label(Component.translatable("screen.m4cne.label.compass_layout_scale_hint"));

        this.section("screen.m4cne.section.compass_entities");
        this.toggle("screen.m4cne.setting.compass_entities", ClientConfig.COMPASS_ENTITIES);
        this.intField("screen.m4cne.setting.compass_entity_range", ClientConfig.COMPASS_ENTITY_RANGE,
                4, 512);
        this.intField("screen.m4cne.setting.compass_merge_pixels", ClientConfig.COMPASS_MERGE_PIXELS,
                2, 48);
        this.label(Component.translatable("screen.m4cne.label.compass_entity_hint"));

        this.section("screen.m4cne.section.compass_waypoints");
        this.toggle("screen.m4cne.setting.compass_waypoints", ClientConfig.COMPASS_WAYPOINTS);
        this.intField("screen.m4cne.setting.compass_waypoint_range", ClientConfig.COMPASS_WAYPOINT_RANGE,
                8, 4096);
        this.label(Component.translatable("screen.m4cne.label.compass_waypoint_hint"));
    }

    private void buildPresets(int entryWidth) {
        this.label(Component.translatable("screen.m4cne.label.preset_note"));
        MapPresetStore presets = MapPresetStore.client();
        int buttonWidth = Math.min(26, Math.max(20, entryWidth / 6));
        int gap = 2;
        int mainWidth = Math.max(48, entryWidth - buttonWidth * 2 - gap * 2);
        for (int slot = 1; slot <= MapPresetStore.SLOTS; slot++) {
            int presetSlot = slot;
            boolean saved = presets.has(slot);
            int rowY = this.cursor;
            if (this.isVisible(rowY, ENTRY_HEIGHT)) {
                Component label = Component.empty()
                        .append(Component.literal(saved ? "\u25CF " : "\u25CB ")
                                .withStyle(saved ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY))
                        .append(Component.translatable("screen.m4cne.preset.row", slot));
                Button apply = Button.builder(label, ignored -> {
                    if (presets.apply(presetSlot)) {
                        this.screen.markMapDirty();
                        this.screen.showStatus(
                                Component.translatable("screen.m4cne.status.preset_applied", presetSlot));
                        this.screen.requestSidebarRebuild();
                    }
                }).bounds(this.x + 6, rowY, mainWidth, ENTRY_HEIGHT).build();
                tip(apply, "screen.m4cne.preset.row.tooltip");
                this.screen.addSidebarWidget(apply);

                int saveX = this.x + 6 + mainWidth + gap;
                Button save = Button.builder(Component.translatable("screen.m4cne.preset.save_short"),
                        ignored -> {
                            presets.save(presetSlot);
                            this.screen.showStatus(
                                    Component.translatable("screen.m4cne.status.preset_saved", presetSlot));
                            this.screen.requestSidebarRebuild();
                        }).bounds(saveX, rowY, buttonWidth, ENTRY_HEIGHT).build();
                tip(save, "screen.m4cne.preset.save_short.tooltip");
                this.screen.addSidebarWidget(save);

                Button delete = Button.builder(Component.translatable("screen.m4cne.preset.delete_short"),
                        ignored -> {
                            if (presets.delete(presetSlot)) {
                                this.screen.showStatus(
                                        Component.translatable("screen.m4cne.status.preset_deleted", presetSlot));
                                this.screen.requestSidebarRebuild();
                            }
                        }).bounds(saveX + buttonWidth + gap, rowY, buttonWidth, ENTRY_HEIGHT).build();
                delete.active = saved;
                tip(delete, "screen.m4cne.preset.delete_short.tooltip");
                this.screen.addSidebarWidget(delete);
            }
            this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
        }
        this.section("screen.m4cne.section.preset_reset");
        this.addButton(Component.translatable("screen.m4cne.action.reset_defaults"), entryWidth, () -> {
            presets.resetDefaults();
            this.screen.markMapDirty();
            this.screen.showStatus(Component.translatable("screen.m4cne.status.defaults_restored"));
            this.screen.requestSidebarRebuild();
        }, "screen.m4cne.action.reset_defaults.tooltip");
    }

    // ------------------------------------------------------------ chunk query

    private void queryFields(int entryWidth) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            int fieldWidth = Math.max(40, (entryWidth - 4) / 2);
            EditBox xField = new EditBox(Minecraft.getInstance().font, this.x + 6, widgetY, fieldWidth,
                    ENTRY_HEIGHT, Component.translatable("screen.m4cne.query.x"));
            xField.setMaxLength(12);
            xField.setHint(Component.translatable("screen.m4cne.query.x_hint"));
            xField.setValue(this.queryX);
            xField.setResponder(value -> this.queryX = value);
            xField.setTooltip(Tooltip.create(Component.translatable("screen.m4cne.query.x.tooltip")));
            this.screen.addSidebarWidget(xField);

            EditBox zField = new EditBox(Minecraft.getInstance().font, this.x + 6 + fieldWidth + 4, widgetY,
                    fieldWidth, ENTRY_HEIGHT, Component.translatable("screen.m4cne.query.z"));
            zField.setMaxLength(12);
            zField.setHint(Component.translatable("screen.m4cne.query.z_hint"));
            zField.setValue(this.queryZ);
            zField.setResponder(value -> this.queryZ = value);
            zField.setTooltip(Tooltip.create(Component.translatable("screen.m4cne.query.z.tooltip")));
            this.screen.addSidebarWidget(zField);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    private void queryResultLines() {
        ChunkQueryService.Report report = this.screen.queryReport();
        if (report == null) {
            this.label(Component.translatable("screen.m4cne.label.query_none"));
            return;
        }
        this.label(Component.translatable("screen.m4cne.label.query_chunk", report.chunkX(), report.chunkZ(),
                Component.translatable(report.recorded() ? "screen.m4cne.label.query_recorded"
                        : "screen.m4cne.label.query_not_recorded")));
        this.label(Component.translatable("screen.m4cne.label.query_biome", report.biomeName()));
        if (report.height() != null) {
            this.label(Component.translatable("screen.m4cne.label.query_height", report.height()));
        }
        if (report.slime() == null) {
            this.label(Component.translatable("screen.m4cne.label.query_slime_unknown"));
        } else {
            this.label(Component.translatable(report.slime() ? "screen.m4cne.label.query_slime"
                    : "screen.m4cne.label.query_not_slime"));
        }
        this.label(Component.translatable("screen.m4cne.label.query_entities", report.entityTotal(),
                report.entityTypes()));
        OreScanCache.Result ores = report.ores();
        this.label(Component.translatable("screen.m4cne.label.query_ores", ores.total()));
        if (!ores.isEmpty()) {
            List<Map.Entry<ResourceLocation, Integer>> sorted = new ArrayList<>(ores.counts().entrySet());
            sorted.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
            for (int index = 0; index < Math.min(3, sorted.size()); index++) {
                Map.Entry<ResourceLocation, Integer> entry = sorted.get(index);
                Component name = BuiltInRegistries.BLOCK.getOptional(entry.getKey())
                        .map(block -> (Component) block.getName())
                        .orElseGet(() -> Component.literal(entry.getKey().toString()));
                this.label(Component.translatable("screen.m4cne.label.query_ore_line", name, entry.getValue()));
            }
        }
    }

    // --------------------------------------------------------------- widgets

    private void addButton(Component label, int entryWidth, Runnable action, String tooltipKey) {
        this.addButton(label, entryWidth, true, action, tooltipKey);
    }

    private void addButton(Component label, int entryWidth, boolean enabled, Runnable action, String tooltipKey) {
        boolean locked = this.lockedTooltipKey != null;
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            Button button = Button.builder(label, ignored -> action.run())
                    .bounds(this.x + 6, widgetY, entryWidth, ENTRY_HEIGHT)
                    .build();
            button.active = enabled && !locked;
            tip(button, locked ? this.lockedTooltipKey : tooltipKey);
            this.screen.addSidebarWidget(button);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    private void addFixedButton(Component label, int entryWidth, int widgetY, Runnable action, String tooltipKey) {
        Button button = Button.builder(label, ignored -> action.run())
                .bounds(this.x + 6, widgetY, entryWidth, ENTRY_HEIGHT)
                .build();
        tip(button, tooltipKey);
        this.screen.addSidebarWidget(button);
    }

    private void toggle(String labelKey, ModConfigSpec.BooleanValue value) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            MapToggleButton button = new MapToggleButton(
                    this.x + 6, widgetY, this.width - 12, ENTRY_HEIGHT,
                    Component.translatable(labelKey), value::get, value::set, this.screen::markMapDirty);
            this.applyLock(button, labelKey + ".tooltip");
            this.screen.addSidebarWidget(button);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    private void valueButton(String labelKey, int entryWidth, Supplier<Component> valueSupplier, Runnable action) {
        this.valueButton(labelKey, entryWidth, valueSupplier, action, true);
    }

    private void valueButton(String labelKey, int entryWidth, Supplier<Component> valueSupplier, Runnable action,
            boolean affectsMap) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            MapValueButton button = new MapValueButton(
                    this.x + 6, widgetY, entryWidth, ENTRY_HEIGHT,
                    Component.translatable(labelKey), valueSupplier, action,
                    affectsMap ? this.screen::markMapDirty : NO_MAP_CHANGE);
            this.applyLock(button, labelKey + ".tooltip");
            this.screen.addSidebarWidget(button);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    private void doubleSlider(String labelKey, ModConfigSpec.DoubleValue value, double minimum, double maximum,
            double step, Function<Double, Component> formatter) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            MapSlider slider = new MapSlider(
                    this.x + 6, widgetY, this.width - 12, ENTRY_HEIGHT,
                    Component.translatable(labelKey), minimum, maximum, step, value.get(), formatter,
                    value::set, this.screen::markMapDirty);
            this.applyLock(slider, labelKey + ".tooltip");
            this.screen.addSidebarWidget(slider);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    /**
     * Integer settings no longer use a slider. The player types the value into a
     * compact text field and confirms it with Enter (or by leaving the field).
     */
    private void intField(String labelKey, ModConfigSpec.IntValue value, int minimum, int maximum) {
        int widgetY = this.cursor;
        if (this.isVisible(widgetY, ENTRY_HEIGHT)) {
            MapNumberField field = new MapNumberField(
                    this.x + 6, widgetY, this.width - 12, ENTRY_HEIGHT,
                    Component.translatable(labelKey), minimum, maximum, value.get(),
                    value::set, this.screen::markMapDirty);
            if (this.lockedTooltipKey != null) {
                field.active = false;
                tip(field, this.lockedTooltipKey);
            } else {
                tip(field, labelKey + ".tooltip", "screen.m4cne.input.enter_confirm");
            }
            this.screen.addSidebarWidget(field);
        }
        this.cursor += ENTRY_HEIGHT + ENTRY_GAP;
    }

    /** Applies the permission lock of the current page to one widget. */
    private void applyLock(AbstractWidget widget, String tooltipKey) {
        if (this.lockedTooltipKey != null) {
            widget.active = false;
            tip(widget, this.lockedTooltipKey);
        } else {
            tip(widget, tooltipKey);
        }
    }

    private static void tip(AbstractWidget widget, String tooltipKey) {
        widget.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
    }

    /**
     * Adds a second tooltip line, used to explain how numeric inputs are confirmed.
     */
    private static void tip(AbstractWidget widget, String tooltipKey, String extraKey) {
        widget.setTooltip(Tooltip.create(Component.translatable(tooltipKey).copy()
                .append("\n")
                .append(Component.translatable(extraKey))));
    }

    private void section(String key) {
        this.section(Component.translatable(key));
    }

    private void section(Component text) {
        this.cursor += 4;
        if (this.isVisible(this.cursor, 11)) {
            this.headings.add(new Heading(text.copy().withStyle(ChatFormatting.GOLD), this.cursor,
                    this.x + 7));
        }
        this.cursor += 13;
    }

    private void label(Component text) {
        if (this.isVisible(this.cursor, 11)) {
            this.lines.add(new LabelLine(text.copy().withStyle(ChatFormatting.GRAY), this.cursor,
                    0xFFA0A0A0, this.x + 7));
        }
        this.cursor += 13;
    }

    /** Heading at an absolute position, used by the fixed two column pages. */
    private void headingAt(int xPos, int yPos, Component text) {
        this.headings.add(new Heading(text.copy().withStyle(ChatFormatting.GOLD), yPos, xPos));
    }

    /** Label at an absolute position, used by the fixed two column pages. */
    private void labelAt(int xPos, int yPos, Component text) {
        this.lines.add(new LabelLine(text.copy().withStyle(ChatFormatting.GRAY), yPos, 0xFFA0A0A0,
                xPos));
    }

    /** Button with an explicit position, used by the fixed two column pages. */
    private void addButtonAt(Component label, int widgetX, int widgetY, int widgetWidth, boolean enabled,
            Runnable action, String tooltipKey) {
        Button button = Button.builder(label, ignored -> action.run())
                .bounds(widgetX, widgetY, widgetWidth, ENTRY_HEIGHT)
                .build();
        button.active = enabled;
        tip(button, tooltipKey);
        this.screen.addSidebarWidget(button);
    }

    private boolean isVisible(int widgetY, int widgetHeight) {
        return widgetY >= this.scrollTop && widgetY + widgetHeight <= this.contentBottom;
    }

    private static Component percent(double value) {
        return Component.translatable("screen.m4cne.unit.percent", Math.round(value * 100.0D));
    }

    // --------------------------------------------------------------- scrolling

    public boolean scroll(double delta) {
        if (this.maxScroll <= 0.0D) {
            return false;
        }
        // Positive delta means the wheel was rolled up: the list scrolls up.
        double next = Math.max(0.0D, Math.min(this.maxScroll, this.scroll - delta * 20.0D));
        if (Math.abs(next - this.scroll) < 0.5D) {
            return false;
        }
        this.scroll = next;
        this.screen.requestSidebarRebuild();
        return true;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
    }

    // ---------------------------------------------------------------- drawing

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        // Vanilla menu background texture plus a darkening layer keeps the
        // white text readable while the buttons stay completely vanilla.
        Screen.renderMenuBackgroundTexture(graphics, Screen.MENU_BACKGROUND,
                this.x - 3, this.y, 0.0F, 0.0F, this.width + 3, this.height);
        graphics.fill(this.x - 3, this.y, this.x + this.width, this.y + this.height, 0x70000000);
        graphics.fill(this.x - 3, this.y, this.x - 2, this.y + this.height, 0xFF8B8B8B);
        graphics.fill(this.x, this.y, this.x + this.width, this.y + 1, 0xFF8B8B8B);
        graphics.fill(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, 0xFF4C4C4C);
        graphics.fill(this.x + 5, this.y + HEADER_HEIGHT - 5, this.x + this.width - 5, this.y + HEADER_HEIGHT - 4,
                0xFF555555);
        if (!this.page.isMain()) {
            // Separator that visually isolates the fixed back button from the
            // scrollable content below it.
            graphics.fill(this.x + 5, this.scrollTop - 4, this.x + this.width - 5, this.scrollTop - 3,
                    0xFF555555);
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (this.page.isMain()) {
            Component title = Component.translatable("screen.m4cne.title");
            graphics.drawString(minecraft.font, title, this.x + (this.width - minecraft.font.width(title)) / 2,
                    this.y + 8, 0xFFFFFFFF, true);
        } else {
            Component title = Component.translatable("screen.m4cne.title");
            Component pageTitle = Component.translatable(this.page.titleKey());
            Component breadcrumb = this.page.parent() == MapSettingsPage.MAIN
                    ? Component.translatable("screen.m4cne.breadcrumb", title, pageTitle)
                    : Component.translatable("screen.m4cne.breadcrumb3", title,
                            Component.translatable(this.page.parent().titleKey()), pageTitle);
            this.drawScaledString(graphics, minecraft, breadcrumb, this.x + 6, this.y + 8,
                    0xFFFFFFFF, this.width - 12);
        }

        for (Heading heading : this.headings) {
            graphics.drawString(minecraft.font, heading.text(), heading.x(), heading.y(), 0xFFFFD070, true);
        }
        for (LabelLine line : this.lines) {
            this.drawScaledString(graphics, minecraft, line.text(), line.x(), line.y(),
                    line.color(), this.width - 14);
        }

        Component footer = this.screen.statusMessage();
        if (footer == null) {
            footer = Component.translatable("screen.m4cne.sidebar.hint");
        }
        graphics.drawString(minecraft.font, footer, this.x + 6, this.y + this.height - 13, 0xFFB0B0B0, false);

        if (this.maxScroll > 0.0D) {
            int trackTop = this.scrollTop;
            int trackBottom = this.contentBottom;
            graphics.fill(this.x + this.width - 3, trackTop, this.x + this.width - 1, trackBottom, 0x60000000);
            double visible = this.contentBottom - this.scrollTop;
            int thumbHeight = Math.max(12, (int) (visible * visible / this.contentHeight));
            int thumbTop = trackTop + (int) ((trackBottom - trackTop - thumbHeight) * (this.scroll / this.maxScroll));
            graphics.fill(this.x + this.width - 3, thumbTop, this.x + this.width - 1, thumbTop + thumbHeight,
                    0xFFA0A0A0);
        }
    }

    private void drawScaledString(GuiGraphics graphics, Minecraft minecraft, Component text,
            int x, int y, int color, int maxWidth) {
        int textWidth = minecraft.font.width(text);
        float scale = textWidth > maxWidth ? maxWidth / (float) textWidth : 1.0F;
        scale = Math.max(0.5F, scale);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(minecraft.font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    /** Utility used by the HUD to render state names. */
    public static Component layerName(MapLayer layer) {
        return Component.translatable(layer.translationKey());
    }

    /** Utility used by the HUD to render the switch mode name. */
    public static Component modeName(LayerSwitchMode mode) {
        return Component.translatable(mode.translationKey());
    }

    /** Utility used by the HUD to render the entity display mode name. */
    public static Component displayModeName(EntityDisplayMode mode) {
        return Component.translatable(mode.translationKey());
    }

    /** Utility used by the HUD to render the marker style name. */
    public static Component markerStyleName(EntityMarkerStyle style) {
        return Component.translatable(style.translationKey());
    }

    /** Utility used by the HUD to render the minimap mode name. */
    public static Component hudModeName(HudMode mode) {
        return Component.translatable(mode.translationKey());
    }

    /** Utility used by the HUD to render the render layer name. */
    public static Component hudLayerName(HudRenderLayer layer) {
        return layer.getTranslatedName();
    }
}
