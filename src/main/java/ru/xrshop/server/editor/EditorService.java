package ru.xrshop.server.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ru.xrshop.common.config.JsonCodec;
import ru.xrshop.common.config.ShopConfig;
import ru.xrshop.common.dto.EditorDto;
import ru.xrshop.common.dto.EditorMode;
import ru.xrshop.common.dto.EditorFieldEdit;
import ru.xrshop.common.dto.PurchaseResultCode;
import ru.xrshop.common.network.EditorActionC2S;
import ru.xrshop.common.network.NetworkHandler;
import ru.xrshop.common.network.ServerEventS2C;
import ru.xrshop.common.validation.ValidationIssue;
import ru.xrshop.server.config.ConfigManager;
import ru.xrshop.server.db.DatabaseExecutor;
import ru.xrshop.server.db.SqliteDatabase;
import ru.xrshop.server.security.ModPermissions;
import ru.xrshop.server.security.RequestRateLimiter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.lang.reflect.Type;
import java.util.concurrent.ConcurrentHashMap;

public final class EditorService {
    private static final Type FIELD_EDITS_TYPE = new TypeToken<List<EditorFieldEdit>>() {}.getType();
    private static final int MAX_FIELD_EDITS = 256;
    private final MinecraftServer server;
    private final ConfigManager config;
    private final SqliteDatabase database;
    private final DatabaseExecutor databaseExecutor;
    private final Logger logger;
    private final StoreChanged storeChanged;
    private final RequestRateLimiter rateLimiter;
    private final Map<UUID, EditorSession> bySession = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> byOwner = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public EditorService(MinecraftServer server, ConfigManager config, SqliteDatabase database,
                         DatabaseExecutor databaseExecutor, Logger logger, StoreChanged storeChanged) {
        this.server = server; this.config = config; this.database = database;
        this.databaseExecutor = databaseExecutor; this.logger = logger; this.storeChanged = storeChanged;
        this.rateLimiter = new RequestRateLimiter(config.serverConfig().rate_limit_window_seconds,
                Math.max(10, config.serverConfig().max_purchase_requests_per_window * 5));
    }

    public void open(ServerPlayer player, EditorMode mode) {
        if (!ModPermissions.has(player, ModPermissions.EDITOR)) { player.sendSystemMessage(Component.literal(config.messages().no_permission)); return; }
        UUID previous = byOwner.remove(player.getUUID()); if (previous != null) bySession.remove(previous);
        UUID id = UUID.randomUUID(); long base = config.snapshot().revision();
        EditorSession session = new EditorSession(id, player.getUUID(), base, config.snapshot().copyConfig(), expiresAt(), mode);
        bySession.put(id, session); byOwner.put(player.getUUID(), id); sendDraft(player, session);
    }

    public void handle(ServerPlayer player, EditorActionC2S request) {
        if (!ModPermissions.has(player, ModPermissions.EDITOR)) { result(player, request.sessionId(), false, config.messages().no_permission); return; }
        EditorSession session = bySession.get(request.sessionId());
        if (session == null || !session.owner.equals(player.getUUID()) || session.expiresAtMillis < clock.millis()) {
            result(player, request.sessionId(), false, "Сессия редактора недействительна или истекла."); return;
        }
        if (!rateLimiter.allow(player.getUUID())) { result(player, request.sessionId(), false, "Слишком много операций редактора. Повторите позже."); return; }
        if (!bounded(request)) { result(player, request.sessionId(), false, "Операция превышает допустимый размер."); return; }
        session.expiresAtMillis = expiresAt();
        if (request.action() == EditorActionC2S.Action.VALIDATE) {
            List<ValidationIssue> issues = config.validateDraft(session.draft);
            result(player, session.id, issues.isEmpty(), issues.isEmpty() ? config.messages().validation_ok : formatIssues(issues));
            return;
        }
        if (request.action() == EditorActionC2S.Action.CHECK_COMMAND) {
            try {
                ShopConfig candidate = JsonCodec.copy(session.draft, ShopConfig.class);
                List<String> commands = requireProduct(requireCategory(candidate, request.categoryId()), request.slotId()).purchase_commands;
                // Check one command without changing the draft or being affected by an already full command list.
                if (commands.isEmpty()) commands.add(request.value());
                else commands.set(0, request.value());
                List<ValidationIssue> issues = config.validateDraft(candidate);
                result(player, session.id, issues.isEmpty(), issues.isEmpty() ? "Команда проверена сервером." : formatIssues(issues));
            } catch (Exception ex) {
                result(player, session.id, false, ex.getMessage() == null ? "Команда отклонена." : ex.getMessage());
            }
            return;
        }
        if (request.action() == EditorActionC2S.Action.CANCEL) { close(player, session.id); result(player, session.id, true, "Изменения отменены."); return; }
        if (request.action() == EditorActionC2S.Action.SAVE) { save(player, session, session.draft); return; }
        if (request.action() == EditorActionC2S.Action.SAVE_FIELDS) { saveFields(player, session, request.value()); return; }
        if (request.action() == EditorActionC2S.Action.CHECK_FIELDS) { checkFields(player, session, request.value()); return; }
        try {
            ShopConfig candidate = JsonCodec.copy(session.draft, ShopConfig.class);
            apply(candidate, request);
            List<ValidationIssue> issues = config.validateDraft(candidate);
            if (!issues.isEmpty()) { result(player, session.id, false, formatIssues(issues)); return; }
            session.draft = candidate;
            sendDraft(player, session);
            result(player, session.id, true, "Изменение применено.");
        } catch (Exception ex) { result(player, session.id, false, ex.getMessage() == null ? "Операция отклонена." : ex.getMessage()); }
    }

    private void saveFields(ServerPlayer player, EditorSession session, String json) {
        try {
            ShopConfig candidate = candidateWithFields(session, json);
            List<ValidationIssue> issues = config.validateDraft(candidate);
            if (!issues.isEmpty()) { result(player, session.id, false, formatIssues(issues)); return; }
            save(player, session, candidate);
        } catch (Exception ex) {
            result(player, session.id, false, ex.getMessage() == null ? "Пакет изменений отклонён." : ex.getMessage());
        }
    }

    private void checkFields(ServerPlayer player, EditorSession session, String json) {
        try {
            ShopConfig candidate = candidateWithFields(session, json);
            List<ValidationIssue> issues = config.validateDraft(candidate);
            result(player, session.id, issues.isEmpty(), issues.isEmpty() ? config.messages().validation_ok : formatIssues(issues));
        } catch (Exception ex) {
            result(player, session.id, false, ex.getMessage() == null ? "Пакет изменений отклонён." : ex.getMessage());
        }
    }

    private static ShopConfig candidateWithFields(EditorSession session, String json) throws Exception {
        List<EditorFieldEdit> edits = JsonCodec.GSON.fromJson(json, FIELD_EDITS_TYPE);
        return applyFieldEdits(session.draft, edits);
    }

    static ShopConfig applyFieldEdits(ShopConfig draft, List<EditorFieldEdit> edits) throws Exception {
        if (edits == null) throw new IllegalArgumentException("Список изменений отсутствует.");
        if (edits.size() > MAX_FIELD_EDITS) throw new IllegalArgumentException("Слишком много изменённых полей.");
        ShopConfig candidate = JsonCodec.copy(draft, ShopConfig.class);
        for (EditorFieldEdit edit : edits) applyFieldEdit(candidate, edit);
        return candidate;
    }

    private static void applyFieldEdit(ShopConfig draft, EditorFieldEdit edit) throws Exception {
        if (edit == null || edit.target() == null) throw new IllegalArgumentException("Не указана цель изменения.");
        if (!editableLeaf(edit.target(), edit.path())) throw new IllegalArgumentException("Поле нельзя изменить из формы: " + edit.path());
        Object root = switch (edit.target()) {
            case CATEGORY -> requireCategory(draft, edit.categoryId());
            case PRODUCT -> requireProduct(requireCategory(draft, edit.categoryId()), edit.slotId());
            case UI -> draft.ui;
            case GLOBAL_STYLE -> draft.style;
        };
        setField(root, edit.path(), edit.jsonValue());
    }

    private static boolean editableLeaf(EditorFieldEdit.Target target, String path) {
        if (path == null || path.isBlank() || path.length() > 128) return false;
        if (path.equals("category_id") || path.equals("slot_id") || path.equals("products") || path.equals("purchase_commands")) return false;
        Class<?> root = switch (target) {
            case CATEGORY -> ShopConfig.CategoryDefinition.class;
            case PRODUCT -> ShopConfig.ProductDefinition.class;
            case UI -> ShopConfig.UiDefinition.class;
            case GLOBAL_STYLE -> ShopConfig.StyleDefinition.class;
        };
        try {
            String[] parts = path.split("\\.");
            Class<?> current = root;
            Field leaf = null;
            for (String part : parts) { leaf = publicField(current, part); current = leaf.getType(); }
            return leaf != null && (current.isPrimitive() || current == String.class || current == Boolean.class
                    || current == ShopConfig.TextureDefinition.class
                    || (List.class.isAssignableFrom(current) && path.equals("description")));
        } catch (ReflectiveOperationException ex) { return false; }
    }

    private void save(ServerPlayer player, EditorSession session, ShopConfig draft) {
        long previousRevision = session.baseRevision;
        config.saveAsync(draft, previousRevision).whenComplete((saved, error) -> server.execute(() -> {
            if (error != null) { logger.error("Ошибка сохранения редактора", error); result(player, session.id, false, "Ошибка сохранения: " + error.getMessage()); return; }
            if (!saved.success()) { result(player, session.id, false, saved.conflict() ? config.messages().editor_conflict : formatIssues(saved.issues())); return; }
            databaseExecutor.run(() -> database.audit(player.getUUID(), "EDITOR_SAVE", "shop.json",
                    "revision " + previousRevision + " -> " + saved.revision()));
            if (bySession.get(session.id) == session) {
                session.baseRevision = saved.revision();
                session.draft = config.snapshot().copyConfig();
                session.expiresAtMillis = expiresAt();
                sendDraft(player, session);
                result(player, session.id, true, config.messages().editor_saved);
            }
            storeChanged.changed(saved.revision());
        }));
    }

    private void apply(ShopConfig draft, EditorActionC2S request) throws Exception {
        switch (request.action()) {
            case CREATE_CATEGORY -> {
                requireAbsentCategory(draft, request.categoryId());
                ShopConfig.CategoryDefinition category = new ShopConfig.CategoryDefinition();
                category.category_id = request.categoryId(); category.title = request.categoryId();
                category.order = draft.categories.size(); draft.categories.add(category);
            }
            case DELETE_CATEGORY -> draft.categories.remove(requireCategory(draft, request.categoryId()));
            case COPY_CATEGORY -> {
                requireAbsentCategory(draft, request.secondary());
                ShopConfig.CategoryDefinition copy = JsonCodec.copy(requireCategory(draft, request.categoryId()), ShopConfig.CategoryDefinition.class);
                copy.category_id = request.secondary(); copy.title = copy.title + " (копия)"; draft.categories.add(copy);
            }
            case MOVE_CATEGORY -> requireCategory(draft, request.categoryId()).order = parseInt(request.value());
            case CREATE_PRODUCT -> {
                ShopConfig.CategoryDefinition category = requireCategory(draft, request.categoryId());
                requireAbsentProduct(category, request.slotId());
                ShopConfig.ProductDefinition product = new ShopConfig.ProductDefinition();
                product.slot_id = request.slotId(); product.title = request.slotId(); product.enabled = false;
                product.icon.size = 0;
                product.order = category.products.size(); category.products.add(product);
            }
            case DELETE_PRODUCT -> requireCategory(draft, request.categoryId()).products.remove(
                    requireProduct(requireCategory(draft, request.categoryId()), request.slotId()));
            case COPY_PRODUCT -> {
                ShopConfig.CategoryDefinition category = requireCategory(draft, request.categoryId());
                requireAbsentProduct(category, request.secondary());
                ShopConfig.ProductDefinition copy = JsonCodec.copy(requireProduct(category, request.slotId()), ShopConfig.ProductDefinition.class);
                copy.slot_id = request.secondary(); copy.title = copy.title + " (копия)"; copy.enabled = false; category.products.add(copy);
            }
            case MOVE_PRODUCT -> {
                ShopConfig.CategoryDefinition source = requireCategory(draft, request.categoryId());
                ShopConfig.CategoryDefinition destination = requireCategory(draft, request.secondary());
                ShopConfig.ProductDefinition product = requireProduct(source, request.slotId());
                requireAbsentProduct(destination, product.slot_id); source.products.remove(product); destination.products.add(product);
            }
            case SET_CATEGORY_FIELD -> setField(requireCategory(draft, request.categoryId()), request.field(), request.value());
            case SET_PRODUCT_FIELD -> setField(requireProduct(requireCategory(draft, request.categoryId()), request.slotId()), request.field(), request.value());
            case SET_UI_FIELD -> setField(draft.ui, request.field(), request.value());
            case SET_GLOBAL_STYLE_FIELD -> setField(draft.style, request.field(), request.value());
            case ADD_COMMAND -> requireProduct(requireCategory(draft, request.categoryId()), request.slotId()).purchase_commands.add(request.value());
            case SET_COMMAND -> {
                List<String> commands = requireProduct(requireCategory(draft, request.categoryId()), request.slotId()).purchase_commands;
                commands.set(parseIndex(request.field(), commands.size()), request.value());
            }
            case REMOVE_COMMAND -> {
                List<String> commands = requireProduct(requireCategory(draft, request.categoryId()), request.slotId()).purchase_commands;
                commands.remove(parseIndex(request.field(), commands.size()));
            }
            default -> throw new IllegalArgumentException("Операция не изменяет черновик");
        }
    }

    private static void setField(Object root, String path, String jsonValue) throws Exception {
        if (root == null || path == null || path.isBlank()) throw new IllegalArgumentException("Поле не указано");
        String[] parts = path.split("\\."); Object target = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Field field = publicField(target.getClass(), parts[i]); target = field.get(target);
            if (target == null) throw new IllegalArgumentException("Вложенный объект отсутствует: " + parts[i]);
        }
        Field field = publicField(target.getClass(), parts[parts.length - 1]);
        JsonElement value;
        try { value = JsonCodec.parse(jsonValue); } catch (Exception ex) { throw new IllegalArgumentException("Значение не является JSON: " + ex.getMessage()); }
        Object converted = JsonCodec.GSON.fromJson(value, field.getGenericType());
        field.set(target, converted);
    }
    private static Field publicField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getField(name);
        if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) throw new NoSuchFieldException(name);
        return field;
    }
    private static ShopConfig.CategoryDefinition requireCategory(ShopConfig draft, String id) {
        return draft.categories.stream().filter(c -> c.category_id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Категория не найдена: " + id));
    }
    private static void requireAbsentCategory(ShopConfig draft, String id) { if (draft.categories.stream().anyMatch(c -> c.category_id.equals(id))) throw new IllegalArgumentException("category_id уже существует"); }
    private static ShopConfig.ProductDefinition requireProduct(ShopConfig.CategoryDefinition category, String id) {
        return category.products.stream().filter(p -> p.slot_id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Товар не найден: " + id));
    }
    private static void requireAbsentProduct(ShopConfig.CategoryDefinition category, String id) { if (category.products.stream().anyMatch(p -> p.slot_id.equals(id))) throw new IllegalArgumentException("slot_id уже существует в категории"); }
    private static int parseInt(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Ожидается целое число"); } }
    private static int parseIndex(String value, int size) { int index = parseInt(value); if (index < 0 || index >= size) throw new IllegalArgumentException("Индекс команды вне диапазона"); return index; }

    private void sendDraft(ServerPlayer player, EditorSession session) {
        var limits = config.serverConfig().limits;
        NetworkHandler.sendEditor(player, new EditorDto(session.id, session.baseRevision,
                JsonCodec.copy(session.draft, ShopConfig.class), session.mode), limits.max_packet_payload_bytes,
                limits.max_transfer_bytes, limits.max_chunks);
    }
    private void result(ServerPlayer player, UUID session, boolean success, String message) {
        player.sendSystemMessage(Component.literal(message));
        NetworkHandler.send(player, new ServerEventS2C(ServerEventS2C.Type.EDITOR_RESULT, session,
                config.snapshot().revision(), -1, success ? PurchaseResultCode.SUCCESS : PurchaseResultCode.INTERNAL_ERROR, message));
    }
    private boolean bounded(EditorActionC2S request) {
        int id = config.serverConfig().limits.max_id_length;
        return request.categoryId().length() <= id && request.slotId().length() <= id
                && request.secondary().length() <= id && request.field().length() <= 128
                && request.value().length() <= (request.action() == EditorActionC2S.Action.SAVE_FIELDS
                || request.action() == EditorActionC2S.Action.CHECK_FIELDS ? 65_535 : 4096);
    }
    private static String formatIssues(List<ValidationIssue> issues) {
        return issues.stream().limit(8).map(ValidationIssue::toString).reduce((a, b) -> a + "\n" + b).orElse("Неизвестная ошибка");
    }
    private long expiresAt() { return clock.millis() + config.serverConfig().editor_timeout_seconds * 1000L; }
    public void close(ServerPlayer player, UUID id) {
        EditorSession session = bySession.get(id);
        if (session != null && session.owner.equals(player.getUUID())) { bySession.remove(id, session); byOwner.remove(player.getUUID(), id); }
    }
    public void removePlayer(UUID player) { UUID id = byOwner.remove(player); if (id != null) bySession.remove(id); rateLimiter.remove(player); }
    public void prune() { long now = clock.millis(); bySession.entrySet().removeIf(e -> { if (e.getValue().expiresAtMillis >= now) return false; byOwner.remove(e.getValue().owner, e.getKey()); rateLimiter.remove(e.getValue().owner); return true; }); rateLimiter.prune(); }
    public boolean hasSession(UUID player, UUID session) { EditorSession value = bySession.get(session); return value != null && value.owner.equals(player) && value.expiresAtMillis >= clock.millis(); }

    private static final class EditorSession {
        final UUID id; final UUID owner; final EditorMode mode; volatile long baseRevision; volatile ShopConfig draft; volatile long expiresAtMillis;
        EditorSession(UUID id, UUID owner, long baseRevision, ShopConfig draft, long expiresAtMillis, EditorMode mode) {
            this.id = id; this.owner = owner; this.baseRevision = baseRevision; this.draft = draft; this.expiresAtMillis = expiresAtMillis;
            this.mode = mode == null ? EditorMode.STANDARD : mode;
        }
    }
    @FunctionalInterface public interface StoreChanged { void changed(long revision); }
}
