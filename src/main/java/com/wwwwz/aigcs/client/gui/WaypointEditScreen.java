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
import net.minecraft.util.Mth;

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
    private static final int MAX_PANEL_WIDTH = 330;
    private static final int MAX_PANEL_HEIGHT = 286;
    private static final int GAP = 1;

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
    private int panelWidth = MAX_PANEL_WIDTH;
    private int panelHeight = MAX_PANEL_HEIGHT;
    /** Palette cell size; shrinks on small windows so nothing leaves the screen. */
    private int cell = 14;
    private int paletteSide = WaypointColors.GRID * (this.cell + GAP) - GAP;

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
        // Keep the whole editor inside the window even at the smallest auto GUI
        // scale: the panel shrinks and the colour palette uses smaller cells.
        this.panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(200, this.width - 12));
        this.panelHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(200, this.height - 12));
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;
        int paletteRoom = this.panelHeight - 152;
        this.cell = Mth.clamp(paletteRoom / WaypointColors.GRID - GAP, 5, 14);
        this.paletteSide = WaypointColors.GRID * (this.cell + GAP) - GAP;

        this.nameField = new EditBox(this.font, this.panelX + 14, this.panelY + 40,
                this.panelWidth - 28, 20, Component.translatable("screen.m4cne.waypoint_edit.name"));
        this.nameField.setMaxLength(48);
        this.nameField.setValue(this.name);
        this.nameField.setResponder(value -> this.name = value);
        this.addRenderableWidget(this.nameField);
        this.setFocused(this.nameField);

        Button categoryButton = Button.builder(this.categoryLabel(), button -> {
            this.category = this.category.next();
            button.setMessage(this.categoryLabel());
        }).bounds(this.panelX + 14, this.panelY + 76, this.panelWidth - 28, 20).build();
        this.addRenderableWidget(categoryButton);

        int buttonWidth = Math.max(60, (this.panelWidth - 36) / 2);
        int buttonY = this.panelY + this.panelHeight - 34;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.m4cne.waypoint_edit.save"),
                button -> this.saveWaypoint())
                .bounds(this.panelX + 14, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.m4cne.waypoint_edit.cancel"),
                button -> this.onClose())
                .bounds(this.panelX + this.panelWidth - 14 - buttonWidth, buttonY, buttonWidth, 20)
                .build());
    }

    private Component categoryLabel() {
        return Component.translatable("screen.m4cne.waypoint_edit.category_value",
                Component.translatable(this.category.translationKey()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(this.panelX - 2, this.panelY - 2, this.panelX + this.panelWidth + 2,
                this.panelY + this.panelHeight + 2, 0xFF000000);
        graphics.fill(this.panelX - 1, this.panelY - 1, this.panelX + this.panelWidth + 1,
                this.panelY + this.panelHeight + 1, 0xFF8B8B8B);
        graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth,
                this.panelY + this.panelHeight, 0xF0181818);
        graphics.fill(this.panelX, this.panelY, this.panelX + this.panelWidth, this.panelY + 1, 0xFFFFD070);

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
                int x = originX + column * (this.cell + GAP);
                int y = originY + row * (this.cell + GAP);
                graphics.fill(x, y, x + this.cell, y + this.cell, 0xFF000000 | WaypointColors.at(index));
                if (index == this.colorIndex) {
                    graphics.fill(x - 2, y - 2, x + this.cell + 2, y - 1, 0xFFFFFFFF);
                    graphics.fill(x - 2, y + this.cell + 1, x + this.cell + 2, y + this.cell + 2, 0xFFFFFFFF);
                    graphics.fill(x - 2, y - 1, x - 1, y + this.cell + 1, 0xFFFFFFFF);
                    graphics.fill(x + this.cell + 1, y - 1, x + this.cell + 2, y + this.cell + 1, 0xFFFFFFFF);
                }
            }
        }
    }

    private void renderPreviewAndInfo(GuiGraphics graphics) {
        int infoX = this.paletteX() + this.paletteSide + 14;
        int infoY = this.paletteY();
        int color = 0xFF000000 | WaypointColors.at(this.colorIndex);
        graphics.fill(infoX - 1, infoY - 1, infoX + 34, infoY + 34, 0xFF000000);
        graphics.fill(infoX, infoY, infoX + 33, infoY + 33, color);
        graphics.fill(infoX + 8, infoY + 8, infoX + 25, infoY + 9, 0xFFFFFFFF);
        graphics.fill(infoX + 8, infoY + 25, infoX + 25, infoY + 26, 0xFFFFFFFF);
        graphics.fill(infoX + 8, infoY + 8, infoX + 9, infoY + 26, 0xFFFFFFFF);
        graphics.fill(infoX + 24, infoY + 8, infoX + 25, infoY + 26, 0xFFFFFFFF);

        int infoWidth = Math.max(60, this.panelWidth - 28 - this.paletteSide - 40);
        Component position = Component.translatable("screen.m4cne.waypoint_edit.position",
                this.blockX, this.blockY, this.blockZ, this.dimension.toString());
        graphics.drawWordWrap(this.font, position, infoX, infoY + 44, infoWidth,
                0xFFFFFFFF);
        graphics.drawWordWrap(this.font, Component.translatable("screen.m4cne.waypoint_edit.hint"),
                infoX, infoY + 76, infoWidth, 0xFFA0A0A0);
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
        int column = localX / (this.cell + GAP);
        int row = localY / (this.cell + GAP);
        if (column >= WaypointColors.GRID || row >= WaypointColors.GRID) {
            return -1;
        }
        if (localX % (this.cell + GAP) >= this.cell || localY % (this.cell + GAP) >= this.cell) {
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
