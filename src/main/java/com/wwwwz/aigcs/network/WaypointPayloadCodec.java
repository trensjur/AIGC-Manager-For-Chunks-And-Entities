package com.wwwwz.aigcs.network;

import com.wwwwz.aigcs.core.WaypointData;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** Shared byte layout of one waypoint inside the waypoint payloads. */
@SuppressWarnings("null")
public final class WaypointPayloadCodec {
    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_NAME_LENGTH = 48;
    public static final int MAX_DIMENSION_LENGTH = 128;
    public static final int MAX_CATEGORY_LENGTH = 32;
    /** Upper bound of waypoints inside one C2S batch. */
    public static final int MAX_WAYPOINTS_PER_PAYLOAD = 64;
    /** Upper bound of a full S2C waypoint list. */
    public static final int MAX_WAYPOINTS_PER_LIST = 8192;

    private WaypointPayloadCodec() {
    }

    public static void write(RegistryFriendlyByteBuf buffer, WaypointData data) {
        buffer.writeUtf(data.id() == null ? "" : data.id(), MAX_ID_LENGTH);
        buffer.writeUtf(data.name() == null ? "" : data.name(), MAX_NAME_LENGTH);
        buffer.writeInt(data.x());
        buffer.writeInt(data.y());
        buffer.writeInt(data.z());
        buffer.writeUtf(data.dimension() == null ? "" : data.dimension(), MAX_DIMENSION_LENGTH);
        buffer.writeInt(data.color());
        buffer.writeUtf(data.category() == null ? "" : data.category(), MAX_CATEGORY_LENGTH);
    }

    public static WaypointData read(RegistryFriendlyByteBuf buffer) {
        return new WaypointData(
                buffer.readUtf(MAX_ID_LENGTH),
                buffer.readUtf(MAX_NAME_LENGTH),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readUtf(MAX_DIMENSION_LENGTH),
                buffer.readInt(),
                buffer.readUtf(MAX_CATEGORY_LENGTH));
    }
}
