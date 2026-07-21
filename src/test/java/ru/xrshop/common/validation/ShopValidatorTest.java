package ru.xrshop.common.validation;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;
import ru.xrshop.TestFixtures;
import ru.xrshop.common.config.JsonCodec;
import ru.xrshop.common.config.ServerConfig;
import ru.xrshop.common.config.ShopConfig;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ShopValidatorTest {
    private final ServerConfig.Limits limits = new ServerConfig().limits;

    @Test void emptyStoreIsValid() { assertTrue(validator().validate(ShopConfig.empty()).isEmpty()); }

    @Test void validShopLoadsStrictly() {
        String json = JsonCodec.GSON.toJson(TestFixtures.shop());
        var root = StrictJsonParser.parse(json); List<ValidationIssue> unknown = new ArrayList<>();
        StrictJsonParser.rejectUnknownFields(root, ShopConfig.class, "$", unknown);
        assertTrue(unknown.isEmpty());
        assertTrue(validator().validate(JsonCodec.GSON.fromJson(root, ShopConfig.class)).isEmpty());
    }

    @Test void corruptedJsonIsRejected() { assertThrows(JsonParseException.class, () -> StrictJsonParser.parse("{\"categories\":[")); }

    @Test void unknownSchemaIsRejected() {
        ShopConfig shop = TestFixtures.shop(); shop.schema_version = 999;
        assertPath(validator().validate(shop), "$.schema_version");
    }

    @Test void duplicateCategoryIsRejected() {
        ShopConfig shop = TestFixtures.shop(); shop.categories.add(JsonCodec.copy(shop.categories.get(0), ShopConfig.CategoryDefinition.class));
        assertTrue(validator().validate(shop).stream().anyMatch(i -> i.message().contains("category_id")));
    }

    @Test void duplicateSlotIsRejected() {
        ShopConfig shop = TestFixtures.shop(); shop.categories.get(0).products.add(JsonCodec.copy(shop.categories.get(0).products.get(0), ShopConfig.ProductDefinition.class));
        assertTrue(validator().validate(shop).stream().anyMatch(i -> i.message().contains("slot_id")));
    }

    @Test void negativePriceIsRejected() {
        ShopConfig shop = TestFixtures.shop(); shop.categories.get(0).products.get(0).price_xr = -1;
        assertPath(validator().validate(shop), ".price_xr");
    }

    @Test void productIconSizeAndGridPaddingAreValidated() {
        ShopConfig shop = TestFixtures.shop();
        shop.ui.grid_left_padding = -1;
        shop.categories.get(0).product_icon_size = 7;
        shop.categories.get(0).products.get(0).icon.size = 1;
        List<ValidationIssue> issues = validator().validate(shop);
        assertPath(issues, ".grid_left_padding");
        assertPath(issues, ".product_icon_size");
        assertPath(issues, ".icon.size");

        shop.ui.grid_left_padding = 8;
        shop.categories.get(0).product_icon_size = 64;
        shop.categories.get(0).products.get(0).icon.size = 0;
        assertTrue(validator().validate(shop).isEmpty(), validator().validate(shop).toString());
    }

    @Test void unknownPlaceholderIsRejected() {
        ShopConfig shop = TestFixtures.shop(); shop.categories.get(0).products.get(0).purchase_commands.set(0, "give ${nickname} minecraft:stone");
        assertTrue(validator().validate(shop).stream().anyMatch(i -> i.message().contains("неизвестный заполнитель")));
    }

    @Test void jsonComponentsAndNbtBracesAreNotTreatedAsPlaceholders() {
        ShopConfig shop = TestFixtures.shop();
        shop.categories.get(0).products.get(0).purchase_commands = List.of(
                "execute at ${player} run particle minecraft:flame ~ ~1 ~ 0.4 0.8 0.4 0.02 40 force",
                "particle minecraft:flame ~ ~1 ~ 0 0 0 0 1",
                "tellraw ${player} {\"text\":\"ТЕСТ\",\"color\":\"green\"}",
                "title ${player} title {\"text\":\"ТЕСТ\"}",
                "title ${player} subtitle {\"text\":\"Подзаголовок\"}",
                "title ${player} actionbar {\"text\":\"ActionBar\"}",
                "give ${player} minecraft:diamond{CustomModelData:1} 1");
        assertTrue(validator().validate(shop).isEmpty(), validator().validate(shop).toString());
    }

    @Test void onlyDollarPlaceholdersAreSubstituted() {
        assertEquals("tellraw Alex {\"text\":\"{player}\"}", ShopValidator.substitute(
                "tellraw ${player} {\"text\":\"{player}\"}", "Alex",
                "00000000-0000-0000-0000-000000000001", 25, "cat", "slot",
                "00000000-0000-0000-0000-000000000002"));
    }

    @Test void malformedDollarPlaceholderIsRejected() {
        ShopConfig shop = TestFixtures.shop();
        shop.categories.get(0).products.get(0).purchase_commands.set(0, "give ${player minecraft:stone 1");
        assertTrue(validator().validate(shop).stream().anyMatch(issue -> issue.message().contains("повреждённый заполнитель")));
    }

    @Test void invalidExecutableCommandIsRejected() {
        ShopValidator.RuntimeChecks checks = new ShopValidator.RuntimeChecks() {
            public boolean itemExists(String id) { return true; }
            public String validateDisplayNbt(String nbt) { return null; }
            public String validateCommand(String command) { return command.startsWith("broken") ? "не разбирается" : null; }
        };
        ShopConfig shop = TestFixtures.shop(); shop.categories.get(0).products.get(0).purchase_commands.set(0, "broken ${player}");
        assertTrue(new ShopValidator(limits, checks).validate(shop).stream().anyMatch(i -> i.message().contains("не разбирается")));
    }

    @Test void texturePlacementIsValidatedAndUiColorsMayBeCleared() {
        ShopConfig shop = TestFixtures.shop();
        shop.ui.background_color = "";
        shop.style.background_texture.path = "minecraft:textures/gui/options_background.png";
        shop.style.background_texture.mode = "COVER";
        shop.style.background_texture.anchor = "BOTTOM_RIGHT";
        shop.style.background_texture.scale = 125;
        assertTrue(validator().validate(shop).isEmpty());

        shop.style.background_texture.mode = "BROKEN";
        shop.style.background_texture.scale = 1;
        List<ValidationIssue> issues = validator().validate(shop);
        assertPath(issues, ".background_texture.mode");
        assertPath(issues, ".background_texture.scale");
    }

    @Test void unknownJsonFieldIsRejected() {
        var root = StrictJsonParser.parse("{\"schema_version\":1,\"revision\":1,\"ui\":{},\"style\":{},\"categories\":[],\"secret\":1}");
        List<ValidationIssue> issues = new ArrayList<>(); StrictJsonParser.rejectUnknownFields(root, ShopConfig.class, "$", issues);
        assertPath(issues, "$.secret");
    }

    @Test void distributedEmptyAndExampleConfigurationsAreValid() throws Exception {
        for (String file : List.of("distribution/config/xrshop/shop.json", "examples/shop.example.json")) {
            var root = StrictJsonParser.parse(Files.readString(Path.of(file)));
            ShopConfig config = JsonCodec.GSON.fromJson(root, ShopConfig.class);
            assertTrue(validator().validate(config).isEmpty(), file + ": " + validator().validate(config));
        }
    }

    private ShopValidator validator() { return new ShopValidator(limits, TestFixtures.runtimeChecks()); }
    private static void assertPath(List<ValidationIssue> issues, String fragment) { assertTrue(issues.stream().anyMatch(i -> i.path().contains(fragment)), issues.toString()); }
}
