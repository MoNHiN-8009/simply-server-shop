package ru.xrshop.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import ru.xrshop.client.editor.CommandBuilders;
import ru.xrshop.client.editor.ColorPickerScreen;
import ru.xrshop.client.editor.EditorFieldSpec;
import ru.xrshop.client.editor.EditorSchemas;
import ru.xrshop.client.editor.EditorValueCodec;
import ru.xrshop.client.editor.ItemPickerScreen;
import ru.xrshop.client.editor.ResourcePickerScreen;
import ru.xrshop.client.editor.SoundPickerScreen;
import ru.xrshop.common.config.ShopConfig;
import ru.xrshop.common.dto.EditorDto;
import ru.xrshop.common.dto.EditorFieldEdit;
import ru.xrshop.common.dto.PurchaseResultCode;
import ru.xrshop.common.network.EditorActionC2S;
import ru.xrshop.common.network.NetworkHandler;
import ru.xrshop.common.network.ServerEventS2C;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Friendly data-driven editor. The server remains authoritative for every operation. */
public final class EditorScreen extends Screen implements EditorSessionScreen {
    private enum View { CATEGORY, PRODUCT, UI, STYLE }
    private enum BuilderType { GIVE, EFFECT }

    private EditorDto dto;
    private View view = View.CATEGORY;
    private String selectedCategory = "";
    private String selectedProduct = "";
    private String selectedGroup = "";
    private int formPage;
    private int listScroll;
    private int effectSuggestionScroll;
    private int selectedCommand = -1;
    private boolean builderExpanded;
    private BuilderType builderType = BuilderType.GIVE;
    private String commandItem = "minecraft:stone";
    private String commandDraft = "";
    private boolean hideParticles;
    private boolean dirty;
    private boolean closed;
    private boolean childOpen;
    private boolean saveInFlight;
    private long confirmCloseUntil;
    private String pendingDelete = "";
    private long pendingDeleteUntil;
    private String status = "Выберите категорию или товар слева.";
    private int statusColor = 0xFFAAAAAA;
    private EditorActionC2S.Action lastAction;
    private String pendingCheckedCommand = "";

    private EditBox newIdBox;
    private EditBox secondaryBox;
    private EditBox commandBox;
    private EditBox targetBox;
    private EditBox countBox;
    private EditBox nbtBox;
    private EditBox effectBox;
    private EditBox durationBox;
    private EditBox levelBox;

    private int leftWidth;
    private int formX;
    private int formWidth;
    private int listTop;
    private int listBottom;
    private int commandListTop;
    private int builderBottom;
    private int effectSuggestionTop;
    private int effectSuggestionBottom;
    private int effectSuggestionMaximum;
    private int effectScrollbarX;
    private int effectScrollbarThumbY;
    private int effectScrollbarThumbHeight;
    private boolean draggingEffectScrollbar;
    private int effectScrollbarDragOffset;
    private final Map<FieldKey, String> pendingValues = new HashMap<>();
    private final Set<FieldKey> changedFields = new HashSet<>();
    private Map<FieldKey, String> submittedSaveValues = Map.of();
    private final Map<String, String> builderValues = new HashMap<>();
    private final List<FieldRow> fieldRows = new ArrayList<>();
    private final List<NavHit> navHits = new ArrayList<>();
    private final List<CommandHit> commandHits = new ArrayList<>();
    private final List<SuggestionHit> suggestionHits = new ArrayList<>();
    private final List<HelpHit> helpHits = new ArrayList<>();
    private final List<GroupButton> groupButtons = new ArrayList<>();

    public EditorScreen(EditorDto dto) {
        super(Component.literal("Редактор XR Shop"));
        this.dto = dto;
        ensureSelection();
    }

    @Override public UUID sessionId() { return dto.editorSessionId(); }

    @Override protected void init() { buildScreen(); }

    private void buildScreen() {
        fieldRows.clear(); helpHits.clear(); groupButtons.clear(); commandHits.clear(); suggestionHits.clear();
        int minimumLeft = width < 500 ? 128 : 178;
        leftWidth = Math.min(240, Math.max(minimumLeft, width / 4));
        formX = leftWidth + 18;
        formWidth = Math.max(210, width - formX - 10);
        listTop = 108;
        listBottom = Math.max(listTop + 30, height - 47);
        buildNavigationWidgets();
        buildBottomWidgets();
        buildFormWidgets();
    }

    private void rebuild() {
        clearWidgets();
        buildScreen();
    }

    private void buildNavigationWidgets() {
        int x = 8;
        newIdBox = new EditBox(font, x, 28, leftWidth - 16, 20, Component.literal("Новый ID"));
        newIdBox.setHint(Component.literal("Новый ID: latin_lowercase"));
        newIdBox.setMaxLength(128);
        addRenderableWidget(newIdBox);
        int half = (leftWidth - 19) / 2;
        addRenderableWidget(Button.builder(Component.literal("+ Категория"), b -> createCategory())
                .bounds(x, 52, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ Товар"), b -> createProduct())
                .bounds(x + half + 3, 52, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Интерфейс"), b -> selectView(View.UI))
                .bounds(x, 76, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Общий стиль"), b -> selectView(View.STYLE))
                .bounds(x + half + 3, 76, half, 20).build());
    }

    private void buildBottomWidgets() {
        int y = height - 25;
        int x = 8;
        addRenderableWidget(Button.builder(Component.literal("Проверить"), b -> submitFields(false))
                .bounds(x, y, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Сохранить"), b -> submitFields(true))
                .bounds(x + 90, y, 86, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Отменить"), b -> cancelAndClose())
                .bounds(x + 180, y, Math.max(70, leftWidth - 188), 20).build());
    }

    private void buildFormWidgets() {
        Object root = currentRoot();
        List<EditorFieldSpec> schema = currentSchema();
        int y = 28;
        if (view == View.CATEGORY || view == View.PRODUCT) y = buildEntityActions(y);
        List<String> groups = new ArrayList<>(EditorSchemas.groups(schema));
        if (view == View.PRODUCT) groups.add("Команды");
        if (selectedGroup.isBlank() || !groups.contains(selectedGroup)) selectedGroup = groups.isEmpty() ? "" : groups.get(0);
        y = buildGroupButtons(groups, y);
        if (view == View.PRODUCT && "Команды".equals(selectedGroup)) {
            buildCommandEditor(y + 3);
            return;
        }
        if (root == null || schema.isEmpty()) return;
        List<EditorFieldSpec> groupFields = schema.stream().filter(s -> s.group().equals(selectedGroup)).toList();
        int rowsPerPage = Math.max(3, (height - y - 58) / 29);
        int pages = Math.max(1, (groupFields.size() + rowsPerPage - 1) / rowsPerPage);
        formPage = Math.max(0, Math.min(formPage, pages - 1));
        int from = formPage * rowsPerPage;
        int to = Math.min(groupFields.size(), from + rowsPerPage);
        for (int index = from; index < to; index++) buildFieldRow(root, groupFields.get(index), y + (index - from) * 29);
        if (pages > 1) {
            int pageY = Math.min(height - 50, y + rowsPerPage * 29 + 2);
            Button prev = Button.builder(Component.literal("←"), b -> { formPage--; rebuild(); }).bounds(formX, pageY, 28, 20).build();
            prev.active = formPage > 0; addRenderableWidget(prev);
            Button next = Button.builder(Component.literal("→"), b -> { formPage++; rebuild(); }).bounds(formX + 92, pageY, 28, 20).build();
            next.active = formPage + 1 < pages; addRenderableWidget(next);
            addRenderableWidget(Button.builder(Component.literal((formPage + 1) + " / " + pages), b -> {})
                    .bounds(formX + 31, pageY, 58, 20).build());
        }
    }

    private int buildEntityActions(int y) {
        boolean compact = formWidth < 500;
        secondaryBox = new EditBox(font, formX, y, compact ? formWidth : Math.max(90, formWidth - 285), 20, Component.literal("Новый/целевой ID"));
        secondaryBox.setHint(Component.literal(view == View.PRODUCT ? "ID копии или целевой категории" : "ID копии"));
        secondaryBox.setMaxLength(128); addRenderableWidget(secondaryBox);
        if (compact) {
            int buttons = view == View.PRODUCT ? 3 : 2;
            int gap = 3;
            int w = Math.max(42, (formWidth - gap * (buttons - 1)) / buttons);
            int by = y + 24;
            addRenderableWidget(Button.builder(Component.literal("Копия"), b -> copySelected()).bounds(formX, by, w, 20).build());
            if (view == View.PRODUCT) addRenderableWidget(Button.builder(Component.literal("Перенести"), b -> moveProduct()).bounds(formX + w + gap, by, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Удалить"), b -> deleteSelected()).bounds(formX + (w + gap) * (buttons - 1), by, w, 20).build());
            return y + 50;
        } else {
            int bx = formX + Math.max(94, formWidth - 281);
            addRenderableWidget(Button.builder(Component.literal("Копировать"), b -> copySelected()).bounds(bx, y, 88, 20).build());
            if (view == View.PRODUCT) addRenderableWidget(Button.builder(Component.literal("Перенести"), b -> moveProduct()).bounds(bx + 91, y, 82, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Удалить"), b -> deleteSelected()).bounds(bx + (view == View.PRODUCT ? 176 : 91), y, 80, 20).build());
            return y + 26;
        }
    }

    private int buildGroupButtons(List<String> groups, int y) {
        int x = formX;
        int maxX = formX + formWidth;
        for (String group : groups) {
            int w = Math.max(58, Math.min(112, font.width(group) + 16));
            if (x + w > maxX) { x = formX; y += 23; }
            Button button = Button.builder(Component.literal(group), b -> { selectedGroup = group; formPage = 0; rebuild(); })
                    .bounds(x, y, w, 20).build();
            button.active = !group.equals(selectedGroup);
            addRenderableWidget(button);
            groupButtons.add(new GroupButton(group, x, y, w));
            x += w + 3;
        }
        return y + 23;
    }

    private void buildFieldRow(Object root, EditorFieldSpec spec, int y) {
        int minimumLabel = formWidth < 260 ? 65 : 105;
        int labelWidth = Math.min(175, Math.max(minimumLabel, formWidth / 3));
        int inputX = formX + labelWidth;
        int buttonsWidth = 24;
        int inputWidth = Math.max(35, formWidth - labelWidth - buttonsWidth - 4);
        FieldKey key = pendingKey(spec.path());
        String initial = pendingValues.computeIfAbsent(key, ignored -> EditorValueCodec.display(root, spec));
        if (spec.kind() == EditorFieldSpec.Kind.BOOLEAN || spec.kind() == EditorFieldSpec.Kind.TRI_STATE || spec.kind() == EditorFieldSpec.Kind.ENUM) {
            List<String> values = spec.kind() == EditorFieldSpec.Kind.BOOLEAN ? List.of("Да", "Нет")
                    : spec.kind() == EditorFieldSpec.Kind.TRI_STATE ? List.of("Наследовать", "Да", "Нет") : spec.options();
            Button cycle = Button.builder(Component.literal(initial), b -> {
                String current = pendingValues.getOrDefault(key, initial);
                int next = (Math.max(0, values.indexOf(current)) + 1) % values.size();
                markChanged(key, values.get(next)); b.setMessage(Component.literal(values.get(next)));
            }).bounds(inputX, y, inputWidth, 20).build();
            addRenderableWidget(cycle);
        } else if (spec.kind() == EditorFieldSpec.Kind.ITEM) {
            Button item = Button.builder(Component.literal(trim(initial, inputWidth - 10)), b -> openItemPicker(id -> markChanged(key, id)))
                    .bounds(inputX, y, inputWidth, 20).build();
            addRenderableWidget(item);
        } else if (spec.kind() == EditorFieldSpec.Kind.COLOR) {
            Button color = Button.builder(Component.literal(initial.isBlank() ? "Не задан — выбрать цвет" : initial),
                    b -> openColorPicker(initial, value -> markChanged(key, value))).bounds(inputX, y, inputWidth, 20).build();
            addRenderableWidget(color);
        } else if (spec.kind() == EditorFieldSpec.Kind.SOUND) {
            Button sound = Button.builder(Component.literal(trim(initial.isBlank() ? "Без звука — выбрать" : initial, inputWidth - 8)),
                    b -> openSoundPicker(root, spec, key)).bounds(inputX, y, inputWidth, 20).build();
            addRenderableWidget(sound);
        } else if (spec.kind() == EditorFieldSpec.Kind.TEXTURE) {
            Button texture = Button.builder(Component.literal(trim(textureLabel(initial), inputWidth - 8)),
                    b -> openTexturePicker(initial, value -> markChanged(key, value))).bounds(inputX, y, inputWidth, 20).build();
            addRenderableWidget(texture);
        } else if (spec.kind() == EditorFieldSpec.Kind.PATH) {
            Button path = Button.builder(Component.literal(trim(initial.isBlank() ? "Без текстуры — выбрать" : initial, inputWidth - 8)),
                    b -> openResourcePicker(initial, value -> markChanged(key, value))).bounds(inputX, y, inputWidth, 20).build();
            addRenderableWidget(path);
        } else {
            EditBox edit = new EditBox(font, inputX, y, inputWidth, 20, Component.literal(spec.label()));
            edit.setMaxLength(spec.kind() == EditorFieldSpec.Kind.TEXT_LIST || spec.kind() == EditorFieldSpec.Kind.NBT ? 2048 : 512);
            edit.setValue(initial); edit.setResponder(value -> markChanged(key, value)); addRenderableWidget(edit);
        }
        int helpX = formX + formWidth - 20;
        addRenderableWidget(Button.builder(Component.literal("?"), b -> {}).bounds(helpX, y, 20, 20).build());
        helpHits.add(new HelpHit(helpX, y, 20, 20, detailedHelp(spec)));
        fieldRows.add(new FieldRow(spec.label(), formX, y, labelWidth - 5));
    }

    private void buildCommandEditor(int y) {
        boolean compact = formWidth < 280;
        commandBox = new EditBox(font, formX, y, compact ? formWidth : Math.max(80, formWidth - 132), 20, Component.literal("Команда"));
        commandBox.setHint(Component.literal("Команда без / или соберите её справа"));
        commandBox.setMaxLength(4096); commandBox.setValue(commandDraft);
        commandBox.setResponder(value -> commandDraft = value); addRenderableWidget(commandBox);
        int builderX = compact ? formX : formX + formWidth - 128;
        int builderY = compact ? y + 24 : y;
        addRenderableWidget(Button.builder(Component.literal("Конструктор"), b -> { builderExpanded = !builderExpanded; rebuild(); })
                .bounds(builderX, builderY, Math.min(104, Math.max(70, formWidth - 24)), 20).build());
        int helpX = compact ? formX + formWidth - 20 : builderX + 108;
        addRenderableWidget(Button.builder(Component.literal("?"), b -> {}).bounds(helpX, builderY, 20, 20).build());
        helpHits.add(new HelpHit(helpX, builderY, 20, 20,
                "Что вводить: любую серверную Minecraft-команду без начального /. Можно использовать ${player}, ${uuid}, ${price}, ${category_id}, ${slot_id} и ${transaction_id}. Фигурные скобки JSON и NBT не считаются переменными.\n\n"
                        + "Примеры: give ${player} minecraft:diamond 16; tellraw ${player} {\"text\":\"Готово\",\"color\":\"green\"}. Конструктор справа собирает give и effect по отдельным полям и отправляет команду на серверную проверку."));
        y += compact ? 48 : 24;
        int actionWidth = compact ? Math.max(40, (formWidth - 6) / 3) : 86;
        addRenderableWidget(Button.builder(Component.literal(compact ? "+" : "+ Добавить"), b -> addCommand()).bounds(formX, y, actionWidth, 20).build());
        Button replace = Button.builder(Component.literal(compact ? "Зам." : "Заменить"), b -> replaceCommand()).bounds(formX + actionWidth + 3, y, compact ? actionWidth : 82, 20).build();
        replace.active = selectedCommand >= 0; addRenderableWidget(replace);
        Button remove = Button.builder(Component.literal(compact ? "Удал." : "Удалить"), b -> removeCommand()).bounds(formX + (actionWidth + 3) * 2, y, compact ? actionWidth : 76, 20).build();
        remove.active = selectedCommand >= 0; addRenderableWidget(remove);
        if (builderExpanded) {
            buildCommandBuilder(y + 26);
            commandListTop = builderBottom + 4;
        } else commandListTop = y + 26;
    }

    private void buildCommandBuilder(int y) {
        Button give = Button.builder(Component.literal("Выдать предмет"), b -> { builderType = BuilderType.GIVE; rebuild(); })
                .bounds(formX, y, 116, 20).build();
        give.active = builderType != BuilderType.GIVE; addRenderableWidget(give);
        Button effect = Button.builder(Component.literal("Наложить эффект"), b -> { builderType = BuilderType.EFFECT; rebuild(); })
                .bounds(formX + 120, y, 124, 20).build();
        effect.active = builderType != BuilderType.EFFECT; addRenderableWidget(effect);
        addRenderableWidget(Button.builder(Component.literal("?"), b -> {}).bounds(formX + 248, y, 20, 20).build());
        helpHits.add(new HelpHit(formX + 248, y, 20, 20, builderType == BuilderType.GIVE
                ? "Выдать предмет: «Кому» обычно оставляют ${player}; предмет выбирают из реестра; количество — целое число от 1. NBT необязателен, пример: {CustomModelData:1}. Результат: give ${player} minecraft:diamond 16."
                : "Наложить эффект: «Кому» обычно ${player}; эффект выбирается автодополнением; секунды — длительность (30 = полминуты); уровень 1 — первый уровень. Пример: effect give ${player} minecraft:absorption 30."));
        y += 24;
        if (builderType == BuilderType.GIVE) buildGiveBuilder(y); else buildEffectBuilder(y);
    }

    private void buildGiveBuilder(int y) {
        if (formWidth < 360) {
            int half = Math.max(55, (formWidth - 4) / 2);
            targetBox = builderBox(formX, y, half, "Кому", "${player}");
            countBox = builderBox(formX + half + 4, y, Math.max(45, formWidth - half - 4), "Кол-во", "1");
            addRenderableWidget(Button.builder(Component.literal(trim(commandItem, formWidth - 10)), b -> openItemPicker(id -> commandItem = id))
                    .bounds(formX, y + 24, formWidth, 20).build());
            nbtBox = builderBox(formX, y + 48, formWidth, "NBT (необязательно)", "");
            addRenderableWidget(Button.builder(Component.literal("Проверить и вставить"), b -> checkBuiltGive())
                    .bounds(formX, y + 72, formWidth, 20).build());
            builderBottom = y + 94;
        } else {
            int col = Math.max(90, (formWidth - 12) / 3);
            targetBox = builderBox(formX, y, col, "Кому", "${player}");
            addRenderableWidget(Button.builder(Component.literal(trim(commandItem, col - 10)), b -> openItemPicker(id -> commandItem = id))
                    .bounds(formX + col + 4, y, col, 20).build());
            countBox = builderBox(formX + (col + 4) * 2, y, Math.max(50, formWidth - (col + 4) * 2), "Количество", "1");
            nbtBox = builderBox(formX, y + 24, Math.max(90, formWidth - 146), "NBT (необязательно)", "");
            addRenderableWidget(Button.builder(Component.literal("Проверить и вставить"), b -> checkBuiltGive())
                    .bounds(formX + formWidth - 142, y + 24, 142, 20).build());
            builderBottom = y + 46;
        }
    }

    private void buildEffectBuilder(int y) {
        int targetW = Math.max(80, formWidth / 4);
        targetBox = builderBox(formX, y, targetW, "Кому", "${player}");
        effectBox = builderBox(formX + targetW + 4, y, Math.max(100, formWidth - targetW - 4), "Эффект", "minecraft:absorption");
        effectBox.setResponder(value -> { builderValues.put("Эффект", value); effectSuggestionScroll = 0; });
        int col = Math.max(65, (formWidth - 150) / 3);
        durationBox = builderBox(formX, y + 24, col, "Секунды", "30");
        levelBox = builderBox(formX + col + 4, y + 24, col, "Уровень", "1");
        if (formWidth < 420) {
            Button particles = Button.builder(Component.literal(hideParticles ? "Частицы: скрыты" : "Частицы: видны"), b -> {
                hideParticles = !hideParticles;
                b.setMessage(Component.literal(hideParticles ? "Частицы: скрыты" : "Частицы: видны"));
            }).bounds(formX, y + 48, formWidth, 20).build();
            addRenderableWidget(particles);
            addRenderableWidget(Button.builder(Component.literal("Проверить и вставить"), b -> checkBuiltEffect())
                    .bounds(formX, y + 72, formWidth, 20).build());
            builderBottom = y + 94;
            return;
        }
        Button particles = Button.builder(Component.literal(hideParticles ? "Частицы: скрыты" : "Частицы: видны"), b -> {
            hideParticles = !hideParticles;
            b.setMessage(Component.literal(hideParticles ? "Частицы: скрыты" : "Частицы: видны"));
        }).bounds(formX + (col + 4) * 2, y + 24, Math.max(100, formWidth - (col + 4) * 2 - 146), 20).build();
        addRenderableWidget(particles);
        addRenderableWidget(Button.builder(Component.literal("Проверить и вставить"), b -> checkBuiltEffect())
                .bounds(formX + formWidth - 142, y + 24, 142, 20).build());
        builderBottom = y + 46;
    }

    private EditBox builderBox(int x, int y, int w, String hint, String value) {
        EditBox box = new EditBox(font, x, y, Math.max(40, w), 20, Component.literal(hint));
        box.setHint(Component.literal(hint)); box.setMaxLength(1024);
        box.setValue(builderValues.computeIfAbsent(hint, ignored -> value));
        box.setResponder(text -> builderValues.put(hint, text));
        addRenderableWidget(box); return box;
    }

    private void createCategory() {
        String id = newIdBox.getValue().trim();
        if (id.isEmpty()) { setError("Введите ID новой категории."); return; }
        selectedCategory = id; selectedProduct = ""; view = View.CATEGORY; selectedGroup = "";
        send(EditorActionC2S.Action.CREATE_CATEGORY, "", "", "");
    }

    private void createProduct() {
        if (selectedCategory.isEmpty()) { setError("Сначала выберите категорию."); return; }
        String id = newIdBox.getValue().trim();
        if (id.isEmpty()) { setError("Введите ID нового товара."); return; }
        selectedProduct = id; view = View.PRODUCT; selectedGroup = "Основное";
        send(EditorActionC2S.Action.CREATE_PRODUCT, "", "", "");
    }

    private void copySelected() {
        if (!requireSavedFields()) return;
        String target = secondaryBox == null ? "" : secondaryBox.getValue().trim();
        if (target.isEmpty()) { setError("Введите ID копии в верхнее поле."); return; }
        if (view == View.CATEGORY) send(EditorActionC2S.Action.COPY_CATEGORY, "", "", target);
        else if (view == View.PRODUCT) send(EditorActionC2S.Action.COPY_PRODUCT, "", "", target);
    }

    private void moveProduct() {
        if (!requireSavedFields()) return;
        String target = secondaryBox == null ? "" : secondaryBox.getValue().trim();
        if (target.isEmpty()) { setError("Введите ID целевой категории."); return; }
        send(EditorActionC2S.Action.MOVE_PRODUCT, "", "", target);
    }

    private void deleteSelected() {
        if (!requireSavedFields()) return;
        String key = view + ":" + selectedCategory + ":" + selectedProduct;
        if (!confirmDelete(key)) return;
        if (view == View.PRODUCT) send(EditorActionC2S.Action.DELETE_PRODUCT, "", "", "");
        else if (view == View.CATEGORY) send(EditorActionC2S.Action.DELETE_CATEGORY, "", "", "");
    }

    private void addCommand() {
        if (commandBox == null || commandBox.getValue().isBlank()) { setError("Введите или соберите команду."); return; }
        send(EditorActionC2S.Action.ADD_COMMAND, "", commandBox.getValue(), "");
    }

    private void replaceCommand() {
        if (selectedCommand < 0) { setError("Выберите команду в списке."); return; }
        send(EditorActionC2S.Action.SET_COMMAND, Integer.toString(selectedCommand), commandBox.getValue(), "");
    }

    private void removeCommand() {
        if (selectedCommand < 0 || !confirmDelete("command:" + selectedCommand)) return;
        send(EditorActionC2S.Action.REMOVE_COMMAND, Integer.toString(selectedCommand), "", "");
        selectedCommand = -1;
    }

    private void checkBuiltGive() {
        try {
            pendingCheckedCommand = CommandBuilders.give(targetBox.getValue(), commandItem, countBox.getValue(), nbtBox.getValue());
            send(EditorActionC2S.Action.CHECK_COMMAND, "", pendingCheckedCommand, "");
        } catch (IllegalArgumentException ex) { setError(ex.getMessage()); }
    }

    private void checkBuiltEffect() {
        try {
            pendingCheckedCommand = CommandBuilders.effect(targetBox.getValue(), effectBox.getValue(), durationBox.getValue(), levelBox.getValue(), hideParticles);
            send(EditorActionC2S.Action.CHECK_COMMAND, "", pendingCheckedCommand, "");
        } catch (IllegalArgumentException ex) { setError(ex.getMessage()); }
    }

    private void openItemPicker(java.util.function.Consumer<String> selected) {
        if (minecraft == null) return;
        childOpen = true;
        minecraft.setScreen(new ItemPickerScreen(this, selected));
    }

    private void openColorPicker(String initial, java.util.function.Consumer<String> selected) {
        if (minecraft == null) return;
        childOpen = true; minecraft.setScreen(new ColorPickerScreen(this, initial, selected));
    }

    private void openResourcePicker(String initial, java.util.function.Consumer<String> selected) {
        if (minecraft == null) return;
        childOpen = true; minecraft.setScreen(new ResourcePickerScreen(this, initial, selected));
    }

    private void openTexturePicker(String initialJson, java.util.function.Consumer<String> selected) {
        if (minecraft == null) return;
        ShopConfig.TextureDefinition initial;
        try { initial = ru.xrshop.common.config.JsonCodec.GSON.fromJson(initialJson, ShopConfig.TextureDefinition.class); }
        catch (RuntimeException ex) { initial = new ShopConfig.TextureDefinition(); }
        if (initial == null) initial = new ShopConfig.TextureDefinition();
        childOpen = true; minecraft.setScreen(new ResourcePickerScreen(this, initial,
                value -> selected.accept(ru.xrshop.common.config.JsonCodec.GSON.toJson(value))));
    }

    private static String textureLabel(String json) {
        try {
            ShopConfig.TextureDefinition texture = ru.xrshop.common.config.JsonCodec.GSON.fromJson(json, ShopConfig.TextureDefinition.class);
            if (texture == null || texture.path == null || texture.path.isBlank()) return "Без текстуры — настроить";
            return texture.path + " · " + texture.mode;
        } catch (RuntimeException ex) { return "Текстура — исправить настройку"; }
    }

    private void openSoundPicker(Object root, EditorFieldSpec soundSpec, FieldKey soundKey) {
        if (minecraft == null) return;
        String prefix = soundSpec.path().substring(0, soundSpec.path().length() - "sound".length());
        EditorFieldSpec volumeSpec = schema(view).stream().filter(spec -> spec.path().equals(prefix + "volume")).findFirst().orElseThrow();
        EditorFieldSpec pitchSpec = schema(view).stream().filter(spec -> spec.path().equals(prefix + "pitch")).findFirst().orElseThrow();
        FieldKey volumeKey = pendingKey(volumeSpec.path()), pitchKey = pendingKey(pitchSpec.path());
        String volumeText = pendingValues.computeIfAbsent(volumeKey, ignored -> EditorValueCodec.display(root, volumeSpec));
        String pitchText = pendingValues.computeIfAbsent(pitchKey, ignored -> EditorValueCodec.display(root, pitchSpec));
        float volume = parseFloat(volumeText, 1F), pitch = parseFloat(pitchText, 1F);
        String sound = pendingValues.getOrDefault(soundKey, "");
        childOpen = true; minecraft.setScreen(new SoundPickerScreen(this, sound, volume, pitch, selection -> {
            markChanged(soundKey, selection.sound());
            markChanged(volumeKey, Float.toString(selection.volume()));
            markChanged(pitchKey, Float.toString(selection.pitch()));
        }));
    }

    private static float parseFloat(String value, float fallback) {
        try { return Float.parseFloat(value.replace(',', '.')); } catch (RuntimeException ex) { return fallback; }
    }

    private void selectView(View next) {
        view = next; selectedGroup = ""; formPage = 0; selectedCommand = -1; rebuild();
    }

    private void selectCategory(String id) {
        selectedCategory = id; selectedProduct = ""; view = View.CATEGORY; selectedGroup = ""; formPage = 0; rebuild();
    }

    private void selectProduct(String category, String product) {
        selectedCategory = category; selectedProduct = product; view = View.PRODUCT; selectedGroup = ""; formPage = 0; selectedCommand = -1; rebuild();
    }

    private void send(EditorActionC2S.Action action, String field, String value, String secondary) {
        if (minecraft == null || minecraft.getConnection() == null) return;
        if (saveInFlight) {
            status = "Дождитесь завершения сохранения."; statusColor = 0xFFFFFF55; return;
        }
        String category = action == EditorActionC2S.Action.CREATE_CATEGORY ? selectedCategory : selectedCategory;
        String slot = action == EditorActionC2S.Action.CREATE_PRODUCT ? selectedProduct : selectedProduct;
        lastAction = action;
        if (action == EditorActionC2S.Action.SAVE || action == EditorActionC2S.Action.SAVE_FIELDS)
            saveInFlight = true;
        NetworkHandler.sendToServer(new EditorActionC2S(dto.editorSessionId(), action, category, slot,
                field == null ? "" : field, value == null ? "" : value, secondary == null ? "" : secondary));
        if (isMutation(action)) dirty = true;
        status = action == EditorActionC2S.Action.CHECK_COMMAND ? "Сервер проверяет команду…" : "Операция отправлена серверу…";
        statusColor = 0xFFFFFF55;
    }

    private static boolean isMutation(EditorActionC2S.Action action) {
        return action != EditorActionC2S.Action.VALIDATE && action != EditorActionC2S.Action.SAVE
                && action != EditorActionC2S.Action.CANCEL && action != EditorActionC2S.Action.CHECK_COMMAND
                && action != EditorActionC2S.Action.CHECK_FIELDS;
    }

    @Override public void update(EditorDto updated) {
        dto = updated; prunePending(); ensureSelection(); rebuild();
    }

    @Override public void serverEvent(ServerEventS2C event) {
        if (event.type() != ServerEventS2C.Type.EDITOR_RESULT) return;
        if (event.sessionId() != null && !event.sessionId().equals(dto.editorSessionId())) return;
        boolean success = event.code() == PurchaseResultCode.SUCCESS;
        status = event.message(); statusColor = success ? 0xFF55FF55 : 0xFFFF5555;
        if (lastAction == EditorActionC2S.Action.CHECK_COMMAND && success && commandBox != null) {
            commandDraft = pendingCheckedCommand; commandBox.setValue(pendingCheckedCommand);
            status = "Команда проверена и вставлена. Теперь нажмите «Добавить».";
        }
        if (lastAction == EditorActionC2S.Action.SAVE || lastAction == EditorActionC2S.Action.SAVE_FIELDS) {
            saveInFlight = false;
            if (success) {
                for (Map.Entry<FieldKey, String> entry : submittedSaveValues.entrySet()) {
                    if (java.util.Objects.equals(pendingValues.get(entry.getKey()), entry.getValue())) {
                        pendingValues.remove(entry.getKey()); changedFields.remove(entry.getKey());
                    }
                }
                dirty = !changedFields.isEmpty();
                rebuild();
            }
            submittedSaveValues = Map.of();
        }
    }

    private void ensureSelection() {
        if (dto.draft().categories == null || dto.draft().categories.isEmpty()) {
            selectedCategory = ""; selectedProduct = "";
            if (view == View.CATEGORY || view == View.PRODUCT) view = View.UI;
            return;
        }
        ShopConfig.CategoryDefinition category = category(selectedCategory);
        if (category == null) {
            category = dto.draft().categories.stream().min(Comparator.comparingInt(c -> c.order)).orElse(null);
            selectedCategory = category == null ? "" : category.category_id;
        }
        if (view == View.PRODUCT && (category == null || product(category, selectedProduct) == null)) {
            selectedProduct = ""; view = View.CATEGORY;
        }
    }

    private Object currentRoot() {
        return switch (view) {
            case UI -> dto.draft().ui;
            case STYLE -> dto.draft().style;
            case CATEGORY -> category(selectedCategory);
            case PRODUCT -> {
                ShopConfig.CategoryDefinition category = category(selectedCategory);
                yield category == null ? null : product(category, selectedProduct);
            }
        };
    }

    private List<EditorFieldSpec> currentSchema() {
        return switch (view) {
            case CATEGORY -> EditorSchemas.CATEGORY;
            case PRODUCT -> EditorSchemas.PRODUCT;
            case UI -> EditorSchemas.UI;
            case STYLE -> EditorSchemas.GLOBAL_STYLE;
        };
    }

    private ShopConfig.CategoryDefinition category(String id) {
        if (id == null) return null;
        return dto.draft().categories.stream().filter(c -> id.equals(c.category_id)).findFirst().orElse(null);
    }

    private static ShopConfig.ProductDefinition product(ShopConfig.CategoryDefinition category, String id) {
        if (id == null) return null;
        return category.products.stream().filter(p -> id.equals(p.slot_id)).findFirst().orElse(null);
    }

    private FieldKey pendingKey(String path) { return new FieldKey(view, selectedCategory, selectedProduct, path); }

    private void markChanged(FieldKey key, String value) {
        pendingValues.put(key, value); changedFields.add(key); dirty = true;
    }

    private void submitFields(boolean save) {
        if (save && saveInFlight) { setError("Сохранение уже выполняется."); return; }
        if (changedFields.isEmpty()) {
            if (save) submittedSaveValues = Map.of();
            send(save ? EditorActionC2S.Action.SAVE : EditorActionC2S.Action.VALIDATE, "", "", "");
            return;
        }
        try {
            List<EditorFieldEdit> edits = changedFields.stream().sorted(Comparator
                    .comparing((FieldKey key) -> key.view.name()).thenComparing(FieldKey::category)
                    .thenComparing(FieldKey::product).thenComparing(FieldKey::path)).map(key -> {
                EditorFieldSpec spec = schema(key.view).stream().filter(value -> value.path().equals(key.path)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Поле исчезло из схемы: " + key.path));
                String json = EditorValueCodec.normalizeJson(spec, pendingValues.getOrDefault(key, ""));
                EditorFieldEdit.Target target = switch (key.view) {
                    case CATEGORY -> EditorFieldEdit.Target.CATEGORY;
                    case PRODUCT -> EditorFieldEdit.Target.PRODUCT;
                    case UI -> EditorFieldEdit.Target.UI;
                    case STYLE -> EditorFieldEdit.Target.GLOBAL_STYLE;
                };
                return new EditorFieldEdit(target, key.category, key.product, key.path, json);
            }).toList();
            String json = ru.xrshop.common.config.JsonCodec.GSON.toJson(edits);
            if (json.length() > 65_535) throw new IllegalArgumentException("Изменений слишком много для одного сохранения.");
            if (save) {
                Map<FieldKey, String> submitted = new HashMap<>();
                for (FieldKey key : changedFields) submitted.put(key, pendingValues.getOrDefault(key, ""));
                submittedSaveValues = Map.copyOf(submitted);
            }
            send(save ? EditorActionC2S.Action.SAVE_FIELDS : EditorActionC2S.Action.CHECK_FIELDS, "", json, "");
        } catch (IllegalArgumentException ex) { setError(ex.getMessage()); }
    }

    private static List<EditorFieldSpec> schema(View view) {
        return switch (view) {
            case CATEGORY -> EditorSchemas.CATEGORY;
            case PRODUCT -> EditorSchemas.PRODUCT;
            case UI -> EditorSchemas.UI;
            case STYLE -> EditorSchemas.GLOBAL_STYLE;
        };
    }

    private void prunePending() {
        changedFields.removeIf(key -> !fieldTargetExists(key));
        pendingValues.keySet().removeIf(key -> !fieldTargetExists(key));
    }

    private boolean fieldTargetExists(FieldKey key) {
        if (key.view == View.UI || key.view == View.STYLE) return true;
        ShopConfig.CategoryDefinition category = category(key.category);
        if (category == null) return false;
        return key.view != View.PRODUCT || product(category, key.product) != null;
    }

    private boolean requireSavedFields() {
        if (changedFields.isEmpty()) return true;
        setError("Сначала нажмите «Сохранить»: есть неприменённые поля.");
        return false;
    }

    private String detailedHelp(EditorFieldSpec spec) {
        String format = switch (spec.kind()) {
            case TEXT -> "Можно вписать обычный текст без JSON-кавычек.";
            case TEXT_LIST -> "Каждую новую строку отделяйте символом |. Квадратные скобки не нужны.";
            case BOOLEAN -> "Нажатие переключает между «Да» и «Нет».";
            case TRI_STATE -> "«Наследовать» берёт общую настройку; «Да»/«Нет» переопределяют её.";
            case INTEGER, LONG -> "Вводится целое число без пробелов и кавычек.";
            case DECIMAL -> "Вводится число с точкой или запятой, например 0.8.";
            case COLOR -> "Нажмите на цвет и выберите его кругом, HSV-квадратом или ARGB-ползунками.";
            case RESOURCE -> "Вводится Minecraft ResourceLocation в виде namespace:path.";
            case ITEM -> "Нажмите и выберите предмет из внутриигровой сетки с поиском.";
            case NBT -> "Можно оставить пустым или ввести SNBT-объект в фигурных скобках.";
            case ENUM -> "Нажатие перебирает разрешённые варианты: " + String.join(", ", spec.options()) + ".";
            case SOUND -> "Нажмите, найдите звук в реестре, прослушайте его и настройте громкость/высоту.";
            case PATH -> "Нажмите и выберите PNG из ресурсов Minecraft, модов и активных ресурспаков. Это кроссплатформенно и не открывает файлы ОС.";
            case TEXTURE -> "Нажмите, выберите отдельную текстуру слоя и настройте режим, привязку, масштаб и смещение с предпросмотром.";
        };
        return spec.help() + "\n\n" + format + "\n\nПример: " + example(spec);
    }

    private static String example(EditorFieldSpec spec) {
        String path = spec.path();
        if (path.equals("description")) return "Выдаёт 16 алмазов | Цена указана ниже";
        if (path.endsWith("_color")) return "#FF55AAFF (AA — прозрачность, RR/GG/BB — цвет)";
        if (spec.kind() == EditorFieldSpec.Kind.TEXTURE) return "Фон: COVER, центр, масштаб 100%, смещение 0/0";
        if (path.endsWith("texture")) return "minecraft:textures/block/stone.png";
        if (path.endsWith("sound")) return "minecraft:entity.player.levelup";
        if (path.endsWith("item")) return "minecraft:diamond";
        if (path.equals("display_nbt") || path.endsWith("display_nbt")) return "{CustomModelData:1}";
        if (path.equals("permission")) return "myserver.shop.vip или пусто";
        if (path.equals("price_xr")) return "100";
        if (path.equals("icon.size")) return "0 — размер категории; 24 — собственный размер";
        if (path.equals("product_icon_size")) return "24 — размер наследующих товаров категории";
        if (path.equals("grid_left_padding")) return "8 — небольшой отступ первой карточки";
        if (path.contains("seconds")) return "30 — тридцать секунд";
        if (spec.kind() == EditorFieldSpec.Kind.DECIMAL) return "1.0";
        if (spec.kind() == EditorFieldSpec.Kind.BOOLEAN) return "Да";
        if (spec.kind() == EditorFieldSpec.Kind.TRI_STATE) return "Наследовать";
        if (spec.kind() == EditorFieldSpec.Kind.INTEGER || spec.kind() == EditorFieldSpec.Kind.LONG) return "0";
        return spec.label() + ": укажите нужное значение";
    }

    private boolean confirmDelete(String key) {
        long now = System.currentTimeMillis();
        if (key.equals(pendingDelete) && now <= pendingDeleteUntil) { pendingDelete = ""; return true; }
        pendingDelete = key; pendingDeleteUntil = now + 3_000;
        status = "Нажмите «Удалить» ещё раз для подтверждения."; statusColor = 0xFFFFAA55;
        return false;
    }

    private boolean pendingBoolean(FieldKey key, boolean fallback) {
        String value = pendingValues.get(key);
        if (value == null) return fallback;
        if (value.equalsIgnoreCase("Да") || value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("Нет") || value.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    private void setError(String message) { status = message == null ? "Операция отклонена." : message; statusColor = 0xFFFF5555; }

    private void cancelAndClose() {
        if (saveInFlight) { setError("Дождитесь завершения сохранения."); return; }
        send(EditorActionC2S.Action.CANCEL, "", "", ""); dirty = false; closed = true;
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override public void onClose() {
        if (saveInFlight) { setError("Дождитесь завершения сохранения."); return; }
        if (dirty && System.currentTimeMillis() > confirmCloseUntil) {
            confirmCloseUntil = System.currentTimeMillis() + 4_000;
            status = "Несохранённые изменения будут отменены. Нажмите Esc ещё раз."; statusColor = 0xFFFFAA55; return;
        }
        if (dirty) send(EditorActionC2S.Action.CANCEL, "", "", "");
        dirty = false; super.onClose();
    }

    @Override public void removed() {
        if (childOpen) { childOpen = false; super.removed(); return; }
        if (!closed) { closed = true; ClientState.closeEditor(dto.editorSessionId()); }
        super.removed();
    }

    @Override public boolean isPauseScreen() { return false; }

    private record FieldRow(String label, int x, int y, int width) {}
    private record FieldKey(View view, String category, String product, String path) {}
    private record NavHit(String category, String product, int x, int y, int w, int h) {}
    private record CommandHit(int index, int x, int y, int w, int h) {}
    private record SuggestionHit(String value, int x, int y, int w, int h) {}
    private record HelpHit(int x, int y, int w, int h, String help) {}
    private record GroupButton(String name, int x, int y, int w) {}

    // Rendering and input are kept below to make the form-building code independently replaceable.

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, 0xE9101018);
        graphics.fill(4, 4, leftWidth + 4, height - 29, 0xF0181820);
        graphics.fill(formX - 6, 4, width - 4, height - 29, 0xF0181820);
        graphics.fill(4, height - 45, width - 4, height - 29, 0xFF121922);
        graphics.drawString(font, title, 9, 10, 0xFFFFFFFF, false);
        graphics.drawString(font, currentTitle(), formX, 10, 0xFF55AAFF, false);
        renderNavigation(graphics, mouseX, mouseY);
        for (FieldRow row : fieldRows) graphics.drawString(font, trim(row.label, row.width), row.x, row.y + 6, 0xFFE5E5E5, false);
        if (view == View.PRODUCT && "Команды".equals(selectedGroup)) renderCommandList(graphics, mouseX, mouseY);
        if (!status.isBlank()) graphics.drawString(font, trim(status.replace('\n', ' '), Math.max(80, width - 16)), 8, height - 41, statusColor, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderEffectSuggestions(graphics, mouseX, mouseY);
        for (HelpHit help : helpHits) if (inside(mouseX, mouseY, help.x, help.y, help.w, help.h)) {
            graphics.renderTooltip(font, font.split(Component.literal(help.help), Math.min(300, Math.max(140, width / 2))), mouseX, mouseY);
            break;
        }
    }

    private void renderNavigation(GuiGraphics graphics, int mouseX, int mouseY) {
        navHits.clear();
        graphics.drawString(font, "Категории и товары", 9, 99, 0xFF55AAFF, false);
        List<ShopConfig.CategoryDefinition> categories = dto.draft().categories.stream()
                .sorted(Comparator.comparingInt(c -> c.order)).toList();
        int totalHeight = categories.stream().mapToInt(c -> 22 + c.products.size() * 20).sum();
        int maxScroll = Math.max(0, totalHeight - Math.max(20, listBottom - listTop));
        listScroll = Math.max(0, Math.min(listScroll, maxScroll));
        int y = listTop - listScroll;
        for (ShopConfig.CategoryDefinition category : categories) {
            if (y + 19 >= listTop && y < listBottom) {
                boolean selected = view == View.CATEGORY && category.category_id.equals(selectedCategory);
                boolean hover = inside(mouseX, mouseY, 8, y, leftWidth - 12, 19);
                boolean categoryEnabled = pendingBoolean(
                        new FieldKey(View.CATEGORY, category.category_id, "", "enabled"), category.enabled);
                graphics.fill(8, y, leftWidth - 4, y + 19, selected ? 0xFF315B7D : hover ? 0xFF30465C : 0xFF24303C);
                graphics.drawString(font, trim(category.title + " [" + category.category_id + "]", leftWidth - 22), 13, y + 6,
                        categoryEnabled ? 0xFFFFFFFF : 0xFF999999, false);
                navHits.add(new NavHit(category.category_id, "", 8, y, leftWidth - 12, 19));
            }
            y += 22;
            for (ShopConfig.ProductDefinition product : category.products.stream().sorted(Comparator.comparingInt(p -> p.order)).toList()) {
                if (y + 18 >= listTop && y < listBottom) {
                    boolean selected = view == View.PRODUCT && category.category_id.equals(selectedCategory) && product.slot_id.equals(selectedProduct);
                    boolean hover = inside(mouseX, mouseY, 18, y, leftWidth - 22, 18);
                    boolean productEnabled = pendingBoolean(
                            new FieldKey(View.PRODUCT, category.category_id, product.slot_id, "enabled"), product.enabled);
                    graphics.fill(18, y, leftWidth - 4, y + 18, selected ? 0xFF3D6382 : hover ? 0xFF405060 : 0xFF29333D);
                    graphics.drawString(font, trim(product.title + " [" + product.slot_id + "]", leftWidth - 38), 23, y + 5,
                            productEnabled ? 0xFFFFFFFF : 0xFFAAAAAA, false);
                    navHits.add(new NavHit(category.category_id, product.slot_id, 18, y, leftWidth - 22, 18));
                }
                y += 20;
            }
        }
        if (categories.isEmpty()) graphics.drawWordWrap(font, Component.literal("Категорий пока нет. Введите ID выше и нажмите «+ Категория»."), 10, listTop + 5, leftWidth - 18, 0xFFAAAAAA);
    }

    private void renderCommandList(GuiGraphics graphics, int mouseX, int mouseY) {
        commandHits.clear();
        ShopConfig.CategoryDefinition category = category(selectedCategory);
        ShopConfig.ProductDefinition product = category == null ? null : product(category, selectedProduct);
        if (product == null) return;
        int y = commandListTop;
        int maxY = height - 47;
        if (product.purchase_commands.isEmpty()) {
            graphics.drawString(font, "Команд пока нет. Соберите первую кнопкой «Конструктор».", formX, y + 5, 0xFFAAAAAA, false);
            return;
        }
        for (int index = 0; index < product.purchase_commands.size() && y + 20 < maxY; index++) {
            boolean selected = selectedCommand == index;
            boolean hover = inside(mouseX, mouseY, formX, y, formWidth, 20);
            graphics.fill(formX, y, formX + formWidth, y + 20, selected ? 0xFF315B7D : hover ? 0xFF30465C : 0xFF242C35);
            graphics.drawString(font, (index + 1) + ". " + trim(product.purchase_commands.get(index), formWidth - 28), formX + 5, y + 6, 0xFFFFFFFF, false);
            commandHits.add(new CommandHit(index, formX, y, formWidth, 20));
            y += 22;
        }
    }

    private void renderEffectSuggestions(GuiGraphics graphics, int mouseX, int mouseY) {
        suggestionHits.clear();
        effectSuggestionTop = effectSuggestionBottom = effectSuggestionMaximum = 0;
        if (effectBox == null || !effectBox.isFocused()) return;
        List<EffectEntry> suggestions = effectSuggestions();
        if (suggestions.isEmpty()) return;
        int x = effectBox.getX();
        int w = effectBox.getWidth();
        int rowHeight = 18;
        int below = Math.max(0, height - 48 - (effectBox.getY() + 22));
        int above = Math.max(0, effectBox.getY() - 8);
        boolean openBelow = below >= rowHeight * 2 || below >= above;
        int available = openBelow ? below : above;
        int visible = Math.max(1, Math.min(6, Math.min(suggestions.size(), available / rowHeight)));
        int y = openBelow ? effectBox.getY() + 21 : effectBox.getY() - visible * rowHeight - 2;
        y = Math.max(8, Math.min(y, height - 48 - visible * rowHeight));
        effectSuggestionMaximum = Math.max(0, suggestions.size() - visible);
        effectSuggestionScroll = Math.max(0, Math.min(effectSuggestionScroll, effectSuggestionMaximum));
        effectSuggestionTop = y;
        effectSuggestionBottom = y + visible * rowHeight;
        graphics.fill(x - 1, y - 1, x + w + 1, effectSuggestionBottom + 1, 0xFF6A9CC4);
        graphics.fill(x, y, x + w, effectSuggestionBottom, 0xFF151C24);
        boolean hasScrollbar = effectSuggestionMaximum > 0;
        int textWidth = w - (hasScrollbar ? 15 : 8);
        for (int index = effectSuggestionScroll; index < suggestions.size() && index < effectSuggestionScroll + visible; index++) {
            EffectEntry entry = suggestions.get(index);
            boolean hover = inside(mouseX, mouseY, x, y, w, 18);
            graphics.fill(x, y, x + w, y + 18, hover ? 0xFF315B7D : 0xFF252D37);
            graphics.drawString(font, trim(entry.id + " — " + entry.name, textWidth), x + 4, y + 5, 0xFFFFFFFF, false);
            suggestionHits.add(new SuggestionHit(entry.id, x, y, hasScrollbar ? w - 10 : w, 18));
            y += 18;
        }
        if (hasScrollbar) {
            int trackX = x + w - 8;
            int trackHeight = visible * rowHeight;
            int thumbHeight = Math.max(12, Math.round(trackHeight * (visible / (float) suggestions.size())));
            int thumbY = effectSuggestionTop + Math.round((trackHeight - thumbHeight)
                    * (effectSuggestionScroll / (float) effectSuggestionMaximum));
            effectScrollbarX = trackX;
            effectScrollbarThumbY = thumbY;
            effectScrollbarThumbHeight = thumbHeight;
            graphics.fill(trackX, effectSuggestionTop, trackX + 6, effectSuggestionBottom, 0xFF10161D);
            graphics.fill(trackX + 1, thumbY, trackX + 5, thumbY + thumbHeight,
                    draggingEffectScrollbar ? 0xFF77BBEE : 0xFF5790BC);
        }
    }

    private List<EffectEntry> effectSuggestions() {
        String query = effectBox == null ? "" : effectBox.getValue().trim().toLowerCase(Locale.ROOT);
        return ForgeRegistries.MOB_EFFECTS.getEntries().stream().map(entry -> {
                    ResourceLocation id = entry.getKey().location();
                    MobEffect effect = entry.getValue();
                    return new EffectEntry(id.toString(), Component.translatable(effect.getDescriptionId()).getString());
                }).filter(entry -> query.isEmpty() || entry.id.toLowerCase(Locale.ROOT).contains(query)
                        || entry.name.toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(entry -> entry.id)).toList();
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (effectSuggestionMaximum > 0 && inside(mouseX, mouseY, effectScrollbarX, effectSuggestionTop,
                    6, effectSuggestionBottom - effectSuggestionTop)) {
                draggingEffectScrollbar = true;
                effectScrollbarDragOffset = inside(mouseX, mouseY, effectScrollbarX, effectScrollbarThumbY,
                        6, effectScrollbarThumbHeight) ? (int) mouseY - effectScrollbarThumbY : effectScrollbarThumbHeight / 2;
                scrollEffectFromThumb(mouseY);
                return true;
            }
            for (SuggestionHit hit : suggestionHits) if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                effectBox.setValue(hit.value); return true;
            }
            for (NavHit hit : navHits) if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                if (hit.product.isEmpty()) selectCategory(hit.category); else selectProduct(hit.category, hit.product);
                return true;
            }
            for (CommandHit hit : commandHits) if (inside(mouseX, mouseY, hit.x, hit.y, hit.w, hit.h)) {
                selectedCommand = hit.index;
                ShopConfig.CategoryDefinition category = category(selectedCategory);
                ShopConfig.ProductDefinition product = category == null ? null : product(category, selectedProduct);
                if (product != null && selectedCommand < product.purchase_commands.size()) {
                    commandDraft = product.purchase_commands.get(selectedCommand);
                    rebuild();
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingEffectScrollbar) { scrollEffectFromThumb(mouseY); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingEffectScrollbar) { draggingEffectScrollbar = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void scrollEffectFromThumb(double mouseY) {
        int trackHeight = Math.max(1, effectSuggestionBottom - effectSuggestionTop);
        int travel = Math.max(1, trackHeight - effectScrollbarThumbHeight);
        int position = Math.max(0, Math.min((int) mouseY - effectScrollbarDragOffset - effectSuggestionTop, travel));
        effectSuggestionScroll = Math.round(effectSuggestionMaximum * (position / (float) travel));
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (effectBox != null && effectSuggestionMaximum > 0 && inside(mouseX, mouseY, effectBox.getX(), effectSuggestionTop,
                effectBox.getWidth(), effectSuggestionBottom - effectSuggestionTop)) {
            effectSuggestionScroll = Math.max(0, Math.min(effectSuggestionMaximum,
                    effectSuggestionScroll - (int) Math.signum(delta) * 3));
            return true;
        }
        if (mouseX < leftWidth + 5 && mouseY >= listTop && mouseY <= listBottom) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(delta) * 30);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_TAB && effectBox != null && effectBox.isFocused()) {
            List<EffectEntry> suggestions = effectSuggestions();
            if (!suggestions.isEmpty()) {
                effectBox.setValue(suggestions.get(Math.min(effectSuggestionScroll, suggestions.size() - 1)).id);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private String currentTitle() {
        return switch (view) {
            case UI -> "Интерфейс магазина";
            case STYLE -> "Общий стиль";
            case CATEGORY -> {
                ShopConfig.CategoryDefinition category = category(selectedCategory);
                yield category == null ? "Категория не выбрана" : "Категория: " + category.title + " [" + category.category_id + "]";
            }
            case PRODUCT -> {
                ShopConfig.CategoryDefinition category = category(selectedCategory);
                ShopConfig.ProductDefinition product = category == null ? null : product(category, selectedProduct);
                yield product == null ? "Товар не выбран" : "Товар: " + product.title + " [" + product.slot_id + "]";
            }
        };
    }

    private String trim(String text, int pixels) {
        if (text == null) return "";
        return font.width(text) <= pixels ? text : font.plainSubstrByWidth(text, Math.max(1, pixels - font.width("…"))) + "…";
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private record EffectEntry(String id, String name) {}
}
