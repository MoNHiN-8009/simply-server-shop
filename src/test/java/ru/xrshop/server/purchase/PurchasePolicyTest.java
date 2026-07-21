package ru.xrshop.server.purchase;

import org.junit.jupiter.api.Test;
import ru.xrshop.server.session.StoreSessionService;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PurchasePolicyTest {
    @Test void repeatedCommandIsBlockedUntilRelease() {
        ActivePurchaseGuard guard = new ActivePurchaseGuard(); UUID player = UUID.randomUUID();
        assertTrue(guard.acquire(player)); assertFalse(guard.acquire(player)); guard.release(player); assertTrue(guard.acquire(player));
    }

    @Test void storeUpdatedRejectsStaleSessionButManualCommandUsesCurrentSnapshot() {
        StoreSessionService service = new StoreSessionService(120); UUID player = UUID.randomUUID();
        var session = service.open(player, 3);
        assertFalse(RevisionPolicy.permits(Optional.of(session), 4));
        assertTrue(RevisionPolicy.permits(Optional.empty(), 4));
    }
}
