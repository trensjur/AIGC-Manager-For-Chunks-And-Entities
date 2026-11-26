package com.wwwwz.aigcs.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

/**
 * A 16x16 surface sample of a single chunk.
 * <p>
 * The sample keeps the different map channels apart instead of pre-mixing them
 * into one colour: the map renderer can later toggle relief shading and the
 * day/night cycle without re-sampling a single block. All arrays use an X-major
 * layout ({@code x * 16 + z}) so a chunk can be copied into a bigger region
 * tile with straight {@link System#arraycopy}.
 */
@SuppressWarnings("null")
public final class ChunkTile {
    public static final int SIZE = 16;
    public static final int AREA = SIZE * SIZE;
    /** Shade value of a perfectly flat column, see {@link #shade}. */
    public static final byte NEUTRAL_SHADE = 0;

    /** ABGR colour of every column, {@code 0} when nothing was recorded. */
    public final int[] colors = new int[AREA];
    /** Surface height in blocks, {@link Short#MIN_VALUE} when unknown. */
    public final short[] heights = new short[AREA];
    /** Relief factor, {@link #NEUTRAL_SHADE} is flat, negative is a west slope. */
    public final byte[] shade = new byte[AREA];
    /** Sky light 0..15 of the block above the sampled surface. */
    public final byte[] skyLight = new byte[AREA];
    /** Block light 0..15 of the block above the sampled surface. */
    public final byte[] blockLight = new byte[AREA];

    public ChunkTile() {
        this.clear();
    }

    public static int index(int localX, int localZ) {
        return localX * SIZE + localZ;
    }

    public void clear() {
        Arrays.fill(this.colors, 0);
        Arrays.fill(this.heights, Short.MIN_VALUE);
        Arrays.fill(this.shade, NEUTRAL_SHADE);
        Arrays.fill(this.skyLight, (byte) 0);
        Arrays.fill(this.blockLight, (byte) 0);
    }

    public void copyFrom(ChunkTile other) {
        System.arraycopy(other.colors, 0, this.colors, 0, AREA);
        System.arraycopy(other.heights, 0, this.heights, 0, AREA);
        System.arraycopy(other.shade, 0, this.shade, 0, AREA);
        System.arraycopy(other.skyLight, 0, this.skyLight, 0, AREA);
        System.arraycopy(other.blockLight, 0, this.blockLight, 0, AREA);
    }

    public ChunkTile copy() {
        ChunkTile copy = new ChunkTile();
        copy.copyFrom(this);
        return copy;
    }

    /** {@code true} when no column of this tile was recorded. */
    public boolean isEmpty() {
        for (int color : this.colors) {
            if (color != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Stable hash of every channel, used by the client to detect changed chunks
     * without keeping a second copy of the tile around.
     */
    public long fingerprint() {
        long hash = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < AREA; i++) {
            hash = (hash ^ this.colors[i]) * 0x100000001B3L;
            hash = (hash ^ this.heights[i]) * 0x100000001B3L;
            hash = (hash ^ this.shade[i]) * 0x100000001B3L;
            hash = (hash ^ this.skyLight[i]) * 0x100000001B3L;
            hash = (hash ^ this.blockLight[i]) * 0x100000001B3L;
        }
        return hash;
    }

    // ------------------------------------------------------------------ codecs

    public void write(FriendlyByteBuf buffer) {
        for (int color : this.colors) {
            buffer.writeInt(color);
        }
        for (short height : this.heights) {
            buffer.writeShort(height);
        }
        buffer.writeByteArray(this.shade);
        buffer.writeByteArray(this.skyLight);
        buffer.writeByteArray(this.blockLight);
    }

    public static ChunkTile read(FriendlyByteBuf buffer) {
        ChunkTile tile = new ChunkTile();
        for (int i = 0; i < AREA; i++) {
            tile.colors[i] = buffer.readInt();
        }
        for (int i = 0; i < AREA; i++) {
            tile.heights[i] = buffer.readShort();
        }
        tile.readChannel(buffer.readByteArray(AREA), tile.shade, "shade");
        tile.readChannel(buffer.readByteArray(AREA), tile.skyLight, "skyLight");
        tile.readChannel(buffer.readByteArray(AREA), tile.blockLight, "blockLight");
        return tile;
    }

    private void readChannel(byte[] source, byte[] target, String name) {
        if (source.length != AREA) {
            throw new DecoderException("Invalid " + name + " length " + source.length + ", expected " + AREA);
        }
        System.arraycopy(source, 0, target, 0, AREA);
    }

    public void writeTo(DataOutputStream out) throws IOException {
        for (int color : this.colors) {
            out.writeInt(color);
        }
        for (short height : this.heights) {
            out.writeShort(height);
        }
        out.write(this.shade);
        out.write(this.skyLight);
        out.write(this.blockLight);
    }

    public static ChunkTile readFrom(DataInputStream in) throws IOException {
        ChunkTile tile = new ChunkTile();
        for (int i = 0; i < AREA; i++) {
            tile.colors[i] = in.readInt();
        }
        for (int i = 0; i < AREA; i++) {
            tile.heights[i] = in.readShort();
        }
        in.readFully(tile.shade);
        in.readFully(tile.skyLight);
        in.readFully(tile.blockLight);
        return tile;
    }
}
