package ru.xrshop.server.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StoreSessionServiceTest {
    @Test void newSessionInvalidatesOldSessionId() {
        StoreSessionService service = new StoreSessionService(120); UUID player = UUID.randomUUID();
        var old = service.open(player, 1); var current = service.open(player, 1);
        assertFalse(service.close(player, old.sessionId())); assertEquals(current.sessionId(), service.active(player).orElseThrow().sessionId());
        assertTrue(service.close(player, current.sessionId())); assertTrue(service.active(player).isEmpty());
    }
}
