package ru.xrshop.server.view;

import org.junit.jupiter.api.Test;
import ru.xrshop.TestFixtures;
import ru.xrshop.common.dto.StoreViewDto;
import ru.xrshop.server.config.ShopSnapshot;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StoreViewFactoryTest {
    @Test void dtoIsFilteredByServerPermissions() {
        var config = TestFixtures.shop(); config.categories.get(0).permission = "restricted.category";
        StoreViewDto hidden = new StoreViewFactory().create(UUID.randomUUID(), new ShopSnapshot(config), 10, permission -> permission.isBlank());
        assertTrue(hidden.categories().isEmpty());
    }

    @Test void ordinaryDtoTypeCannotContainPurchaseCommands() {
        assertFalse(Arrays.stream(StoreViewDto.ProductDto.class.getRecordComponents())
                .anyMatch(component -> component.getName().toLowerCase().contains("command")));
    }

    @Test void snapshotIsDetachedFromMutableSource() {
        var config = TestFixtures.shop(); ShopSnapshot snapshot = new ShopSnapshot(config);
        config.categories.clear(); snapshot.copyConfig().categories.clear();
        assertEquals("resources", snapshot.orderedCategories().get(0).id());
    }

    @Test void textureLayersReachFilteredClientDto() {
        var config = TestFixtures.shop();
        config.categories.get(0).style.background_texture.path = "minecraft:textures/block/stone.png";
        config.categories.get(0).style.background_texture.mode = "COVER";
        StoreViewDto dto = new StoreViewFactory().create(UUID.randomUUID(), new ShopSnapshot(config), 10, ignored -> true);

        assertEquals("minecraft:textures/block/stone.png", dto.categories().get(0).style().backgroundTexture().path());
        assertEquals("COVER", dto.categories().get(0).style().backgroundTexture().mode());
    }

    @Test void gridPaddingAndProductIconSizeReachClientDto() {
        var config = TestFixtures.shop();
        config.ui.grid_left_padding = 14;
        config.categories.get(0).products.get(0).icon.size = 32;
        StoreViewDto dto = new StoreViewFactory().create(UUID.randomUUID(), new ShopSnapshot(config), 10, ignored -> true);

        assertEquals(14, dto.ui().gridLeftPadding());
        assertEquals(32, dto.categories().get(0).products().get(0).icon().size());
    }

    @Test void zeroProductIconSizeInheritsOnlyCategorySize() {
        var config = TestFixtures.shop();
        var category = config.categories.get(0);
        var product = category.products.get(0);
        category.product_icon_size = 40;
        product.icon.item = "minecraft:gold_ingot";
        product.icon.count = 7;
        product.icon.display_nbt = "{CustomModelData:2}";
        product.icon.size = 0;

        StoreViewDto dto = new StoreViewFactory().create(UUID.randomUUID(), new ShopSnapshot(config), 10, ignored -> true);
        StoreViewDto.IconDto icon = dto.categories().get(0).products().get(0).icon();

        assertEquals(40, icon.size());
        assertEquals("minecraft:gold_ingot", icon.item());
        assertEquals(7, icon.count());
        assertEquals("{CustomModelData:2}", icon.displayNbt());
    }
}
