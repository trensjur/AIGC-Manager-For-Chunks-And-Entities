package com.wwwwz.aigcs.client.preload;

/**
 * Runtime limits of the preload performance settings.
 * <p>
 * Both values are queried every time the settings page is built, so a game that
 * grows its heap at runtime (dynamic allocation) automatically gets a matching
 * memory slider. The memory maximum keeps a ten percent safety buffer so the
 * recorded chunks can never push the JVM into an out of memory situation.
 */
public final class PreloadLimits {
    private static final long MEGABYTE = 1_048_576L;

    private PreloadLimits() {
    }

    /** Hardware thread count, the hard upper bound of the thread slider. */
    public static int maxThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** Half of the hardware threads, the default of the thread slider. */
    public static int defaultThreads() {
        return Math.max(1, maxThreads() / 2);
    }

    /** Maximum memory budget in MiB, 90 % of the current JVM heap. */
    public static int maxMemoryMb() {
        long heap = Runtime.getRuntime().maxMemory();
        long usable = heap / MEGABYTE * 9L / 10L;
        return (int) Math.max(256L, Math.min(65536L, usable));
    }

    public static int defaultMemoryMb() {
        return Math.min(maxMemoryMb(), 2048);
    }

    public static int clampMemory(int megabytes) {
        return Math.max(256, Math.min(maxMemoryMb(), megabytes));
    }

    public static int clampThreads(int threads) {
        return Math.max(1, Math.min(maxThreads(), threads));
    }

    /** Human readable heap size, used by the settings page. */
    public static int heapMb() {
        return (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / MEGABYTE);
    }
}
