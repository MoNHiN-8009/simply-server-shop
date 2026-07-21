package ru.xrshop.server.purchase;

import ru.xrshop.server.session.StoreSessionService;

import java.util.Optional;

public final class RevisionPolicy {
    private RevisionPolicy() {}
    public static boolean permits(Optional<StoreSessionService.StoreSession> session, long currentRevision) {
        return session.isEmpty() || session.get().revision() == currentRevision;
    }
}
