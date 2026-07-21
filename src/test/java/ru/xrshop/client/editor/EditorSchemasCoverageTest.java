package ru.xrshop.client.editor;

import org.junit.jupiter.api.Test;
import ru.xrshop.common.config.JsonCodec;
import ru.xrshop.common.config.ShopConfig;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSchemasCoverageTest {
    @Test void categorySchemaCoversEveryEditableLeaf() {
        assertCoverage(ShopConfig.CategoryDefinition.class, EditorSchemas.CATEGORY,
                Set.of("category_id", "products", "icon.size", "style.texture"));
    }

    @Test void productSchemaCoversEveryEditableLeaf() {
        assertCoverage(ShopConfig.ProductDefinition.class, EditorSchemas.PRODUCT, Set.of(
                "slot_id", "purchase_commands", "style.texture", "style.background_texture", "style.panel_texture",
                "style.category_list_texture", "style.tab_texture", "style.tab_hover_texture", "style.tab_active_texture",
                "style.background_color", "style.panel_color", "style.category_list_color", "style.tab_color",
                "style.tab_hover_color", "style.tab_active_color", "style.balance_color", "style.padding"));
    }

    @Test void uiAndGlobalStyleSchemasCoverEveryLeaf() {
        assertCoverage(ShopConfig.UiDefinition.class, EditorSchemas.UI, Set.of());
        assertCoverage(ShopConfig.StyleDefinition.class, EditorSchemas.GLOBAL_STYLE, Set.of("texture"));
    }

    @Test void friendlyDescriptionAndTriStateBecomeCorrectJson() {
        EditorFieldSpec description = EditorSchemas.PRODUCT.stream().filter(s -> s.path().equals("description")).findFirst().orElseThrow();
        assertEquals(List.of("Первая строка", "Вторая строка"),
                JsonCodec.GSON.fromJson(EditorValueCodec.toJson(description, "Первая строка | Вторая строка"), List.class));
        EditorFieldSpec confirmation = EditorSchemas.PRODUCT.stream().filter(s -> s.path().equals("confirmation_enabled")).findFirst().orElseThrow();
        assertEquals("null", EditorValueCodec.toJson(confirmation, "Наследовать"));
    }

    @Test void soundsAndTexturesUseDedicatedPickers() {
        List<EditorFieldSpec> all = new java.util.ArrayList<>();
        all.addAll(EditorSchemas.CATEGORY);
        all.addAll(EditorSchemas.PRODUCT);
        all.addAll(EditorSchemas.UI);
        all.addAll(EditorSchemas.GLOBAL_STYLE);

        assertTrue(all.stream().anyMatch(spec -> spec.path().endsWith("sound")));
        assertTrue(all.stream().filter(spec -> spec.path().endsWith("sound"))
                .allMatch(spec -> spec.kind() == EditorFieldSpec.Kind.SOUND));
        assertTrue(all.stream().anyMatch(spec -> spec.path().endsWith("_texture")));
        assertTrue(all.stream().filter(spec -> spec.path().endsWith("_texture"))
                .allMatch(spec -> spec.kind() == EditorFieldSpec.Kind.TEXTURE));
        assertTrue(all.stream().filter(spec -> spec.path().endsWith("icon.texture"))
                .allMatch(spec -> spec.kind() == EditorFieldSpec.Kind.PATH));
    }

    @Test void productStyleOnlyContainsPropertiesRenderedOnAProductCard() {
        Set<String> stylePaths = EditorSchemas.PRODUCT.stream().map(EditorFieldSpec::path)
                .filter(path -> path.startsWith("style.")).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("style.card_color", "style.card_hover_color", "style.button_color",
                "style.border_color", "style.text_color", "style.price_color", "style.card_texture",
                "style.card_hover_texture", "style.button_texture", "style.border_width", "style.opacity"), stylePaths);
        assertTrue(EditorSchemas.CATEGORY.stream().anyMatch(spec -> spec.path().equals("product_icon_size")));
        assertTrue(EditorSchemas.PRODUCT.stream().anyMatch(spec -> spec.path().equals("icon.size")));
    }

    private static void assertCoverage(Class<?> type, List<EditorFieldSpec> schema, Set<String> excluded) {
        Set<String> expected = leafPaths(type, "");
        expected.removeAll(excluded);
        Set<String> actual = new HashSet<>(schema.stream().map(EditorFieldSpec::path).toList());
        assertEquals(expected, actual);
    }

    private static Set<String> leafPaths(Class<?> type, String prefix) {
        Set<String> result = new HashSet<>();
        for (Field field : type.getFields()) {
            String path = prefix + field.getName();
            Class<?> fieldType = field.getType();
            if (fieldType == ShopConfig.TextureDefinition.class || fieldType.isPrimitive() || fieldType == String.class
                    || fieldType == Boolean.class || List.class.isAssignableFrom(fieldType)) {
                result.add(path);
            } else result.addAll(leafPaths(fieldType, path + "."));
        }
        return result;
    }
}
