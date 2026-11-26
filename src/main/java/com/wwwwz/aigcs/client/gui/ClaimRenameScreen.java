package com.wwwwz.aigcs.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wwwwz.aigcs.client.claim.ClaimStore;
import com.wwwwz.aigcs.core.claim.ClaimChunk;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;

/**
 * Small modal editor that renames one or several claimed chunks.
 * <p>
 * It opens from the "rename selected chunks" button of the chunk list page.
 * Every selected chunk gets the same new id; the merge feature is the only
 * feature that needs a shared id, so a multi rename behaves like a merge with a
 * custom id.
 */
@SuppressWarnings("null")
public final class ClaimRenameScreen extends Screen {
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 132;

    private final Screen parent;
    private final List<ClaimChunk> chunks;
    private String value;
    private EditBox nameField;

    private int panelX;
    private int panelY;

    public ClaimRenameScreen(Screen parent, List<ClaimChunk> chunks) {
        super(Component.translatable("screen.m4cne.claim_rename.title"));
        this.parent = parent;
        this.chunks = new ArrayList<>(chunks);
        this.value = this.chunks.isEmpty() ? "" : this.chunks.get(0).displayId();
    }

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - PANEL_HEIGHT) / 2;
        this.nameField = new EditBox(this.font, this.panelX + 14, this.panelY + 38,
                PANEL_WIDTH - 28, 20, Component.translatable("screen.m4cne.claim_rename.id"));
        this.nameField.setMaxLength(ClaimChunk.MAX_ID_LENGTH);
        this.nameField.setValue(this.value);
        this.nameField.setResponder(text -> this.value = text);
        this.addRenderableWidget(this.nameField);
        this.setFocused(this.nameField);
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.m4cne.claim_rename.save"),
                button -> this.save())
                .bounds(this.panelX + 14, this.panelY + PANEL_HEIGHT - 32, 132, 20).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.m4cne.claim_rename.cancel"),
                button -> this.onClose())
                .bounds(this.panelX + PANEL_WIDTH - 146, this.panelY + PANEL_HEIGHT - 32, 132, 20).build());
    }

    private void save() {
        if (this.chunks.isEmpty()) {
            this.onClose();
            return;
        }
        ClaimChunk first = this.chunks.get(0);
        String id = ClaimChunk.sanitizeId(this.value, first.x(), first.z());
        Map<String, List<Long>> grouped = new LinkedHashMap<>();
        for (ClaimChunk chunk : this.chunks) {
            grouped.computeIfAbsent(chunk.dimension(), ignored -> new ArrayList<>())
                    .add(ChunkPos.asLong(chunk.x(), chunk.z()));
        }
        int renamed = 0;
        for (Map.Entry<String, List<Long>> group : grouped.entrySet()) {
            renamed += ClaimStore.get().renameChunks(group.getKey(), group.getValue(), id);
        }
        if (this.parent instanceof WorldMapScreen map) {
            map.showStatus(Component.translatable("screen.m4cne.status.claims.renamed", id, renamed));
            map.requestSidebarRebuild();
        }
        Minecraft.getInstance().setScreen(this.parent);
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
        graphics.drawString(this.font, Component.translatable("screen.m4cne.claim_rename.id"),
                this.panelX + 14, this.panelY + 26, 0xFFB0B0B0, false);
        graphics.drawString(this.font,
                Component.translatable("screen.m4cne.claim_rename.hint", this.chunks.size()),
                this.panelX + 14, this.panelY + 66, 0xFFA0A0A0, false);
        super.render(graphics, mouseX, mouseY, partialTick);
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
