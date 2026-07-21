package ru.xrshop.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.xrshop.common.config.ShopConfig;
import ru.xrshop.common.dto.EditorDto;
import ru.xrshop.common.dto.StoreViewDto;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClientStateCleanupTest {
    @AfterEach void cleanup() { ClientState.disconnect(); }

    @Test void closingScreensDropsStoreAndAdministrativeCommandsFromMemory() {
        ClientState.installStore(new StoreViewDto(UUID.randomUUID(), 1, null, null, List.of(), 0));
        ShopConfig draft = ShopConfig.empty();
        ShopConfig.CategoryDefinition category = new ShopConfig.CategoryDefinition();
        category.category_id = "c"; category.title = "C";
        ShopConfig.ProductDefinition product = new ShopConfig.ProductDefinition();
        product.slot_id = "p"; product.title = "P"; product.purchase_commands.add("say secret");
        category.products.add(product); draft.categories.add(category);
        ClientState.installEditor(new EditorDto(UUID.randomUUID(), 1, draft));
        assertTrue(ClientState.hasStore()); assertTrue(ClientState.hasEditor());
        ClientState.clearStoreMemory(); ClientState.clearEditorMemory();
        assertFalse(ClientState.hasStore()); assertFalse(ClientState.hasEditor());
    }
}
