package ru.xrshop.server.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import ru.xrshop.common.config.JsonCodec;
import ru.xrshop.common.config.MessagesConfig;
import ru.xrshop.common.config.ServerConfig;
import ru.xrshop.common.config.ShopConfig;
import ru.xrshop.common.validation.ShopValidator;
import ru.xrshop.common.validation.StrictJsonParser;
import ru.xrshop.common.validation.ValidationException;
import ru.xrshop.common.validation.ValidationIssue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigManager implements AutoCloseable {
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private final Path configDir;
    private final Path shopFile;
    private final Path serverFile;
    private final Path messagesFile;
    private final Path backupDir;
    private final Logger logger;
    private final ShopValidator.RuntimeChecks runtimeChecks;
    private final AtomicReference<ShopSnapshot> snapshot = new AtomicReference<>();
    private final ThreadPoolExecutor ioExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), runnable -> { Thread t = new Thread(runnable, "XRShop-ConfigIO"); t.setDaemon(true); return t; });
    private volatile ServerConfig serverConfig;
    private volatile MessagesConfig messages;

    public ConfigManager(Path configDir, Logger logger, ShopValidator.RuntimeChecks runtimeChecks) {
        this.configDir = configDir;
        this.shopFile = configDir.resolve("shop.json");
        this.serverFile = configDir.resolve("server.json");
        this.messagesFile = configDir.resolve("messages.json");
        this.backupDir = configDir.resolve("backups");
        this.logger = logger;
        this.runtimeChecks = runtimeChecks;
    }

    public void initialize() throws IOException, ValidationException {
        Files.createDirectories(backupDir);
        serverConfig = loadOrCreate(serverFile, ServerConfig.class, new ServerConfig());
        validateServerConfig(serverConfig);
        messages = loadOrCreate(messagesFile, MessagesConfig.class, new MessagesConfig());
        if (!Files.exists(shopFile)) writeDirect(shopFile, ShopConfig.empty());
        try {
            snapshot.set(loadShop(shopFile));
        } catch (Exception primary) {
            logger.error("shop.json не загружен: {}", primary.getMessage());
            ShopSnapshot recovered = recoverNewestBackup();
            if (recovered != null) {
                snapshot.set(recovered);
                logger.warn("Использована последняя корректная резервная копия revision={}", recovered.revision());
            } else {
                ShopConfig empty = ShopConfig.empty();
                new ShopValidator(serverConfig.limits, runtimeChecks).requireValid(empty);
                snapshot.set(new ShopSnapshot(empty));
                logger.error("Резервная копия отсутствует; используется пустой снимок в памяти. Повреждённый файл сохранён для исправления.");
            }
        }
    }

    public ShopSnapshot snapshot() { return snapshot.get(); }
    public ServerConfig serverConfig() { return serverConfig; }
    public MessagesConfig messages() { return messages; }
    public Path shopFile() { return shopFile; }
    public Path backupDir() { return backupDir; }

    public CompletableFuture<ReloadResult> reloadAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ShopSnapshot loaded = loadShop(shopFile);
                ShopSnapshot current = snapshot.get();
                if (loaded.revision() < current.revision()
                        || (loaded.revision() == current.revision()
                        && !JsonCodec.GSON.toJsonTree(loaded.copyConfig()).equals(JsonCodec.GSON.toJsonTree(current.copyConfig()))))
                    return new ReloadResult(false, current.revision(),
                            List.of(new ValidationIssue("$.revision", "изменённый файл должен иметь revision больше текущего " + current.revision())));
                snapshot.set(loaded);
                return new ReloadResult(true, loaded.revision(), List.of());
            } catch (Exception ex) {
                return new ReloadResult(false, snapshot.get().revision(), issues(ex));
            }
        }, ioExecutor);
    }

    public CompletableFuture<SaveResult> saveAsync(ShopConfig draft, long baseRevision) {
        ShopConfig detached = JsonCodec.copy(draft, ShopConfig.class);
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                try {
                    ShopSnapshot current = snapshot.get();
                    if (current.revision() != baseRevision)
                        return new SaveResult(false, current.revision(), true,
                                List.of(new ValidationIssue("$.revision", "конфликт base_revision")));
                    detached.revision = Math.addExact(current.revision(), 1);
                    new ShopValidator(serverConfig.limits, runtimeChecks).requireValid(detached);
                    createBackupNow();
                    Path temp = configDir.resolve("shop.json.tmp-" + UUID.randomUUID());
                    try {
                        writeDirect(temp, detached);
                        ShopSnapshot verified = loadShop(temp);
                        atomicReplace(temp, shopFile);
                        snapshot.set(verified);
                        pruneBackups();
                        return new SaveResult(true, verified.revision(), false, List.of());
                    } finally { Files.deleteIfExists(temp); }
                } catch (Exception ex) {
                    return new SaveResult(false, snapshot.get().revision(), false, issues(ex));
                }
            }
        }, ioExecutor);
    }

    public CompletableFuture<Path> backupAsync() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                try { Path result = createBackupNow(); pruneBackups(); return result; }
                catch (IOException ex) { throw new RuntimeException(ex); }
            }
        }, ioExecutor);
    }

    public List<ValidationIssue> validateDraft(ShopConfig draft) {
        return new ShopValidator(serverConfig.limits, runtimeChecks).validate(draft);
    }

    private ShopSnapshot loadShop(Path file) throws IOException, ValidationException {
        long size = Files.size(file);
        if (size > serverConfig.limits.max_json_bytes)
            throw new IOException("$. Размер shop.json превышает " + serverConfig.limits.max_json_bytes + " байт");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement root = StrictJsonParser.parse(text);
        List<ValidationIssue> unknown = new ArrayList<>();
        StrictJsonParser.rejectUnknownFields(root, ShopConfig.class, "$", unknown);
        if (!unknown.isEmpty()) throw new ValidationException(unknown);
        ShopConfig config = JsonCodec.GSON.fromJson(root, ShopConfig.class);
        new ShopValidator(serverConfig.limits, runtimeChecks).requireValid(config);
        return new ShopSnapshot(config);
    }

    private ShopSnapshot recoverNewestBackup() throws IOException {
        try (var stream = Files.list(backupDir)) {
            for (Path backup : stream.filter(Files::isRegularFile).sorted(Comparator.comparingLong(this::modified).reversed()).toList()) {
                try { return loadShop(backup); }
                catch (Exception ex) { logger.error("Резервная копия {} повреждена: {}", backup.getFileName(), ex.getMessage()); }
            }
        }
        return null;
    }

    private Path createBackupNow() throws IOException {
        if (!Files.exists(shopFile)) throw new IOException("shop.json отсутствует");
        String name = "shop-" + BACKUP_TIME.format(Instant.now()) + "-r" + snapshot.get().revision() + ".json";
        Path destination = backupDir.resolve(name);
        try {
            loadShop(shopFile);
            Files.copy(shopFile, destination, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (Exception invalidCurrentFile) {
            writeDirect(destination, snapshot.get().copyConfig());
            logger.warn("Текущий shop.json невалиден; backup создан из последнего корректного snapshot");
        }
        return destination;
    }

    private void pruneBackups() throws IOException {
        int keep = Math.max(1, Math.min(256, serverConfig.max_backups));
        try (var stream = Files.list(backupDir)) {
            List<Path> all = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(this::modified).reversed()).toList();
            for (int i = keep; i < all.size(); i++) Files.deleteIfExists(all.get(i));
        }
    }

    private long modified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ex) { return 0; } }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private <T> T loadOrCreate(Path file, Class<T> type, T defaults) throws IOException, ValidationException {
        if (!Files.exists(file)) writeDirect(file, defaults);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement root;
        try { root = StrictJsonParser.parse(text); }
        catch (JsonParseException ex) { throw new IOException(file.getFileName() + ": " + ex.getMessage(), ex); }
        List<ValidationIssue> unknown = new ArrayList<>();
        StrictJsonParser.rejectUnknownFields(root, type, "$", unknown);
        if (!unknown.isEmpty()) throw new ValidationException(unknown);
        T value = JsonCodec.GSON.fromJson(root, type);
        if (value == null) throw new IOException(file.getFileName() + ": пустой JSON");
        return value;
    }

    private void validateServerConfig(ServerConfig config) throws ValidationException {
        List<ValidationIssue> out = new ArrayList<>();
        if (config.schema_version != 1) out.add(new ValidationIssue("$.schema_version", "поддерживается только версия 1"));
        if (config.max_balance <= 0) out.add(new ValidationIssue("$.max_balance", "должен быть положительным"));
        if (config.max_balance > 9_000_000_000_000_000_000L) out.add(new ValidationIssue("$.max_balance", "превышен абсолютный максимум"));
        if (config.session_timeout_seconds < 10 || config.session_timeout_seconds > 3600) out.add(new ValidationIssue("$.session_timeout_seconds", "допустимо 10..3600"));
        if (config.editor_timeout_seconds < 30 || config.editor_timeout_seconds > 86400) out.add(new ValidationIssue("$.editor_timeout_seconds", "допустимо 30..86400"));
        if (config.rate_limit_window_seconds < 1 || config.rate_limit_window_seconds > 300) out.add(new ValidationIssue("$.rate_limit_window_seconds", "допустимо 1..300"));
        if (config.max_purchase_requests_per_window < 1 || config.max_purchase_requests_per_window > 100) out.add(new ValidationIssue("$.max_purchase_requests_per_window", "допустимо 1..100"));
        if (config.sqlite_queue_size < 16 || config.sqlite_queue_size > 4096) out.add(new ValidationIssue("$.sqlite_queue_size", "допустимо 16..4096"));
        if (config.sqlite_busy_timeout_ms < 100 || config.sqlite_busy_timeout_ms > 60000) out.add(new ValidationIssue("$.sqlite_busy_timeout_ms", "допустимо 100..60000"));
        if (config.max_backups < 1 || config.max_backups > 256) out.add(new ValidationIssue("$.max_backups", "допустимо 1..256"));
        if (config.limits == null) out.add(new ValidationIssue("$.limits", "объект отсутствует"));
        else {
            if (config.limits.max_id_length < 1 || config.limits.max_id_length > 128) out.add(new ValidationIssue("$.limits.max_id_length", "допустимо 1..128"));
            limit(config.limits.max_title_length, 1, 512, "$.limits.max_title_length", out);
            limit(config.limits.max_description_lines, 0, 64, "$.limits.max_description_lines", out);
            limit(config.limits.max_description_line_length, 1, 1024, "$.limits.max_description_line_length", out);
            limit(config.limits.max_categories, 0, 512, "$.limits.max_categories", out);
            limit(config.limits.max_products_per_category, 0, 2048, "$.limits.max_products_per_category", out);
            limit(config.limits.max_commands_per_product, 1, 64, "$.limits.max_commands_per_product", out);
            limit(config.limits.max_command_length, 1, 4096, "$.limits.max_command_length", out);
            limit(config.limits.max_permission_length, 1, 256, "$.limits.max_permission_length", out);
            limit(config.limits.max_nbt_bytes, 0, 16384, "$.limits.max_nbt_bytes", out);
            limit(config.limits.max_nbt_depth, 0, 16, "$.limits.max_nbt_depth", out);
            if (config.limits.max_json_bytes < 1024 || config.limits.max_json_bytes > 32 * 1024 * 1024) out.add(new ValidationIssue("$.limits.max_json_bytes", "допустимо 1024..33554432"));
            if (config.limits.max_packet_payload_bytes < 1024 || config.limits.max_packet_payload_bytes > 30 * 1024) out.add(new ValidationIssue("$.limits.max_packet_payload_bytes", "допустимо 1024..30720"));
            if (config.limits.max_transfer_bytes < config.limits.max_packet_payload_bytes || config.limits.max_transfer_bytes > 32 * 1024 * 1024) out.add(new ValidationIssue("$.limits.max_transfer_bytes", "должен быть не меньше payload и не больше 33554432"));
            if (config.limits.max_chunks < 1 || config.limits.max_chunks > 1024) out.add(new ValidationIssue("$.limits.max_chunks", "допустимо 1..1024"));
            limit(config.limits.max_dimension, 16, 8192, "$.limits.max_dimension", out);
            limit(config.limits.max_coordinate, 1, 1_000_000, "$.limits.max_coordinate", out);
            longLimit(config.limits.max_limit_period_seconds, 1, 10L * 365 * 24 * 60 * 60, "$.limits.max_limit_period_seconds", out);
            longLimit(config.limits.max_cooldown_seconds, 1, 365L * 24 * 60 * 60, "$.limits.max_cooldown_seconds", out);
        }
        if (!out.isEmpty()) throw new ValidationException(out);
    }

    private static void writeDirect(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, JsonCodec.GSON.toJson(value) + System.lineSeparator(), StandardCharsets.UTF_8);
    }
    private static void limit(int value, int min, int max, String path, List<ValidationIssue> out) {
        if (value < min || value > max) out.add(new ValidationIssue(path, "допустимо " + min + ".." + max));
    }
    private static void longLimit(long value, long min, long max, String path, List<ValidationIssue> out) {
        if (value < min || value > max) out.add(new ValidationIssue(path, "допустимо " + min + ".." + max));
    }
    private static List<ValidationIssue> issues(Exception ex) {
        if (ex instanceof ValidationException validation) return validation.issues();
        return List.of(new ValidationIssue("$", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
    }

    @Override public void close() {
        ioExecutor.shutdown();
        try { if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) ioExecutor.shutdownNow(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); ioExecutor.shutdownNow(); }
    }

    public record ReloadResult(boolean success, long revision, List<ValidationIssue> issues) {}
    public record SaveResult(boolean success, long revision, boolean conflict, List<ValidationIssue> issues) {}
}
