package com.wwwwz.aigcs.client.gui;

/**
 * First level entries of the world map side bar.
 * <p>
 * The enum order is the display order of the buttons. Every page is rendered in
 * the same right hand column; nested pages are reached by clicking a button and
 * leave through the back button or ESC.
 */
public enum MapSettingsPage {
    MAIN("screen.m4cne.page.main", "screen.m4cne.title"),
    WORLD_MAP("screen.m4cne.page.world_map", "screen.m4cne.page.world_map"),
    RADAR("screen.m4cne.page.radar", "screen.m4cne.page.radar"),
    WAYPOINTS("screen.m4cne.page.waypoints", "screen.m4cne.page.waypoints"),
    WAYPOINT_MANAGE("screen.m4cne.page.waypoint_manage", "screen.m4cne.page.waypoint_manage"),
    WAYPOINT_TRASH("screen.m4cne.page.waypoint_trash", "screen.m4cne.page.waypoint_trash"),
    STRUCTURE_SCAN("screen.m4cne.page.structure_scan", "screen.m4cne.page.structure_scan"),
    CLAIMS("screen.m4cne.page.claims", "screen.m4cne.page.claims"),
    CLAIM_CHUNKS("screen.m4cne.page.claim_chunks", "screen.m4cne.page.claims"),
    CLAIM_ENTITY_RULES("screen.m4cne.page.claim_entity_rules", "screen.m4cne.page.claims"),
    PRELOAD("screen.m4cne.page.preload", "screen.m4cne.page.preload"),
    OVERLAYS("screen.m4cne.page.overlays", "screen.m4cne.page.overlays"),
    MINIMAP_HUD("screen.m4cne.page.minimap_hud", "screen.m4cne.page.minimap_hud"),
    MINIMAP_LAYOUT("screen.m4cne.page.minimap_layout", "screen.m4cne.page.minimap_layout"),
    COMPASS_LAYOUT("screen.m4cne.page.compass_layout", "screen.m4cne.page.compass_layout"),
    PRESETS("screen.m4cne.page.presets", "screen.m4cne.page.presets");

    private final String titleKey;
    private final String breadcrumbKey;

    MapSettingsPage(String titleKey, String breadcrumbKey) {
        this.titleKey = titleKey;
        this.breadcrumbKey = breadcrumbKey;
    }

    public String titleKey() {
        return this.titleKey;
    }

    public String breadcrumbKey() {
        return this.breadcrumbKey;
    }

    public boolean isMain() {
        return this == MAIN;
    }

    /**
     * Page the back button returns to. Third level pages open from their
     * second level parent instead of the main menu.
     */
    public MapSettingsPage parent() {
        if (this == MINIMAP_LAYOUT || this == COMPASS_LAYOUT) {
            return MINIMAP_HUD;
        }
        if (this == WAYPOINT_MANAGE || this == WAYPOINT_TRASH || this == STRUCTURE_SCAN) {
            return WAYPOINTS;
        }
        if (this == CLAIM_CHUNKS) {
            return CLAIMS;
        }
        if (this == CLAIM_ENTITY_RULES) {
            return CLAIMS;
        }
        return MAIN;
    }
}
