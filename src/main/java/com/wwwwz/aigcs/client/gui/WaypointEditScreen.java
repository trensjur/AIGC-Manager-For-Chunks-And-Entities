package com.wwwwz.aigcs.client.gui;

import com.wwwwz.aigcs.client.map.WaypointColors;
import com.wwwwz.aigcs.client.map.WaypointStore;
import com.wwwwz.aigcs.core.WaypointCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Modal editor for a new or an existing waypoint.
 * <p>
 * It opens from the right click menu ("create a waypoint here"), from the
 * waypoint manager list and from the new waypoint hotkey. The name can be
 * edited, the colour is picked from the 64 colour grid and the category cycles
 * through the supported types. When it is opened for an existing waypoint the
 * saved values replace the stored ones.
 */
@SuppressWarnings("null")
public final class WaypointEditScreen extends Screen {
    private static final int PANEL_WIDTH = 330;
    private static final int PANEL_HEIGHT = 286;
    private static final int CELL = 14;
    private static final int GAP = 1;
    private static final int PALETTE_SIDE = WaypointColors.GRID * (CELL + GAP) - GAP;

    private final Screen parent;
    private final ResourceLocation dimension;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final String defaultName;
    /** Id of the edited waypoint, {@code null} while creating a new one. */
    private final String editingId;
    private final boolean sync;
    private final boolean showDistance;

    private String name;
    private int colorIndex;
    private WaypointCategory category = WaypointCategory.VILLAGE;
    private EditBox nameField;

    private int panelX;
    private int panelY;

    public WaypointEditScreen(Screen parent, ResourceLocation dimension, int x, int y, int z,
            String defaultName) {
        super(Component.translatable("screen.m4cne.waypoint_edit.title"));
        this.parent = parent;
        this.dimension = dimension;
        this.blockX = x;
        this.blockY = y;
        this.blockZ = z;
        this.defaultName = defaultName;
        this.name = defaultName;
        this.colorIndex = 3;
        this.editingId = null;
        this.sync = true;
        this.showDistance = false;
    }

    /**
     * Editor of an existing waypoint: the name, category and colour can be changed.
     */
    public WaypointEditScreen(Screen parent, WaypointStore.Waypoint waypoint) {
        super(Component.translatable("screen.m4cne.waypoint_edit.title_edit"));
        this.parent = parent;
        this.dimension = waypoint.dimensionId();
        this.blockX = waypoint.x();
        this.blockY = waypoint.y();
        this.blockZ = waypoint.z();
        this.defaultName = waypoint.name();
        this.name = waypoint.name();
        this.colorIndex = WaypointColors.indexOf(waypoint.color());
        this.category = waypoint.category();
        this.editingId = waypoint.id();
        this.sync = waypoint.sync();
        this.showDistance = waypoint.showDistance();
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - PANEL_HEIGHT) / 2;

        this.nameField = new EditBox(this.font, this.panelX + 14, this.panelY + 40,
                PANEL_WIDTH - 28, 20, Component.translatable("screen.m4cne.waypoint_edit.name"));
        this.nameField.setMaxLength(48);
        this.nameField.setValue(this.name);
        this.nameField.setResponder(value -> this.name = value);
        this.addRenderableWidget(this.nameField);
        this.setFocused(this.nameField);

        Button categoryButton = Button.builder(this.categoryLabel(), button -> {
            this.category = this.category.next();
            button.setMessage(this.categoryLabel());
        }).bounds(this.panelX + 14, this.panelY + 76, PANEL_WIDTH - 28, 20).build();
        this.addRenderableWidget(categoryButton);

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.m4cne.waypoint_edit.save"),
                button -> this.saveWaypoint())
                .bounds(this.panelX + 14, this.panelY + PANEL_HEIGHT - 34, 150, 20).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.m4cne.waypoint_edit.cancel"),
                button -> this.onClose())
                .bounds(this.panelX + PANEL_WIDTH - 164, this.panelY + PANEL_HEIGHT - 34, 150, 20).build());
    }

    private Component categoryLabel() {
        return Component.translatable("screen.m4cne.waypoint_edit.category_value",
                Component.translatable(this.category.translationKey()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(this.panelX - 2, this.panelY - 2, this.panelX + PANEL_WIDTH + 2,
                this.panelY + PANEL_HEIGHT + 2, 0xFF000000);
        graphics.fill(this.panelX - 1, this.panelY - 1, this.panelX + PANEL_WIDTH + 1,
                this.panelY + PANEL_HEIGHT + 1, 0xFF8B8B8B);
        graphics.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH,
                this.panelY + PANEL_HEIGHT, 0xF0181818);
        graphics.fill(this.panelX, this.panelY, this.panelX + PANEL_WIDTH, this.panelY + 1, 0xFFFFD070);

        graphics.drawString(this.font, this.title, this.panelX + 14, this.panelY + 10, 0xFFFFFFFF, true);
        graphics.drawString(this.font, Component.translatable("screen.m4cne.waypoint_edit.name"),
                this.panelX + 14, this.panelY + 28, 0xFFB0B0B0, false);
        graphics.drawString(this.font, Component.translatable("screen.m4cne.waypoint_edit.category"),
                this.panelX + 14, this.panelY + 64, 0xFFB0B0B0, false);
        graphics.drawString(this.font, Component.translatable("screen.m4cne.waypoint_edit.color"),
                this.panelX + 14, this.panelY + 102, 0xFFB0B0B0, false);

        this.renderPalette(graphics);
        this.renderPreviewAndInfo(graphics);

        for (Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void renderPalette(GuiGraphics graphics) {
        int originX = this.paletteX();
        int originY = this.paletteY();
        for (int column = 0; column < WaypointColors.GRID; column++) {
            for (int row = 0; row < WaypointColors.GRID; row++) {
                int index = column * WaypointColors.GRID + row;
                int x = originX + column * (CELL + GAP);
                int y = originY + row * (CELL + GAP);
                graphics.fill(x, y, x + CELL, y + CELL, 0xFF000000 | WaypointColors.at(index));
                if (index == this.colorIndex) {
                    graphics.fill(x - 2, y - 2, x + CELL + 2, y - 1, 0xFFFFFFFF);
                    graphics.fill(x - 2, y + CELL + 1, x + CELL + 2, y + CELL + 2, 0xFFFFFFFF);
                    graphics.fill(x - 2, y - 1, x - 1, y + CELL + 1, 0xFFFFFFFF);
                    graphics.fill(x + CELL + 1, y - 1, x + CELL + 2, y + CELL + 1, 0xFFFFFFFF);
                }
            }
        }
    }

    private void renderPreviewAndInfo(GuiGraphics graphics) {
        int infoX = this.paletteX() + PALETTE_SIDE + 14;
        int infoY = this.paletteY();
        int color = 0xFF000000 | WaypointColors.at(this.colorIndex);
        graphics.fill(infoX - 1, infoY - 1, infoX + 34, infoY + 34, 0xFF000000);
        graphics.fill(infoX, infoY, infoX + 33, infoY + 33, color);
        graphics.fill(infoX + 8, infoY + 8, infoX + 25, infoY + 9, 0xFFFFFFFF);
        graphics.fill(infoX + 8, infoY + 25, infoX + 25, infoY + 26, 0xFFFFFFFF);
        graphics.fill(infoX + 8, infoY + 8, infoX + 9, infoY + 26, 0xFFFFFFFF);
        graphics.fill(infoX + 24, infoY + 8, infoX + 25, infoY + 26, 0xFFFFFFFF);

        Component position = Component.translatable("screen.m4cne.waypoint_edit.position",
                this.blockX, this.blockY, this.blockZ, this.dimension.toString());
        graphics.drawWordWrap(this.font, position, infoX, infoY + 44, PANEL_WIDTH - 28 - PALETTE_SIDE - 40,
                0xFFFFFFFF);
        graphics.drawWordWrap(this.font, Component.translatable("screen.m4cne.waypoint_edit.hint"),
                infoX, infoY + 76, PANEL_WIDTH - 28 - PALETTE_SIDE - 40, 0xFFA0A0A0);
    }

    private int paletteX() {
        return this.panelX + 14;
    }

    private int paletteY() {
        return this.panelY + 114;
    }

    private int paletteIndexAt(double mouseX, double mouseY) {
        int localX = (int) Math.floor(mouseX) - this.paletteX();
        int localY = (int) Math.floor(mouseY) - this.paletteY();
        if (localX < 0 || localY < 0) {
            return -1;
        }
        int column = localX / (CELL + GAP);
        int row = localY / (CELL + GAP);
        if (column >= WaypointColors.GRID || row >= WaypointColors.GRID) {
            return -1;
        }
        if (localX % (CELL + GAP) >= CELL || localY % (CELL + GAP) >= CELL) {
            return -1;
        }
        return column * WaypointColors.GRID + row;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int index = this.paletteIndexAt(mouseX, mouseY);
            if (index >= 0) {
                this.colorIndex = index;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void saveWaypoint() {
        String finalName = this.name == null ? "" : this.name.trim();
        if (finalName.isEmpty()) {
            finalName = this.defaultName;
        }
        int color = WaypointColors.at(this.colorIndex);
        if (this.editingId != null) {
            WaypointStore.get().update(new WaypointStore.Waypoint(this.editingId, finalName, this.blockX,
                    this.blockY, this.blockZ, this.dimension.toString(), color, this.category, this.sync,
                    this.showDistance));
            if (this.parent instanceof WorldMapScreen map) {
                map.markMapDirty();
                map.showStatus(Component.translatable("screen.m4cne.status.waypoint_updated", finalName));
                map.requestSidebarRebuild();
            }
            Minecraft.getInstance().setScreen(this.parent);
            return;
        }
        WaypointStore.get().add(finalName, this.blockX, this.blockY, this.blockZ, this.dimension,
                color, this.category);
        if (this.parent instanceof WorldMapScreen map) {
            map.markMapDirty();
            map.showStatus(Component.translatable("screen.m4cne.status.waypoint_added_named", finalName));
            map.requestSidebarRebuild();
        }
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
