package com.wwwwz.aigcs.server;

/**
 * Token bucket used to keep a single client from flooding the server with chunk
 * updates. The bucket allows a small burst and then settles at the configured
 * rate.
 */
final class SyncRateLimiter {
    private final double permitsPerSecond;
    private final double capacity;
    private double tokens;
    private long lastRefillNanos;

    SyncRateLimiter(int permitsPerSecond) {
        this.permitsPerSecond = Math.max(1.0D, permitsPerSecond);
        this.capacity = this.permitsPerSecond * 2.0D;
        this.tokens = this.capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    synchronized boolean tryConsume(int permits) {
        long now = System.nanoTime();
        double elapsedSeconds = (now - this.lastRefillNanos) / 1_000_000_000.0D;
        if (elapsedSeconds > 0.0D) {
            this.tokens = Math.min(this.capacity, this.tokens + elapsedSeconds * this.permitsPerSecond);
            this.lastRefillNanos = now;
        }
        if (this.tokens < permits) {
            return false;
        }
        this.tokens -= permits;
        return true;
    }
}
