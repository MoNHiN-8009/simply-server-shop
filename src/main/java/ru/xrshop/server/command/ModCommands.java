package ru.xrshop.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import ru.xrshop.XRShopMod;
import ru.xrshop.server.ServerRuntime;
import ru.xrshop.common.dto.EditorMode;
import ru.xrshop.server.config.ShopSnapshot;
import ru.xrshop.server.db.SqliteDatabase;
import ru.xrshop.server.security.ModPermissions;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class ModCommands {
    private static final List<String> REVIEW_REASONS = List.of(
            "Товар не был выдан",
            "Ошибка выполнения команды",
            "Частичное выполнение покупки",
            "Возврат по решению администратора",
            "Проверено и решено администратором");

    private ModCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("ms")
                .executes(ctx -> playerAction(ctx, ServerRuntime::openStore))
                .then(Commands.literal("open").executes(ctx -> playerAction(ctx, ServerRuntime::openStore)))
                .then(Commands.literal("balance").executes(ctx -> playerAction(ctx, ServerRuntime::sendBalance)))
                .then(Commands.literal("buy")
                        .then(Commands.literal("category")
                                .then(Commands.argument("category_id", StringArgumentType.word())
                                        .suggests(ModCommands::suggestCategories)
                                        .then(Commands.literal("slot")
                                                .then(Commands.argument("slot_id", StringArgumentType.word())
                                                        .suggests(ModCommands::suggestProducts)
                                                        .executes(ModCommands::buy))))))
                .then(adminTree());
        dispatcher.register(root);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> adminTree() {
        return Commands.literal("admin").requires(ModCommands::allowedAnyAdmin)
                .then(Commands.literal("editor").requires(source -> allowedAdmin(source, ModPermissions.EDITOR))
                        .executes(ctx -> playerAction(ctx, (runtime, player) -> runtime.editor().open(player, EditorMode.STANDARD))))
                .then(Commands.literal("editor_extension").requires(source -> allowedAdmin(source, ModPermissions.EDITOR))
                        .executes(ctx -> playerAction(ctx, (runtime, player) -> runtime.editor().open(player, EditorMode.EXTENDED))))
                .then(Commands.literal("reload").requires(source -> allowedAdmin(source, ModPermissions.RELOAD)).executes(ModCommands::reload))
                .then(Commands.literal("validate").requires(source -> allowedAdmin(source, ModPermissions.VALIDATE)).executes(ModCommands::validate))
                .then(Commands.literal("backup").requires(source -> allowedAdmin(source, ModPermissions.BACKUP)).executes(ModCommands::backup))
                .then(Commands.literal("xr").requires(source -> allowedAdmin(source, ModPermissions.CURRENCY))
                        .then(Commands.literal("get").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> currency(ctx, "get"))))
                        .then(Commands.literal("add").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1)).executes(ctx -> currency(ctx, "add"))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(ctx -> currency(ctx, "add"))))))
                        .then(Commands.literal("remove").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1)).executes(ctx -> currency(ctx, "remove"))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(ctx -> currency(ctx, "remove"))))))
                        .then(Commands.literal("set").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(0)).executes(ctx -> currency(ctx, "set"))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(ctx -> currency(ctx, "set")))))))
                .then(Commands.literal("history").requires(source -> allowedAdmin(source, ModPermissions.HISTORY))
                        .executes(ctx -> history(ctx, null)).then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> history(ctx, EntityArgument.getPlayer(ctx, "player").getUUID()))))
                .then(Commands.literal("review").requires(source -> allowedAdmin(source, ModPermissions.REVIEW))
                        .then(Commands.literal("list").executes(ModCommands::reviewList))
                        .then(Commands.literal("show").then(Commands.argument("transaction_id", StringArgumentType.word())
                                .suggests(ModCommands::suggestRecentTransactions).executes(ModCommands::reviewShow)))
                        .then(Commands.literal("refund").then(Commands.argument("transaction_id", StringArgumentType.word())
                                .suggests(ModCommands::suggestReviewTransactions)
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .suggests(ModCommands::suggestReviewReasons).executes(ctx -> reviewAction(ctx, true)))))
                        .then(Commands.literal("resolve").then(Commands.argument("transaction_id", StringArgumentType.word())
                                .suggests(ModCommands::suggestReviewTransactions)
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .suggests(ModCommands::suggestReviewReasons).executes(ctx -> reviewAction(ctx, false))))));
    }

    private static int playerAction(CommandContext<CommandSourceStack> ctx, BiConsumer<ServerRuntime, ServerPlayer> action) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            return runtime(ctx, rt -> action.accept(rt, player)) ? 1 : 0;
        } catch (Exception ex) { ctx.getSource().sendFailure(Component.literal("Команда доступна только игроку.")); return 0; }
    }
    private static int buy(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            return runtime(ctx, rt -> rt.purchases().buy(player, StringArgumentType.getString(ctx, "category_id"), StringArgumentType.getString(ctx, "slot_id"))) ? 1 : 0;
        } catch (Exception ex) { ctx.getSource().sendFailure(Component.literal("Команда покупки доступна только игроку.")); return 0; }
    }
    private static int reload(CommandContext<CommandSourceStack> ctx) {
        return runtime(ctx, rt -> rt.config().reloadAsync().whenComplete((result, error) -> rt.server().execute(() -> {
            if (error != null || !result.success()) ctx.getSource().sendFailure(Component.literal("Конфигурация отклонена: " + (error != null ? error.getMessage() : result.issues())));
            else {
                ctx.getSource().sendSuccess(() -> Component.literal("Конфигурация загружена, revision=" + result.revision()), true);
                rt.notifyStoreChanged(result.revision());
                audit(rt, ctx, "CONFIG_RELOAD", "shop.json", "revision=" + result.revision());
            }
        }))) ? 1 : 0;
    }
    private static int validate(CommandContext<CommandSourceStack> ctx) {
        return runtime(ctx, rt -> {
            var issues = rt.config().validateDraft(rt.config().snapshot().copyConfig());
            if (issues.isEmpty()) ctx.getSource().sendSuccess(() -> Component.literal(rt.config().messages().validation_ok), false);
            else ctx.getSource().sendFailure(Component.literal(issues.toString()));
        }) ? 1 : 0;
    }
    private static int backup(CommandContext<CommandSourceStack> ctx) {
        return runtime(ctx, rt -> rt.config().backupAsync().whenComplete((path, error) -> rt.server().execute(() -> {
            if (error != null) ctx.getSource().sendFailure(Component.literal("Ошибка резервного копирования: " + error.getMessage()));
            else {
                ctx.getSource().sendSuccess(() -> Component.literal("Создана резервная копия: " + path.getFileName()), true);
                audit(rt, ctx, "CONFIG_BACKUP", path.getFileName().toString(), "");
            }
        }))) ? 1 : 0;
    }
    private static int currency(CommandContext<CommandSourceStack> ctx, String operation) {
        return runtime(ctx, rt -> {
            try {
                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                long amount = operation.equals("get") ? 0 : LongArgumentType.getLong(ctx, "amount");
                String reason = optionalString(ctx, "reason", "Изменение администратором");
                String actor = ctx.getSource().getTextName();
                var future = switch (operation) {
                    case "get" -> rt.currency().balance(target.getUUID()).thenApply(v -> new SqliteDatabase.CurrencyChange(v, false));
                    case "add" -> rt.currency().add(target.getUUID(), amount, reason, actor);
                    case "remove" -> rt.currency().remove(target.getUUID(), amount, reason, actor);
                    case "set" -> rt.currency().set(target.getUUID(), amount, reason, actor);
                    default -> throw new IllegalArgumentException(operation);
                };
                future.whenComplete((change, error) -> rt.server().execute(() -> {
                    if (error != null) ctx.getSource().sendFailure(Component.literal("Операция XR отклонена: " + rootMessage(error)));
                    else {
                        ctx.getSource().sendSuccess(() -> Component.literal(target.getGameProfile().getName() + ": " + change.balance() + " XR"), true);
                        if (!operation.equals("get")) audit(rt, ctx, "XR_" + operation.toUpperCase(), target.getUUID().toString(), "balance=" + change.balance() + "; reason=" + reason);
                    }
                }));
            } catch (Exception ex) { ctx.getSource().sendFailure(Component.literal(ex.getMessage())); }
        }) ? 1 : 0;
    }
    private static int history(CommandContext<CommandSourceStack> ctx, UUID player) {
        return runtime(ctx, rt -> rt.databaseExecutor().submit(() -> rt.database().history(player, 20)).whenComplete((rows, error) -> rt.server().execute(() -> {
            if (error != null) ctx.getSource().sendFailure(Component.literal("Ошибка истории: " + rootMessage(error)));
            else if (rows.isEmpty()) ctx.getSource().sendSuccess(() -> Component.literal("История пуста."), false);
            else rows.forEach(row -> ctx.getSource().sendSuccess(() -> Component.literal(row.transactionId() + " " + row.playerUuid() + " " + row.categoryId() + "/" + row.slotId() + " " + row.price() + " XR " + row.state()), false));
        }))) ? 1 : 0;
    }
    private static int reviewList(CommandContext<CommandSourceStack> ctx) {
        return runtime(ctx, rt -> rt.databaseExecutor().submit(() -> rt.database().reviews(50)).whenComplete((rows, error) -> rt.server().execute(() -> {
            if (error != null) ctx.getSource().sendFailure(Component.literal("Ошибка списка: " + rootMessage(error)));
            else if (rows.isEmpty()) ctx.getSource().sendSuccess(() -> Component.literal("Транзакций REVIEW_REQUIRED нет."), false);
            else rows.forEach(row -> {
                Component line = Component.empty().append(clickableTransaction(row.transactionId(),
                                "/ms admin review show " + row.transactionId(), "Нажмите, чтобы подставить команду просмотра"))
                        .append(Component.literal(" " + row.playerUuid() + " " + row.categoryId() + "/" + row.slotId()));
                ctx.getSource().sendSuccess(() -> line, false);
            });
        }))) ? 1 : 0;
    }
    private static int reviewShow(CommandContext<CommandSourceStack> ctx) {
        UUID id; try { id = UUID.fromString(StringArgumentType.getString(ctx, "transaction_id")); } catch (IllegalArgumentException ex) { ctx.getSource().sendFailure(Component.literal("Некорректный transaction_id")); return 0; }
        return runtime(ctx, rt -> rt.databaseExecutor().submit(() -> new ReviewDetails(rt.database().purchase(id), rt.database().commandResults(id))).whenComplete((details, error) -> rt.server().execute(() -> {
            if (error != null) ctx.getSource().sendFailure(Component.literal("Ошибка: " + rootMessage(error)));
            else {
                ctx.getSource().sendSuccess(() -> Component.literal(details.purchase().map(Object::toString).orElse("Транзакция не найдена")), false);
                details.commands().forEach(command -> ctx.getSource().sendSuccess(() -> Component.literal("#" + command.index() + " success=" + command.success() + " command=" + command.command() + " result=" + command.result()), false));
                details.purchase().filter(purchase -> purchase.state() == ru.xrshop.server.db.TransactionState.REVIEW_REQUIRED)
                        .ifPresent(purchase -> ctx.getSource().sendSuccess(() -> reviewActions(purchase.transactionId()), false));
            }
        }))) ? 1 : 0;
    }
    private static int reviewAction(CommandContext<CommandSourceStack> ctx, boolean refund) {
        UUID id; try { id = UUID.fromString(StringArgumentType.getString(ctx, "transaction_id")); } catch (IllegalArgumentException ex) { ctx.getSource().sendFailure(Component.literal("Некорректный transaction_id")); return 0; }
        String reason = StringArgumentType.getString(ctx, "reason");
        return runtime(ctx, rt -> {
            var future = refund ? rt.databaseExecutor().submit(() -> rt.database().refund(id, reason)).thenApply(Object.class::cast)
                    : rt.databaseExecutor().run(() -> rt.database().resolve(id, reason)).thenApply(Object.class::cast);
            future.whenComplete((ignored, error) -> rt.server().execute(() -> {
                if (error != null) ctx.getSource().sendFailure(Component.literal("Операция отклонена: " + rootMessage(error)));
                else {
                    ctx.getSource().sendSuccess(() -> Component.literal(refund ? "XR возвращены." : "Транзакция отмечена решённой."), true);
                    audit(rt, ctx, refund ? "REVIEW_REFUND" : "REVIEW_RESOLVE", id.toString(), reason);
                }
            }));
        }) ? 1 : 0;
    }

    private static CompletableFuture<Suggestions> suggestCategories(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        XRShopMod.runtime().ifPresent(rt -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer player)
                rt.config().snapshot().orderedCategories().stream().filter(ShopSnapshot.CategoryEntry::enabled)
                        .filter(c -> ModPermissions.hasNamed(player, c.permission())).forEach(c -> builder.suggest(c.id()));
        });
        return builder.buildFuture();
    }
    private static CompletableFuture<Suggestions> suggestProducts(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        XRShopMod.runtime().ifPresent(rt -> {
            if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) return;
            String categoryId;
            try { categoryId = StringArgumentType.getString(ctx, "category_id"); } catch (IllegalArgumentException ex) { return; }
            rt.config().snapshot().category(categoryId).ifPresent(category -> category.orderedProducts().stream()
                    .filter(ShopSnapshot.ProductEntry::enabled).filter(p -> ModPermissions.hasNamed(player, p.permission()))
                    .forEach(p -> builder.suggest(p.id())));
        });
        return builder.buildFuture();
    }
    private static CompletableFuture<Suggestions> suggestRecentTransactions(CommandContext<CommandSourceStack> ctx,
                                                                              SuggestionsBuilder builder) {
        var runtime = XRShopMod.runtime();
        if (runtime.isEmpty()) return builder.buildFuture();
        ServerRuntime rt = runtime.get();
        return rt.databaseExecutor().submit(() -> rt.database().history(null, 50)).handle((rows, error) -> {
            if (error == null) rows.forEach(row -> suggestTransaction(builder, row));
            return builder.build();
        });
    }
    private static CompletableFuture<Suggestions> suggestReviewTransactions(CommandContext<CommandSourceStack> ctx,
                                                                              SuggestionsBuilder builder) {
        var runtime = XRShopMod.runtime();
        if (runtime.isEmpty()) return builder.buildFuture();
        ServerRuntime rt = runtime.get();
        return rt.databaseExecutor().submit(() -> rt.database().reviews(50)).handle((rows, error) -> {
            if (error == null) rows.forEach(row -> suggestTransaction(builder, row));
            return builder.build();
        });
    }
    private static CompletableFuture<Suggestions> suggestReviewReasons(CommandContext<CommandSourceStack> ctx,
                                                                         SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        REVIEW_REASONS.stream().filter(reason -> reason.toLowerCase(Locale.ROOT).startsWith(remaining))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }
    private static void suggestTransaction(SuggestionsBuilder builder, SqliteDatabase.PurchaseRecord row) {
        String id = row.transactionId().toString();
        if (id.startsWith(builder.getRemainingLowerCase()))
            builder.suggest(id, Component.literal(row.categoryId() + "/" + row.slotId() + " — " + row.state()));
    }
    private static Component clickableTransaction(UUID id, String command, String tooltip) {
        return Component.literal(id.toString()).withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(tooltip))));
    }
    private static Component reviewActions(UUID id) {
        Component refund = Component.literal("[Вернуть XR]").withStyle(style -> style.withColor(ChatFormatting.GOLD)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                        "/ms admin review refund " + id + " "))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Подставить команду возврата"))));
        Component resolve = Component.literal("[Решить]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                        "/ms admin review resolve " + id + " "))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Подставить команду решения"))));
        return Component.empty().append(refund).append(" ").append(resolve);
    }
    private static boolean allowedAnyAdmin(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player
                ? ModPermissions.hasAnyAdmin(player) : source.hasPermission(4);
    }
    private static boolean allowedAdmin(CommandSourceStack source,
                                        net.minecraftforge.server.permission.nodes.PermissionNode<Boolean> operation) {
        return source.getEntity() instanceof ServerPlayer player
                ? ModPermissions.hasAdmin(player, operation) : source.hasPermission(4);
    }
    private static boolean runtime(CommandContext<CommandSourceStack> ctx, Consumer<ServerRuntime> action) {
        var runtime = XRShopMod.runtime();
        if (runtime.isEmpty()) { ctx.getSource().sendFailure(Component.literal("XR Shop ещё не запущен.")); return false; }
        action.accept(runtime.get()); return true;
    }
    private static String optionalString(CommandContext<CommandSourceStack> ctx, String name, String fallback) {
        try { String value = StringArgumentType.getString(ctx, name); return value.isBlank() ? fallback : value; }
        catch (IllegalArgumentException ex) { return fallback; }
    }
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
    private static void audit(ServerRuntime runtime, CommandContext<CommandSourceStack> ctx,
                              String action, String target, String detail) {
        UUID actor = ctx.getSource().getEntity() instanceof ServerPlayer player ? player.getUUID() : new UUID(0, 0);
        runtime.databaseExecutor().run(() -> runtime.database().audit(actor, action, target, detail));
    }
    private record ReviewDetails(java.util.Optional<SqliteDatabase.PurchaseRecord> purchase,
                                 java.util.List<SqliteDatabase.CommandResultRecord> commands) {}
}
