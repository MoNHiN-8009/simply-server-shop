package ru.xrshop;

import ru.xrshop.common.config.ShopConfig;
import ru.xrshop.common.validation.ShopValidator;

public final class TestFixtures {
    private TestFixtures() {}
    public static ShopConfig shop() {
        ShopConfig config = ShopConfig.empty();
        ShopConfig.CategoryDefinition category = new ShopConfig.CategoryDefinition();
        category.category_id = "resources"; category.title = "Ресурсы";
        ShopConfig.ProductDefinition product = new ShopConfig.ProductDefinition();
        product.slot_id = "diamonds_16"; product.title = "16 алмазов"; product.price_xr = 100;
        product.purchase_commands.add("give ${player} minecraft:diamond 16");
        category.products.add(product); config.categories.add(category); return config;
    }

    public static ShopValidator.RuntimeChecks runtimeChecks() {
        return new ShopValidator.RuntimeChecks() {
            public boolean itemExists(String ignored) { return true; }
            public String validateDisplayNbt(String ignored) { return null; }
            public String validateCommand(String ignored) { return null; }
        };
    }
}
