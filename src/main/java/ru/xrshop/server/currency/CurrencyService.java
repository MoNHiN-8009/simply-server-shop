package ru.xrshop.server.currency;

import ru.xrshop.server.db.DatabaseExecutor;
import ru.xrshop.server.db.SqliteDatabase;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The only currency mutation boundary; future website adapters must call externalCredit. */
public final class CurrencyService {
    private final SqliteDatabase database;
    private final DatabaseExecutor executor;

    public CurrencyService(SqliteDatabase database, DatabaseExecutor executor) {
        this.database = database;
        this.executor = executor;
    }

    public CompletableFuture<Long> balance(UUID player) {
        return executor.submit(() -> database.balance(player));
    }

    public CompletableFuture<SqliteDatabase.CurrencyChange> add(UUID player, long amount, String reason, String actor) {
        requirePositive(amount);
        return executor.submit(() -> database.changeBalance(player, amount, reason, "ADMIN:" + actor, null));
    }

    public CompletableFuture<SqliteDatabase.CurrencyChange> remove(UUID player, long amount, String reason, String actor) {
        requirePositive(amount);
        long delta = Math.negateExact(amount);
        return executor.submit(() -> database.changeBalance(player, delta, reason, "ADMIN:" + actor, null));
    }

    public CompletableFuture<SqliteDatabase.CurrencyChange> set(UUID player, long amount, String reason, String actor) {
        if (amount < 0) throw new IllegalArgumentException("Баланс не может быть отрицательным");
        return executor.submit(() -> database.setBalance(player, amount, reason, "ADMIN:" + actor));
    }

    public CompletableFuture<SqliteDatabase.CurrencyChange> externalCredit(String source,
            String externalTransactionId, UUID player, long amount, String reason) {
        if (source == null || source.isBlank() || source.length() > 128)
            throw new IllegalArgumentException("Некорректный source");
        if (externalTransactionId == null || externalTransactionId.isBlank() || externalTransactionId.length() > 256)
            throw new IllegalArgumentException("Некорректный external_transaction_id");
        requirePositive(amount);
        return executor.submit(() -> database.changeBalance(player, amount, reason, source, externalTransactionId));
    }

    private static void requirePositive(long value) {
        if (value <= 0) throw new IllegalArgumentException("Сумма должна быть положительной");
    }
}
