package com.wwwwz.aigcs.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.wwwwz.aigcs.client.claim.ClaimStore;
import com.wwwwz.aigcs.client.claim.ClaimSyncManager;
import com.wwwwz.aigcs.client.claim.EntityRuleCatalog;
import com.wwwwz.aigcs.core.claim.EntityRule;
import com.wwwwz.aigcs.core.claim.EntityRuleSet;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Editor of one entity rule (要求补充-15 item 3).
 * <p>
 * The left half picks the target entity: any registered mob or the player
 * entity. The right half edits the flags of the rule (enabled, natural spawn,
 * unnatural spawn, isolation, aggression, optional population limit) and the
 * buffs the rule grants inside its chunk group. Spawn / isolation / aggression
 * controls are disabled for player rules, exactly as the requirement asks.
 * <p>
 * The screen writes the new rule set through {@link ClaimStore} and uploads it
 * with {@link ClaimSyncManager}; the server validates the types again and the
 * next authoritative list confirms the change.
 */
@SuppressWarnings("null")
public final class ClaimRuleEditScreen extends Screen {
    /** Selectable population limits of the "limit" button. */
    private static final int[] LIMIT_CHOICES = { 0, 1, 2, 3, 5, 10, 20, 50, 100, 1000 };
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_GAP = 2;

    private final Screen parent;
    private final String ownerId;
    private final String groupId;
    private final String originalType;
    private EntityRule rule;

    private String entityFilter = "";
    private String buffFilter = "";
    private int entityPage;
    private int buffPage;
    private EditBox entityFilterField;
    private EditBox buffFilterField;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int leftX;
    private int rightX;
    private int columnWidth;
    private int bodyTop;
    private int listTop;
    private int listBottom;
    private int entityRows;
    private int buffRows;
    private int buffTop;

    public ClaimRuleEditScreen(Screen parent, String ownerId, String groupId, EntityRule rule) {
        super(Component.translatable("screen.m4cne.claims.rule_editor.title"));
        this.parent = parent;
        this.ownerId = ownerId;
        this.groupId = groupId;
        this.rule = rule;
        this.originalType = rule.type();
    }

    @Override
    protected void init() {
        super.init();
        this.panelWidth = Math.min(this.width - 20, 460);
        this.panelHeight = Math.min(this.height - 20, 280);
        this.panelX = (this.width - this.panelWidth) / 2;
        this.panelY = (this.height - this.panelHeight) / 2;
        this.columnWidth = Math.max(90, (this.panelWidth - 28 - 8) / 2);
        this.leftX = this.panelX + 14;
        this.rightX = this.leftX + this.columnWidth + 8;
        this.bodyTop = this.panelY + 44;
        this.listTop = this.bodyTop + 24;
        this.listBottom = this.panelY + this.panelHeight - 44;
        this.entityRows = Math.max(3, (this.listBottom - this.listTop) / (ROW_HEIGHT + ROW_GAP));

        this.buildEntityPicker();
        this.buildRuleOptions();
        this.buildBuffPicker();
        this.buildFooter();
    }

    // -------------------------------------------------------------- left half

    private void buildEntityPicker() {
        this.entityFilterField = new EditBox(this.font, this.leftX, this.bodyTop,
                this.columnWidth - 64, ROW_HEIGHT,
                Component.translatable("screen.m4cne.claims.rule_editor.filter"));
        this.entityFilterField.setMaxLength(64);
        this.entityFilterField.setHint(Component.translatable("screen.m4cne.claims.rule_editor.filter_hint"));
        this.entityFilterField.setValue(this.entityFilter);
        this.entityFilterField.setResponder(value -> this.entityFilter = value);
        this.addRenderableWidget(this.entityFilterField);
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.m4cne.claims.rule_editor.apply"),
                button -> {
                    this.entityPage = 0;
                    this.rebuildWidgets();
                })
                .bounds(this.leftX + this.columnWidth - 60, this.bodyTop, 60, ROW_HEIGHT)
                .build());

        List<EntityRuleCatalog.Entry> entries = new ArrayList<>();
        entries.add(EntityRuleCatalog.player());
        for (EntityRuleCatalog.Entry entry : EntityRuleCatalog.filtered(this.entityFilter)) {
            entries.add(entry);
        }
        int pages = Math.max(1, (entries.size() + this.entityRows - 1) / this.entityRows);
        this.entityPage = Mth.clamp(this.entityPage, 0, pages - 1);
        int start = this.entityPage * this.entityRows;
        int end = Math.min(entries.size(), start + this.entityRows);
        int y = this.listTop;
        for (int index = start; index < end; index++) {
            EntityRuleCatalog.Entry entry = entries.get(index);
            boolean selected = entry.id().equals(this.rule.type());
            Component label = Component.empty()
                    .append(Component.literal(selected ? "\u25B6 " : "\u25CB ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(entry.name()));
            Button button = Button.builder(label, ignored -> {
                this.rule = this.rule.withType(entry.id());
                this.rebuildWidgets();
            }).bounds(this.leftX, y, this.columnWidth, ROW_HEIGHT).build();
            button.setTooltip(Tooltip.create(Component.literal(entry.id())));
            this.addRenderableWidget(button);
            y += ROW_HEIGHT + ROW_GAP;
        }
        int navY = this.listBottom + 2;
        this.addRenderableWidget(Button.builder(Component.translatable("screen.m4cne.claims.rule.prev"),
                button -> {
                    this.entityPage = Math.max(0, this.entityPage - 1);
                    this.rebuildWidgets();
                }).bounds(this.leftX, navY, 60, ROW_HEIGHT).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.m4cne.claims.rule.next"),
                button -> {
                    this.entityPage++;
                    this.rebuildWidgets();
                }).bounds(this.leftX + this.columnWidth - 60, navY, 60, ROW_HEIGHT).build());
    }

    // ------------------------------------------------------------- right half

    private void buildRuleOptions() {
        int halfWidth = (this.columnWidth - 4) / 2;
        int y = this.bodyTop;
        boolean mob = !this.rule.isPlayer();
        this.addRenderableWidget(Button.builder(
                toggleLabel("screen.m4cne.claims.rule_editor.enabled", this.rule.enabled()),
                button -> {
                    this.rule = this.rule.withEnabled(!this.rule.enabled());
                    this.rebuildWidgets();
                }).bounds(this.rightX, y, halfWidth, ROW_HEIGHT).build());
        Button limit = Button.builder(limitLabel(), button -> {
            this.rule = this.rule.withMaxCount(nextLimit());
            this.rebuildWidgets();
        }).bounds(this.rightX + halfWidth + 4, y, halfWidth, ROW_HEIGHT).build();
        limit.active = mob;
        this.addRenderableWidget(limit);
        y += ROW_HEIGHT + ROW_GAP;

        Button natural = Button.builder(
                toggleLabel("screen.m4cne.claims.rule_editor.natural", this.rule.allowNaturalSpawn()),
                button -> {
                    this.rule = this.rule.withAllowNaturalSpawn(!this.rule.allowNaturalSpawn());
                    this.rebuildWidgets();
                }).bounds(this.rightX, y, halfWidth, ROW_HEIGHT).build();
        natural.active = mob;
        this.addRenderableWidget(natural);
        Button unnatural = Button.builder(
                toggleLabel("screen.m4cne.claims.rule_editor.unnatural",
                        this.rule.allowUnnaturalSpawn()),
                button -> {
                    this.rule = this.rule.withAllowUnnaturalSpawn(!this.rule.allowUnnaturalSpawn());
                    this.rebuildWidgets();
                }).bounds(this.rightX + halfWidth + 4, y, halfWidth, ROW_HEIGHT).build();
        unnatural.active = mob;
        this.addRenderableWidget(unnatural);
        y += ROW_HEIGHT + ROW_GAP;

        Button isolated = Button.builder(
                toggleLabel("screen.m4cne.claims.rule_editor.isolated", this.rule.isolated()),
                button -> {
                    this.rule = this.rule.withIsolated(!this.rule.isolated());
                    this.rebuildWidgets();
                }).bounds(this.rightX, y, halfWidth, ROW_HEIGHT).build();
        isolated.active = mob;
        this.addRenderableWidget(isolated);
        Button aggro = Button.builder(
                toggleLabel("screen.m4cne.claims.rule_editor.aggro", this.rule.allowAggro()),
                button -> {
                    this.rule = this.rule.withAllowAggro(!this.rule.allowAggro());
                    this.rebuildWidgets();
                }).bounds(this.rightX + halfWidth + 4, y, halfWidth, ROW_HEIGHT).build();
        aggro.active = mob;
        this.addRenderableWidget(aggro);

        int buffTop = y + ROW_HEIGHT + 8;
        // The buff list starts below the filter row of the right column.
        int buffGridTop = buffTop + ROW_HEIGHT + 4;
        this.buffRows = Math.max(2, (this.listBottom - buffGridTop) / (ROW_HEIGHT + ROW_GAP));
        this.buffTop = buffTop;
    }

    private void buildBuffPicker() {
        this.buffFilterField = new EditBox(this.font, this.rightX, this.buffTop,
                this.columnWidth - 64, ROW_HEIGHT,
                Component.translatable("screen.m4cne.claims.rule_editor.buff_filter"));
        this.buffFilterField.setMaxLength(64);
        this.buffFilterField.setHint(Component.translatable("screen.m4cne.claims.rule_editor.filter_hint"));
        this.buffFilterField.setValue(this.buffFilter);
        this.buffFilterField.setResponder(value -> this.buffFilter = value);
        this.addRenderableWidget(this.buffFilterField);
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.m4cne.claims.rule_editor.apply"),
                button -> {
                    this.buffPage = 0;
                    this.rebuildWidgets();
                })
                .bounds(this.rightX + this.columnWidth - 60, this.buffTop, 60, ROW_HEIGHT)
                .build());

        List<EntityRuleCatalog.Buff> buffs = EntityRuleCatalog.filteredBuffs(this.buffFilter);
        int perPage = Math.max(2, this.buffRows * 2);
        int pages = Math.max(1, (buffs.size() + perPage - 1) / perPage);
        this.buffPage = Mth.clamp(this.buffPage, 0, pages - 1);
        int halfWidth = (this.columnWidth - 4) / 2;
        int start = this.buffPage * perPage;
        int end = Math.min(buffs.size(), start + perPage);
        int y = this.buffTop + ROW_HEIGHT + 4;
        for (int index = start; index < end; index++) {
            EntityRuleCatalog.Buff buff = buffs.get(index);
            boolean active = this.rule.hasBuff(buff.id());
            int column = (index - start) % 2;
            int row = (index - start) / 2;
            Component label = Component.literal((active ? "\u25CF " : "\u25CB ")
                    + (buff.name().length() > 14 ? buff.name().substring(0, 14) : buff.name()));
            Button button = Button.builder(label, ignored -> {
                this.rule = this.rule.toggleBuff(buff.id());
                this.rebuildWidgets();
            }).bounds(this.rightX + column * (halfWidth + 4), y + row * (ROW_HEIGHT + ROW_GAP),
                    halfWidth, ROW_HEIGHT).build();
            button.setTooltip(Tooltip.create(Component.literal(buff.id())));
            this.addRenderableWidget(button);
        }
        int navY = this.listBottom + 2;
        this.addRenderableWidget(Button.builder(Component.translatable("screen.m4cne.claims.rule.prev"),
                button -> {
                    this.buffPage = Math.max(0, this.buffPage - 1);
                    this.rebuildWidgets();
                }).bounds(this.rightX, navY, 60, ROW_HEIGHT).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.m4cne.claims.rule.next"),
                button -> {
                    this.buffPage++;
                    this.rebuildWidgets();
                }).bounds(this.rightX + this.columnWidth - 60, navY, 60, ROW_HEIGHT).build());
    }

    private void buildFooter() {
        int y = this.panelY + this.panelHeight - 26;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.m4cne.claims.rule_editor.save"),
                button -> this.save())
                .bounds(this.panelX + this.panelWidth - 190, y, 84, 20).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.m4cne.claims.rule_editor.cancel"),
                button -> this.onClose())
                .bounds(this.panelX + this.panelWidth - 100, y, 84, 20).build());
    }

    // ------------------------------------------------------------------ save

    private void save() {
        ClaimStore store = ClaimStore.get();
        ClaimStore.Owner owner = store.owner(this.ownerId);
        EntityRuleSet base = owner == null ? EntityRuleSet.EMPTY : owner.rulesFor(this.groupId);
        EntityRuleSet next = base.withoutRule(this.originalType);
        if (this.rule.valid()) {
            next = next.withRule(this.rule);
        }
        store.setGroupRules(this.ownerId, this.groupId, next);
        boolean sent = ClaimSyncManager.get().setGroupRules(this.ownerId, this.groupId, next);
        if (this.parent instanceof WorldMapScreen map) {
            map.showStatus(Component.translatable(sent ? "screen.m4cne.status.claims.rule_saved"
                    : "screen.m4cne.status.claims.unavailable", entityName(this.rule.type())));
            map.requestSidebarRebuild();
        }
        Minecraft.getInstance().setScreen(this.parent);
    }

    // --------------------------------------------------------------- helpers

    private static Component entityName(String type) {
        if (EntityRule.PLAYER_TYPE.equals(type)) {
            return Component.translatable("screen.m4cne.claims.rule_editor.player");
        }
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation
                .tryParse(type);
        net.minecraft.world.entity.EntityType<?> entityType = id == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(id);
        return entityType == null ? Component.literal(type) : entityType.getDescription();
    }

    private static Component toggleLabel(String key, boolean value) {
        return Component.translatable(key, Component.translatable(value
                ? "screen.m4cne.state.enabled"
                : "screen.m4cne.state.disabled"));
    }

    private Component limitLabel() {
        return Component.translatable("screen.m4cne.claims.rule_editor.limit",
                this.rule.maxCount() <= 0
                        ? Component.translatable("screen.m4cne.claims.entity_unlimited")
                        : Component.literal(Integer.toString(this.rule.maxCount())));
    }

    private int nextLimit() {
        int current = this.rule.maxCount();
        for (int index = 0; index < LIMIT_CHOICES.length; index++) {
            if (LIMIT_CHOICES[index] > current) {
                return LIMIT_CHOICES[index];
            }
        }
        return LIMIT_CHOICES[0];
    }

    // --------------------------------------------------------------- drawing

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
        graphics.drawString(this.font, this.title, this.panelX + 14, this.panelY + 8, 0xFFFFFFFF, true);
        ClaimStore.Owner owner = ClaimStore.get().owner(this.ownerId);
        graphics.drawString(this.font,
                Component.translatable("screen.m4cne.claims.rule_editor.target", this.groupId,
                        owner == null ? this.ownerId : owner.name()),
                this.panelX + 14, this.panelY + 20, 0xFFA0A0A0, false);
        graphics.drawString(this.font, Component.translatable("screen.m4cne.section.claims_rule_entities"),
                this.leftX, this.bodyTop - 11, 0xFFFFD070, false);
        graphics.drawString(this.font, Component.translatable("screen.m4cne.section.claims_rule_flags"),
                this.rightX, this.bodyTop - 11, 0xFFFFD070, false);
        graphics.drawString(this.font, Component.translatable("screen.m4cne.section.claims_rule_buffs"),
                this.rightX, this.buffTop - 11, 0xFFFFD070, false);
        if (this.rule.isPlayer()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.m4cne.claims.rule_editor.player_hint"),
                    this.panelX + 14, this.panelY + this.panelHeight - 12, 0xFF808080, false);
        }
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
