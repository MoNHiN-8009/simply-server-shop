package ru.xrshop.server.purchase;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ru.xrshop.common.config.MessagesConfig;
import ru.xrshop.common.dto.PurchaseResultCode;
import ru.xrshop.common.network.NetworkHandler;
import ru.xrshop.common.network.ServerEventS2C;
import ru.xrshop.common.validation.ShopValidator;
import ru.xrshop.server.config.ConfigManager;
import ru.xrshop.server.config.ShopSnapshot;
import ru.xrshop.server.db.DatabaseExecutor;
import ru.xrshop.server.db.SqliteDatabase;
import ru.xrshop.server.security.ModPermissions;
import ru.xrshop.server.security.RequestRateLimiter;
import ru.xrshop.server.session.StoreSessionService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class PurchaseService {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+");
    private final MinecraftServer server;
    private final ConfigManager config;
    private final SqliteDatabase database;
    private final DatabaseExecutor databaseExecutor;
    private final StoreSessionService sessions;
    private final RequestRateLimiter rateLimiter;
    private final ActivePurchaseGuard active = new ActivePurchaseGuard();
    private final Logger logger;
    private final RefreshStore refreshStore;

    public PurchaseService(MinecraftServer server, ConfigManager config, SqliteDatabase database,
                           DatabaseExecutor databaseExecutor, StoreSessionService sessions,
                           Logger logger, RefreshStore refreshStore) {
        this.server = server; this.config = config; this.database = database;
        this.databaseExecutor = databaseExecutor; this.sessions = sessions; this.logger = logger;
        this.refreshStore = refreshStore;
        this.rateLimiter = new RequestRateLimiter(config.serverConfig().rate_limit_window_seconds,
                config.serverConfig().max_purchase_requests_per_window);
    }

    public void buy(ServerPlayer player, String categoryId, String slotId) {
        UUID playerId = player.getUUID();
        if (!ModPermissions.has(player, ModPermissions.BUY)) { reject(player, PurchaseResultCode.NO_PERMISSION, config.messages().no_permission, -1); return; }
        int maxId = config.serverConfig().limits.max_id_length;
        if (!validId(categoryId, maxId) || !validId(slotId, maxId)) { reject(player, PurchaseResultCode.INVALID_ID, "Некорректный идентификатор.", -1); return; }
        if (!rateLimiter.allow(playerId)) { reject(player, PurchaseResultCode.RATE_LIMITED, config.messages().rate_limited, -1); return; }
        if (!active.acquire(playerId)) { reject(player, PurchaseResultCode.ACTIVE_PURCHASE, config.messages().active_purchase, -1); return; }
        boolean asyncStarted = false;
        try {
            ShopSnapshot snapshot = config.snapshot();
            var session = sessions.active(playerId);
            if (!RevisionPolicy.permits(session, snapshot.revision())) {
                reject(player, PurchaseResultCode.STORE_UPDATED, config.messages().store_updated, -1);
                active.release(playerId);
                refreshStore.open(player);
                return;
            }
            ShopSnapshot.CategoryEntry category = snapshot.category(categoryId).orElse(null);
            if (category == null || !category.enabled()) { reject(player, PurchaseResultCode.NOT_FOUND, config.messages().invalid_product, -1); return; }
            if (!ModPermissions.hasNamed(player, category.permission())) { reject(player, PurchaseResultCode.NO_PERMISSION, config.messages().no_permission, -1); return; }
            ShopSnapshot.ProductEntry product = category.product(slotId).orElse(null);
            if (product == null || !product.enabled()) { reject(player, PurchaseResultCode.NOT_FOUND, config.messages().invalid_product, -1); return; }
            if (!ModPermissions.hasNamed(player, product.permission())) { reject(player, PurchaseResultCode.NO_PERMISSION, config.messages().no_permission, -1); return; }
            if (product.price() < 0 || product.price() > config.serverConfig().max_balance) { reject(player, PurchaseResultCode.INVALID_COMMAND, "Некорректная цена товара.", -1); return; }

            UUID transactionId = UUID.randomUUID();
            List<String> commands = substituteAndValidate(player, snapshot, category, product, transactionId);
            if (commands == null) { reject(player, PurchaseResultCode.INVALID_COMMAND, config.messages().invalid_command, -1); return; }
            asyncStarted = true;
            processing(player);
            databaseExecutor.submit(() -> database.reserve(transactionId, playerId, categoryId, slotId,
                    product.price(), snapshot.revision(), product.purchaseLimit(), product.limitPeriodSeconds(),
                    product.cooldownSeconds())).whenComplete((reserve, error) -> server.execute(() -> {
                if (error != null) { logger.error("Ошибка резервирования {}", transactionId, error); reject(player, PurchaseResultCode.INTERNAL_ERROR, config.messages().internal_error, -1); finish(playerId); return; }
                if (reserve.code() != PurchaseResultCode.SUCCESS) {
                    reject(player, reserve.code(), messageFor(reserve.code(), reserve.balance(), reserve.retryAfterSeconds(), product.errorMessage()), reserve.balance()); finish(playerId); return;
                }
                databaseExecutor.run(() -> database.markExecuting(transactionId)).whenComplete((ignored, markError) -> server.execute(() -> {
                    if (markError != null) {
                        databaseExecutor.submit(() -> database.refund(transactionId, "Ошибка перед началом выполнения команд"))
                                .whenComplete((balance, refundError) -> server.execute(() -> {
                                    if (refundError != null) logger.error("Не удалось безопасно вернуть XR по {}", transactionId, refundError);
                                    reject(player, PurchaseResultCode.INTERNAL_ERROR, config.messages().internal_error, balance == null ? -1 : balance);
                                    finish(playerId);
                                }));
                    } else executeNext(player, transactionId, commands, 0, reserve.balance(), product.successMessage());
                }));
            }));
        } finally {
            if (!asyncStarted) active.release(playerId);
        }
    }

    private List<String> substituteAndValidate(ServerPlayer player, ShopSnapshot snapshot,
                                               ShopSnapshot.CategoryEntry category,
                                               ShopSnapshot.ProductEntry product, UUID transactionId) {
        List<String> result = new ArrayList<>();
        ShopValidator.RuntimeChecks checks = new ru.xrshop.server.validation.ForgeRuntimeChecks(server);
        for (String template : product.commands()) {
            String command = ShopValidator.substitute(template, player.getGameProfile().getName(),
                    player.getUUID().toString(), product.price(), category.id(), product.id(), transactionId.toString());
            if (command.length() > config.serverConfig().limits.max_command_length || checks.validateCommand(command) != null) return null;
            result.add(command);
        }
        return List.copyOf(result);
    }

    private void executeNext(ServerPlayer player, UUID transactionId, List<String> commands, int index, long balance,
                             String customSuccessMessage) {
        if (index >= commands.size()) {
            databaseExecutor.run(() -> database.complete(transactionId)).whenComplete((ignored, error) -> server.execute(() -> {
                if (error != null) {
                    logger.error("Не удалось завершить транзакцию {}", transactionId, error);
                    markReviewAndFinish(player, transactionId, "Ошибка записи COMPLETED", balance);
                } else {
                    success(player, transactionId, balance, customSuccessMessage); finish(player.getUUID());
                }
            }));
            return;
        }
        String command = commands.get(index);
        boolean success;
        String detail;
        try {
            CommandOutcome outcome = new CommandOutcome();
            int dispatcherResult = server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withPermission(4).withSuppressedOutput()
                            .withCallback((context, commandSuccess, commandResult) ->
                                    outcome.accept(commandSuccess, commandResult)), command);
            success = outcome.successful();
            detail = outcome.detail(dispatcherResult);
        } catch (Throwable throwable) {
            success = false;
            detail = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        final boolean recordedSuccess = success;
        final String recordedDetail = detail;
        databaseExecutor.run(() -> database.recordCommand(transactionId, index, command, recordedSuccess, recordedDetail))
                .whenComplete((ignored, recordError) -> server.execute(() -> {
                    if (recordError != null || !recordedSuccess) {
                        String reason = recordError != null ? "Ошибка записи результата команды: " + recordError.getMessage()
                                : "Команда " + index + " завершилась неуспешно: " + recordedDetail;
                        markReviewAndFinish(player, transactionId, reason, balance);
                    } else executeNext(player, transactionId, commands, index + 1, balance, customSuccessMessage);
                }));
    }

    private void markReviewAndFinish(ServerPlayer player, UUID transactionId, String reason, long balance) {
        databaseExecutor.run(() -> database.reviewRequired(transactionId, reason)).whenComplete((ignored, error) -> server.execute(() -> {
            if (error != null) logger.error("Не удалось записать REVIEW_REQUIRED для {}", transactionId, error);
            String message = config.messages().review_required.replace("{transaction_id}", transactionId.toString());
            reject(player, PurchaseResultCode.REVIEW_REQUIRED, message, balance); finish(player.getUUID());
        }));
    }

    private void processing(ServerPlayer player) {
        send(player, PurchaseResultCode.PROCESSING, config.messages().purchase_started, -1);
    }

    /** Aggregates Brigadier callbacks; numeric result 0 is valid when Minecraft reports success. */
    static final class CommandOutcome {
        private int callbacks;
        private int successes;
        private int failures;
        private long resultTotal;

        void accept(boolean success, int result) {
            callbacks++;
            if (success) successes++; else failures++;
            resultTotal += result;
        }

        boolean successful() { return callbacks > 0 && failures == 0; }

        String detail(int dispatcherResult) {
            return "callbacks=" + callbacks + ",successes=" + successes + ",failures=" + failures
                    + ",callback_result=" + resultTotal + ",dispatcher_result=" + dispatcherResult;
        }
    }
    private void success(ServerPlayer player, UUID transactionId, long balance, String customMessage) {
        String message = customMessage == null || customMessage.isBlank() ? config.messages().purchase_success : customMessage;
        message = message.replace("{balance}", Long.toString(balance)).replace("{transaction_id}", transactionId.toString());
        send(player, PurchaseResultCode.SUCCESS, message, balance);
    }
    private void reject(ServerPlayer player, PurchaseResultCode code, String message, long balance) { send(player, code, message, balance); }
    private void send(ServerPlayer player, PurchaseResultCode code, String message, long balance) {
        player.sendSystemMessage(Component.literal(message));
        UUID session = sessions.active(player.getUUID()).map(StoreSessionService.StoreSession::sessionId).orElse(null);
        NetworkHandler.send(player, new ServerEventS2C(ServerEventS2C.Type.PURCHASE_RESULT, session,
                config.snapshot().revision(), balance, code, message));
    }
    private String messageFor(PurchaseResultCode code, long balance, long seconds, String customError) {
        MessagesConfig m = config.messages();
        String standard = switch (code) {
            case INSUFFICIENT_FUNDS -> m.insufficient_funds.replace("{balance}", Long.toString(balance));
            case LIMIT_REACHED -> m.limit_reached;
            case COOLDOWN -> m.cooldown.replace("{seconds}", Long.toString(seconds));
            default -> m.internal_error;
        };
        if (customError == null || customError.isBlank()) return standard;
        return customError.replace("{balance}", Long.toString(balance)).replace("{seconds}", Long.toString(seconds)) + " " + standard;
    }
    private void finish(UUID player) { active.release(player); }
    public boolean isActive(UUID player) { return active.isActive(player); }
    public void removePlayer(UUID player) { active.release(player); rateLimiter.remove(player); }
    public void prune() { rateLimiter.prune(); }
    private static boolean validId(String id, int max) { return id != null && id.length() <= max && ID.matcher(id).matches(); }

    @FunctionalInterface public interface RefreshStore { void open(ServerPlayer player); }
}
