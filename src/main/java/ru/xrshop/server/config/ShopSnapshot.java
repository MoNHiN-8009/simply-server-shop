package ru.xrshop.server.config;

import ru.xrshop.common.config.JsonCodec;
import ru.xrshop.common.config.ShopConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A deeply detached, immutable server-side view of one validated shop revision. */
public final class ShopSnapshot {
    private final ShopConfig serializedCopy;
    private final long revision;
    private final Map<String, CategoryEntry> categories;

    public ShopSnapshot(ShopConfig validated) {
        this.serializedCopy = JsonCodec.copy(validated, ShopConfig.class);
        this.revision = validated.revision;
        Map<String, CategoryEntry> categoryMap = new LinkedHashMap<>();
        for (ShopConfig.CategoryDefinition category : validated.categories) {
            categoryMap.put(category.category_id, new CategoryEntry(category));
        }
        this.categories = Collections.unmodifiableMap(categoryMap);
    }

    public long revision() { return revision; }
    public ShopConfig copyConfig() { return JsonCodec.copy(serializedCopy, ShopConfig.class); }
    public ShopConfig.UiDefinition copyUi() { return JsonCodec.copy(serializedCopy.ui, ShopConfig.UiDefinition.class); }
    public ShopConfig.StyleDefinition copyStyle() { return JsonCodec.copy(serializedCopy.style, ShopConfig.StyleDefinition.class); }
    public Optional<CategoryEntry> category(String id) { return Optional.ofNullable(categories.get(id)); }
    public List<CategoryEntry> orderedCategories() {
        return categories.values().stream().sorted(Comparator.comparingInt(CategoryEntry::order)
                .thenComparing(CategoryEntry::id)).toList();
    }

    public static final class CategoryEntry {
        private final ShopConfig.CategoryDefinition definition;
        private final Map<String, ProductEntry> products;

        private CategoryEntry(ShopConfig.CategoryDefinition source) {
            definition = JsonCodec.copy(source, ShopConfig.CategoryDefinition.class);
            Map<String, ProductEntry> productMap = new LinkedHashMap<>();
            for (ShopConfig.ProductDefinition product : source.products)
                productMap.put(product.slot_id, new ProductEntry(product));
            products = Collections.unmodifiableMap(productMap);
        }
        public String id() { return definition.category_id; }
        public String title() { return definition.title; }
        public boolean enabled() { return definition.enabled; }
        public int order() { return definition.order; }
        public String permission() { return definition.permission; }
        public ShopConfig.CategoryDefinition copyDefinition() {
            return JsonCodec.copy(definition, ShopConfig.CategoryDefinition.class);
        }
        public Optional<ProductEntry> product(String slotId) { return Optional.ofNullable(products.get(slotId)); }
        public List<ProductEntry> orderedProducts() {
            return products.values().stream().sorted(Comparator.comparingInt(ProductEntry::order)
                    .thenComparing(ProductEntry::id)).toList();
        }
    }

    public static final class ProductEntry {
        private final ShopConfig.ProductDefinition definition;
        private final List<String> commands;

        private ProductEntry(ShopConfig.ProductDefinition source) {
            definition = JsonCodec.copy(source, ShopConfig.ProductDefinition.class);
            commands = Collections.unmodifiableList(new ArrayList<>(source.purchase_commands));
        }
        public String id() { return definition.slot_id; }
        public String title() { return definition.title; }
        public boolean enabled() { return definition.enabled; }
        public int order() { return definition.order; }
        public String permission() { return definition.permission; }
        public long price() { return definition.price_xr; }
        public long purchaseLimit() { return definition.purchase_limit; }
        public long limitPeriodSeconds() { return definition.limit_period_seconds; }
        public long cooldownSeconds() { return definition.cooldown_seconds; }
        public String successMessage() { return definition.success_message; }
        public String errorMessage() { return definition.error_message; }
        public List<String> commands() { return commands; }
        public ShopConfig.ProductDefinition copyDefinition() {
            return JsonCodec.copy(definition, ShopConfig.ProductDefinition.class);
        }
    }
}
