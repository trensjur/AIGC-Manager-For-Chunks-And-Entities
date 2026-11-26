package com.wwwwz.aigcs.client.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.wwwwz.aigcs.client.ClientChunkStore;
import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.ClientWorldKey;
import com.wwwwz.aigcs.client.entity.ClientEntityMarkerCache;
import com.wwwwz.aigcs.client.entity.EntityIconCache;
import com.wwwwz.aigcs.client.map.HighlightedPlayers;
import com.wwwwz.aigcs.client.map.MapCamera;
import com.wwwwz.aigcs.client.map.MapLayer;
import com.wwwwz.aigcs.client.map.MapTextureRenderer;
import com.wwwwz.aigcs.client.map.PlayerArrowTexture;
import com.wwwwz.aigcs.client.map.SlimeChunkCache;
import com.wwwwz.aigcs.client.map.WaypointStore;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * In game minimap / compass HUD.
 * <p>
 * The two widgets are laid out independently. The minimap snaps to one of the
 * four screen corners and grows away from that corner, so it can never leave
 * the window. The compass is positioned relative to the vertical centre line of
 * the screen: its horizontal offset moves it away from the line, its vertical
 * position moves it along the line, and every size change grows symmetrically
 * around its own centre point.
 * <p>
 * The compass also renders the nearby entities: their icons sit on the strip,
 * icons that would overlap are merged into one marker with a count, and the
 * entity closest to the heading line shows its name, distance and relative
 * height directly below its own icon. Players highlighted on the world map
 * ignore the distance limit.
 */
@SuppressWarnings("null")
public final class ClientHudOverlay {

    /** Compass height without the entity row. */
    private static final int COMPASS_HEIGHT_PLAIN = 20;
    /** Compass height including the entity label area. */
    private static final int COMPASS_HEIGHT_ENTITIES = 42;
    /** Height of the heading band inside the compass strip. */
    private static final int COMPASS_HEADING_BAND = 28;
    /** Edge length of the compass entity icons in base pixels. */
    private static final float COMPASS_ICON_SIZE = 9.0F;
    /**
     * Detail text scale per unit of {@code compass.iconScale}. The icon scale
     * slider therefore also scales the entity information, and the default
     * icon scale of 1.5 keeps the previous 0.75 text scale.
     */
    private static final float COMPASS_LABEL_SCALE = 0.5F;
    /** Merged count text scale per unit of {@code compass.iconScale}. */
    private static final float COMPASS_COUNT_SCALE = 0.4F;
    /** Distance between the heading line and an icon that reveals details. */
    private static final int COMPASS_INFO_RADIUS = 12;
    /** Height difference (blocks) below which the entity counts as level. */
    private static final double LEVEL_TOLERANCE = 1.5D;
    /** Gap between two information entries of the horizontal compass rows. */
    private static final int INFO_GAP = 4;
    /** Maximum number of information entries in one horizontal row. */
    private static final int INFO_MAX_PER_ROW = 3;
    /** Minimum distance between the complete widget and the screen border. */
    private static final int SCREEN_MARGIN = 2;

    private final MapCamera camera = new MapCamera();
    private final MapTextureRenderer renderer = new MapTextureRenderer();

    private String worldKey = "";
    private ResourceLocation lastDimension;

    private ClientHudOverlay() {
    }

    /** Draws the HUD before the vanilla GUI so other overlays can cover it. */
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        if (ClientConfig.MINIMAP_RENDER_LAYER.get() == HudRenderLayer.BELOW) {
            renderIfEnabled(event.getGuiGraphics());
        }
    }

    /** Draws the HUD after the vanilla GUI so it stays on top of most overlays. */
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (ClientConfig.MINIMAP_RENDER_LAYER.get() == HudRenderLayer.ABOVE) {
            renderIfEnabled(event.getGuiGraphics());
        }
    }

    private static void renderIfEnabled(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        // Any open screen (inventory, chest, JEI / EMI overlays, chat, ...)
        // hides the HUD so it never fights with container UIs.
        if (ClientConfig.MINIMAP_HIDE_IN_SCREENS.getAsBoolean() && minecraft.screen != null) {
            return;
        }
        if (!ClientConfig.MINIMAP_ENABLED.getAsBoolean()) {
            return;
        }
        INSTANCE.draw(graphics, minecraft, minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(), 1.0F, 0.0F, 0.0F, null);
    }

    private static final ClientHudOverlay INSTANCE = new ClientHudOverlay();

    public static ClientHudOverlay get() {
        return INSTANCE;
    }

    /**
     * Draws one of the widgets into a virtual game window, used by the layout
     * previews of the world map settings. All layout maths is shared with the
     * real HUD, so the preview is pixel accurate.
     *
     * @param guiWidth     GUI width of the simulated game window
     * @param guiHeight    GUI height of the simulated game window
     * @param previewScale scale applied to the simulated window
     * @param originX      screen x of the simulated window's top left corner
     * @param originY      screen y of the simulated window's top left corner
     * @param forcedMode   widget to draw regardless of the configured HUD mode
     */
    public void renderPreview(GuiGraphics graphics, Minecraft minecraft, int guiWidth, int guiHeight,
            float previewScale, float originX, float originY, HudMode forcedMode) {
        this.draw(graphics, minecraft, guiWidth, guiHeight, previewScale, originX, originY, forcedMode);
    }

    /**
     * Lays out and draws the widget.
     * <p>
     * Everything inside the widget is drawn in unscaled "base" coordinates
     * while the pose applies the widget scale, which keeps the text and the
     * icon geometry identical at every scale. Scissor rectangles are not
     * affected by the pose, so {@link Space} maps base coordinates onto the
     * screen.
     */
    private void draw(GuiGraphics graphics, Minecraft minecraft, int guiWidth, int guiHeight,
            float previewScale, float originX, float originY, HudMode forcedMode) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || guiWidth <= 0 || guiHeight <= 0) {
            return;
        }
        HudMode mode = forcedMode != null ? forcedMode : ClientConfig.MINIMAP_MODE.get();
        if (mode == HudMode.MINIMAP) {
            this.drawMinimapWidget(graphics, minecraft, player, level, guiWidth, guiHeight,
                    previewScale, originX, originY);
        } else {
            this.drawCompassWidget(graphics, minecraft, player, level, guiWidth, guiHeight,
                    previewScale, originX, originY);
        }
    }

    /**
     * Places the minimap in its configured screen corner. The pose origin is
     * the widget corner that touches the screen border, so scaling grows away
     * from that corner and never pushes the minimap off screen.
     */
    private void drawMinimapWidget(GuiGraphics graphics, Minecraft minecraft, LocalPlayer player,
            ClientLevel level, int guiWidth, int guiHeight, float previewScale, float originX, float originY) {
        int baseSize = ClientConfig.MINIMAP_SIZE.getAsInt();
        float widgetScale = (float) (double) ClientConfig.MINIMAP_SCALE.get();
        int margin = ClientConfig.MINIMAP_MARGIN.getAsInt();
        HudCorner corner = ClientConfig.MINIMAP_CORNER.get();

        List<Component> lines = this.buildInfoLines(minecraft, player, level);
        // The minimap now also carries the marker information of the compass:
        // the nearest entity and the nearest waypoint are listed below the HUD.
        lines.addAll(this.buildMarkerInfoLines(player, level));
        float infoScale = (float) (double) ClientConfig.MINIMAP_INFO_SCALE.get();
        int lineHeight = Math.max(6, Math.round(10.0F * infoScale));
        int infoHeight = lines.isEmpty() ? 0 : lines.size() * lineHeight + 4;
        int blockGap = infoHeight > 0 ? 3 : 0;

        float blockWidth = baseSize * widgetScale;
        float blockHeight = (baseSize + blockGap + infoHeight) * widgetScale;
        float left = corner.isLeft() ? margin : guiWidth - margin - blockWidth;
        float top = corner.isTop() ? margin : guiHeight - margin - blockHeight;
        left = Math.max(0.0F, Math.min(left, Math.max(0.0F, guiWidth - blockWidth)));
        top = Math.max(0.0F, Math.min(top, Math.max(0.0F, guiHeight - blockHeight)));

        float scale = widgetScale * previewScale;
        float screenX = originX + left * previewScale;
        float screenY = originY + top * previewScale;
        graphics.pose().pushPose();
        graphics.pose().translate(screenX, screenY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        Space space = new Space(scale, screenX, screenY);
        this.renderMinimap(graphics, minecraft, player, level, space, 0, 0, baseSize, baseSize);
        if (!lines.isEmpty()) {
            this.renderInfoVertical(graphics, minecraft, lines, 0, baseSize + 3, baseSize, infoHeight,
                    infoScale);
        }
        graphics.pose().popPose();
    }

    /**
     * Places the compass relative to the vertical centre line of the screen.
     * The scale origin is the centre of the strip itself, so widening or
     * scaling it expands both sides equally. The information rows below the
     * strip are centred on the same axis.
     */
    private void drawCompassWidget(GuiGraphics graphics, Minecraft minecraft, LocalPlayer player,
            ClientLevel level, int guiWidth, int guiHeight, float previewScale, float originX, float originY) {
        // Entity icons and waypoint icons share the same detail band, so the
        // strip grows as soon as either of them is enabled.
        boolean marks = ClientConfig.COMPASS_ENTITIES.getAsBoolean()
                || ClientConfig.COMPASS_WAYPOINTS.getAsBoolean();
        int baseWidth = ClientConfig.COMPASS_WIDTH.getAsInt();
        float iconScale = (float) (double) ClientConfig.COMPASS_ICON_SCALE.get();
        // The detail label belongs to the icon settings: scaling the icons
        // also scales the text below them, so the strip height has to follow.
        float labelScale = compassLabelScale(iconScale);
        int entityHeight = compassLabelOffset(iconScale)
                + Math.round(minecraft.font.lineHeight * labelScale) + 4;
        int baseHeight = marks ? Math.max(COMPASS_HEIGHT_ENTITIES, entityHeight)
                : COMPASS_HEIGHT_PLAIN;
        float widgetScale = (float) (double) ClientConfig.COMPASS_SCALE.get();

        List<Component> items = this.buildInfoLines(minecraft, player, level);
        float infoScale = (float) (double) ClientConfig.MINIMAP_INFO_SCALE.get();
        int lineHeight = Math.max(6, Math.round(10.0F * infoScale));
        List<InfoRow> rows = this.layoutInfoRows(minecraft.font, items);
        int panelWidth = baseWidth;
        if (!rows.isEmpty()) {
            int rowWidth = 0;
            for (InfoRow row : rows) {
                rowWidth = Math.max(rowWidth, Math.round(row.width() * infoScale) + 8);
            }
            panelWidth = Math.min(Math.max(baseWidth, rowWidth),
                    Math.max(baseWidth, guiWidth - SCREEN_MARGIN * 2));
        }
        int panelHeight = rows.isEmpty() ? 0 : rows.size() * lineHeight + 4;

        float stripHalfWidth = baseWidth * widgetScale / 2.0F;
        float panelHalfWidth = panelWidth * widgetScale / 2.0F;
        float halfWidth = Math.max(stripHalfWidth, panelHalfWidth);
        float stripHalfHeight = baseHeight * widgetScale / 2.0F;
        float belowHeight = panelHeight > 0 ? (panelHeight + 3) * widgetScale : 0.0F;

        float anchorX = guiWidth / 2.0F + ClientConfig.COMPASS_POS_X.getAsInt();
        float anchorY = ClientConfig.COMPASS_POS_Y.getAsInt();
        float minimumX = halfWidth + SCREEN_MARGIN;
        float maximumX = guiWidth - halfWidth - SCREEN_MARGIN;
        anchorX = maximumX < minimumX ? guiWidth / 2.0F : Mth.clamp(anchorX, minimumX, maximumX);
        float minimumY = stripHalfHeight + SCREEN_MARGIN;
        float maximumY = guiHeight - stripHalfHeight - belowHeight - SCREEN_MARGIN;
        anchorY = maximumY < minimumY ? Math.max(SCREEN_MARGIN, maximumY)
                : Mth.clamp(anchorY, minimumY, maximumY);

        float scale = widgetScale * previewScale;
        float screenX = originX + (anchorX - stripHalfWidth) * previewScale;
        float screenY = originY + (anchorY - stripHalfHeight) * previewScale;
        graphics.pose().pushPose();
        graphics.pose().translate(screenX, screenY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        this.renderCompass(graphics, minecraft, player, 0, 0, baseWidth, baseHeight, marks);
        if (!rows.isEmpty()) {
            int panelX = Math.round((baseWidth - panelWidth) / 2.0F);
            this.renderInfoHorizontal(graphics, minecraft, rows, panelX, baseHeight + 3, panelWidth,
                    panelHeight, infoScale);
        }
        graphics.pose().popPose();
    }

    /** Maps the unscaled HUD coordinates onto real screen pixels. */
    private record Space(float scale, float originX, float originY) {
        private float x(double localX) {
            return this.originX + (float) localX * this.scale;
        }

        private float y(double localY) {
            return this.originY + (float) localY * this.scale;
        }
    }

    // --------------------------------------------------------------- minimap

    private void renderMinimap(GuiGraphics graphics, Minecraft minecraft, LocalPlayer player, ClientLevel level,
            Space space, int x, int y, int width, int height) {
        ResourceLocation dimension = level.dimension().location();
        String world = ClientWorldKey.current();
        if (!world.equals(this.worldKey) || !dimension.equals(this.lastDimension)) {
            this.worldKey = world;
            this.lastDimension = dimension;
            this.renderer.markDirty();
        }

        this.camera.setZoom(ClientConfig.MINIMAP_ZOOM.get());
        this.camera.centerOn(player.getX(), player.getZ());
        this.renderer.setAllowMotionRebuild(true);

        int sliceY = Mth.clamp(Mth.floor(player.getY()), level.getMinBuildHeight() + 1,
                level.getMaxBuildHeight() - 1);
        // The scissor rectangle is applied in real screen space, so the unscaled
        // widget bounds have to be mapped through the preview/scale transform.
        graphics.enableScissor(Math.round(space.x(x)), Math.round(space.y(y)),
                Math.round(space.x(x + width)), Math.round(space.y(y + height)));
        graphics.fill(x, y, x + width, y + height, 0xFF0C0C0C);
        this.renderer.draw(graphics, level, dimension, this.camera, x, y, width, height,
                MapLayer.SURFACE, sliceY);
        int dim = (int) (level.getSkyDarken() / 11.0F * 0.35F * 255.0F);
        if (dim > 0) {
            graphics.fill(x, y, x + width, y + height, (dim << 24));
        }
        this.drawMinimapMarkers(graphics, player, level, x, y, width, height);
        this.drawHighlightedPlayers(graphics, player, x, y, width, height);
        this.drawPlayerArrow(graphics, x + width / 2, y + height / 2, player.getYRot());
        graphics.disableScissor();

        if (ClientConfig.MINIMAP_BORDER.getAsBoolean()) {
            graphics.fill(x - 1, y - 1, x + width + 1, y, 0xFF8B8B8B);
            graphics.fill(x - 1, y + height, x + width + 1, y + height + 1, 0xFF4C4C4C);
            graphics.fill(x - 1, y, x, y + height, 0xFF8B8B8B);
            graphics.fill(x + width, y, x + width + 1, y + height, 0xFF4C4C4C);
        }
    }

    private void drawPlayerArrow(GuiGraphics graphics, int centerX, int centerY, float yaw) {
        PlayerArrowTexture.get().draw(graphics, centerX, centerY, 15.0F, yaw);
    }

    /**
     * Draws the entity icons and waypoint diamonds of the minimap. The same
     * radar filters and distance limits as on the compass apply; targets that
     * are farther away than the widget size are pinned to its border so their
     * direction stays readable.
     */
    private void drawMinimapMarkers(GuiGraphics graphics, LocalPlayer player, ClientLevel level, int x, int y,
            int width, int height) {
        ResourceLocation dimension = level.dimension().location();
        double zoom = ClientConfig.MINIMAP_ZOOM.get();
        float iconScale = (float) (double) ClientConfig.MINIMAP_ICON_SCALE.get();
        double centerX = x + width / 2.0D;
        double centerY = y + height / 2.0D;
        double limitX = Math.max(2.0D, width / 2.0D - 4.0D);
        double limitY = Math.max(2.0D, height / 2.0D - 4.0D);

        if (ClientConfig.MINIMAP_WAYPOINTS.getAsBoolean()) {
            double range = ClientConfig.MINIMAP_WAYPOINT_RANGE.getAsInt();
            double rangeSquared = range * range;
            float iconSize = 5.0F * iconScale;
            for (WaypointStore.Waypoint waypoint : WaypointStore.get().forDimension(dimension)) {
                double deltaX = waypoint.x() + 0.5D - player.getX();
                double deltaZ = waypoint.z() + 0.5D - player.getZ();
                if (deltaX * deltaX + deltaZ * deltaZ > rangeSquared) {
                    continue;
                }
                double markerX = deltaX * zoom;
                double markerZ = deltaZ * zoom;
                if (Math.abs(markerX) > limitX || Math.abs(markerZ) > limitY) {
                    // Scale the offset until it touches the inner border.
                    double factorX = Math.abs(markerX) > 1.0E-6D ? limitX / Math.abs(markerX) : Double.MAX_VALUE;
                    double factorZ = Math.abs(markerZ) > 1.0E-6D ? limitY / Math.abs(markerZ) : Double.MAX_VALUE;
                    double factor = Math.min(factorX, factorZ);
                    markerX *= factor;
                    markerZ *= factor;
                }
                this.drawWaypointIcon(graphics, waypoint.color(), (float) (centerX + markerX),
                        (float) (centerY + markerZ), iconSize);
            }
        }

        if (!ClientConfig.MINIMAP_ENTITIES.getAsBoolean()) {
            return;
        }
        double range = ClientConfig.MINIMAP_ENTITY_RANGE.getAsInt();
        double rangeSquared = range * range;
        float iconSize = 7.0F * iconScale;
        for (ClientEntityMarkerCache.Marker marker : ClientEntityMarkerCache.get().markers()) {
            if (!categoryVisible(marker)) {
                continue;
            }
            double deltaX = marker.x() - player.getX();
            double deltaZ = marker.z() - player.getZ();
            if (deltaX * deltaX + deltaZ * deltaZ > rangeSquared) {
                continue;
            }
            double markerX = deltaX * zoom;
            double markerZ = deltaZ * zoom;
            if (Math.abs(markerX) > limitX || Math.abs(markerZ) > limitY) {
                double factorX = Math.abs(markerX) > 1.0E-6D ? limitX / Math.abs(markerX) : Double.MAX_VALUE;
                double factorZ = Math.abs(markerZ) > 1.0E-6D ? limitY / Math.abs(markerZ) : Double.MAX_VALUE;
                double factor = Math.min(factorX, factorZ);
                markerX *= factor;
                markerZ *= factor;
            }
            this.drawEntityIcon(graphics, marker.type(), (float) (centerX + markerX),
                    (float) (centerY + markerZ), iconSize);
        }
    }

    /**
     * Highlighted players are always visible on the minimap. Players outside the
     * sampled area are pinned to the border so their direction stays readable.
     */
    private void drawHighlightedPlayers(GuiGraphics graphics, LocalPlayer player, int x, int y, int width,
            int height) {
        int highlighted = HighlightedPlayers.count();
        if (highlighted <= 0) {
            return;
        }
        double zoom = ClientConfig.MINIMAP_ZOOM.get();
        double halfWidth = Math.max(2.0D, width / 2.0D - 6.0D);
        double halfHeight = Math.max(2.0D, height / 2.0D - 6.0D);
        for (ClientEntityMarkerCache.Marker marker : ClientEntityMarkerCache.get().markers()) {
            if (marker.type() != EntityType.PLAYER || !HighlightedPlayers.isHighlighted(marker.id())) {
                continue;
            }
            double offsetX = (marker.x() - player.getX()) * zoom;
            double offsetZ = (marker.z() - player.getZ()) * zoom;
            if (Math.abs(offsetX) > halfWidth || Math.abs(offsetZ) > halfHeight) {
                // Scale the offset until it touches the inner border rectangle.
                double factorX = Math.abs(offsetX) > 1.0E-6D ? halfWidth / Math.abs(offsetX) : Double.MAX_VALUE;
                double factorZ = Math.abs(offsetZ) > 1.0E-6D ? halfHeight / Math.abs(offsetZ) : Double.MAX_VALUE;
                double factor = Math.min(factorX, factorZ);
                offsetX *= factor;
                offsetZ *= factor;
            }
            float centerX = (float) (x + width / 2.0D + offsetX);
            float centerY = (float) (y + height / 2.0D + offsetZ);
            // Gold ring so the highlighted player stands out from the map.
            graphics.fill((int) centerX - 5, (int) centerY - 5, (int) centerX + 5, (int) centerY + 5,
                    0xFFFFD050);
            this.drawEntityIcon(graphics, marker.type(), centerX, centerY, 8.0F);
        }
    }

    // --------------------------------------------------------------- compass

    private void renderCompass(GuiGraphics graphics, Minecraft minecraft, LocalPlayer player, int x, int y,
            int width, int height, boolean marks) {
        Font font = minecraft.font;
        // The strip background is translucent and its opacity is configurable.
        int backgroundAlpha = compassBackgroundAlpha(255);
        graphics.fill(x, y, x + width, y + height, backgroundAlpha << 24);
        if (ClientConfig.MINIMAP_BORDER.getAsBoolean()) {
            // Real 1px frame: the old single fill covered the whole strip with
            // opaque grey. The frame uses the same transparency coefficient as
            // the background, so the opacity slider controls both at once.
            int frameLight = (backgroundAlpha << 24) | 0x8B8B8B;
            int frameDark = (backgroundAlpha << 24) | 0x4C4C4C;
            graphics.fill(x - 1, y - 1, x + width + 1, y, frameLight);
            graphics.fill(x - 1, y + height, x + width + 1, y + height + 1, frameDark);
            graphics.fill(x - 1, y, x, y + height, frameLight);
            graphics.fill(x + width, y, x + width + 1, y + height, frameDark);
        }

        float heading = Mth.wrapDegrees(player.getYRot());
        int centerX = x + width / 2;
        float radius = width / 2.0F - 7.0F;
        int headingBand = marks ? Math.min(COMPASS_HEADING_BAND, height) : height;
        int tickColor = 0xFFB0B0B0;
        int tickBottom = y + headingBand - 2;
        for (int degree = 0; degree < 360; degree += 15) {
            float delta = Mth.wrapDegrees(degree - heading);
            if (Math.abs(delta) > 60.0F) {
                continue;
            }
            int tickX = Math.round(centerX + delta / 60.0F * radius);
            boolean major = degree % 90 == 0;
            int tickHeight = major ? 4 : (degree % 45 == 0 ? 3 : 2);
            graphics.fill(tickX, tickBottom - tickHeight, tickX + 1, tickBottom, tickColor);
        }
        String[] cardinals = { "N", "E", "S", "W" };
        for (int index = 0; index < cardinals.length; index++) {
            float delta = Mth.wrapDegrees(index * 90.0F - heading);
            if (Math.abs(delta) > 60.0F) {
                continue;
            }
            int textX = Math.round(centerX + delta / 60.0F * radius);
            graphics.drawString(font, cardinals[index], textX - font.width(cardinals[index]) / 2, y + 2,
                    0xFFFFFFFF, true);
        }

        CompassGroup detail = null;
        if (marks) {
            detail = this.drawCompassEntities(graphics, minecraft, player, x, y, width, heading, centerX);
        }

        // Prominent vertical line that marks the facing direction.
        graphics.fill(centerX - 2, y, centerX + 3, y + headingBand, 0xFF000000);
        graphics.fill(centerX - 1, y + 1, centerX + 2, y + headingBand - 1, 0xFFFFE060);
        graphics.fill(centerX, y + 1, centerX + 1, y + headingBand - 1, 0xFFFFFFFF);

        // The old full width information box is gone: the entity details are
        // drawn directly below their own icon instead.
        if (detail != null) {
            this.drawCompassEntityLabel(graphics, minecraft, detail, x, y, width);
        }
    }

    /**
     * Draws the entity and waypoint icons of the compass strip and merges icons
     * that would overlap. Highlighted players are always drawn, even outside
     * the range.
     *
     * @return the merged marker whose icon is closest to the heading line, or
     *         {@code null} when no icon is close enough to show details
     */
    private CompassGroup drawCompassEntities(GuiGraphics graphics, Minecraft minecraft, LocalPlayer player,
            int x, int y, int width, float heading, int centerX) {
        float radius = width / 2.0F - 7.0F;
        float iconScale = (float) (double) ClientConfig.COMPASS_ICON_SCALE.get();
        float iconSize = COMPASS_ICON_SIZE * iconScale;
        float iconCenterY = y + COMPASS_HEADING_BAND - 12;

        List<CompassTarget> targets = new ArrayList<>();
        if (ClientConfig.COMPASS_ENTITIES.getAsBoolean()) {
            this.collectEntityTargets(player, heading, centerX, radius, targets);
        }
        if (ClientConfig.COMPASS_WAYPOINTS.getAsBoolean()) {
            this.collectWaypointTargets(minecraft, player, heading, centerX, radius, targets);
        }
        if (targets.isEmpty()) {
            return null;
        }
        targets.sort(Comparator.comparingDouble(CompassTarget::screenX));

        int mergeDistance = Math.max(2,
                Math.round(ClientConfig.COMPASS_MERGE_PIXELS.getAsInt() * Math.max(1.0F, iconScale)));
        List<CompassGroup> groups = new ArrayList<>();
        CompassGroup current = null;
        for (CompassTarget target : targets) {
            // Entities and waypoints never merge with each other; only icons of
            // the same kind are summarised as one marker.
            if (current == null || current.kind != target.kind()
                    || Math.abs(target.screenX() - current.screenX) > mergeDistance) {
                current = new CompassGroup(target.kind(), target.screenX());
                groups.add(current);
            }
            current.add(target);
        }

        CompassGroup closest = null;
        double closestDistance = COMPASS_INFO_RADIUS * Math.max(1.0F, iconScale);
        for (CompassGroup group : groups) {
            CompassTarget nearest = group.nearest;
            float iconX = Mth.clamp((float) group.screenX, x + 4.0F, x + width - 4.0F);
            if (group.kind == MarkerKind.WAYPOINT) {
                this.drawWaypointIcon(graphics, nearest.waypoint().color(), iconX, iconCenterY, iconSize);
            } else {
                if (nearest.highlighted()) {
                    int ring = Math.round(iconSize / 2.0F) + 2;
                    graphics.fill((int) iconX - ring, (int) iconCenterY - ring,
                            (int) iconX + ring, (int) iconCenterY + ring, 0xFFFFD050);
                }
                this.drawEntityIcon(graphics, nearest.marker().type(), iconX, iconCenterY,
                        nearest.highlighted() ? iconSize + 1.0F : iconSize);
            }
            if (group.members.size() > 1) {
                String count = "x" + group.members.size();
                // The count badge follows the icon scale slider together with
                // the icon itself.
                float countScale = COMPASS_COUNT_SCALE * iconScale;
                graphics.pose().pushPose();
                graphics.pose().translate(iconX + iconSize / 2.0F - 1.0F,
                        iconCenterY - iconSize / 2.0F, 0.0F);
                graphics.pose().scale(countScale, countScale, 1.0F);
                graphics.drawString(minecraft.font, count, 0, 0, 0xFFFFE060, true);
                graphics.pose().popPose();
            }
            double delta = Math.abs(group.screenX - centerX);
            if (delta < closestDistance) {
                closestDistance = delta;
                closest = group;
            }
        }
        return closest;
    }

    /** Collects the entity markers of the compass, honouring the radar filters. */
    private void collectEntityTargets(LocalPlayer player, float heading, int centerX, float radius,
            List<CompassTarget> targets) {
        double range = ClientConfig.COMPASS_ENTITY_RANGE.getAsInt();
        double rangeSquared = range * range;
        for (ClientEntityMarkerCache.Marker marker : ClientEntityMarkerCache.get().markers()) {
            boolean highlighted = marker.type() == EntityType.PLAYER
                    && HighlightedPlayers.isHighlighted(marker.id());
            // The visible categories follow the entity radar settings so both
            // features always agree. Highlighted players are never filtered.
            if (!highlighted && !categoryVisible(marker)) {
                continue;
            }
            double deltaX = marker.x() - player.getX();
            double deltaZ = marker.z() - player.getZ();
            double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (!highlighted && distanceSquared > rangeSquared) {
                continue;
            }
            float deltaAngle = bearing(deltaX, deltaZ, heading);
            if (Math.abs(deltaAngle) > 60.0F) {
                if (!highlighted) {
                    continue;
                }
                // Highlighted players are pinned to the strip edge.
                deltaAngle = deltaAngle < 0.0F ? -60.0F : 60.0F;
            }
            targets.add(new CompassTarget(MarkerKind.ENTITY, marker, null,
                    centerX + deltaAngle / 60.0F * radius, Math.sqrt(distanceSquared),
                    marker.y() - player.getY(), highlighted));
        }
    }

    /**
     * Collects the waypoints of the current dimension that fit the compass range.
     */
    private void collectWaypointTargets(Minecraft minecraft, LocalPlayer player, float heading, int centerX,
            float radius, List<CompassTarget> targets) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        double range = ClientConfig.COMPASS_WAYPOINT_RANGE.getAsInt();
        double rangeSquared = range * range;
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().forDimension(level.dimension().location())) {
            double deltaX = waypoint.x() + 0.5D - player.getX();
            double deltaZ = waypoint.z() + 0.5D - player.getZ();
            double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (distanceSquared > rangeSquared) {
                continue;
            }
            float deltaAngle = bearing(deltaX, deltaZ, heading);
            if (Math.abs(deltaAngle) > 60.0F) {
                continue;
            }
            targets.add(new CompassTarget(MarkerKind.WAYPOINT, null, waypoint,
                    centerX + deltaAngle / 60.0F * radius, Math.sqrt(distanceSquared),
                    waypoint.y() + 0.5D - player.getY(), false));
        }
    }

    /**
     * Angle of a target relative to the player heading. Minecraft yaw: 0 faces
     * +Z and 90 faces -X, so the direction of the target is atan2(-dx, dz).
     */
    private static float bearing(double deltaX, double deltaZ, float heading) {
        float yaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        return Mth.wrapDegrees(yaw - heading);
    }

    /**
     * Draws the name, the horizontal distance and the relative height of the
     * entity right below its own icon. The label follows the icon sideways and
     * is clamped to the strip so it can never leave the widget.
     */
    private void drawCompassEntityLabel(GuiGraphics graphics, Minecraft minecraft, CompassGroup group, int x,
            int y, int width) {
        CompassTarget nearest = group.nearest;
        Font font = minecraft.font;
        String distanceText = String.format(java.util.Locale.ROOT, "%.1f", nearest.distance());
        Component text;
        if (group.kind == MarkerKind.WAYPOINT) {
            // Waypoints show their name and distance, the height marker does
            // not apply to them.
            text = Component.literal(nearest.waypoint().name()).withStyle(ChatFormatting.WHITE)
                    .copy()
                    .append(" ")
                    .append(Component.translatable("screen.m4cne.hud.entity_distance", distanceText)
                            .withStyle(ChatFormatting.GRAY));
        } else {
            Component name = nearest.marker().name().copy().withStyle(ChatFormatting.WHITE);
            if (group.members.size() > 1) {
                name = name.copy().append(Component.literal(" x" + group.members.size())
                        .withStyle(ChatFormatting.YELLOW));
            }
            // ^ above the player, v below, - roughly level with the player.
            String indicator = heightIndicator(nearest.heightDelta());
            int indicatorColor = "^".equals(indicator) ? 0xFFFF7070
                    : ("v".equals(indicator) ? 0xFF70B0FF : 0xFF9E9E9E);
            text = name.copy()
                    .append(" ")
                    .append(Component.translatable("screen.m4cne.hud.entity_distance", distanceText))
                    .append(" ")
                    .append(Component.literal(indicator).withStyle(style -> style.withColor(indicatorColor)));
        }

        float iconScale = (float) (double) ClientConfig.COMPASS_ICON_SCALE.get();
        // The information text is part of the icon scaling setting, so the
        // "entity icon scale" slider adjusts icons and their details together.
        float textScale = compassLabelScale(iconScale);
        float textWidth = font.width(text) * textScale;
        float iconX = Mth.clamp((float) group.screenX, x + 4.0F, x + width - 4.0F);
        float labelX = Mth.clamp(iconX - textWidth / 2.0F, x + 2.0F,
                Math.max(x + 2.0F, x + width - textWidth - 2.0F));
        int labelY = y + compassLabelOffset(iconScale);
        int backgroundAlpha = compassBackgroundAlpha(220);
        graphics.fill(Math.round(labelX) - 2, labelY - 1, Math.round(labelX + textWidth) + 2,
                labelY + Math.round(font.lineHeight * textScale) + 2, backgroundAlpha << 24);
        graphics.pose().pushPose();
        graphics.pose().translate(labelX, labelY, 0.0F);
        graphics.pose().scale(textScale, textScale, 1.0F);
        graphics.drawString(font, text, 0, 0, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    /** Applies the entity radar category filters to a compass marker. */
    private static boolean categoryVisible(ClientEntityMarkerCache.Marker marker) {
        if (marker.type() == EntityType.PLAYER) {
            return ClientConfig.RADAR_PLAYERS.getAsBoolean();
        }
        MobCategory category = marker.category();
        if (category == MobCategory.MONSTER) {
            return ClientConfig.RADAR_HOSTILE.getAsBoolean();
        }
        if (category == MobCategory.MISC) {
            return ClientConfig.RADAR_ITEMS.getAsBoolean();
        }
        return ClientConfig.RADAR_PASSIVE.getAsBoolean();
    }

    /** Scale of the entity detail text; it is driven by the icon scale slider. */
    private static float compassLabelScale(float iconScale) {
        return COMPASS_LABEL_SCALE * iconScale;
    }

    /**
     * Base space y offset of the entity detail label. Oversized icons push the
     * label down so it keeps following the icon instead of overlapping it.
     */
    private static int compassLabelOffset(float iconScale) {
        float iconBottom = COMPASS_HEADING_BAND - 12.0F + COMPASS_ICON_SIZE * iconScale / 2.0F;
        return Math.max(COMPASS_HEADING_BAND + 1, Math.round(iconBottom) + 2);
    }

    /** Maps the compass background opacity setting to an ARGB alpha byte. */
    private static int compassBackgroundAlpha(int maximum) {
        return Mth.clamp((int) Math.round(ClientConfig.COMPASS_BACKGROUND_OPACITY.get() * maximum),
                0, 255);
    }

    /** Draws the cached icon of an entity type centred on the given position. */
    private void drawEntityIcon(GuiGraphics graphics, EntityType<?> type, float centerX, float centerY,
            float size) {
        ItemStack stack = EntityIconCache.get().icon(type).stack();
        float scale = size / 16.0F;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX - size / 2.0F, centerY - size / 2.0F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }

    /**
     * Draws a small diamond in the waypoint colour centred on the given position.
     */
    private void drawWaypointIcon(GuiGraphics graphics, int color, float centerX, float centerY, float size) {
        int half = Math.max(2, Math.round(size / 2.0F));
        int x = Math.round(centerX);
        int y = Math.round(centerY);
        int rgb = 0xFF000000 | (color & 0xFFFFFF);
        graphics.fill(x - half, y - 1, x + half + 1, y + 1, 0xFF000000);
        graphics.fill(x - 1, y - half, x + 1, y + half + 1, 0xFF000000);
        graphics.fill(x - half + 1, y - 1, x + half, y + 1, rgb);
        graphics.fill(x - 1, y - half + 1, x + 1, y + half, rgb);
        graphics.fill(x, y, x + 1, y + 1, 0xFFFFFFFF);
    }

    /** Height indicator of a target relative to the player. */
    private static String heightIndicator(double heightDelta) {
        return heightDelta > LEVEL_TOLERANCE ? "^" : (heightDelta < -LEVEL_TOLERANCE ? "v" : "-");
    }

    /** Kind of a compass marker. */
    private enum MarkerKind {
        ENTITY, WAYPOINT
    }

    /**
     * One compass marker: an entity or a waypoint mapped onto the strip.
     * Exactly one of {@code marker} / {@code waypoint} is set.
     */
    private record CompassTarget(MarkerKind kind, ClientEntityMarkerCache.Marker marker,
            WaypointStore.Waypoint waypoint, double screenX, double distance, double heightDelta,
            boolean highlighted) {
    }

    /** A set of markers of the same kind that were merged into one marker. */
    private static final class CompassGroup {
        private final List<CompassTarget> members = new ArrayList<>();
        private final MarkerKind kind;
        private final double screenX;
        private CompassTarget nearest;

        private CompassGroup(MarkerKind kind, double screenX) {
            this.kind = kind;
            this.screenX = screenX;
        }

        private void add(CompassTarget target) {
            this.members.add(target);
            if (this.nearest == null || target.distance() < this.nearest.distance()) {
                this.nearest = target;
            }
        }
    }

    // ------------------------------------------------------------ info lines

    /**
     * Extra HUD lines describing the nearest entity and the nearest waypoint.
     * They carry the information the compass shows below its icons, so the
     * minimap displays all necessary details as well.
     */
    private List<Component> buildMarkerInfoLines(LocalPlayer player, ClientLevel level) {
        List<Component> lines = new ArrayList<>();
        if (ClientConfig.MINIMAP_ENTITIES.getAsBoolean()) {
            double best = (double) ClientConfig.MINIMAP_ENTITY_RANGE.getAsInt();
            best *= best;
            ClientEntityMarkerCache.Marker nearest = null;
            for (ClientEntityMarkerCache.Marker marker : ClientEntityMarkerCache.get().markers()) {
                if (!categoryVisible(marker)) {
                    continue;
                }
                double deltaX = marker.x() - player.getX();
                double deltaZ = marker.z() - player.getZ();
                double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
                if (distanceSquared <= best) {
                    best = distanceSquared;
                    nearest = marker;
                }
            }
            if (nearest != null) {
                lines.add(Component.translatable("screen.m4cne.hud.nearest_entity", nearest.name(),
                        formatDistance(Math.sqrt(best)), heightIndicator(nearest.y() - player.getY())));
            }
        }
        if (ClientConfig.MINIMAP_WAYPOINTS.getAsBoolean()) {
            ResourceLocation dimension = level.dimension().location();
            double best = (double) ClientConfig.MINIMAP_WAYPOINT_RANGE.getAsInt();
            best *= best;
            WaypointStore.Waypoint nearest = null;
            for (WaypointStore.Waypoint waypoint : WaypointStore.get().forDimension(dimension)) {
                double deltaX = waypoint.x() + 0.5D - player.getX();
                double deltaZ = waypoint.z() + 0.5D - player.getZ();
                double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
                if (distanceSquared <= best) {
                    best = distanceSquared;
                    nearest = waypoint;
                }
            }
            if (nearest != null) {
                lines.add(Component.translatable("screen.m4cne.hud.nearest_waypoint",
                        Component.literal(nearest.name()), formatDistance(Math.sqrt(best))));
            }
        }
        return lines;
    }

    /** Formats a distance like the compass labels do. */
    private static Component formatDistance(double distance) {
        return Component.translatable("screen.m4cne.hud.entity_distance",
                String.format(java.util.Locale.ROOT, "%.1f", distance));
    }

    private List<Component> buildInfoLines(Minecraft minecraft, LocalPlayer player, ClientLevel level) {
        List<Component> lines = new ArrayList<>();
        if (ClientConfig.MINIMAP_SHOW_COORDINATES.getAsBoolean()) {
            lines.add(Component.translatable("screen.m4cne.hud.coordinates",
                    String.format(java.util.Locale.ROOT, "%.1f", player.getX()),
                    String.format(java.util.Locale.ROOT, "%.1f", player.getY()),
                    String.format(java.util.Locale.ROOT, "%.1f", player.getZ())));
        }
        if (ClientConfig.MINIMAP_SHOW_BIOME.getAsBoolean()) {
            ResourceLocation biome = null;
            try {
                biome = level.getBiome(player.blockPosition()).unwrapKey().map(key -> key.location()).orElse(null);
            } catch (RuntimeException ignored) {
                // The level may be unloading; the biome line is simply skipped.
            }
            lines.add(Component.translatable("screen.m4cne.hud.biome",
                    biome != null ? Component.translatable(biome.toLanguageKey("biome"))
                            : Component.translatable("screen.m4cne.query.unknown")));
        }
        if (ClientConfig.MINIMAP_SHOW_DIMENSION.getAsBoolean()) {
            ResourceLocation dimension = level.dimension().location();
            lines.add(Component.translatable("screen.m4cne.hud.dimension",
                    Component.translatable(dimension.toLanguageKey("dimension"))));
        }
        if (ClientConfig.MINIMAP_SHOW_CHUNK.getAsBoolean()) {
            ResourceLocation dimension = level.dimension().location();
            ChunkPos chunk = player.chunkPosition();
            Boolean slime = SlimeChunkCache.get().isSlimeChunk(dimension, chunk.x, chunk.z);
            ClientChunkStore.Entry entry = ClientChunkStore.get().get(dimension, chunk.x, chunk.z);
            int entities = entry != null ? entry.entities().total() : 0;
            if (slime == null) {
                lines.add(Component.translatable("screen.m4cne.hud.chunk_info", chunk.x, chunk.z, entities));
            } else {
                lines.add(Component.translatable(slime ? "screen.m4cne.hud.chunk_info_slime"
                        : "screen.m4cne.hud.chunk_info_not_slime", chunk.x, chunk.z, entities));
            }
        }
        return lines;
    }

    private void renderInfoVertical(GuiGraphics graphics, Minecraft minecraft, List<Component> lines, int x,
            int y, int width, int height, float scale) {
        graphics.fill(x, y, x + width, y + height, 0x90000000);
        graphics.fill(x, y, x + width, y + 1, 0x40FFFFFF);
        graphics.pose().pushPose();
        graphics.pose().translate(x + 3, y + 3, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        int lineY = 0;
        for (Component line : lines) {
            graphics.drawString(minecraft.font, line, 0, lineY, 0xFFFFFFFF, true);
            // Step in the unscaled text space; the pose already applies the scale.
            lineY += 10;
        }
        graphics.pose().popPose();
    }

    /**
     * Lays the compass information entries out horizontally. A row holds at
     * most {@link #INFO_MAX_PER_ROW} entries; the following entries start a new
     * row below it.
     */
    private List<InfoRow> layoutInfoRows(Font font, List<Component> items) {
        List<InfoRow> rows = new ArrayList<>();
        List<Component> current = new ArrayList<>();
        int width = 0;
        for (Component item : items) {
            if (current.size() >= INFO_MAX_PER_ROW) {
                rows.add(new InfoRow(List.copyOf(current), width));
                current.clear();
                width = 0;
            }
            if (!current.isEmpty()) {
                width += INFO_GAP;
            }
            current.add(item);
            width += font.width(item);
        }
        if (!current.isEmpty()) {
            rows.add(new InfoRow(List.copyOf(current), width));
        }
        return rows;
    }

    /** Draws the horizontal compass information panel, one row at a time. */
    private void renderInfoHorizontal(GuiGraphics graphics, Minecraft minecraft, List<InfoRow> rows, int x,
            int y, int width, int height, float scale) {
        int backgroundAlpha = compassBackgroundAlpha(200);
        graphics.fill(x, y, x + width, y + height, backgroundAlpha << 24);
        graphics.fill(x, y, x + width, y + 1, 0x40FFFFFF);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y + 2, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        Font font = minecraft.font;
        float panelWidth = width / scale;
        int lineY = 0;
        for (InfoRow row : rows) {
            // Each row is centred inside the panel so the layout stays balanced.
            float startX = Math.max(3.0F, (panelWidth - row.width()) / 2.0F);
            for (Component item : row.items()) {
                graphics.drawString(font, item, Math.round(startX), lineY, 0xFFFFFFFF, true);
                startX += font.width(item) + INFO_GAP;
            }
            lineY += 10;
        }
        graphics.pose().popPose();
    }

    /** One horizontal row of the compass information panel. */
    private record InfoRow(List<Component> items, int width) {
    }
}
