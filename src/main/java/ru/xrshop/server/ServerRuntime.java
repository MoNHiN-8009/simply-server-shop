package ru.xrshop.server;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ru.xrshop.common.dto.PurchaseResultCode;
import ru.xrshop.common.network.NetworkHandler;
import ru.xrshop.common.network.ServerEventS2C;
import ru.xrshop.server.config.ConfigManager;
import ru.xrshop.server.config.ShopSnapshot;
import ru.xrshop.server.currency.CurrencyService;
import ru.xrshop.server.db.DatabaseExecutor;
import ru.xrshop.server.db.SqliteDatabase;
import ru.xrshop.server.editor.EditorService;
import ru.xrshop.server.purchase.PurchaseService;
import ru.xrshop.server.security.ModPermissions;
import ru.xrshop.server.session.StoreSessionService;
import ru.xrshop.server.validation.ForgeRuntimeChecks;
import ru.xrshop.server.view.StoreViewFactory;

import java.nio.file.Path;
import java.util.UUID;

public final class ServerRuntime implements AutoCloseable {
    private final MinecraftServer server;
    private final ConfigManager config;
    private final SqliteDatabase database;
    private final DatabaseExecutor databaseExecutor;
    private final CurrencyService currency;
    private final StoreSessionService sessions;
    private final StoreViewFactory views = new StoreViewFactory();
    private final PurchaseService purchases;
    private final EditorService editor;
    private final Logger logger;

    private ServerRuntime(MinecraftServer server, ConfigManager config, SqliteDatabase database,
                          DatabaseExecutor executor, CurrencyService currency, StoreSessionService sessions,
                          Logger logger) {
        this.server = server; this.config = config; this.database = database; this.databaseExecutor = executor;
        this.currency = currency; this.sessions = sessions; this.logger = logger;
        this.purchases = new PurchaseService(server, config, database, executor, sessions, logger, this::openStore);
        this.editor = new EditorService(server, config, database, executor, logger, this::notifyStoreChanged);
    }

    public static ServerRuntime start(MinecraftServer server, Path configPath, Path databasePath, Logger logger) throws Exception {
        Class.forName("org.sqlite.JDBC");
        ConfigManager config = new ConfigManager(configPath, logger, new ForgeRuntimeChecks(server));
        config.initialize();
        DatabaseExecutor executor = new DatabaseExecutor(config.serverConfig().sqlite_queue_size);
        SqliteDatabase database = new SqliteDatabase(databasePath, config.serverConfig().max_balance,
                config.serverConfig().sqlite_wal, config.serverConfig().sqlite_busy_timeout_ms);
        try {
            executor.run(database::initialize).join();
            CurrencyService currency = new CurrencyService(database, executor);
            StoreSessionService sessions = new StoreSessionService(config.serverConfig().session_timeout_seconds);
            return new ServerRuntime(server, config, database, executor, currency, sessions, logger);
        } catch (Exception ex) { executor.close(); config.close(); throw ex; }
    }

    public void openStore(ServerPlayer player) {
        if (!ModPermissions.has(player, ModPermissions.OPEN)) { player.sendSystemMessage(Component.literal(config.messages().no_permission)); return; }
        currency.balance(player.getUUID()).whenComplete((balance, error) -> server.execute(() -> {
            if (error != null || player.hasDisconnected()) {
                if (error != null) { logger.error("Не удалось получить баланс {}", player.getUUID(), error); player.sendSystemMessage(Component.literal(config.messages().internal_error)); }
                return;
            }
            ShopSnapshot snapshot = config.snapshot();
            StoreSessionService.StoreSession session = sessions.open(player.getUUID(), snapshot.revision());
            try {
                var limits = config.serverConfig().limits;
                NetworkHandler.sendStore(player, views.create(session.sessionId(), snapshot, player, balance),
                        limits.max_packet_payload_bytes, limits.max_transfer_bytes, limits.max_chunks);
            } catch (RuntimeException ex) {
                sessions.remove(player.getUUID()); logger.error("DTO магазина не отправлен", ex);
                player.sendSystemMessage(Component.literal("Магазин слишком велик для безопасной передачи: " + ex.getMessage()));
            }
        }));
    }

    public void sendBalance(ServerPlayer player) {
        if (!ModPermissions.has(player, ModPermissions.BALANCE)) { player.sendSystemMessage(Component.literal(config.messages().no_permission)); return; }
        currency.balance(player.getUUID()).whenComplete((balance, error) -> server.execute(() -> {
            if (error != null) { logger.error("Ошибка чтения баланса", error); player.sendSystemMessage(Component.literal(config.messages().internal_error)); return; }
            String message = config.messages().balance.replace("{balance}", Long.toString(balance));
            player.sendSystemMessage(Component.literal(message));
            NetworkHandler.send(player, new ServerEventS2C(ServerEventS2C.Type.BALANCE, null,
                    config.snapshot().revision(), balance, PurchaseResultCode.SUCCESS, message));
        }));
    }

    public void notifyStoreChanged(long revision) {
        for (StoreSessionService.StoreSession session : sessions.snapshot().values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId());
            if (player != null) NetworkHandler.send(player, new ServerEventS2C(ServerEventS2C.Type.STORE_UPDATED,
                    session.sessionId(), revision, -1, PurchaseResultCode.STORE_UPDATED, config.messages().store_updated));
        }
    }

    public void removePlayer(UUID player) { sessions.remove(player); editor.removePlayer(player); purchases.removePlayer(player); }
    public void prune() { sessions.prune(); editor.prune(); purchases.prune(); }
    public MinecraftServer server() { return server; }
    public ConfigManager config() { return config; }
    public SqliteDatabase database() { return database; }
    public DatabaseExecutor databaseExecutor() { return databaseExecutor; }
    public CurrencyService currency() { return currency; }
    public StoreSessionService sessions() { return sessions; }
    public PurchaseService purchases() { return purchases; }
    public EditorService editor() { return editor; }

    @Override public void close() {
        config.close(); databaseExecutor.close(); database.close();
    }
}
