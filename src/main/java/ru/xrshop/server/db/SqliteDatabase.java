package ru.xrshop.server.db;

import ru.xrshop.common.dto.PurchaseResultCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqliteDatabase implements AutoCloseable {
    private final String jdbcUrl;
    private final long maxBalance;
    private final boolean wal;
    private final int busyTimeoutMs;

    public SqliteDatabase(Path file, long maxBalance, boolean wal, int busyTimeoutMs) {
        this.jdbcUrl = "jdbc:sqlite:" + file.toAbsolutePath();
        this.maxBalance = maxBalance;
        this.wal = wal;
        this.busyTimeoutMs = busyTimeoutMs;
    }

    public void initialize() throws SQLException, IOException {
        Path file = Path.of(jdbcUrl.substring("jdbc:sqlite:".length()));
        Files.createDirectories(file.getParent());
        try (Connection connection = open()) {
            int version;
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
                version = rs.next() ? rs.getInt(1) : 0;
            }
            if (version > 1) throw new SQLException("База данных использует неизвестную схему " + version);
            if (version == 0) {
                String migration = resource("/db/migration/V1__initial.sql");
                try (Statement statement = connection.createStatement()) {
                    for (String sql : migration.split(";")) if (!sql.isBlank()) statement.execute(sql);
                }
            }
        }
        recoverExecuting();
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMs);
            if (wal) statement.execute("PRAGMA journal_mode=WAL");
        }
        return connection;
    }

    public long balance(UUID player) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM balances WHERE player_uuid=?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        }
    }

    public CurrencyChange changeBalance(UUID player, long delta, String reason, String source,
                                        String externalTransactionId) throws SQLException {
        if (delta == 0) throw new IllegalArgumentException("Сумма не может быть нулевой");
        if (reason == null || reason.isBlank() || source == null || source.isBlank())
            throw new IllegalArgumentException("Причина и источник обязательны");
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                ensureBalanceRow(c, player);
                long current = selectBalance(c, player);
                long next = Math.addExact(current, delta);
                if (next < 0 || next > maxBalance) throw new IllegalArgumentException("Баланс вне допустимого диапазона");
                if (externalTransactionId != null && externalExists(c, source, externalTransactionId)) {
                    c.rollback();
                    return new CurrencyChange(current, false);
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE balances SET balance=?, updated_at=? WHERE player_uuid=?")) {
                    ps.setLong(1, next); ps.setLong(2, now()); ps.setString(3, player.toString()); ps.executeUpdate();
                }
                insertLedger(c, UUID.randomUUID(), player, delta, next, reason, source, externalTransactionId);
                c.commit();
                return new CurrencyChange(next, true);
            } catch (SQLException | RuntimeException ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        }
    }

    public CurrencyChange setBalance(UUID player, long amount, String reason, String source) throws SQLException {
        if (amount < 0 || amount > maxBalance) throw new IllegalArgumentException("Баланс вне допустимого диапазона");
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                ensureBalanceRow(c, player);
                long current = selectBalance(c, player);
                long delta = Math.subtractExact(amount, current);
                try (PreparedStatement ps = c.prepareStatement("UPDATE balances SET balance=?, updated_at=? WHERE player_uuid=?")) {
                    ps.setLong(1, amount); ps.setLong(2, now()); ps.setString(3, player.toString()); ps.executeUpdate();
                }
                if (delta != 0) insertLedger(c, UUID.randomUUID(), player, delta, amount, reason, source, null);
                c.commit();
                return new CurrencyChange(amount, delta != 0);
            } catch (SQLException | RuntimeException ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        }
    }

    public ReserveResult reserve(UUID transactionId, UUID player, String categoryId, String slotId,
                                 long price, long revision, long purchaseLimit,
                                 long limitPeriodSeconds, long cooldownSeconds) throws SQLException {
        if (price < 0) throw new IllegalArgumentException("Отрицательная цена");
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                ensureBalanceRow(c, player);
                long current = selectBalance(c, player);
                long now = now();
                LimitCheck restriction = checkLimit(c, player, categoryId, slotId,
                        purchaseLimit, limitPeriodSeconds, cooldownSeconds, now);
                insertPurchase(c, transactionId, player, categoryId, slotId, price, revision,
                        restriction == null ? TransactionState.CREATED : TransactionState.REJECTED,
                        restriction == null ? "" : restriction.code.name(), now);
                if (restriction != null) { c.commit(); return new ReserveResult(restriction.code, current, restriction.retryAfterSeconds); }
                int changed;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE balances SET balance=balance-?, updated_at=? WHERE player_uuid=? AND balance>=?")) {
                    ps.setLong(1, price); ps.setLong(2, now); ps.setString(3, player.toString()); ps.setLong(4, price);
                    changed = ps.executeUpdate();
                }
                if (changed != 1) {
                    setState(c, transactionId, TransactionState.REJECTED, "INSUFFICIENT_FUNDS");
                    c.commit();
                    return new ReserveResult(PurchaseResultCode.INSUFFICIENT_FUNDS, current, 0);
                }
                long next = Math.subtractExact(current, price);
                insertLedger(c, UUID.randomUUID(), player, -price, next,
                        "Покупка " + categoryId + "/" + slotId, "PURCHASE", transactionId.toString());
                setState(c, transactionId, TransactionState.FUNDS_RESERVED, "");
                updateLimit(c, player, categoryId, slotId, purchaseLimit, limitPeriodSeconds, now);
                c.commit();
                return new ReserveResult(PurchaseResultCode.SUCCESS, next, 0);
            } catch (SQLException | RuntimeException ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        }
    }

    private LimitCheck checkLimit(Connection c, UUID player, String category, String slot,
                                           long limit, long period, long cooldown, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT window_start,purchase_count,last_purchase_at FROM purchase_limits WHERE player_uuid=? AND category_id=? AND slot_id=?")) {
            ps.setString(1, player.toString()); ps.setString(2, category); ps.setString(3, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long window = rs.getLong(1), count = rs.getLong(2), last = rs.getLong(3);
                if (cooldown > 0 && now - last < cooldown) return new LimitCheck(PurchaseResultCode.COOLDOWN, cooldown - (now - last));
                if (limit > 0 && now - window < period && count >= limit) return new LimitCheck(PurchaseResultCode.LIMIT_REACHED, period - (now - window));
            }
        }
        return null;
    }

    private void updateLimit(Connection c, UUID player, String category, String slot,
                             long limit, long period, long now) throws SQLException {
        if (limit <= 0 && period <= 0) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO purchase_limits(player_uuid,category_id,slot_id,window_start,purchase_count,last_purchase_at) VALUES(?,?,?,?,?,?) ON CONFLICT(player_uuid,category_id,slot_id) DO UPDATE SET last_purchase_at=excluded.last_purchase_at")) {
                ps.setString(1, player.toString()); ps.setString(2, category); ps.setString(3, slot);
                ps.setLong(4, now); ps.setLong(5, 0); ps.setLong(6, now); ps.executeUpdate();
            }
            return;
        }
        long oldWindow = 0, oldCount = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT window_start,purchase_count FROM purchase_limits WHERE player_uuid=? AND category_id=? AND slot_id=?")) {
            ps.setString(1, player.toString()); ps.setString(2, category); ps.setString(3, slot);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { oldWindow = rs.getLong(1); oldCount = rs.getLong(2); } }
        }
        boolean reset = oldWindow == 0 || (period > 0 && now - oldWindow >= period);
        long window = reset ? now : oldWindow;
        long count = reset ? 1 : Math.addExact(oldCount, 1);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO purchase_limits(player_uuid,category_id,slot_id,window_start,purchase_count,last_purchase_at) VALUES(?,?,?,?,?,?) ON CONFLICT(player_uuid,category_id,slot_id) DO UPDATE SET window_start=excluded.window_start,purchase_count=excluded.purchase_count,last_purchase_at=excluded.last_purchase_at")) {
            ps.setString(1, player.toString()); ps.setString(2, category); ps.setString(3, slot);
            ps.setLong(4, window); ps.setLong(5, count); ps.setLong(6, now); ps.executeUpdate();
        }
    }

    public void markExecuting(UUID transactionId) throws SQLException { updateState(transactionId, TransactionState.EXECUTING, ""); }
    public void complete(UUID transactionId) throws SQLException { updateState(transactionId, TransactionState.COMPLETED, ""); }
    public void reviewRequired(UUID transactionId, String detail) throws SQLException { updateState(transactionId, TransactionState.REVIEW_REQUIRED, detail); }

    public void recordCommand(UUID transactionId, int index, String command, boolean success, String result) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO command_results(transaction_id,command_index,command,success,result,executed_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, transactionId.toString()); ps.setInt(2, index); ps.setString(3, command);
            ps.setInt(4, success ? 1 : 0); ps.setString(5, truncate(result, 4096)); ps.setLong(6, now()); ps.executeUpdate();
        }
    }

    public long refund(UUID transactionId, String reason) throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                PurchaseRecord purchase = purchase(c, transactionId).orElseThrow(() -> new IllegalArgumentException("Транзакция не найдена"));
                if (purchase.state != TransactionState.FUNDS_RESERVED && purchase.state != TransactionState.REVIEW_REQUIRED)
                    throw new IllegalStateException("Возврат недоступен для состояния " + purchase.state);
                UUID player = UUID.fromString(purchase.playerUuid);
                ensureBalanceRow(c, player);
                long current = selectBalance(c, player);
                long next = Math.addExact(current, purchase.price);
                if (next > maxBalance) throw new IllegalArgumentException("Возврат превысит максимальный баланс");
                try (PreparedStatement ps = c.prepareStatement("UPDATE balances SET balance=?,updated_at=? WHERE player_uuid=?")) {
                    ps.setLong(1, next); ps.setLong(2, now()); ps.setString(3, purchase.playerUuid); ps.executeUpdate();
                }
                insertLedger(c, UUID.randomUUID(), player, purchase.price, next, reason, "REFUND", transactionId.toString());
                setState(c, transactionId, TransactionState.REFUNDED, reason);
                c.commit(); return next;
            } catch (SQLException | RuntimeException ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        }
    }

    public void resolve(UUID transactionId, String reason) throws SQLException {
        try (Connection c = open()) {
            PurchaseRecord record = purchase(c, transactionId).orElseThrow(() -> new IllegalArgumentException("Транзакция не найдена"));
            if (record.state != TransactionState.REVIEW_REQUIRED)
                throw new IllegalStateException("Транзакция не требует проверки");
        }
        updateState(transactionId, TransactionState.COMPLETED, "Решено администратором: " + reason);
    }

    public int recoverExecuting() throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE purchases SET state='REVIEW_REQUIRED',detail='Перезапуск сервера во время EXECUTING; повтор запрещён',updated_at=? WHERE state='EXECUTING'")) {
            ps.setLong(1, now()); return ps.executeUpdate();
        }
    }

    public List<PurchaseRecord> history(UUID player, int limit) throws SQLException {
        String sql = player == null
                ? "SELECT transaction_id,player_uuid,category_id,slot_id,price,config_revision,state,created_at,updated_at,detail FROM purchases ORDER BY created_at DESC LIMIT ?"
                : "SELECT transaction_id,player_uuid,category_id,slot_id,price,config_revision,state,created_at,updated_at,detail FROM purchases WHERE player_uuid=? ORDER BY created_at DESC LIMIT ?";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int index = 1; if (player != null) ps.setString(index++, player.toString()); ps.setInt(index, Math.min(100, Math.max(1, limit)));
            try (ResultSet rs = ps.executeQuery()) { List<PurchaseRecord> result = new ArrayList<>(); while (rs.next()) result.add(readPurchase(rs)); return result; }
        }
    }

    public List<PurchaseRecord> reviews(int limit) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT transaction_id,player_uuid,category_id,slot_id,price,config_revision,state,created_at,updated_at,detail FROM purchases WHERE state='REVIEW_REQUIRED' ORDER BY updated_at ASC LIMIT ?")) {
            ps.setInt(1, Math.min(100, Math.max(1, limit)));
            try (ResultSet rs = ps.executeQuery()) { List<PurchaseRecord> result = new ArrayList<>(); while (rs.next()) result.add(readPurchase(rs)); return result; }
        }
    }

    public List<CommandResultRecord> commandResults(UUID transactionId) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT command_index,command,success,result,executed_at FROM command_results WHERE transaction_id=? ORDER BY command_index")) {
            ps.setString(1, transactionId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<CommandResultRecord> result = new ArrayList<>();
                while (rs.next()) result.add(new CommandResultRecord(rs.getInt(1), rs.getString(2), rs.getInt(3) != 0, rs.getString(4), rs.getLong(5)));
                return result;
            }
        }
    }

    public Optional<PurchaseRecord> purchase(UUID transactionId) throws SQLException {
        try (Connection c = open()) { return purchase(c, transactionId); }
    }

    public void audit(UUID actor, String action, String target, String detail) throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit(id,actor_uuid,action,target,detail,created_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString()); ps.setString(2, actor.toString()); ps.setString(3, action);
            ps.setString(4, target); ps.setString(5, truncate(detail, 4096)); ps.setLong(6, now()); ps.executeUpdate();
        }
    }

    private Optional<PurchaseRecord> purchase(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT transaction_id,player_uuid,category_id,slot_id,price,config_revision,state,created_at,updated_at,detail FROM purchases WHERE transaction_id=?")) {
            ps.setString(1, id.toString()); try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(readPurchase(rs)) : Optional.empty(); }
        }
    }
    private PurchaseRecord readPurchase(ResultSet rs) throws SQLException {
        return new PurchaseRecord(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getLong(6), TransactionState.valueOf(rs.getString(7)), rs.getLong(8), rs.getLong(9), rs.getString(10));
    }
    private void updateState(UUID id, TransactionState state, String detail) throws SQLException {
        try (Connection c = open()) { setState(c, id, state, detail); }
    }
    private void setState(Connection c, UUID id, TransactionState state, String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE purchases SET state=?,detail=?,updated_at=? WHERE transaction_id=?")) {
            ps.setString(1, state.name()); ps.setString(2, truncate(detail, 4096)); ps.setLong(3, now()); ps.setString(4, id.toString());
            if (ps.executeUpdate() != 1) throw new SQLException("Транзакция не найдена: " + id);
        }
    }
    private void insertPurchase(Connection c, UUID id, UUID player, String category, String slot, long price,
                                long revision, TransactionState state, String detail, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO purchases(transaction_id,player_uuid,category_id,slot_id,price,config_revision,state,created_at,updated_at,detail) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id.toString()); ps.setString(2, player.toString()); ps.setString(3, category); ps.setString(4, slot);
            ps.setLong(5, price); ps.setLong(6, revision); ps.setString(7, state.name()); ps.setLong(8, now); ps.setLong(9, now); ps.setString(10, detail); ps.executeUpdate();
        }
    }
    private void ensureBalanceRow(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO balances(player_uuid,balance,updated_at) VALUES(?,0,?)")) {
            ps.setString(1, player.toString()); ps.setLong(2, now()); ps.executeUpdate();
        }
    }
    private long selectBalance(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM balances WHERE player_uuid=?")) {
            ps.setString(1, player.toString()); try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new SQLException("Баланс не создан"); return rs.getLong(1); }
        }
    }
    private boolean externalExists(Connection c, String source, String externalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ledger WHERE source=? AND external_transaction_id=?")) {
            ps.setString(1, source); ps.setString(2, externalId); try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }
    private void insertLedger(Connection c, UUID id, UUID player, long delta, long after, String reason,
                              String source, String externalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO ledger(id,player_uuid,delta,balance_after,reason,source,external_transaction_id,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id.toString()); ps.setString(2, player.toString()); ps.setLong(3, delta); ps.setLong(4, after);
            ps.setString(5, truncate(reason, 512)); ps.setString(6, truncate(source, 128)); ps.setString(7, externalId); ps.setLong(8, now()); ps.executeUpdate();
        }
    }
    private static String resource(String name) throws IOException {
        try (InputStream in = SqliteDatabase.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("Не найдена миграция " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static long now() { return Instant.now().getEpochSecond(); }
    private static String truncate(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max); }
    @Override public void close() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA optimize");
        } catch (SQLException ex) {
            throw new IllegalStateException("Не удалось оптимизировать SQLite при остановке", ex);
        }
    }

    public record CurrencyChange(long balance, boolean applied) {}
    public record ReserveResult(PurchaseResultCode code, long balance, long retryAfterSeconds) {}
    private record LimitCheck(PurchaseResultCode code, long retryAfterSeconds) {}
    public record CommandResultRecord(int index, String command, boolean success, String result, long executedAt) {}
    public record PurchaseRecord(UUID transactionId, String playerUuid, String categoryId, String slotId,
                                 long price, long revision, TransactionState state, long createdAt,
                                 long updatedAt, String detail) {}
}
