package com.wwwwz.aigcs.client.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.wwwwz.aigcs.client.ClientChunkRecorder;
import com.wwwwz.aigcs.client.ClientChunkStore;
import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.ClientKeyBindings;
import com.wwwwz.aigcs.client.ClientWorldKey;
import com.wwwwz.aigcs.client.claim.ClaimColors;
import com.wwwwz.aigcs.client.claim.ClaimStore;
import com.wwwwz.aigcs.client.claim.ClaimSyncManager;
import com.wwwwz.aigcs.client.chunk.ChunkQueryService;
import com.wwwwz.aigcs.client.entity.ClientEntityMarkerCache;
import com.wwwwz.aigcs.client.gui.widget.MapNumberField;
import com.wwwwz.aigcs.client.hud.HudMode;
import com.wwwwz.aigcs.client.map.CaveTileCache;
import com.wwwwz.aigcs.client.map.ChunkColorCache;
import com.wwwwz.aigcs.client.map.HighlightedPlayers;
import com.wwwwz.aigcs.client.map.LayerSwitchMode;
import com.wwwwz.aigcs.client.map.MapCamera;
import com.wwwwz.aigcs.client.map.MapLayer;
import com.wwwwz.aigcs.client.map.MapTextureRenderer;
import com.wwwwz.aigcs.client.map.MapViewport;
import com.wwwwz.aigcs.client.map.StructureScanController;
import com.wwwwz.aigcs.client.map.WaypointStore;
import com.wwwwz.aigcs.client.preload.PreloadCenterMode;
import com.wwwwz.aigcs.client.preload.PreloadController;
import com.wwwwz.aigcs.client.preload.PreloadMarkerStore;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.network.TeleportRequestPayload;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Full screen world map.
 * <p>
 * Layout: the map canvas takes the left part of the screen while the settings
 * column sits on the right edge. The canvas keeps a 4:3 .. 3:2 aspect ratio and
 * grows as far as the remaining space allows. All widgets are vanilla buttons
 * and sliders.
 */
@SuppressWarnings("null")
public final class WorldMapScreen extends Screen {
    private static final int MARGIN = 8;
    private static final double MIN_ASPECT = 4.0D / 3.0D;
    private static final double MAX_ASPECT = 3.0D / 2.0D;
    /** Selection drag modes, decided by the modifier keys while dragging. */
    private static final int CLAIM_DRAG_REPLACE = 0;
    private static final int CLAIM_DRAG_ADD = 1;
    private static final int CLAIM_DRAG_REMOVE = 2;
    /** Upper bound of chunks one drag may select. */
    private static final int MAX_DRAG_CHUNKS_PER_AXIS = 128;

    private final MapCamera camera = new MapCamera();
    private final MapTextureRenderer mapTexture = new MapTextureRenderer();
    private final MapOverlayRenderer overlays = new MapOverlayRenderer();
    private final PreloadOverlay preloadOverlay = new PreloadOverlay();
    private final MapContextMenu contextMenu = new MapContextMenu();
    private final MapSettingsPanel settingsPanel = new MapSettingsPanel(this);
    private final HudLayoutPreview hudPreview = new HudLayoutPreview();
    private final ClaimOverlay claims = new ClaimOverlay();
    private final ClaimSelection claimSelection = new ClaimSelection();

    private MapViewport viewport = new MapViewport(0, 0, 1, 1);
    private boolean initialised;
    private boolean sidebarNeedsRebuild;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;
    private Component status;
    private long statusUntil;
    private String worldKey = "";
    private MapLayer lastLayer;
    private int lastSliceY = Integer.MIN_VALUE;
    private long lastPreloadVersion = Long.MIN_VALUE;
    private long lastPreloadRebuildAt;
    private long lastWaypointVersion = Long.MIN_VALUE;
    private long lastWaypointRebuildAt;
    private long lastStructureVersion = Long.MIN_VALUE;
    private long lastStructureRebuildAt;
    /** Claim store / selection state the page was last rebuilt for. */
    private long lastClaimVersion = Long.MIN_VALUE;
    private int lastClaimSelectionSize = -1;
    private long lastClaimRebuildAt;
    /** Modifier of the running claim selection drag. */
    private int claimDragMode = CLAIM_DRAG_REPLACE;
    /** Page the settings column was last laid out for. */
    private MapSettingsPage lastPanelPage;

    public WorldMapScreen() {
        super(Component.translatable("screen.m4cne.title"));
    }

    @Override
    protected void init() {
        super.init();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!this.initialised && player != null) {
            this.initialised = true;
            this.camera.centerOn(player.getX(), player.getZ());
            this.camera.resetZoom();
        }

        this.worldKey = ClientWorldKey.current();
        WaypointStore.get().ensureWorld(this.worldKey);
        CaveTileCache.get().ensureWorld(this.worldKey);
        PreloadMarkerStore.get().ensureWorld(this.worldKey);
        PreloadController.get().setScreenOpen(true);

        int panelWidth = Mth.clamp((int) (this.width * 0.19D), 156, 250);
        panelWidth = Math.min(panelWidth, Math.max(80, this.width - 140));
        int panelX = this.width - panelWidth - MARGIN;
        int availableWidth = Math.max(60, panelX - MARGIN * 2);
        int availableHeight = Math.max(60, this.height - MARGIN * 2);
        double wantedAspect = Mth.clamp(availableWidth / (double) availableHeight, MIN_ASPECT, MAX_ASPECT);

        int mapWidth;
        int mapHeight;
        if (availableWidth / (double) availableHeight > wantedAspect) {
            mapHeight = availableHeight;
            mapWidth = (int) Math.round(mapHeight * wantedAspect);
        } else {
            mapWidth = availableWidth;
            mapHeight = (int) Math.round(mapWidth / wantedAspect);
        }
        int mapX = MARGIN + (availableWidth - mapWidth) / 2;
        int mapY = MARGIN + (availableHeight - mapHeight) / 2;
        this.viewport = new MapViewport(mapX, mapY, mapWidth, mapHeight);

        this.settingsPanel.setBounds(panelX, MARGIN, panelWidth, this.height - MARGIN * 2);
        // Force the first render to re-apply the layout: list pages (waypoint
        // manager, chunk list, entity rules) span the whole window and must not
        // keep the narrow side bar bounds after the screen was re-opened.
        this.lastPanelPage = null;
        this.mapTexture.markDirty();
    }

    @Override
    public void tick() {
        super.tick();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return;
        }
        String currentWorld = ClientWorldKey.current();
        if (!currentWorld.equals(this.worldKey)) {
            this.worldKey = currentWorld;
            WaypointStore.get().ensureWorld(currentWorld);
            CaveTileCache.get().ensureWorld(currentWorld);
            this.settingsPanel.rebuild();
        }

        ResourceLocation dimension = level.dimension().location();
        this.claimSelection.ensureDimension(dimension.toString());
        MapLayer layer = this.effectiveLayer(level, player);
        int sliceY = this.caveSliceY(level, player);
        if (layer != this.lastLayer || sliceY != this.lastSliceY) {
            this.lastLayer = layer;
            this.lastSliceY = sliceY;
            this.mapTexture.markDirty();
        }

        if (layer == MapLayer.CAVE && !this.settingsPanel.hidesMap()
                && this.mapTexture.prefetchCave(level, dimension, this.camera, this.viewport.width(),
                        this.viewport.height(), sliceY, 6)) {
            this.overlays.update(dimension, this.camera, this.viewport);
            this.mapTexture.markDirty();
        }

        if (this.status != null && System.currentTimeMillis() > this.statusUntil) {
            this.status = null;
        }
        this.refreshPreloadPanel();
        this.refreshWaypointPanel();
        this.refreshStructurePanel();
        this.refreshClaimPanel();
    }

    /**
     * Keeps the claim page labels in sync with the synced claims and the current
     * box selection.
     */
    private void refreshClaimPanel() {
        MapSettingsPage page = this.settingsPanel.page();
        if (page != MapSettingsPage.CLAIMS && page != MapSettingsPage.CLAIM_CHUNKS
                && page != MapSettingsPage.CLAIM_ENTITY_RULES) {
            return;
        }
        long version = ClaimStore.get().version();
        int selectionSize = this.claimSelection.size();
        if (version == this.lastClaimVersion && selectionSize == this.lastClaimSelectionSize) {
            return;
        }
        if (this.getFocused() instanceof EditBox || this.getFocused() instanceof MapNumberField) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastClaimRebuildAt < 400L) {
            return;
        }
        this.lastClaimVersion = version;
        this.lastClaimSelectionSize = selectionSize;
        this.lastClaimRebuildAt = now;
        this.requestSidebarRebuild();
    }

    /** Updates the structure scan page when a result or timeout arrives. */
    private void refreshStructurePanel() {
        if (this.settingsPanel.page() != MapSettingsPage.STRUCTURE_SCAN) {
            return;
        }
        StructureScanController controller = StructureScanController.get();
        long version = controller.version();
        if (version == this.lastStructureVersion) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastStructureRebuildAt < 300L) {
            return;
        }
        // The page only has a free text field; the text is restored by the
        // rebuild, so the status can update even while it has focus.
        if (this.getFocused() instanceof MapNumberField) {
            return;
        }
        this.lastStructureVersion = version;
        this.lastStructureRebuildAt = now;
        this.requestSidebarRebuild();
    }

    /**
     * Keeps the waypoint manager list, the sync countdown and the recycle bin
     * countdown live while one of those pages is open.
     */
    private void refreshWaypointPanel() {
        MapSettingsPage page = this.settingsPanel.page();
        if (page != MapSettingsPage.WAYPOINT_MANAGE && page != MapSettingsPage.WAYPOINT_TRASH) {
            return;
        }
        WaypointStore store = WaypointStore.get();
        long version = store.version();
        long now = System.currentTimeMillis();
        boolean countdown = page == MapSettingsPage.WAYPOINT_TRASH && store.trashCount() > 0;
        boolean changed = version != this.lastWaypointVersion;
        if (!changed && !(countdown && now - this.lastWaypointRebuildAt >= 1000L)) {
            return;
        }
        // Never rebuild while the player types into one of the number fields.
        if (this.getFocused() instanceof EditBox || this.getFocused() instanceof MapNumberField) {
            return;
        }
        if (now - this.lastWaypointRebuildAt < 500L) {
            return;
        }
        this.lastWaypointVersion = version;
        this.lastWaypointRebuildAt = now;
        this.requestSidebarRebuild();
    }

    /**
     * The preload page shows live counters, so it has to be rebuilt while a task
     * runs. Rebuilds are throttled and skipped while a slider is dragged.
     */
    private void refreshPreloadPanel() {
        if (this.settingsPanel.page() != MapSettingsPage.PRELOAD) {
            return;
        }
        PreloadController controller = PreloadController.get();
        long version = controller.statusVersion();
        if (version == this.lastPreloadVersion) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastPreloadRebuildAt < 700L) {
            return;
        }
        if (GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) {
            return;
        }
        // Never rebuild the page while the player is typing into a field.
        if (this.getFocused() instanceof EditBox || this.getFocused() instanceof MapNumberField) {
            return;
        }
        this.lastPreloadVersion = version;
        this.lastPreloadRebuildAt = now;
        this.requestSidebarRebuild();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.updatePanelLayout();
        if (this.sidebarNeedsRebuild) {
            this.sidebarNeedsRebuild = false;
            this.settingsPanel.rebuild();
        }
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        this.drawMapFrame(graphics);
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;

        if (level != null && player != null) {
            ResourceLocation dimension = level.dimension().location();
            MapLayer layer = this.effectiveLayer(level, player);
            int sliceY = this.caveSliceY(level, player);
            if (this.isLayoutPreviewPage()) {
                // The layout pages replace the canvas with a preview of the game
                // window so the widget position and scale can be judged directly.
                this.hudPreview.render(graphics, this.viewport, minecraft, this.layoutPreviewMode());
            } else if (this.settingsPanel.hidesMap()) {
                // The waypoint / recycle bin lists replace the map rendering
                // completely, so the canvas stays empty behind the wide panel.
            } else {
                this.overlays.update(dimension, this.camera, this.viewport);

                graphics.enableScissor(this.viewport.x(), this.viewport.y(),
                        this.viewport.x() + this.viewport.width(), this.viewport.y() + this.viewport.height());
                this.mapTexture.draw(graphics, level, dimension, this.camera,
                        this.viewport.x(), this.viewport.y(), this.viewport.width(), this.viewport.height(),
                        layer, sliceY);
                this.overlays.drawBiomes(graphics, this.camera, this.viewport, dimension);
                this.overlays.drawSlimeChunks(graphics, this.camera, this.viewport, dimension);
                this.claims.drawClaims(graphics, this.camera, this.viewport, dimension);
                this.drawDayNightDim(graphics, level);
                this.overlays.drawGrid(graphics, this.camera, this.viewport);
                this.overlays.drawOreIcons(graphics, this.camera, this.viewport, dimension);
                this.overlays.drawRadar(graphics, this.camera, this.viewport);
                this.overlays.drawWaypointMarkers(graphics, this.camera, this.viewport, dimension);
                this.overlays.drawOtherPlayers(graphics, this.camera, this.viewport);
                this.preloadOverlay.drawRange(graphics, this.camera, this.viewport, PreloadController.get().status());
                this.preloadOverlay.drawMarkers(graphics, this.camera, this.viewport, dimension);
                this.overlays.drawQueryHighlight(graphics, this.camera, this.viewport);
                this.overlays.drawPlayerMarker(graphics, player, this.camera, this.viewport);
                if (!this.dragging && this.viewport.contains(mouseX, mouseY)
                        && ClientConfig.OVERLAY_HIGHLIGHT_HOVERED.getAsBoolean()) {
                    this.overlays.drawHoveredChunk(graphics, this.camera, this.viewport, mouseX, mouseY);
                }
                // HUD pass: every HUD element is drawn after all map icons, so
                // nothing on the map can cover it.
                this.overlays.drawChunkLabels(graphics, this.camera, this.viewport, dimension);
                this.overlays.drawWaypointLabels(graphics, this.camera, this.viewport, dimension);
                this.claims.drawSelection(graphics, this.camera, this.viewport, this.claimSelection);
                this.overlays.drawSelectedEntity(graphics, this.camera, this.viewport);
                if (!this.dragging && this.viewport.contains(mouseX, mouseY)
                        && ClientConfig.OVERLAY_HIGHLIGHT_HOVERED.getAsBoolean()) {
                    this.overlays.drawChunkTooltip(graphics, this.camera, this.viewport, mouseX, mouseY, dimension);
                }
                this.overlays.drawHud(graphics, this.camera, this.viewport, player, layer,
                        ClientConfig.MAP_LAYER_MODE.get(), sliceY);
                graphics.disableScissor();
            }
        } else {
            Component message = Component.translatable("screen.m4cne.no_world");
            graphics.drawString(minecraft.font, message,
                    this.viewport.centerX() - minecraft.font.width(message) / 2,
                    this.viewport.centerY(), 0xFFFFFFFF, true);
        }

        this.drawMapBorder(graphics);
        this.settingsPanel.render(graphics, mouseX, mouseY);
        for (Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        this.contextMenu.render(graphics, mouseX, mouseY);
    }

    /**
     * Re-lays out the settings column when the page changed. The waypoint
     * manager and the recycle bin are drawn above the whole map canvas, so
     * their panel spans the window width while the map itself is not drawn.
     */
    private void updatePanelLayout() {
        MapSettingsPage page = this.settingsPanel.page();
        if (page == this.lastPanelPage) {
            return;
        }
        this.lastPanelPage = page;
        int panelWidth = Mth.clamp((int) (this.width * 0.19D), 156, 250);
        panelWidth = Math.min(panelWidth, Math.max(80, this.width - 140));
        int panelX = this.width - panelWidth - MARGIN;
        if (this.settingsPanel.hidesMap()) {
            panelX = MARGIN;
            panelWidth = Math.max(120, this.width - MARGIN * 2);
        }
        this.settingsPanel.setBounds(panelX, MARGIN, panelWidth, this.height - MARGIN * 2);
    }

    private void drawMapFrame(GuiGraphics graphics) {
        graphics.fill(this.viewport.x() - 4, this.viewport.y() - 4,
                this.viewport.x() + this.viewport.width() + 4,
                this.viewport.y() + this.viewport.height() + 4, 0xFF000000);
        graphics.fill(this.viewport.x() - 3, this.viewport.y() - 3,
                this.viewport.x() + this.viewport.width() + 3,
                this.viewport.y() + this.viewport.height() + 3, 0xFF2B2B2B);
        graphics.fill(this.viewport.x(), this.viewport.y(),
                this.viewport.x() + this.viewport.width(),
                this.viewport.y() + this.viewport.height(), 0xFF0C0C0C);
    }

    private void drawMapBorder(GuiGraphics graphics) {
        int x0 = this.viewport.x() - 1;
        int y0 = this.viewport.y() - 1;
        int x1 = this.viewport.x() + this.viewport.width() + 1;
        int y1 = this.viewport.y() + this.viewport.height() + 1;
        graphics.fill(x0, y0, x1, y0 + 1, 0xFF9A9A9A);
        graphics.fill(x0, y1 - 1, x1, y1, 0xFF4A4A4A);
        graphics.fill(x0, y0, x0 + 1, y1, 0xFF9A9A9A);
        graphics.fill(x1 - 1, y0, x1, y1, 0xFF4A4A4A);
    }

    /**
     * Applies the day/night brightness as one translucent layer. Baking it into
     * the map texture would invalidate every cached chunk colour on every
     * sunrise, so it is drawn on top instead.
     */
    private void drawDayNightDim(GuiGraphics graphics, ClientLevel level) {
        if (!ClientConfig.MAP_DAY_NIGHT.getAsBoolean()) {
            return;
        }
        float darken = level.getSkyDarken() / 11.0F;
        if (darken <= 0.01F) {
            return;
        }
        int alpha = (int) Math.round(darken * 140.0F * ClientConfig.MAP_LIGHT_INTENSITY.get());
        if (alpha <= 0) {
            return;
        }
        graphics.fill(this.viewport.x(), this.viewport.y(),
                this.viewport.x() + this.viewport.width(), this.viewport.y() + this.viewport.height(),
                alpha << 24);
    }

    // ---------------------------------------------------------------- layout

    /** @return {@code true} while one of the third level layout previews is open */
    private boolean isLayoutPreviewPage() {
        MapSettingsPage page = this.settingsPanel.page();
        return page == MapSettingsPage.MINIMAP_LAYOUT || page == MapSettingsPage.COMPASS_LAYOUT;
    }

    /** @return the widget drawn by the currently open layout preview */
    private HudMode layoutPreviewMode() {
        return this.settingsPanel.page() == MapSettingsPage.COMPASS_LAYOUT ? HudMode.COMPASS : HudMode.MINIMAP;
    }

    private MapLayer effectiveLayer(ClientLevel level, LocalPlayer player) {
        if (ClientConfig.MAP_LAYER_MODE.get() == LayerSwitchMode.MANUAL) {
            return ClientConfig.MAP_MANUAL_LAYER.get();
        }
        int blockX = Mth.floor(player.getX());
        int blockZ = Mth.floor(player.getZ());
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
        int threshold = ClientConfig.MAP_AUTO_CAVE_DEPTH.get();
        return player.getY() < surface - threshold ? MapLayer.CAVE : MapLayer.SURFACE;
    }

    private int caveSliceY(ClientLevel level, LocalPlayer player) {
        int offset = ClientConfig.MAP_CAVE_SLICE_OFFSET.get();
        int y = Mth.floor(player.getY()) + offset;
        // Snap to 8 block slices: walking up and down inside a cave then does
        // not invalidate and resample the whole visible cave every block.
        y = (y >> 3) << 3;
        return Mth.clamp(y, level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 1);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Commit a numeric input before any other widget handles the click, so
        // e.g. pressing "start preload" uses the value the player just typed.
        if (this.getFocused() instanceof MapNumberField field && !field.isMouseOver(mouseX, mouseY)) {
            field.commit();
        }
        // The context menu grabs the click even when it lands outside of it,
        // so a click that dismisses the menu never starts a map drag.
        if (this.contextMenu.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.viewport.contains(mouseX, mouseY) && !this.settingsPanel.hidesMap()) {
            if (this.isLayoutPreviewPage()) {
                // While a layout preview is open the canvas places the widget
                // instead of panning the map.
                HudMode previewMode = this.layoutPreviewMode();
                if (button == 0 && this.hudPreview.placeAt(mouseX, mouseY, previewMode)) {
                    if (previewMode == HudMode.COMPASS) {
                        this.showStatus(Component.translatable("screen.m4cne.hud_layout.status_compass",
                                ClientConfig.COMPASS_POS_X.getAsInt(), ClientConfig.COMPASS_POS_Y.getAsInt()));
                    } else {
                        this.showStatus(Component.translatable("screen.m4cne.hud_layout.status_minimap",
                                Component.translatable(ClientConfig.MINIMAP_CORNER.get().translationKey())));
                    }
                    return true;
                }
                return false;
            }
            if (button == 0) {
                // Left clicking a radar entity selects it instead of panning.
                if (this.overlays.pickEntity(this.camera, this.viewport, mouseX, mouseY)) {
                    this.dragging = false;
                    return true;
                }
                this.overlays.clearSelection();
                if (ClientConfig.CLAIM_SELECT_LEFT_DRAG.getAsBoolean()) {
                    // Hold and drag selects chunks for the claim management; the
                    // middle mouse button still pans the map.
                    this.beginClaimDrag(mouseX, mouseY);
                    return true;
                }
                this.dragging = true;
                this.lastMouseX = mouseX;
                this.lastMouseY = mouseY;
                return true;
            }
            if (button == 1) {
                this.openContextMenu(mouseX, mouseY);
                return true;
            }
            if (button == 2) {
                // Middle mouse always pans, even while the left drag selects.
                this.dragging = true;
                this.lastMouseX = mouseX;
                this.lastMouseY = mouseY;
                return true;
            }
        }
        return false;
    }

    /**
     * Right click menu of the map. Right clicking a bright centre marker offers
     * to use or delete it, right clicking empty terrain offers to place one.
     */
    private void openContextMenu(double mouseX, double mouseY) {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            return;
        }
        List<MapContextMenu.Entry> entries = new ArrayList<>();
        // Right clicking a selected area opens the quick settings of the chunk
        // claim management page.
        int selectionSize = this.claimSelection.size();
        if (selectionSize > 0) {
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.claim_selection", selectionSize),
                    this::claimSelectedChunks));
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.unclaim_selection"),
                    this::unclaimSelectedChunks));
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.force_selection"),
                    this::forceLoadSelectedChunks));
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.unforce_selection"),
                    this::clearSelectedForceLoad));
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.clear_selection"),
                    this::clearClaimSelection));
        }
        int hoveredChunkX = Mth.floor(this.viewport.screenToWorldX(this.camera, mouseX)) >> 4;
        int hoveredChunkZ = Mth.floor(this.viewport.screenToWorldZ(this.camera, mouseY)) >> 4;
        ClaimStore.Ref claimed = ClaimStore.get().refAt(dimension.toString(), hoveredChunkX, hoveredChunkZ);
        if (claimed != null) {
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.claim_info", claimed.owner().name(),
                            claimed.displayId(hoveredChunkX, hoveredChunkZ),
                            Component.translatable(claimed.forceLoad() ? "screen.m4cne.state.enabled"
                                    : "screen.m4cne.state.disabled")),
                    null, false));
        }
        // Right clicking a waypoint marker offers its teleport and recycle bin
        // actions.
        WaypointStore.Waypoint waypoint = this.overlays.pickWaypoint(this.camera, this.viewport, mouseX,
                mouseY, dimension);
        if (waypoint != null) {
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.waypoint_teleport"),
                    () -> this.teleportToWaypoint(waypoint)));
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.waypoint_trash"),
                    () -> this.trashWaypoint(waypoint)));
        }
        // Right clicking a player icon offers the highlight toggles plus the
        // teleport to that player.
        ClientEntityMarkerCache.Marker player = this.overlays.pickPlayer(this.camera, this.viewport, mouseX,
                mouseY);
        if (player != null) {
            boolean highlighted = HighlightedPlayers.isHighlighted(player.id());
            entries.add(new MapContextMenu.Entry(
                    Component.translatable(highlighted ? "screen.m4cne.context.player_unhighlight"
                            : "screen.m4cne.context.player_highlight", player.name()),
                    () -> this.togglePlayerHighlight(player)));
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.player_teleport", player.name()),
                    () -> this.teleportToPlayer(player)));
        }
        // Preload entries stay visible but disabled when the server did not
        // grant the permission, so the reason is obvious instead of the menu
        // simply missing its entries.
        boolean preloadUsable = PreloadController.get().canControlPreload();
        PreloadMarkerStore.Marker marker = this.preloadOverlay.pickMarker(this.camera, this.viewport, dimension,
                mouseX, mouseY);
        if (marker != null) {
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.preload_use"),
                    () -> this.setPreloadMarkerActive(marker), preloadUsable));
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.preload_delete"),
                    () -> this.deletePreloadMarker(marker), preloadUsable));
        } else {
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.context.preload_mark"),
                    () -> this.addPreloadMarkerAtScreen(mouseX, mouseY), preloadUsable));
        }
        if (!preloadUsable) {
            entries.add(new MapContextMenu.Entry(
                    Component.translatable("screen.m4cne.preload.no_permission.short"), null, false));
        }
        entries.add(new MapContextMenu.Entry(
                Component.translatable("screen.m4cne.context.quick_teleport"),
                () -> this.quickTeleportAtScreen(mouseX, mouseY)));
        entries.add(new MapContextMenu.Entry(
                Component.translatable("screen.m4cne.context.waypoint_new_here"),
                () -> this.openWaypointEditorAtScreen(mouseX, mouseY)));
        this.contextMenu.open(mouseX, mouseY, entries, this.viewport);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.claims.isDragging() && button == 0) {
            // Modifiers can still change while dragging, so the release uses the
            // state of the keys at that moment.
            this.claimDragMode = hasControlDown() ? CLAIM_DRAG_REMOVE
                    : (hasShiftDown() ? CLAIM_DRAG_ADD : CLAIM_DRAG_REPLACE);
            this.claims.updateDrag((int) mouseX, (int) mouseY);
            return true;
        }
        if (this.dragging && (button == 0 || button == 2)) {
            double deltaX = mouseX - this.lastMouseX;
            double deltaY = mouseY - this.lastMouseY;
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
            this.camera.panPixels(deltaX, deltaY);
            this.mapTexture.markDirty();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.claims.isDragging()) {
            this.claims.updateDrag((int) mouseX, (int) mouseY);
            this.finishClaimDrag();
            return true;
        }
        if (button == 0 || button == 2) {
            this.dragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Starts the claim selection drag; the modifier decides add / remove. */
    private void beginClaimDrag(double mouseX, double mouseY) {
        this.dragging = false;
        this.claimDragMode = hasControlDown() ? CLAIM_DRAG_REMOVE
                : (hasShiftDown() ? CLAIM_DRAG_ADD : CLAIM_DRAG_REPLACE);
        this.claims.beginDrag((int) Math.round(mouseX), (int) Math.round(mouseY),
                (int) Math.round(mouseX), (int) Math.round(mouseY));
    }

    /** Applies the finished drag rectangle to the chunk selection. */
    private void finishClaimDrag() {
        int startX = this.claims.dragStartX();
        int startY = this.claims.dragStartY();
        int endX = this.claims.dragEndX();
        int endY = this.claims.dragEndY();
        int mode = this.claimDragMode;
        this.claims.endDrag();
        this.applySelection(startX, startY, endX, endY, mode);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.contextMenu.isOpen()) {
            this.contextMenu.close();
        }
        if (this.settingsPanel.contains(mouseX, mouseY) && this.settingsPanel.scroll(scrollY)) {
            return true;
        }
        if (this.viewport.contains(mouseX, mouseY) && scrollY != 0.0D
                && !this.settingsPanel.hidesMap()) {
            if (this.isLayoutPreviewPage()) {
                return false;
            }
            double anchorWorldX = this.viewport.screenToWorldX(this.camera, mouseX);
            double anchorWorldZ = this.viewport.screenToWorldZ(this.camera, mouseY);
            // Wheel up reports a positive delta and zooms in; the optional
            // inversion flips that for players used to the opposite scheme.
            double direction = ClientConfig.MAP_INVERT_WHEEL_ZOOM.getAsBoolean() ? -scrollY : scrollY;
            this.camera.zoomAt(direction, anchorWorldX, anchorWorldZ,
                    mouseX - this.viewport.centerX(), mouseY - this.viewport.centerY());
            this.mapTexture.markDirty();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter scans the structure name typed into the structure scan page.
        if (keyCode == GLFW.GLFW_KEY_ENTER && this.settingsPanel.page() == MapSettingsPage.STRUCTURE_SCAN) {
            this.settingsPanel.scanTypedStructure();
            return true;
        }
        // Let the coordinate fields and numeric inputs handle their own keys.
        if (this.getFocused() instanceof EditBox || this.getFocused() instanceof MapNumberField) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && this.contextMenu.isOpen()) {
            this.contextMenu.close();
            return true;
        }
        if (ClientKeyBindings.OPEN_MAP.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        if (ClientKeyBindings.TOGGLE_HUD.matches(keyCode, scanCode)) {
            return true;
        }
        if (ClientKeyBindings.CYCLE_HUD.matches(keyCode, scanCode)) {
            return true;
        }
        if (ClientKeyBindings.NEW_WAYPOINT.matches(keyCode, scanCode)) {
            this.openWaypointEditorAtPlayer();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                this.camera.centerOn(minecraft.player.getX(), minecraft.player.getZ());
                this.camera.resetZoom();
                this.mapTexture.markDirty();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        ClientConfig.SPEC.save();
        this.overlays.clearSelection();
        this.claims.endDrag();
        this.contextMenu.close();
        PreloadController.get().setScreenOpen(false);
        this.mapTexture.close();
        CaveTileCache.get().clear();
        super.removed();
    }

    // ------------------------------------------------- service methods for UI

    public void addSidebarWidget(AbstractWidget widget) {
        this.addRenderableWidget(widget);
    }

    /**
     * Defers a menu rebuild to the next frame, safe for button callbacks.
     */
    public void requestSidebarRebuild() {
        this.sidebarNeedsRebuild = true;
    }

    public void clearSidebarWidgets() {
        // The query text fields are recreated on every rebuild, so drop the
        // focus first instead of leaving it on a removed widget.
        this.setFocused(null);
        this.clearWidgets();
    }

    /**
     * Marks the canvas dirty. Any visual setting change also invalidates the
     * baked chunk colours, so both caches stay in sync.
     */
    public void markMapDirty() {
        ChunkColorCache.get().bumpStyle();
        this.mapTexture.markDirty();
    }

    public int sampledChunkCount() {
        ResourceLocation dimension = this.currentDimension();
        return dimension != null ? ClientChunkStore.get().size(dimension) : 0;
    }

    public int caveCacheSize() {
        return CaveTileCache.get().size();
    }

    public void scanLoadedChunksNow() {
        ClientChunkRecorder.get().requestImmediateSweep();
    }

    public void clearMapCache() {
        ClientChunkRecorder.get().clearSampledCache();
        this.mapTexture.markDirty();
    }

    public void addWaypointAtPlayer() {
        this.openWaypointEditorAtPlayer();
    }

    public void addWaypointAtCenter() {
        int x = Mth.floor(this.camera.centerX());
        int z = Mth.floor(this.camera.centerZ());
        this.openWaypointEditor(x, this.targetHeightAt(x, z), z);
    }

    /** Opens the "new waypoint" editor at the player position. */
    public void openWaypointEditorAtPlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return;
        }
        this.openWaypointEditor(Mth.floor(player.getX()), Mth.floor(player.getY()), Mth.floor(player.getZ()));
    }

    /** Opens the "new waypoint" editor with fixed coordinates. */
    public void openWaypointEditor(int x, int y, int z) {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return;
        }
        String name = Component.translatable("screen.m4cne.waypoint.default_name",
                WaypointStore.get().count() + 1).getString();
        Minecraft.getInstance().setScreen(new WaypointEditScreen(this, dimension, x, y, z, name));
    }

    /**
     * Opens the editor of an existing waypoint to change name, category and colour.
     */
    public void openWaypointEditor(WaypointStore.Waypoint waypoint) {
        if (waypoint == null) {
            return;
        }
        Minecraft.getInstance().setScreen(new WaypointEditScreen(this, waypoint));
    }

    private void openWaypointEditorAtScreen(double mouseX, double mouseY) {
        int x = Mth.floor(this.viewport.screenToWorldX(this.camera, mouseX));
        int z = Mth.floor(this.viewport.screenToWorldZ(this.camera, mouseY));
        this.openWaypointEditor(x, this.targetHeightAt(x, z), z);
    }

    /** Moves a waypoint from the map directly into the recycle bin. */
    private void trashWaypoint(WaypointStore.Waypoint waypoint) {
        if (WaypointStore.get().moveToTrash(waypoint.id())) {
            this.markMapDirty();
            this.showStatus(Component.translatable("screen.m4cne.status.waypoint_trashed", waypoint.name()));
            this.requestSidebarRebuild();
        }
    }

    /** Teleports the player to a waypoint of the current dimension. */
    public void teleportToWaypoint(WaypointStore.Waypoint waypoint) {
        if (waypoint == null) {
            return;
        }
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null || !waypoint.dimensionId().equals(dimension)) {
            this.showStatus(Component.translatable("screen.m4cne.status.teleport_wrong_dimension"));
            return;
        }
        this.teleportTo(dimension, waypoint.x() + 0.5D, waypoint.y(), waypoint.z() + 0.5D, false,
                Component.translatable("screen.m4cne.status.teleport_waypoint", waypoint.name()));
    }

    /** Teleports the player to a picked player marker. */
    public void teleportToPlayer(ClientEntityMarkerCache.Marker marker) {
        ResourceLocation dimension = this.currentDimension();
        if (marker == null || dimension == null) {
            return;
        }
        this.teleportTo(dimension, marker.x(), marker.y(), marker.z(), false,
                Component.translatable("screen.m4cne.status.teleport_player", marker.name()));
    }

    /** Teleports to the clicked map point, following the displayed layer. */
    private void quickTeleportAtScreen(double mouseX, double mouseY) {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        LocalPlayer player = Minecraft.getInstance().player;
        boolean surface = level == null || player == null
                || this.effectiveLayer(level, player) == MapLayer.SURFACE;
        int x = Mth.floor(this.viewport.screenToWorldX(this.camera, mouseX));
        int z = Mth.floor(this.viewport.screenToWorldZ(this.camera, mouseY));
        this.teleportTo(dimension, x + 0.5D, this.targetHeightAt(x, z), z + 0.5D, surface,
                Component.translatable("screen.m4cne.status.teleporting"));
    }

    /**
     * Sends the teleport request. Servers with this mod resolve a safe feet
     * position themselves; without the mod the client resolves a safe Y from the
     * loaded chunks and uses the vanilla teleport command as a fallback.
     *
     * @param surface {@code true} to align with the terrain surface
     */
    private boolean teleportTo(ResourceLocation dimension, double x, double y, double z, boolean surface,
            Component message) {
        LocalPlayer player = Minecraft.getInstance().player;
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (player == null || listener == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return false;
        }
        if (!player.hasPermissions(2)) {
            this.showStatus(Component.translatable("screen.m4cne.status.teleport_no_permission"));
            return false;
        }
        boolean channel;
        try {
            channel = NetworkRegistry.hasChannel(listener.getConnection(), ConnectionProtocol.PLAY,
                    TeleportRequestPayload.TYPE.id());
        } catch (RuntimeException exception) {
            channel = false;
        }
        if (channel) {
            try {
                PacketDistributor.sendToServer(new TeleportRequestPayload(dimension, x, y, z, surface));
            } catch (RuntimeException exception) {
                this.showStatus(Component.translatable("screen.m4cne.status.teleport_no_permission"));
                return false;
            }
        } else {
            ClientLevel level = Minecraft.getInstance().level;
            int safeY = level != null
                    ? this.safeTeleportY(level, Mth.floor(x), Mth.floor(z), y, surface)
                    : Mth.floor(y);
            listener.sendCommand(String.format(java.util.Locale.ROOT, "tp %.2f %d %.2f", x, safeY, z));
        }
        this.showStatus(message);
        return true;
    }

    /**
     * Client-side fallback for servers without the mod: keeps the requested Y
     * when it is already safe, otherwise walks up/down a few blocks and finally
     * falls back to the surface height.
     */
    private int safeTeleportY(ClientLevel level, int blockX, int blockZ, double preferredY,
            boolean surfaceOnly) {
        boolean nether = level.dimension().equals(Level.NETHER);
        boolean loaded = level.getChunkSource().hasChunk(blockX >> 4, blockZ >> 4);
        int start;
        int range;
        if (surfaceOnly && nether) {
            // Nether heightmaps stop at the bedrock roof: start at the player's
            // own level instead.
            LocalPlayer player = Minecraft.getInstance().player;
            start = player != null ? Mth.floor(player.getY()) : Mth.floor(preferredY);
            range = 64;
        } else if (surfaceOnly) {
            start = loaded ? level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ)
                    : Mth.floor(preferredY);
            range = 16;
        } else {
            start = Mth.floor(preferredY);
            range = 16;
        }
        if (loaded) {
            for (int offset = 0; offset <= range; offset++) {
                int up = start + offset;
                if (this.standable(level, blockX, up, blockZ)) {
                    return up;
                }
                int down = start - offset;
                if (offset > 0 && this.standable(level, blockX, down, blockZ)) {
                    return down;
                }
            }
        }
        if (nether) {
            return start;
        }
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
        return loaded && surface > level.getMinBuildHeight() ? surface : start;
    }

    private boolean standable(ClientLevel level, int blockX, int feetY, int blockZ) {
        BlockPos feet = new BlockPos(blockX, feetY, blockZ);
        BlockPos head = feet.above();
        if (!level.isInWorldBounds(feet) || !level.isInWorldBounds(head)) {
            return false;
        }
        BlockState feetState = level.getBlockState(feet);
        if (!feetState.getCollisionShape(level, feet).isEmpty()) {
            return false;
        }
        BlockState headState = level.getBlockState(head);
        if (!headState.getCollisionShape(level, head).isEmpty()) {
            return false;
        }
        BlockPos floor = feet.below();
        BlockState floorState = level.getBlockState(floor);
        return !floorState.getCollisionShape(level, floor).isEmpty()
                || !floorState.getFluidState().isEmpty();
    }

    /** Y coordinate that matches the map layer currently displayed. */
    private int targetHeightAt(int blockX, int blockZ) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            return 64;
        }
        if (this.effectiveLayer(level, player) == MapLayer.CAVE) {
            return this.safeTeleportY(level, blockX, blockZ,
                    this.caveSliceY(level, player), false);
        }
        return this.surfaceHeightAt(blockX, blockZ);
    }

    public int clearWaypoints() {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            return 0;
        }
        return WaypointStore.get().removeDimension(dimension);
    }

    // ------------------------------------------------------------- preload UI

    public PreloadController preload() {
        return PreloadController.get();
    }

    /** Starts or resumes a task for the configured centre. */
    public void startPreload() {
        Component error = PreloadController.get().startPreload();
        this.showStatus(error != null ? error : Component.translatable("screen.m4cne.preload.status.started"));
    }

    public void pausePreload() {
        PreloadController.get().pausePreload();
        this.showStatus(Component.translatable("screen.m4cne.preload.status.paused"));
    }

    public void resumePreload() {
        PreloadController.get().resumePreload();
        this.showStatus(Component.translatable("screen.m4cne.preload.status.resumed"));
    }

    /** Cancels the task and drops both the progress and the map cache. */
    public void cancelPreload() {
        PreloadController.get().cancelPreload();
        this.markMapDirty();
        this.showStatus(Component.translatable("screen.m4cne.preload.status.cancelled"));
    }

    public void importPreload() {
        Component error = PreloadController.get().importPreload();
        this.showStatus(error != null ? error : Component.translatable("screen.m4cne.preload.status.importing"));
    }

    /** Places a bright preload centre marker at the clicked block. */
    public void addPreloadMarkerAtScreen(double mouseX, double mouseY) {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            return;
        }
        int x = Mth.floor(this.viewport.screenToWorldX(this.camera, mouseX));
        int z = Mth.floor(this.viewport.screenToWorldZ(this.camera, mouseY));
        PreloadMarkerStore.get().add(dimension, x, z);
        this.showStatus(Component.translatable("screen.m4cne.preload.status.marker_added", x, z));
    }

    public void deletePreloadMarker(PreloadMarkerStore.Marker marker) {
        if (PreloadMarkerStore.get().remove(marker)) {
            this.showStatus(Component.translatable("screen.m4cne.preload.status.marker_deleted"));
        }
    }

    public void setPreloadMarkerActive(PreloadMarkerStore.Marker marker) {
        PreloadMarkerStore.get().setActive(marker);
        ClientConfig.PRELOAD_CENTER_MODE.set(PreloadCenterMode.MARKER);
        this.showStatus(Component.translatable("screen.m4cne.preload.status.marker_active", marker.x(), marker.z()));
    }

    /** Removes every bright centre marker of the current dimension. */
    public void clearPreloadMarkers() {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            return;
        }
        int removed = PreloadMarkerStore.get().clearDimension(dimension);
        this.showStatus(Component.translatable("screen.m4cne.preload.status.markers_cleared", removed));
    }

    // ----------------------------------------------------------- chunk claims

    /** Number of chunks currently selected on the map canvas. */
    public int claimSelectionSize() {
        return this.claimSelection.size();
    }

    /** Selects every chunk currently visible on the canvas. */
    public void selectVisibleChunks() {
        this.applySelection(this.viewport.x(), this.viewport.y(),
                this.viewport.x() + this.viewport.width(), this.viewport.y() + this.viewport.height(),
                CLAIM_DRAG_REPLACE);
    }

    /** Selects the chunk the player is standing in. */
    public void selectPlayerChunks() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return;
        }
        ChunkPos chunk = player.chunkPosition();
        int centerX = (int) Math.round(
                this.viewport.worldToScreenX(this.camera, chunk.x * 16.0D + 8.0D));
        int centerZ = (int) Math.round(
                this.viewport.worldToScreenZ(this.camera, chunk.z * 16.0D + 8.0D));
        this.applySelection(centerX, centerZ, centerX, centerZ, CLAIM_DRAG_REPLACE);
    }

    public void clearClaimSelection() {
        this.claimSelection.clear();
        this.requestSidebarRebuild();
    }

    /**
     * Converts a screen rectangle into chunk keys and applies it to the selection.
     */
    private void applySelection(int startX, int startY, int endX, int endY, int mode) {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            return;
        }
        this.claimSelection.ensureDimension(dimension.toString());
        LongOpenHashSet keys = this.selectionKeys(startX, startY, endX, endY);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        switch (mode) {
            case CLAIM_DRAG_ADD -> this.claimSelection.addAll(keys);
            case CLAIM_DRAG_REMOVE -> this.claimSelection.removeAll(keys);
            default -> this.claimSelection.set(keys);
        }
        this.requestSidebarRebuild();
    }

    /**
     * Chunk keys covered by a screen rectangle. The rectangle is clamped around
     * the corner the drag started at, so a drag on a fully zoomed out map can
     * never create millions of selected chunks at once.
     */
    private LongOpenHashSet selectionKeys(int startX, int startY, int endX, int endY) {
        int startChunkX = Mth.floor(this.viewport.screenToWorldX(this.camera, startX) / 16.0D);
        int startChunkZ = Mth.floor(this.viewport.screenToWorldZ(this.camera, startY) / 16.0D);
        int endChunkX = Mth.floor(this.viewport.screenToWorldX(this.camera, endX) / 16.0D);
        int endChunkZ = Mth.floor(this.viewport.screenToWorldZ(this.camera, endY) / 16.0D);
        int minX = Math.min(startChunkX, endChunkX);
        int maxX = Math.max(startChunkX, endChunkX);
        int minZ = Math.min(startChunkZ, endChunkZ);
        int maxZ = Math.max(startChunkZ, endChunkZ);
        if (maxX - minX >= MAX_DRAG_CHUNKS_PER_AXIS) {
            if (startChunkX <= endChunkX) {
                minX = startChunkX;
                maxX = startChunkX + MAX_DRAG_CHUNKS_PER_AXIS - 1;
            } else {
                maxX = startChunkX;
                minX = startChunkX - MAX_DRAG_CHUNKS_PER_AXIS + 1;
            }
        }
        if (maxZ - minZ >= MAX_DRAG_CHUNKS_PER_AXIS) {
            if (startChunkZ <= endChunkZ) {
                minZ = startChunkZ;
                maxZ = startChunkZ + MAX_DRAG_CHUNKS_PER_AXIS - 1;
            } else {
                maxZ = startChunkZ;
                minZ = startChunkZ - MAX_DRAG_CHUNKS_PER_AXIS + 1;
            }
        }
        return ClaimSelection.keysOf(minX, minZ, maxX, maxZ);
    }

    /** Colour new claims of the local player use. */
    private int ownClaimColor() {
        ClaimStore.Owner self = ClaimStore.get().self();
        return self != null ? self.color() : ClaimColors.defaultColor();
    }

    /**
     * @return {@code true} when the server supports claims; shows a status
     *         otherwise
     */
    private boolean claimsAvailable() {
        if (ClaimSyncManager.get().available()) {
            return true;
        }
        this.showStatus(Component.translatable("screen.m4cne.status.claims.unavailable"));
        return false;
    }

    public int claimSelectedChunks() {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return 0;
        }
        if (!this.claimsAvailable()) {
            return 0;
        }
        if (this.claimSelection.isEmpty()) {
            this.showStatus(Component.translatable("screen.m4cne.status.claims.no_selection"));
            return 0;
        }
        int added = ClaimStore.get().claim(dimension.toString(),
                new ArrayList<>(this.claimSelection.chunks()), this.ownClaimColor());
        this.showStatus(Component.translatable("screen.m4cne.status.claims.claimed", added,
                this.claimSelection.size()));
        this.requestSidebarRebuild();
        return added;
    }

    public int unclaimSelectedChunks() {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return 0;
        }
        if (!this.claimsAvailable()) {
            return 0;
        }
        int removed = ClaimStore.get().unclaim(dimension.toString(),
                new ArrayList<>(this.claimSelection.chunks()));
        this.showStatus(Component.translatable("screen.m4cne.status.claims.unclaimed", removed));
        this.requestSidebarRebuild();
        return removed;
    }

    public int forceLoadSelectedChunks() {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return 0;
        }
        if (!this.claimsAvailable()) {
            return 0;
        }
        int changed = ClaimStore.get().setForceLoad(dimension.toString(),
                new ArrayList<>(this.claimSelection.chunks()), true, this.ownClaimColor());
        this.showStatus(Component.translatable("screen.m4cne.status.claims.forced", changed,
                ClaimSyncManager.get().maxForced()));
        this.requestSidebarRebuild();
        return changed;
    }

    public int clearSelectedForceLoad() {
        ResourceLocation dimension = this.currentDimension();
        if (dimension == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return 0;
        }
        if (!this.claimsAvailable()) {
            return 0;
        }
        int changed = ClaimStore.get().setForceLoad(dimension.toString(),
                new ArrayList<>(this.claimSelection.chunks()), false, this.ownClaimColor());
        this.showStatus(Component.translatable("screen.m4cne.status.claims.unforced", changed));
        this.requestSidebarRebuild();
        return changed;
    }

    /** Removes every own claim of the displayed dimension. */
    public int clearOwnClaimsInDimension() {
        ResourceLocation dimension = this.currentDimension();
        ClaimStore.Owner self = ClaimStore.get().self();
        if (dimension == null || self == null) {
            return 0;
        }
        if (!this.claimsAvailable()) {
            return 0;
        }
        LongOpenHashSet keys = ClaimStore.get().ownKeys(dimension.toString());
        int removed = ClaimStore.get().unclaim(dimension.toString(), keys);
        this.showStatus(Component.translatable("screen.m4cne.status.claims.unclaimed", removed));
        this.requestSidebarRebuild();
        return removed;
    }

    /** Deletes every claim of the given player (owner / admin only). */
    public void deleteClaimOwner(ClaimStore.Owner owner) {
        if (owner == null) {
            return;
        }
        boolean sent = ClaimSyncManager.get().deleteClaims(owner.id(), List.of());
        this.showStatus(Component.translatable(
                sent ? "screen.m4cne.status.claims.owner_deleted" : "screen.m4cne.status.claims.unavailable",
                owner.name()));
        this.requestSidebarRebuild();
    }

    /** Clears every force load flag of the given player (owner / admin only). */
    public void clearClaimOwnerForce(ClaimStore.Owner owner) {
        if (owner == null) {
            return;
        }
        boolean sent = ClaimSyncManager.get().clearForce(owner.id(), List.of());
        this.showStatus(Component.translatable(
                sent ? "screen.m4cne.status.claims.owner_unforced" : "screen.m4cne.status.claims.unavailable",
                owner.name()));
        this.requestSidebarRebuild();
    }

    /** Changes the boundary flag of one owner (owner / admin only). */
    public void setClaimOwnerBoundary(ClaimStore.Owner owner, boolean value) {
        if (owner == null) {
            return;
        }
        if (owner.id().equals(ClaimStore.get().selfId())) {
            ClaimStore.get().setOwnBoundary(value);
        }
        boolean sent = ClaimSyncManager.get().setBoundary(owner.id(), value);
        this.showStatus(Component.translatable(
                sent ? "screen.m4cne.status.claims.boundary" : "screen.m4cne.status.claims.unavailable",
                owner.name(), Component.translatable(value ? "screen.m4cne.state.enabled"
                        : "screen.m4cne.state.disabled")));
        this.requestSidebarRebuild();
    }

    /** Toggles the boundary outline of the own claims. */
    public void toggleOwnClaimBoundary() {
        ClaimStore.Owner self = ClaimStore.get().self();
        boolean value = self == null || !self.boundary();
        ClaimStore.get().setOwnBoundary(value);
        boolean sent = ClaimSyncManager.get().setBoundary("", value);
        this.showStatus(Component.translatable(
                sent ? "screen.m4cne.status.claims.boundary_self" : "screen.m4cne.status.claims.unavailable",
                Component.translatable(value ? "screen.m4cne.state.enabled" : "screen.m4cne.state.disabled")));
        this.requestSidebarRebuild();
    }

    /** Changes the claim colour of the local player. */
    public void setOwnClaimColor(int color) {
        ClaimStore.get().setOwnColor(color);
        boolean sent = ClaimSyncManager.get().setColor(color);
        this.showStatus(Component.translatable(
                sent ? "screen.m4cne.status.claims.color" : "screen.m4cne.status.claims.unavailable",
                String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF)));
        this.requestSidebarRebuild();
    }

    /** Uploads the own claims immediately. */
    public void syncClaimsNow() {
        boolean sent = ClaimSyncManager.get().syncNow();
        this.showStatus(Component.translatable(
                sent ? "screen.m4cne.status.claims.syncing" : "screen.m4cne.status.claims.unavailable"));
    }

    /** Asks the server to scan every client again (administrators only). */
    public void requestClaimScan() {
        boolean sent = ClaimSyncManager.get().requestScan();
        this.showStatus(Component.translatable(
                sent ? "screen.m4cne.status.claims.scan_requested" : "screen.m4cne.status.claims.unavailable"));
    }

    // ----------------------------------------------------------- chunk query

    /** Result of the last chunk information query, {@code null} when none ran. */
    public ChunkQueryService.Report queryReport() {
        return ChunkQueryService.get().report();
    }

    /** Queries a chunk by its chunk coordinates and highlights it on the map. */
    public boolean queryChunk(int chunkX, int chunkZ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return false;
        }
        ChunkQueryService.get().query(level, chunkX, chunkZ);
        this.markMapDirty();
        this.showStatus(Component.translatable("screen.m4cne.status.query_done", chunkX, chunkZ));
        return true;
    }

    /** Parses the two text fields of the chunk query. */
    public boolean queryChunkFromText(String chunkX, String chunkZ) {
        try {
            int x = Integer.parseInt(chunkX.trim());
            int z = Integer.parseInt(chunkZ.trim());
            return this.queryChunk(x, z);
        } catch (NumberFormatException exception) {
            this.showStatus(Component.translatable("screen.m4cne.status.query_invalid"));
            return false;
        }
    }

    public boolean queryPlayerChunk() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            this.showStatus(Component.translatable("screen.m4cne.no_world"));
            return false;
        }
        return this.queryChunk(player.chunkPosition().x, player.chunkPosition().z);
    }

    public boolean queryCenterChunk() {
        return this.queryChunk(Mth.floor(this.camera.centerX()) >> 4, Mth.floor(this.camera.centerZ()) >> 4);
    }

    public void clearQuery() {
        ChunkQueryService.get().clear();
        this.markMapDirty();
        this.showStatus(Component.translatable("screen.m4cne.status.query_cleared"));
    }

    /** Centres the camera on the chunk of the last query. */
    public void focusQueriedChunk() {
        ChunkQueryService.Report report = ChunkQueryService.get().report();
        if (report == null) {
            return;
        }
        this.camera.centerOn(report.chunkX() * 16.0D + 8.0D, report.chunkZ() * 16.0D + 8.0D);
        this.camera.setZoom(1.0D);
        this.markMapDirty();
    }

    public void showStatus(Component message) {
        this.status = message;
        this.statusUntil = System.currentTimeMillis() + 4000L;
    }

    /**
     * Adds or removes the highlight of one player. Highlighted players ignore
     * the distance limit of the compass HUD and stay on the minimap border.
     */
    public void togglePlayerHighlight(ClientEntityMarkerCache.Marker marker) {
        boolean highlighted = HighlightedPlayers.toggle(marker.id(), marker.name());
        this.showStatus(Component.translatable(
                highlighted ? "screen.m4cne.status.player_highlighted" : "screen.m4cne.status.player_unhighlighted",
                marker.name()));
    }

    /** Current status message or {@code null} when it expired. */
    public Component statusMessage() {
        if (this.status != null && System.currentTimeMillis() <= this.statusUntil) {
            return this.status;
        }
        return null;
    }

    private int surfaceHeightAt(int blockX, int blockZ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.dimension().equals(Level.NETHER)) {
            // The Nether has a bedrock roof; its heightmap is not a walkable
            // surface. Search around the player's level at that column instead.
            return this.safeTeleportY(level, blockX, blockZ, 64.0D, true);
        }
        ResourceLocation dimension = this.currentDimension();
        if (dimension != null) {
            ClientChunkStore.Entry entry = ClientChunkStore.get().get(dimension, blockX >> 4, blockZ >> 4);
            if (entry != null) {
                short height = entry.tile().heights[ChunkTile.index(blockX & 15, blockZ & 15)];
                if (height != Short.MIN_VALUE) {
                    return height + 1;
                }
            }
        }
        if (level != null && level.getChunkSource().hasChunk(blockX >> 4, blockZ >> 4)) {
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
            if (top > level.getMinBuildHeight()) {
                return top;
            }
        }
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? Mth.floor(player.getY()) : 64;
    }

    private ResourceLocation currentDimension() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null ? level.dimension().location() : null;
    }
}
