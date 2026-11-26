package com.wwwwz.aigcs.core;

/**
 * Shared, serialisable representation of a waypoint.
 * <p>
 * The client keeps the display only flags ({@code sync}, {@code showDistance})
 * locally; everything in this record is synchronised with the server so the
 * same waypoint list is available to every player.
 */
public record WaypointData(String id, String name, int x, int y, int z, String dimension, int color,
        String category) {

    public WaypointCategory categoryValue() {
        return WaypointCategory.byName(this.category);
    }
}
