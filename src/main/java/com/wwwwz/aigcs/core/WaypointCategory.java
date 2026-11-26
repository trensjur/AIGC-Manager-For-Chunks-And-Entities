package com.wwwwz.aigcs.core;

/**
 * User selectable categories of a waypoint.
 * <p>
 * The enum name is used on the wire so two clients of different languages still
 * agree on the category; the translation key only drives the display name.
 */
public enum WaypointCategory {
    VILLAGE("village"),
    RUINS("ruins"),
    TREASURE("treasure"),
    DUNGEON("dungeon"),
    OUTPOST("outpost"),
    BASE("base"),
    ORE("ore"),
    PORTAL("portal"),
    TEMPLE("temple"),
    TRIAL_CHAMBER("trial_chamber");

    private final String key;

    WaypointCategory(String key) {
        this.key = key;
    }

    public String key() {
        return this.key;
    }

    public String translationKey() {
        return "screen.m4cne.waypoint.category." + this.key;
    }

    /** Cycles to the next category, used by the cycle button of the editor. */
    public WaypointCategory next() {
        WaypointCategory[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    /** Resolves a category by enum name (preferred) or by its short key. */
    public static WaypointCategory byName(String name) {
        if (name != null) {
            for (WaypointCategory category : values()) {
                if (category.name().equalsIgnoreCase(name) || category.key.equalsIgnoreCase(name)) {
                    return category;
                }
            }
        }
        return VILLAGE;
    }
}
