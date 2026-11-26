package com.wwwwz.aigcs.core;

import java.util.Arrays;
import java.util.Base64;

/**
 * Fixed size bit set over the squared bounding box of a preload region.
 * <p>
 * A preload region of radius 400 covers 801 x 801 chunks, so a boolean array
 * would waste 640 KiB per task and a set of chunk keys even more. One bit per
 * chunk keeps the persisted progress around 80 KiB and can be serialised as one
 * Base64 string.
 */
public final class ChunkBitset {
    private final int width;
    private final int height;
    private final long[] words;
    private int count;

    public ChunkBitset(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid bitset size " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        int bits = width * height;
        this.words = new long[(bits + 63) >>> 6];
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    /** Number of cells the bit set can hold. */
    public int size() {
        return this.width * this.height;
    }

    /** Number of set cells. */
    public int count() {
        return this.count;
    }

    public int remaining() {
        return this.size() - this.count;
    }

    public boolean get(int x, int z) {
        if (x < 0 || z < 0 || x >= this.width || z >= this.height) {
            return false;
        }
        int index = z * this.width + x;
        return (this.words[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    /** @return {@code true} when the bit was not set before */
    public boolean set(int x, int z) {
        if (x < 0 || z < 0 || x >= this.width || z >= this.height) {
            return false;
        }
        int index = z * this.width + x;
        long mask = 1L << (index & 63);
        int word = index >>> 6;
        if ((this.words[word] & mask) != 0L) {
            return false;
        }
        this.words[word] |= mask;
        this.count++;
        return true;
    }

    public void clear() {
        Arrays.fill(this.words, 0L);
        this.count = 0;
    }

    /** Encodes the bit set as Base64, most significant byte first. */
    public String encode() {
        byte[] bytes = new byte[this.words.length * 8];
        for (int word = 0; word < this.words.length; word++) {
            long value = this.words[word];
            for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
                bytes[word * 8 + byteIndex] = (byte) (value >>> (56 - byteIndex * 8));
            }
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Restores a bit set. Broken or truncated data simply yields the bits that
     * could be read, so a corrupt progress file never blocks a new task.
     */
    public static ChunkBitset decode(String encoded, int width, int height) {
        ChunkBitset bits = new ChunkBitset(width, height);
        if (encoded == null || encoded.isEmpty()) {
            return bits;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            return bits;
        }
        int limit = Math.min(bits.words.length * 8, bytes.length);
        for (int index = 0; index < limit; index++) {
            long value = bytes[index] & 0xFFL;
            bits.words[index >>> 3] |= value << (56 - (index & 7) * 8);
        }
        int total = 0;
        for (long word : bits.words) {
            total += Long.bitCount(word);
        }
        bits.count = total;
        return bits;
    }
}
