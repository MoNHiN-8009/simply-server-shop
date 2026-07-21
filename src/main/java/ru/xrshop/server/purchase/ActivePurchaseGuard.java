package ru.xrshop.server.purchase;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ActivePurchaseGuard {
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    public boolean acquire(UUID player) { return active.add(player); }
    public void release(UUID player) { active.remove(player); }
    public boolean isActive(UUID player) { return active.contains(player); }
}
