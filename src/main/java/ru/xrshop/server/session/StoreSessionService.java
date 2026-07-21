package ru.xrshop.server.session;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StoreSessionService {
    private final Map<UUID, StoreSession> sessions = new ConcurrentHashMap<>();
    private final long timeoutMillis;
    private final Clock clock;

    public StoreSessionService(int timeoutSeconds) { this(timeoutSeconds, Clock.systemUTC()); }
    StoreSessionService(int timeoutSeconds, Clock clock) {
        this.timeoutMillis = Math.max(10, timeoutSeconds) * 1000L;
        this.clock = clock;
    }

    public StoreSession open(UUID player, long revision) {
        StoreSession session = new StoreSession(UUID.randomUUID(), player, revision, clock.millis() + timeoutMillis);
        sessions.put(player, session);
        return session;
    }

    public Optional<StoreSession> active(UUID player) {
        StoreSession session = sessions.get(player);
        if (session == null) return Optional.empty();
        if (session.expiresAtMillis < clock.millis()) { sessions.remove(player, session); return Optional.empty(); }
        return Optional.of(session);
    }

    public boolean close(UUID player, UUID sessionId) {
        StoreSession session = sessions.get(player);
        return session != null && session.sessionId.equals(sessionId) && sessions.remove(player, session);
    }
    public void remove(UUID player) { sessions.remove(player); }
    public void prune() { long now = clock.millis(); sessions.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now); }
    public Map<UUID, StoreSession> snapshot() { return Map.copyOf(sessions); }

    public record StoreSession(UUID sessionId, UUID playerId, long revision, long expiresAtMillis) {}
}
