package ru.xrshop.server.security;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RequestRateLimiter {
    private final Map<UUID, ArrayDeque<Long>> requests = new ConcurrentHashMap<>();
    private final long windowMillis;
    private final int limit;
    private final Clock clock;

    public RequestRateLimiter(int windowSeconds, int limit) {
        this(windowSeconds, limit, Clock.systemUTC());
    }

    RequestRateLimiter(int windowSeconds, int limit, Clock clock) {
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
        this.limit = Math.max(1, limit);
        this.clock = clock;
    }

    public boolean allow(UUID player) {
        long now = clock.millis();
        ArrayDeque<Long> queue = requests.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() >= windowMillis) queue.removeFirst();
            if (queue.size() >= limit) return false;
            queue.addLast(now);
            return true;
        }
    }

    public void remove(UUID player) { requests.remove(player); }
    public void prune() {
        long now = clock.millis();
        requests.entrySet().removeIf(entry -> {
            ArrayDeque<Long> queue = entry.getValue();
            synchronized (queue) {
                while (!queue.isEmpty() && now - queue.peekFirst() >= windowMillis) queue.removeFirst();
                return queue.isEmpty();
            }
        });
    }
}
