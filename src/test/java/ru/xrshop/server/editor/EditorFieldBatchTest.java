package ru.xrshop.server.editor;

import org.junit.jupiter.api.Test;
import ru.xrshop.TestFixtures;
import ru.xrshop.common.config.ShopConfig;
import ru.xrshop.common.dto.EditorFieldEdit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorFieldBatchTest {
    @Test void appliesSeveralFieldsToDetachedCandidate() throws Exception {
        ShopConfig original = TestFixtures.shop();
        List<EditorFieldEdit> edits = List.of(
                new EditorFieldEdit(EditorFieldEdit.Target.CATEGORY, "resources", "", "title", "\"Материалы\""),
                new EditorFieldEdit(EditorFieldEdit.Target.PRODUCT, "resources", "diamonds_16", "title", "\"Алмазы\""),
                new EditorFieldEdit(EditorFieldEdit.Target.PRODUCT, "resources", "diamonds_16", "price_xr", "250"),
                new EditorFieldEdit(EditorFieldEdit.Target.PRODUCT, "resources", "diamonds_16", "description", "[\"Строка 1\",\"Строка 2\"]")
        );

        ShopConfig candidate = EditorService.applyFieldEdits(original, edits);

        assertEquals("Ресурсы", original.categories.get(0).title);
        assertEquals("16 алмазов", original.categories.get(0).products.get(0).title);
        assertEquals(100, original.categories.get(0).products.get(0).price_xr);
        assertEquals("Материалы", candidate.categories.get(0).title);
        assertEquals("Алмазы", candidate.categories.get(0).products.get(0).title);
        assertEquals(250, candidate.categories.get(0).products.get(0).price_xr);
        assertEquals(List.of("Строка 1", "Строка 2"), candidate.categories.get(0).products.get(0).description);
    }

    @Test void rejectsIdentityFieldsFromFriendlyForm() {
        ShopConfig original = TestFixtures.shop();
        List<EditorFieldEdit> edits = List.of(new EditorFieldEdit(
                EditorFieldEdit.Target.PRODUCT, "resources", "diamonds_16", "slot_id", "\"hacked\""));

        assertThrows(IllegalArgumentException.class, () -> EditorService.applyFieldEdits(original, edits));
        assertEquals("diamonds_16", original.categories.get(0).products.get(0).slot_id);
    }

    @Test void appliesWholeTextureObjectAtomically() throws Exception {
        ShopConfig original = TestFixtures.shop();
        String texture = "{\"path\":\"minecraft:textures/block/stone.png\",\"mode\":\"COVER\","
                + "\"anchor\":\"TOP_RIGHT\",\"offset_x\":7,\"offset_y\":-3,\"scale\":120}";

        ShopConfig candidate = EditorService.applyFieldEdits(original, List.of(new EditorFieldEdit(
                EditorFieldEdit.Target.CATEGORY, "resources", "", "style.background_texture", texture)));

        assertEquals("", original.categories.get(0).style.background_texture.path);
        assertEquals("minecraft:textures/block/stone.png", candidate.categories.get(0).style.background_texture.path);
        assertEquals("COVER", candidate.categories.get(0).style.background_texture.mode);
        assertEquals("TOP_RIGHT", candidate.categories.get(0).style.background_texture.anchor);
        assertEquals(7, candidate.categories.get(0).style.background_texture.offset_x);
        assertEquals(-3, candidate.categories.get(0).style.background_texture.offset_y);
        assertEquals(120, candidate.categories.get(0).style.background_texture.scale);
    }
}
