package ru.xrshop.server.security;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModPermissions {
    private static final String NS = "minecraftshop";
    public static final PermissionNode<Boolean> OPEN = user("open");
    public static final PermissionNode<Boolean> BUY = user("buy");
    public static final PermissionNode<Boolean> BALANCE = user("balance");
    public static final PermissionNode<Boolean> ADMIN = admin("admin");
    public static final PermissionNode<Boolean> EDITOR = admin("admin.editor");
    public static final PermissionNode<Boolean> RELOAD = admin("admin.reload");
    public static final PermissionNode<Boolean> VALIDATE = admin("admin.validate");
    public static final PermissionNode<Boolean> BACKUP = admin("admin.backup");
    public static final PermissionNode<Boolean> CURRENCY = admin("admin.currency");
    public static final PermissionNode<Boolean> HISTORY = admin("admin.history");
    public static final PermissionNode<Boolean> REVIEW = admin("admin.review");
    private static final List<PermissionNode<Boolean>> ADMIN_NODES = List.of(
            ADMIN, EDITOR, RELOAD, VALIDATE, BACKUP, CURRENCY, HISTORY, REVIEW);
    private static final Map<String, PermissionNode<Boolean>> DYNAMIC = new ConcurrentHashMap<>();

    private ModPermissions() {}

    public static void register(PermissionGatherEvent.Nodes event) {
        event.addNodes(OPEN, BUY, BALANCE, ADMIN, EDITOR, RELOAD, VALIDATE, BACKUP,
                CURRENCY, HISTORY, REVIEW);
    }

    public static boolean has(ServerPlayer player, PermissionNode<Boolean> node) {
        return PermissionAPI.getPermission(player, node);
    }

    public static boolean hasAnyAdmin(ServerPlayer player) {
        return ADMIN_NODES.stream().anyMatch(node -> has(player, node));
    }

    public static boolean hasAdmin(ServerPlayer player, PermissionNode<Boolean> operation) {
        return adminAccess(has(player, ADMIN), has(player, operation));
    }

    static boolean adminAccess(boolean master, boolean operation) { return master || operation; }

    public static boolean hasNamed(ServerPlayer player, String permission) {
        if (permission == null || permission.isBlank()) return true;
        PermissionNode<Boolean> node = DYNAMIC.computeIfAbsent(permission, ModPermissions::dynamic);
        return PermissionAPI.getPermission(player, node);
    }

    private static PermissionNode<Boolean> user(String name) {
        return new PermissionNode<>(NS, name, PermissionTypes.BOOLEAN, (player, uuid, context) -> true);
    }

    private static PermissionNode<Boolean> admin(String name) {
        return new PermissionNode<>(NS, name, PermissionTypes.BOOLEAN,
                (player, uuid, context) -> player != null && player.hasPermissions(2));
    }

    private static PermissionNode<Boolean> dynamic(String permission) {
        int dot = permission.indexOf('.');
        String namespace = dot > 0 ? permission.substring(0, dot) : NS;
        String path = dot > 0 ? permission.substring(dot + 1) : permission;
        return new PermissionNode<>(namespace, path, PermissionTypes.BOOLEAN,
                (player, uuid, context) -> player != null && player.hasPermissions(2));
    }
}
