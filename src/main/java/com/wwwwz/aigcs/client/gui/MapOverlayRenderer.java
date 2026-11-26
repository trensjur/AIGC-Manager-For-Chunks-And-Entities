package com.wwwwz.aigcs.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.math.Axis;
import com.wwwwz.aigcs.client.ClientChunkStore;
import com.wwwwz.aigcs.client.ClientConfig;
import com.wwwwz.aigcs.client.ClientKeyBindings;
import com.wwwwz.aigcs.client.claim.ClaimStore;
import com.wwwwz.aigcs.client.chunk.ChunkQueryService;
import com.wwwwz.aigcs.client.entity.ClientEntityMarkerCache;
import com.wwwwz.aigcs.client.entity.EntityIconCache;
import com.wwwwz.aigcs.client.map.EntityDisplayMode;
import com.wwwwz.aigcs.client.map.EntityMarkerStyle;
import com.wwwwz.aigcs.client.map.HighlightedPlayers;
import com.wwwwz.aigcs.client.map.LayerSwitchMode;
import com.wwwwz.aigcs.client.map.MapCamera;
import com.wwwwz.aigcs.client.map.MapLayer;
import com.wwwwz.aigcs.client.map.MapViewport;
import com.wwwwz.aigcs.client.map.OreScanCache;
import com.wwwwz.aigcs.client.map.PlayerArrowTexture;
import com.wwwwz.aigcs.client.map.SlimeChunkCache;
import com.wwwwz.aigcs.client.map.WaypointStore;
import com.wwwwz.aigcs.core.ChunkTile;
import com.wwwwz.aigcs.core.EntityCensus;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

/**
 * Draws all non terrain overlays of the world map: chunk grid, radar, waypoint
 * and player markers, the chunk tooltip and the configurable HUD text.
 * <p>
 * Entity counts are cached and only recomputed when the recorded chunk data
 * changed. That keeps per frame rendering independent of the number of chunks.
 */
@SuppressWarnings("null")
public final class MapOverlayRenderer {
    private record RadarEntry(int chunkX, int chunkZ, int players, int hostile, int passive, int misc, int total) {
        private int countFor(boolean showPlayers, boolean showHostile, boolean showPassive, boolean showItems) {
            int count = 0;
            if (showPlayers) {
                count += this.players;
            }
            if (showHostile) {
                count += this.hostile;
            }
            if (showPassive) {
                count += this.passive;
            }
            if (showItems) {
                count += this.misc;
            }
            return count;
        }

        private int dominantCategory(boolean showPlayers, boolean showHostile, boolean showPassive, boolean showItems) {
            int best = 0;
            int bestCount = -1;
            if (showPlayers && this.players > bestCount) {
                best = 0;
                bestCount = this.players;
            }
            if (showHostile && this.hostile > bestCount) {
                best = 1;
                bestCount = this.hostile;
            }
            if (showPassive && this.passive > bestCount) {
                best = 2;
                bestCount = this.passive;
            }
            if (showItems && this.misc > bestCount) {
                best = 3;
                bestCount = this.misc;
            }
            return best;
        }

        private boolean hasAny(boolean showPlayers, boolean showHostile, boolean showPassive, boolean showItems) {
            return this.countFor(showPlayers, showHostile, showPassive, showItems) > 0;
        }
    }

    private static final int[] CATEGORY_COLORS = { 0xFFFFFF, 0xFF5555, 0x55FF55, 0xFFD755 };
    private static final int MAX_MARKERS = 400;
    /** Text size of the chunk information labels. */
    private static final float LABEL_SCALE = 0.7F;
    /** Labels never shrink below this size, they are hidden instead. */
    private static final float LABEL_MIN_SCALE = 0.4F;
    /** Below this chunk size (GUI pixels) the chunk labels are hidden. */
    private static final double MIN_LABEL_CHUNK_PIXELS = 16.0D;
    private static final int MAX_LABELS = 400;

    private final List<RadarEntry> entries = new ArrayList<>();
    private final Map<ResourceLocation, ItemStack> oreIcons = new HashMap<>();
    private long cachedVersion = Long.MIN_VALUE;
    private long lastCacheUpdateAt;
    private ResourceLocation cachedDimension;
    private int cachedMinChunkX = Integer.MIN_VALUE;
    private int cachedMinChunkZ = Integer.MIN_VALUE;
    private int cachedMaxChunkX = Integer.MIN_VALUE;
    private int cachedMaxChunkZ = Integer.MIN_VALUE;
    private ClientEntityMarkerCache.Marker selected;
    private long selectedVersion = Long.MIN_VALUE;

    /** Removes the entity selection, for example when the map is closed. */
    public void clearSelection() {
        this.selected = null;
        this.selectedVersion = Long.MIN_VALUE;
    }

    /** @return the currently selected entity marker or {@code null} */
    public ClientEntityMarkerCache.Marker selected() {
        return this.selected;
    }

    /**
     * Recomputes the entity cache when the recorded data or the visible area
     * changed. Only chunks inside the view are summarised, so the list stays
     * small even when the client store keeps every explored chunk.
     */
    public void update(ResourceLocation dimension, MapCamera camera, MapViewport viewport) {
        int minChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x()) / 16.0D) - 1;
        int maxChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x() + viewport.width()) / 16.0D) + 2;
        int minChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y()) / 16.0D) - 1;
        int maxChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y() + viewport.height()) / 16.0D) + 2;
        long version = ClientChunkStore.get().version();
        boolean dimensionChanged = !dimension.equals(this.cachedDimension);
        boolean viewChanged = minChunkX != this.cachedMinChunkX || maxChunkX != this.cachedMaxChunkX
                || minChunkZ != this.cachedMinChunkZ || maxChunkZ != this.cachedMaxChunkZ;
        if (version == this.cachedVersion && !dimensionChanged && !viewChanged) {
            return;
        }
        // The recorder can change a few chunks every tick. Refreshing the
        // marker cache at most four times per second is plenty for a map.
        long now = System.currentTimeMillis();
        if (!dimensionChanged && this.cachedVersion != Long.MIN_VALUE && now - this.lastCacheUpdateAt < 250L) {
            return;
        }
        this.cachedVersion = version;
        this.cachedDimension = dimension;
        this.cachedMinChunkX = minChunkX;
        this.cachedMaxChunkX = maxChunkX;
        this.cachedMinChunkZ = minChunkZ;
        this.cachedMaxChunkZ = maxChunkZ;
        this.lastCacheUpdateAt = now;
        this.entries.clear();
        ClientChunkStore.get().forEachRegion(dimension, minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                (key, entry) -> this.entries.add(summarise(key, entry.entities())));
    }

    private static RadarEntry summarise(long key, EntityCensus census) {
        int players = 0;
        int hostile = 0;
        int passive = 0;
        int misc = 0;
        for (var count : census.counts().entrySet()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(count.getKey()).orElse(null);
            if (type == null) {
                continue;
            }
            int amount = count.getValue();
            if (type == EntityType.PLAYER) {
                players += amount;
                continue;
            }
            MobCategory category = type.getCategory();
            if (category == MobCategory.MONSTER) {
                hostile += amount;
            } else if (category == MobCategory.MISC) {
                misc += amount;
            } else {
                passive += amount;
            }
        }
        return new RadarEntry(ChunkPos.getX(key), ChunkPos.getZ(key), players, hostile, passive, misc,
                census.total());
    }

    // ------------------------------------------------------------------ radar

    public void drawRadar(GuiGraphics graphics, MapCamera camera, MapViewport viewport) {
        if (!ClientConfig.RADAR_ENABLED.getAsBoolean()) {
            return;
        }
        if (ClientConfig.RADAR_DISPLAY_MODE.get() == EntityDisplayMode.INDIVIDUAL) {
            this.drawEntityMarkers(graphics, camera, viewport);
            return;
        }
        boolean showPlayers = ClientConfig.RADAR_PLAYERS.getAsBoolean();
        boolean showHostile = ClientConfig.RADAR_HOSTILE.getAsBoolean();
        boolean showPassive = ClientConfig.RADAR_PASSIVE.getAsBoolean();
        boolean showItems = ClientConfig.RADAR_ITEMS.getAsBoolean();
        boolean showCounts = ClientConfig.RADAR_SHOW_COUNTS.getAsBoolean();
        double scale = ClientConfig.RADAR_MARKER_SCALE.get();
        int size = Math.max(2, (int) Math.round(3.0D * scale));
        int drawn = 0;

        for (RadarEntry entry : this.entries) {
            if (!entry.hasAny(showPlayers, showHostile, showPassive, showItems)) {
                continue;
            }
            double x = viewport.worldToScreenX(camera, entry.chunkX() * 16.0D + 8.0D);
            double y = viewport.worldToScreenZ(camera, entry.chunkZ() * 16.0D + 8.0D);
            if (!viewport.contains(x, y)) {
                continue;
            }
            int category = entry.dominantCategory(showPlayers, showHostile, showPassive, showItems);
            int color = 0xFF000000 | CATEGORY_COLORS[category];
            int screenX = (int) Math.round(x);
            int screenY = (int) Math.round(y);
            graphics.fill(screenX - size - 1, screenY - size - 1, screenX + size + 1, screenY + size + 1,
                    0xFF000000);
            graphics.fill(screenX - size, screenY - size, screenX + size, screenY + size, color);

            if (showCounts && camera.zoom() * 16.0D >= 20.0D) {
                String text = Integer.toString(entry.countFor(showPlayers, showHostile, showPassive, showItems));
                drawSmallString(graphics, text, screenX + size + 2, screenY - size, 0xFFFFFFFF);
            }
            if (++drawn >= MAX_MARKERS) {
                break;
            }
        }
    }

    // ------------------------------------------------- individual entity dots

    /**
     * Draws every sampled entity separately, either as a dot or as the cached
     * static icon of its type. The marker list is pre-sampled by
     * {@link ClientEntityMarkerCache}, so this loop never touches the level.
     */
    private void drawEntityMarkers(GuiGraphics graphics, MapCamera camera, MapViewport viewport) {
        boolean iconStyle = ClientConfig.RADAR_MARKER_STYLE.get() == EntityMarkerStyle.ICON;
        double iconScale = ClientConfig.RADAR_ICON_SCALE.get();
        double dotScale = ClientConfig.RADAR_MARKER_SCALE.get();
        int maximum = ClientConfig.RADAR_MAX_MARKERS.getAsInt();
        int dotSize = Math.max(1, (int) Math.round(1.6D * dotScale));
        int drawn = 0;

        for (ClientEntityMarkerCache.Marker marker : ClientEntityMarkerCache.get().markers()) {
            if (!this.categoryVisible(marker)) {
                continue;
            }
            // Other players are drawn by drawOtherPlayers so they always carry a
            // name tag and can be highlighted through the right click menu.
            if (marker.type() == EntityType.PLAYER) {
                continue;
            }
            double screenX = viewport.worldToScreenX(camera, marker.x());
            double screenY = viewport.worldToScreenZ(camera, marker.z());
            if (!viewport.contains(screenX, screenY)) {
                continue;
            }
            int x = (int) Math.round(screenX);
            int y = (int) Math.round(screenY);
            if (iconStyle) {
                EntityIconCache.Icon icon = EntityIconCache.get().icon(marker.type());
                ItemStack stack = icon.stack();
                graphics.pose().pushPose();
                graphics.pose().translate(x - 8.0F * (float) iconScale, y - 8.0F * (float) iconScale, 0.0F);
                graphics.pose().scale((float) iconScale, (float) iconScale, 1.0F);
                graphics.renderItem(stack, 0, 0);
                graphics.pose().popPose();
            } else {
                int color = 0xFF000000 | this.markerColor(marker);
                graphics.fill(x - dotSize - 1, y - dotSize - 1, x + dotSize + 1, y + dotSize + 1, 0xFF000000);
                graphics.fill(x - dotSize, y - dotSize, x + dotSize, y + dotSize, color);
            }
            if (++drawn >= maximum) {
                break;
            }
        }
    }

    private boolean categoryVisible(ClientEntityMarkerCache.Marker marker) {
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

    private int markerColor(ClientEntityMarkerCache.Marker marker) {
        if (marker.type() == EntityType.PLAYER) {
            return 0xFFFFFF;
        }
        MobCategory category = marker.category();
        if (category == MobCategory.MONSTER) {
            return 0xFF5555;
        }
        if (category == MobCategory.MISC) {
            return 0xFFD755;
        }
        if (category == MobCategory.WATER_CREATURE || category == MobCategory.WATER_AMBIENT
                || category == MobCategory.UNDERGROUND_WATER_CREATURE || category == MobCategory.AXOLOTLS) {
            return 0x55AAFF;
        }
        if (category == MobCategory.AMBIENT) {
            return 0xAA55FF;
        }
        return 0x55FF55;
    }

    // ---------------------------------------------------- entity click selection

    /**
     * Selects the sampled entity closest to the mouse cursor.
     *
     * @return {@code true} when an entity was hit and the map should not start
     *         dragging
     */
    public boolean pickEntity(MapCamera camera, MapViewport viewport, double mouseX, double mouseY) {
        if (!ClientConfig.RADAR_ENABLED.getAsBoolean() || !ClientConfig.RADAR_CLICK_INFO.getAsBoolean()) {
            return false;
        }
        double reach = ClientConfig.RADAR_DISPLAY_MODE.get() == EntityDisplayMode.INDIVIDUAL
                ? Math.max(7.0D, 8.0D * ClientConfig.RADAR_ICON_SCALE.get())
                : Math.max(10.0D, 6.0D * ClientConfig.RADAR_MARKER_SCALE.get());
        double bestDistance = reach * reach;
        ClientEntityMarkerCache.Marker best = null;
        for (ClientEntityMarkerCache.Marker marker : ClientEntityMarkerCache.get().markers()) {
            if (!this.categoryVisible(marker)) {
                continue;
            }
            double screenX = viewport.worldToScreenX(camera, marker.x());
            double screenY = viewport.worldToScreenZ(camera, marker.z());
            if (!viewport.contains(screenX, screenY)) {
                continue;
            }
            double deltaX = screenX - mouseX;
            double deltaY = screenY - mouseY;
            double distance = deltaX * deltaX + deltaY * deltaY;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = marker;
            }
        }
        if (best == null) {
            return false;
        }
        this.selected = best;
        this.selectedVersion = ClientEntityMarkerCache.get().version();
        return true;
    }

    /** Draws the selection frame plus the name and coordinate box. */
    public void drawSelectedEntity(GuiGraphics graphics, MapCamera camera, MapViewport viewport) {
        if (this.selected == null) {
            return;
        }
        this.refreshSelection();
        ClientEntityMarkerCache.Marker marker = this.selected;
        if (marker == null) {
            return;
        }
        double screenX = viewport.worldToScreenX(camera, marker.x());
        double screenY = viewport.worldToScreenZ(camera, marker.z());
        if (!viewport.contains(screenX, screenY)) {
            this.clearSelection();
            return;
        }
        int x = (int) Math.round(screenX);
        int y = (int) Math.round(screenY);
        int radius = 9;
        int color = 0xFFFFD070;
        // Four corner brackets around the marker.
        graphics.fill(x - radius, y - radius, x - radius + 4, y - radius + 1, color);
        graphics.fill(x - radius, y - radius, x - radius + 1, y - radius + 4, color);
        graphics.fill(x + radius - 4, y - radius, x + radius, y - radius + 1, color);
        graphics.fill(x + radius - 1, y - radius, x + radius, y - radius + 4, color);
        graphics.fill(x - radius, y + radius - 1, x - radius + 4, y + radius, color);
        graphics.fill(x - radius, y + radius - 4, x - radius + 1, y + radius, color);
        graphics.fill(x + radius - 4, y + radius - 1, x + radius, y + radius, color);
        graphics.fill(x + radius - 1, y + radius - 4, x + radius, y + radius, color);

        Font font = Minecraft.getInstance().font;
        List<Component> lines = new ArrayList<>();
        lines.add(marker.name().copy().withStyle(ChatFormatting.WHITE));
        lines.add(Component.translatable("screen.m4cne.entity_info.coords",
                Math.round(marker.x()), Math.round(marker.y()), Math.round(marker.z())));
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        int boxWidth = width + 8;
        int boxHeight = lines.size() * 10 + 6;
        int boxX = x + radius + 4;
        int boxY = y - boxHeight / 2;
        if (boxX + boxWidth > viewport.x() + viewport.width() - 2) {
            boxX = x - radius - boxWidth - 4;
        }
        boxX = Mth.clamp(boxX, viewport.x() + 2, viewport.x() + viewport.width() - boxWidth - 2);
        boxY = Mth.clamp(boxY, viewport.y() + 2, viewport.y() + viewport.height() - boxHeight - 2);
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xE0000000);
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + 1, 0x50FFD070);
        int lineY = boxY + 3;
        for (Component line : lines) {
            graphics.drawString(font, line, boxX + 4, lineY, 0xFFFFFFFF, false);
            lineY += 10;
        }
    }

    /**
     * Keeps the selection attached to the same entity while the marker cache is
     * re-sampled, and drops it once the entity is gone.
     */
    private void refreshSelection() {
        long version = ClientEntityMarkerCache.get().version();
        if (version == this.selectedVersion) {
            return;
        }
        this.selectedVersion = version;
        ClientEntityMarkerCache.Marker previous = this.selected;
        ClientEntityMarkerCache.Marker best = null;
        double bestDistance = 64.0D;
        for (ClientEntityMarkerCache.Marker marker : ClientEntityMarkerCache.get().markers()) {
            if (marker.type() != previous.type()) {
                continue;
            }
            double deltaX = marker.x() - previous.x();
            double deltaZ = marker.z() - previous.z();
            double distance = deltaX * deltaX + deltaZ * deltaZ;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = marker;
            }
        }
        if (best != null) {
            this.selected = best;
        } else {
            this.selected = null;
        }
    }

    // ------------------------------------------------------------ biome layer

    /** Tints every recorded chunk with a stable colour derived from its biome. */
    public void drawBiomes(GuiGraphics graphics, MapCamera camera, MapViewport viewport,
            ResourceLocation dimension) {
        if (!ClientConfig.OVERLAY_BIOME.getAsBoolean()) {
            return;
        }
        int alpha = (int) Math.round(ClientConfig.OVERLAY_BIOME_OPACITY.get() * 150.0D);
        if (alpha <= 0) {
            return;
        }
        int minChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x()) / 16.0D) - 1;
        int maxChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x() + viewport.width()) / 16.0D) + 2;
        int minChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y()) / 16.0D) - 1;
        int maxChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y() + viewport.height()) / 16.0D) + 2;
        ClientChunkStore.get().forEachRegion(dimension, minChunkX, minChunkZ, maxChunkX, maxChunkZ, (key, entry) -> {
            ResourceLocation biome = entry.biome();
            if (biome == null) {
                return;
            }
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            double minX = viewport.worldToScreenX(camera, chunkX * 16.0D);
            double minY = viewport.worldToScreenZ(camera, chunkZ * 16.0D);
            double maxX = viewport.worldToScreenX(camera, chunkX * 16.0D + 16.0D);
            double maxY = viewport.worldToScreenZ(camera, chunkZ * 16.0D + 16.0D);
            if (maxX < viewport.x() || minX > viewport.x() + viewport.width()
                    || maxY < viewport.y() || minY > viewport.y() + viewport.height()) {
                return;
            }
            int left = Mth.clamp((int) Math.floor(minX), viewport.x(), viewport.x() + viewport.width());
            int top = Mth.clamp((int) Math.floor(minY), viewport.y(), viewport.y() + viewport.height());
            int right = Mth.clamp((int) Math.ceil(maxX), left, viewport.x() + viewport.width());
            int bottom = Mth.clamp((int) Math.ceil(maxY), top, viewport.y() + viewport.height());
            if (right <= left || bottom <= top) {
                return;
            }
            graphics.fill(left, top, right, bottom, (alpha << 24) | biomeColor(biome));
        });
    }

    /** Marks slime chunks with a translucent green hatch pattern. */
    public void drawSlimeChunks(GuiGraphics graphics, MapCamera camera, MapViewport viewport,
            ResourceLocation dimension) {
        if (!ClientConfig.OVERLAY_SLIME_CHUNKS.getAsBoolean()) {
            return;
        }
        SlimeChunkCache slime = SlimeChunkCache.get();
        if (!slime.hasSeed(dimension)) {
            return;
        }
        int minChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x()) / 16.0D);
        int maxChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x() + viewport.width()) / 16.0D);
        int minChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y()) / 16.0D);
        int maxChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y() + viewport.height()) / 16.0D);
        long total = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        if (total <= 0 || total > 6000L) {
            return;
        }
        int alpha = (int) Math.round(ClientConfig.OVERLAY_SLIME_OPACITY.get() * 170.0D);
        if (alpha <= 0) {
            return;
        }
        int stripe = (alpha << 24) | 0x55FF55;
        int background = ((alpha / 4) << 24) | 0x55FF55;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!Boolean.TRUE.equals(slime.isSlimeChunk(dimension, chunkX, chunkZ))) {
                    continue;
                }
                int left = (int) Math.floor(viewport.worldToScreenX(camera, chunkX * 16.0D));
                int top = (int) Math.floor(viewport.worldToScreenZ(camera, chunkZ * 16.0D));
                int right = (int) Math.ceil(viewport.worldToScreenX(camera, chunkX * 16.0D + 16.0D));
                int bottom = (int) Math.ceil(viewport.worldToScreenZ(camera, chunkZ * 16.0D + 16.0D));
                left = Mth.clamp(left, viewport.x(), viewport.x() + viewport.width());
                top = Mth.clamp(top, viewport.y(), viewport.y() + viewport.height());
                right = Mth.clamp(right, left, viewport.x() + viewport.width());
                bottom = Mth.clamp(bottom, top, viewport.y() + viewport.height());
                if (right <= left || bottom <= top) {
                    continue;
                }
                graphics.fill(left, top, right, bottom, background);
                fillStripes(graphics, left, top, right, bottom, stripe);
            }
        }
    }

    /**
     * Fills a rectangle with diagonal stripes. The stripes are drawn as rotated
     * quads and clipped to the rectangle through its scissor, which keeps the
     * pattern cheap even for large chunks.
     */
    private static void fillStripes(GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 2 || height <= 2) {
            graphics.fill(left, top, right, bottom, color);
            return;
        }
        // Stripe and gap scale with the chunk so the hatch stays readable at
        // every zoom level.
        int thickness = Math.max(1, Math.round(Math.min(width, height) * 0.18F));
        int period = Math.max(thickness + 2, Math.round(Math.min(width, height) * 0.45F));
        int extent = Math.max(width, height);
        graphics.enableScissor(left, top, right, bottom);
        graphics.pose().pushPose();
        graphics.pose().translate((left + right) / 2.0F, (top + bottom) / 2.0F, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(45.0F));
        for (int offset = -extent; offset <= extent; offset += period) {
            graphics.fill(-extent, offset, extent, offset + thickness, color);
        }
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    /**
     * Draws the icon of the most common ore of every visible chunk and requests
     * the missing scans. The count itself is no longer rendered.
     */
    public void drawOreIcons(GuiGraphics graphics, MapCamera camera, MapViewport viewport,
            ResourceLocation dimension) {
        double chunkPixels = camera.zoom() * 16.0D;
        if (!ClientConfig.OVERLAY_ORE_VEINS.getAsBoolean() || chunkPixels < 12.0D) {
            return;
        }
        float iconScale = (float) Mth.clamp(chunkPixels * 0.55D / 16.0D, 0.5D, 1.0D);
        int drawn = 0;
        for (RadarEntry entry : this.entries) {
            double x = viewport.worldToScreenX(camera, entry.chunkX() * 16.0D + 8.0D);
            double y = viewport.worldToScreenZ(camera, entry.chunkZ() * 16.0D + 8.0D);
            if (!viewport.contains(x, y)) {
                continue;
            }
            OreScanCache.Result result = OreScanCache.get().get(dimension, entry.chunkX(), entry.chunkZ());
            if (result == null) {
                OreScanCache.get().request(dimension, entry.chunkX(), entry.chunkZ());
                continue;
            }
            ItemStack icon = this.dominantOreIcon(result);
            if (icon.isEmpty()) {
                continue;
            }
            drawItemIcon(graphics, icon, x, y, iconScale);
            if (++drawn >= MAX_MARKERS) {
                break;
            }
        }
    }

    /**
     * Draws the yellow frame around the chunk selected by the information query.
     */
    public void drawQueryHighlight(GuiGraphics graphics, MapCamera camera, MapViewport viewport) {
        ChunkQueryService.Report report = ChunkQueryService.get().report();
        if (report == null || !ClientConfig.OVERLAY_QUERY_HIGHLIGHT.getAsBoolean()) {
            return;
        }
        int left = (int) Math.floor(viewport.worldToScreenX(camera, report.chunkX() * 16.0D));
        int top = (int) Math.floor(viewport.worldToScreenZ(camera, report.chunkZ() * 16.0D));
        int right = (int) Math.ceil(viewport.worldToScreenX(camera, report.chunkX() * 16.0D + 16.0D));
        int bottom = (int) Math.ceil(viewport.worldToScreenZ(camera, report.chunkZ() * 16.0D + 16.0D));
        left = Mth.clamp(left, viewport.x(), viewport.x() + viewport.width());
        top = Mth.clamp(top, viewport.y(), viewport.y() + viewport.height());
        right = Mth.clamp(right, viewport.x(), viewport.x() + viewport.width());
        bottom = Mth.clamp(bottom, viewport.y(), viewport.y() + viewport.height());
        if (right <= left || bottom <= top) {
            return;
        }
        int color = 0xFFFFD070;
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, bottom - 1, right, bottom, color);
        graphics.fill(left, top, left + 1, bottom, color);
        graphics.fill(right - 1, top, right, bottom, color);
    }

    private static int biomeColor(ResourceLocation biome) {
        int hash = biome.hashCode();
        int red = 56 + Math.floorMod(hash, 144);
        int green = 56 + Math.floorMod(hash >>> 8, 144);
        int blue = 56 + Math.floorMod(hash >>> 16, 144);
        return (red << 16) | (green << 8) | blue;
    }

    // ---------------------------------------------------------- chunk labels

    /**
     * Draws the chunk information label of every visible chunk: the chunk
     * coordinates and the sampled surface height share one line.
     * <p>
     * The label is independent of the chunk grid, so it stays visible while the
     * grid lines themselves are switched off.
     */
    public void drawChunkLabels(GuiGraphics graphics, MapCamera camera, MapViewport viewport,
            ResourceLocation dimension) {
        LabelMode mode = labelsMode();
        if (mode == LabelMode.NONE) {
            return;
        }
        double chunkPixels = camera.zoom() * 16.0D;
        if (chunkPixels < MIN_LABEL_CHUNK_PIXELS) {
            return;
        }
        ClientChunkStore store = ClientChunkStore.get();
        Font font = Minecraft.getInstance().font;
        int color = withAlpha(0xFFFFFF, ClientConfig.OVERLAY_OPACITY.get());
        String heightPrefix = mode.showHeights()
                ? Component.translatable("screen.m4cne.overlay.height_prefix").getString()
                : "";
        int minChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x()) / 16.0D);
        int maxChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x() + viewport.width()) / 16.0D) + 1;
        int minChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y()) / 16.0D);
        int maxChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y() + viewport.height()) / 16.0D) + 1;
        int labels = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX && labels < MAX_LABELS; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ && labels < MAX_LABELS; chunkZ++) {
                int x = (int) Math.round(viewport.worldToScreenX(camera, chunkX * 16.0D)) + 2;
                int y = (int) Math.round(viewport.worldToScreenZ(camera, chunkZ * 16.0D)) + 2;
                if (!viewport.contains(x, y)) {
                    continue;
                }
                String label = chunkLabel(mode, chunkX, chunkZ, dimension != null
                        ? store.get(dimension, chunkX, chunkZ)
                        : null, heightPrefix);
                if (label.isEmpty()) {
                    continue;
                }
                // Shrink the label so it stays inside its chunk, but never below
                // a size that is still readable.
                double fit = (chunkPixels - 4.0D) / Math.max(1, font.width(label));
                if (fit < LABEL_MIN_SCALE) {
                    continue;
                }
                drawScaledString(graphics, label, x, y, (float) Math.min(LABEL_SCALE, fit), color);
                labels++;
            }
        }
    }

    /** Builds the shared coordinate / height line of one chunk. */
    private static String chunkLabel(LabelMode mode, int chunkX, int chunkZ,
            ClientChunkStore.Entry entry, String heightPrefix) {
        StringBuilder builder = new StringBuilder();
        if (mode.showCoordinates()) {
            builder.append('(').append(chunkX).append(", ").append(chunkZ).append(')');
        }
        if (mode.showHeights()) {
            short height = surfaceHeight(entry);
            if (height != Short.MIN_VALUE) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(heightPrefix).append(' ').append(height);
            }
        }
        return builder.toString();
    }

    /** Sampled surface height in the centre of a recorded chunk. */
    private static short surfaceHeight(ClientChunkStore.Entry entry) {
        return entry != null ? entry.tile().heights[ChunkTile.index(8, 8)] : Short.MIN_VALUE;
    }

    /** Which parts of the chunk information the labels currently show. */
    private enum LabelMode {
        NONE(false, false),
        COORDINATES(true, false),
        HEIGHTS(false, true),
        BOTH(true, true);

        private final boolean coordinates;
        private final boolean heights;

        LabelMode(boolean coordinates, boolean heights) {
            this.coordinates = coordinates;
            this.heights = heights;
        }

        private boolean showCoordinates() {
            return this.coordinates;
        }

        private boolean showHeights() {
            return this.heights;
        }
    }

    private static LabelMode labelsMode() {
        boolean coordinates = ClientConfig.OVERLAY_CHUNK_COORDINATES.getAsBoolean();
        boolean heights = ClientConfig.OVERLAY_HEIGHTS.getAsBoolean();
        if (coordinates && heights) {
            return LabelMode.BOTH;
        }
        if (coordinates) {
            return LabelMode.COORDINATES;
        }
        return heights ? LabelMode.HEIGHTS : LabelMode.NONE;
    }

    // ------------------------------------------------------------------- grid

    public void drawGrid(GuiGraphics graphics, MapCamera camera, MapViewport viewport) {
        double chunkPixels = camera.zoom() * 16.0D;
        if (!ClientConfig.OVERLAY_CHUNK_GRID.getAsBoolean() || chunkPixels < 3.0D) {
            return;
        }
        int minChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x()) / 16.0D);
        int maxChunkX = Mth.floor(viewport.screenToWorldX(camera, viewport.x() + viewport.width()) / 16.0D) + 1;
        int minChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y()) / 16.0D);
        int maxChunkZ = Mth.floor(viewport.screenToWorldZ(camera, viewport.y() + viewport.height()) / 16.0D) + 1;
        int color = withAlpha(0xFFFFFF, ClientConfig.OVERLAY_OPACITY.get() * 0.55D);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            int x = (int) Math.round(viewport.worldToScreenX(camera, chunkX * 16.0D));
            if (x >= viewport.x() && x <= viewport.x() + viewport.width()) {
                graphics.fill(x, viewport.y(), x + 1, viewport.y() + viewport.height(), color);
            }
        }
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            int y = (int) Math.round(viewport.worldToScreenZ(camera, chunkZ * 16.0D));
            if (y >= viewport.y() && y <= viewport.y() + viewport.height()) {
                graphics.fill(viewport.x(), y, viewport.x() + viewport.width(), y + 1, color);
            }
        }
    }

    public void drawHoveredChunk(GuiGraphics graphics, MapCamera camera, MapViewport viewport, int mouseX,
            int mouseY) {
        if (!ClientConfig.OVERLAY_HIGHLIGHT_HOVERED.getAsBoolean() || !viewport.contains(mouseX, mouseY)) {
            return;
        }
        int chunkX = Mth.floor(viewport.screenToWorldX(camera, mouseX) / 16.0D);
        int chunkZ = Mth.floor(viewport.screenToWorldZ(camera, mouseY) / 16.0D);
        double minX = viewport.worldToScreenX(camera, chunkX * 16.0D);
        double minY = viewport.worldToScreenZ(camera, chunkZ * 16.0D);
        double maxX = viewport.worldToScreenX(camera, chunkX * 16.0D + 16.0D);
        double maxY = viewport.worldToScreenZ(camera, chunkZ * 16.0D + 16.0D);
        int left = Mth.clamp((int) Math.floor(minX), viewport.x(), viewport.x() + viewport.width() - 1);
        int top = Mth.clamp((int) Math.floor(minY), viewport.y(), viewport.y() + viewport.height() - 1);
        int right = Mth.clamp((int) Math.ceil(maxX), left + 1, viewport.x() + viewport.width());
        int bottom = Mth.clamp((int) Math.ceil(maxY), top + 1, viewport.y() + viewport.height());
        int alpha = (int) (ClientConfig.OVERLAY_OPACITY.get() * 90.0D);
        int fill = (alpha << 24) | 0xFFFFFF;
        graphics.fill(left, top, right, bottom, fill);
        int border = (alpha << 24) | 0xFFFFFF;
        graphics.fill(left, top, right, top + 1, border);
        graphics.fill(left, bottom - 1, right, bottom, border);
        graphics.fill(left, top, left + 1, bottom, border);
        graphics.fill(right - 1, top, right, bottom, border);
    }

    // -------------------------------------------------------------- waypoints

    /**
     * Draws the waypoint markers. The names are drawn later by
     * {@link #drawWaypointLabels}, after every other map icon, so the waypoint
     * HUD is never covered by terrain markers.
     */
    public void drawWaypointMarkers(GuiGraphics graphics, MapCamera camera, MapViewport viewport,
            ResourceLocation dimension) {
        if (!ClientConfig.WAYPOINT_SHOW.getAsBoolean()) {
            return;
        }
        double scale = ClientConfig.WAYPOINT_MARKER_SCALE.get();
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().forDimension(dimension)) {
            double x = viewport.worldToScreenX(camera, waypoint.x() + 0.5D);
            double y = viewport.worldToScreenZ(camera, waypoint.z() + 0.5D);
            if (!viewport.contains(x, y)) {
                continue;
            }
            int screenX = (int) Math.round(x);
            int screenY = (int) Math.round(y);
            int radius = Math.max(2, (int) Math.round(3.0D * scale));
            int color = 0xFF000000 | waypoint.color();
            // Small diamond: wide centre row plus two narrower rows.
            graphics.fill(screenX - radius, screenY - 1, screenX + radius, screenY + 1, 0xFF000000);
            graphics.fill(screenX - 1, screenY - radius, screenX + 1, screenY + radius, 0xFF000000);
            graphics.fill(screenX - radius + 1, screenY - 1, screenX + radius - 1, screenY + 1, color);
            graphics.fill(screenX - 1, screenY - radius + 1, screenX + 1, screenY + radius - 1, color);
            graphics.fill(screenX - 1, screenY - 1, screenX + 1, screenY + 1, 0xFFFFFFFF);
        }
    }

    /**
     * Draws the waypoint names as the topmost map layer, so no map icon can
     * cover this HUD. The former distance line was removed together with the
     * "render distance" setting.
     */
    public void drawWaypointLabels(GuiGraphics graphics, MapCamera camera, MapViewport viewport,
            ResourceLocation dimension) {
        if (!ClientConfig.WAYPOINT_SHOW.getAsBoolean() || !ClientConfig.WAYPOINT_SHOW_NAMES.getAsBoolean()) {
            return;
        }
        int radius = Math.max(2, (int) Math.round(3.0D * ClientConfig.WAYPOINT_MARKER_SCALE.get()));
        Font font = Minecraft.getInstance().font;
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().forDimension(dimension)) {
            double x = viewport.worldToScreenX(camera, waypoint.x() + 0.5D);
            double y = viewport.worldToScreenZ(camera, waypoint.z() + 0.5D);
            if (!viewport.contains(x, y)) {
                continue;
            }
            int screenX = (int) Math.round(x);
            int screenY = (int) Math.round(y);
            Component name = Component.literal(waypoint.name()).withStyle(ChatFormatting.WHITE);
            int width = font.width(name);
            int boxHeight = 12;
            graphics.fill(screenX - width / 2 - 2, screenY + radius + 2,
                    screenX + width / 2 + 2, screenY + radius + 2 + boxHeight, 0x80000000);
            graphics.drawString(font, name, screenX - width / 2, screenY + radius + 4, 0xFFFFFFFF, false);
        }
    }

    /**
     * Finds the waypoint marker under the mouse cursor.
     *
     * @return the picked waypoint or {@code null} when the click missed
     */
    public WaypointStore.Waypoint pickWaypoint(MapCamera camera, MapViewport viewport, double mouseX,
            double mouseY, ResourceLocation dimension) {
        if (!ClientConfig.WAYPOINT_SHOW.getAsBoolean()) {
            return null;
        }
        double radius = Math.max(6.0D, 5.0D * ClientConfig.WAYPOINT_MARKER_SCALE.get());
        double bestDistance = radius * radius;
        WaypointStore.Waypoint best = null;
        for (WaypointStore.Waypoint waypoint : WaypointStore.get().forDimension(dimension)) {
            double screenX = viewport.worldToScreenX(camera, waypoint.x() + 0.5D);
            double screenY = viewport.worldToScreenZ(camera, waypoint.z() + 0.5D);
            if (!viewport.contains(screenX, screenY)) {
                continue;
            }
            double deltaX = screenX - mouseX;
            double deltaY = screenY - mouseY;
            double distance = deltaX * deltaX + deltaY * deltaY;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = waypoint;
            }
        }
        return best;
    }

    // ---------------------------------------------------------- player marker

    public void drawPlayerMarker(GuiGraphics graphics, LocalPlayer player, MapCamera camera, MapViewport viewport) {
        double x = viewport.worldToScreenX(camera, player.getX());
        double y = viewport.worldToScreenZ(camera, player.getZ());
        if (!viewport.contains(x, y)) {
            return;
        }
        PlayerArrowTexture.get().draw(graphics, (float) x, (float) y, 16.0F, player.getYRot());
    }

    /**
     * Draws every other player with a name tag. Highlighted players get a gold
     * diamond behind their icon so the state is visible on the map too.
     */
    public void drawOtherPlayers(GuiGraphics graphics, MapCamera camera, MapViewport viewport) {
        if (!ClientConfig.RADAR_PLAYERS.getAsBoolean()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean names = camera.zoom() * 16.0D >= 7.0D;
        for (ClientEntityMarkerCache.Marker marker : ClientEntityMarkerCache.get().markers()) {
            if (marker.type() != EntityType.PLAYER) {
                continue;
            }
            double screenX = viewport.worldToScreenX(camera, marker.x());
            double screenY = viewport.worldToScreenZ(camera, marker.z());
            if (!viewport.contains(screenX, screenY)) {
                continue;
            }
            int x = (int) Math.round(screenX);
            int y = (int) Math.round(screenY);
            if (HighlightedPlayers.isHighlighted(marker.id())) {
                graphics.fill(x - 7, y - 2, x + 8, y + 2, 0xFFFFD050);
                graphics.fill(x - 2, y - 7, x + 2, y + 8, 0xFFFFD050);
            } else {
                graphics.fill(x - 5, y - 5, x + 6, y + 6, 0x80000000);
            }
            ItemStack stack = EntityIconCache.get().icon(marker.type()).stack();
            graphics.pose().pushPose();
            graphics.pose().translate(x - 6.0F, y - 6.0F, 0.0F);
            graphics.pose().scale(0.75F, 0.75F, 1.0F);
            graphics.renderItem(stack, 0, 0);
            graphics.pose().popPose();
            if (names) {
                Component name = marker.name();
                int width = font.width(name);
                int textX = Mth.clamp(x - width / 2, viewport.x() + 1, viewport.x() + viewport.width() - width - 1);
                graphics.fill(textX - 2, y + 7, textX + width + 2, y + 17, 0x80000000);
                graphics.drawString(font, name, textX, y + 8, 0xFFFFFFFF, false);
            }
        }
    }

    /**
     * Finds the player icon under the mouse cursor.
     *
     * @return the picked player marker or {@code null} when the click missed
     */
    public ClientEntityMarkerCache.Marker pickPlayer(MapCamera camera, MapViewport viewport, double mouseX,
            double mouseY) {
        double bestDistance = 12.0D * 12.0D;
        ClientEntityMarkerCache.Marker best = null;
        for (ClientEntityMarkerCache.Marker marker : ClientEntityMarkerCache.get().markers()) {
            if (marker.type() != EntityType.PLAYER) {
                continue;
            }
            double screenX = viewport.worldToScreenX(camera, marker.x());
            double screenY = viewport.worldToScreenZ(camera, marker.z());
            if (!viewport.contains(screenX, screenY)) {
                continue;
            }
            double deltaX = screenX - mouseX;
            double deltaY = screenY - mouseY;
            double distance = deltaX * deltaX + deltaY * deltaY;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = marker;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------- HUD

    public void drawHud(GuiGraphics graphics, MapCamera camera, MapViewport viewport, LocalPlayer player,
            MapLayer layer, LayerSwitchMode mode, int caveY) {
        if (!ClientConfig.HUD_ENABLED.getAsBoolean()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        List<Component> lines = new ArrayList<>();
        if (ClientConfig.HUD_SHOW_COORDINATES.getAsBoolean()) {
            lines.add(Component.translatable("screen.m4cne.hud.coordinates",
                    String.format(java.util.Locale.ROOT, "%.1f", player.getX()),
                    String.format(java.util.Locale.ROOT, "%.1f", player.getY()),
                    String.format(java.util.Locale.ROOT, "%.1f", player.getZ())));
        }
        ChunkPos chunk = player.chunkPosition();
        if (ClientConfig.HUD_SHOW_CHUNK_COORDINATES.getAsBoolean()) {
            lines.add(Component.translatable("screen.m4cne.hud.chunk", chunk.x, chunk.z));
        }
        if (ClientConfig.HUD_SHOW_ENTITY_TOTALS.getAsBoolean()) {
            lines.add(Component.translatable("screen.m4cne.hud.entities", this.totalInView(camera, viewport)));
        }
        if (ClientConfig.HUD_SHOW_SCALE.getAsBoolean()) {
            lines.add(Component.translatable("screen.m4cne.hud.scale",
                    String.format(java.util.Locale.ROOT, "%.2f", 1.0D / camera.zoom())));
        }
        Component layerName = MapSettingsPanel.layerName(layer);
        if (layer == MapLayer.CAVE) {
            layerName = Component.translatable("screen.m4cne.hud.layer_cave", layerName, caveY);
        }
        lines.add(Component.translatable("screen.m4cne.hud.layer", layerName,
                MapSettingsPanel.modeName(mode)));

        if (lines.isEmpty()) {
            return;
        }
        float scale = (float) (double) ClientConfig.HUD_INFO_SCALE.get();
        int maxWidth = 0;
        for (Component line : lines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }
        int boxX = viewport.x() + 5;
        int boxY = viewport.y() + 5;
        int boxWidth = (int) Math.ceil((maxWidth + 8) * scale);
        int boxHeight = (int) Math.ceil((lines.size() * 10 + 6) * scale);
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0x90000000);
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + 1, 0x40FFFFFF);

        graphics.pose().pushPose();
        graphics.pose().translate(boxX + 4, boxY + 3, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        int y = 0;
        for (Component line : lines) {
            graphics.drawString(font, line, 0, y, 0xFFFFFFFF, true);
            y += 10;
        }
        graphics.pose().popPose();

        Component hint = Component.translatable("screen.m4cne.hud.hint",
                ClientKeyBindings.OPEN_MAP.getTranslatedKeyMessage());
        int hintWidth = Math.round(font.width(hint) * 0.8F);
        graphics.pose().pushPose();
        graphics.pose().translate(viewport.x() + viewport.width() - hintWidth - 5, viewport.y() + 5, 0.0F);
        graphics.pose().scale(0.8F, 0.8F, 1.0F);
        graphics.drawString(font, hint, 0, 0, 0xFFC0C0C0, true);
        graphics.pose().popPose();
    }

    public void drawChunkTooltip(GuiGraphics graphics, MapCamera camera, MapViewport viewport, int mouseX,
            int mouseY, ResourceLocation dimension) {
        if (!viewport.contains(mouseX, mouseY)) {
            return;
        }
        int chunkX = Mth.floor(viewport.screenToWorldX(camera, mouseX) / 16.0D);
        int chunkZ = Mth.floor(viewport.screenToWorldZ(camera, mouseY) / 16.0D);
        if (camera.zoom() * 16.0D < 16.0D) {
            return;
        }
        ClientChunkStore.Entry entry = ClientChunkStore.get().get(dimension, chunkX, chunkZ);
        Font font = Minecraft.getInstance().font;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.m4cne.tooltip.chunk", chunkX, chunkZ));
        if (entry == null) {
            lines.add(Component.translatable("screen.m4cne.tooltip.no_data"));
        } else {
            short height = entry.tile().heights[ChunkTile.index(8, 8)];
            if (height != Short.MIN_VALUE) {
                lines.add(Component.translatable("screen.m4cne.tooltip.height", height));
            }
            lines.add(Component.translatable("screen.m4cne.tooltip.entities", entry.entities().total()));
            if (!entry.entities().isEmpty()) {
                lines.add(Component.translatable("screen.m4cne.tooltip.entity_types",
                        entry.entities().counts().size()));
            }
        }
        if (entry != null && entry.biome() != null) {
            lines.add(Component.translatable("screen.m4cne.tooltip.biome",
                    Component.translatable(entry.biome().toLanguageKey("biome"))));
        }
        Boolean slime = SlimeChunkCache.get().isSlimeChunk(dimension, chunkX, chunkZ);
        if (slime != null) {
            lines.add(Component.translatable(slime ? "screen.m4cne.tooltip.slime" : "screen.m4cne.tooltip.not_slime"));
        }
        ClaimStore.Ref claim = ClaimStore.get().refAt(dimension.toString(), chunkX, chunkZ);
        if (claim != null) {
            lines.add(Component.translatable("screen.m4cne.tooltip.claim", claim.owner().name(),
                    claim.displayId(chunkX, chunkZ),
                    Component.translatable(claim.forceLoad() ? "screen.m4cne.state.enabled"
                            : "screen.m4cne.state.disabled")));
        }
        OreScanCache.Result ores = OreScanCache.get().get(dimension, chunkX, chunkZ);
        if (ores != null && !ores.isEmpty()) {
            lines.add(Component.translatable("screen.m4cne.tooltip.ores", ores.total()));
        }

        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        int boxWidth = width + 8;
        int boxHeight = lines.size() * 10 + 6;
        int boxX = mouseX + 8;
        int boxY = mouseY + 8;
        if (boxX + boxWidth > viewport.x() + viewport.width() - 2) {
            boxX = mouseX - boxWidth - 8;
        }
        if (boxY + boxHeight > viewport.y() + viewport.height() - 2) {
            boxY = mouseY - boxHeight - 8;
        }
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xE0000000);
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + 1, 0x50FFFFFF);
        int y = boxY + 3;
        for (Component line : lines) {
            graphics.drawString(font, line, boxX + 4, y, 0xFFFFFFFF, false);
            y += 10;
        }
    }

    private int totalInView(MapCamera camera, MapViewport viewport) {
        int total = 0;
        for (RadarEntry entry : this.entries) {
            double x = viewport.worldToScreenX(camera, entry.chunkX() * 16.0D + 8.0D);
            double y = viewport.worldToScreenZ(camera, entry.chunkZ() * 16.0D + 8.0D);
            if (viewport.contains(x, y)) {
                total += entry.total();
            }
        }
        return total;
    }

    // --------------------------------------------------------------- helpers

    /**
     * Resolves the icon of the most abundant ore of one chunk. The resolved
     * stacks are cached per block id, so the registry is only queried once.
     */
    private ItemStack dominantOreIcon(OreScanCache.Result result) {
        ResourceLocation best = null;
        int bestCount = 0;
        for (Map.Entry<ResourceLocation, Integer> entry : result.counts().entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best != null ? this.oreIcons.computeIfAbsent(best, MapOverlayRenderer::resolveOreIcon)
                : ItemStack.EMPTY;
    }

    /** Ore block id to its block item; empty when the block has no item. */
    private static ItemStack resolveOreIcon(ResourceLocation id) {
        ItemStack material = resolveMaterialItem(id);
        if (!material.isEmpty()) {
            return material;
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) {
            return ItemStack.EMPTY;
        }
        Item item = block.asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    /**
     * Ore blocks whose material item does not follow the
     * {@code <material>_ore -> raw_<material>} pattern.
     */
    private static final Map<String, String> MATERIAL_OVERRIDES = Map.of(
            "ancient_debris", "netherite_scrap",
            "nether_quartz_ore", "quartz",
            "quartz_ore", "quartz",
            "lapis_ore", "lapis_lazuli",
            "deepslate_lapis_ore", "lapis_lazuli");

    /**
     * Resolves the material item of an ore block, so a deepslate iron ore is
     * shown as raw iron instead of the ore block itself.
     */
    private static ItemStack resolveMaterialItem(ResourceLocation oreId) {
        String material = MATERIAL_OVERRIDES.get(oreId.getPath());
        if (material == null) {
            material = materialName(oreId.getPath());
        }
        if (material.isEmpty()) {
            return ItemStack.EMPTY;
        }
        String[] candidates = { "raw_" + material, material, material + "_ingot", material + "_gem",
                material + "_nugget", material + "_dust" };
        for (String candidate : candidates) {
            ResourceLocation itemId = ResourceLocation.tryBuild(oreId.getNamespace(), candidate);
            if (itemId == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
            if (item != null && item != Items.AIR) {
                return new ItemStack(item);
            }
        }
        return ItemStack.EMPTY;
    }

    /** Strips the usual prefixes and suffixes from an ore block path. */
    private static String materialName(String path) {
        String name = path;
        for (String prefix : new String[] { "deepslate_", "nether_", "end_", "raw_", "poor_", "rich_" }) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                name = name.substring(prefix.length());
            }
        }
        for (String suffix : new String[] { "_ores", "_ore", "ore" }) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                name = name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    /** Draws a 16x16 item icon centred on the given screen position. */
    private static void drawItemIcon(GuiGraphics graphics, ItemStack stack, double centerX, double centerY,
            float scale) {
        graphics.pose().pushPose();
        graphics.pose().translate((float) centerX - 8.0F * scale, (float) centerY - 8.0F * scale, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }

    private static void drawSmallString(GuiGraphics graphics, String text, int x, int y, int color) {
        drawScaledString(graphics, text, x, y, 0.7F, color);
    }

    private static void drawScaledString(GuiGraphics graphics, String text, int x, int y, float scale,
            int color) {
        Font font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, color, true);
        graphics.pose().popPose();
    }

    private static int withAlpha(int rgb, double opacity) {
        int alpha = Mth.clamp((int) Math.round(opacity * 255.0D), 0, 255);
        return (alpha << 24) | rgb;
    }
}
