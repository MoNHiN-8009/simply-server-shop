package ru.xrshop.client;

import org.junit.jupiter.api.Test;
import ru.xrshop.common.dto.StoreViewDto;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoreScrollLimitsTest {
    @Test void stopsAtBottomOfLastProductRow() {
        List<StoreViewDto.ProductDto> products = List.of(
                product("one", 0, 0), product("two", 0, 0), product("three", 0, 0),
                product("four", 0, 0), product("five", 0, 0));

        assertEquals(40, StoreScrollLimits.products(products, 2, 100, 10, 280));
        assertEquals(40, StoreScrollLimits.clamp(10_000, 40));
        assertEquals(0, StoreScrollLimits.clamp(-50, 40));
    }

    @Test void honorsExplicitDistantRowsAndBoundsCategoryList() {
        assertEquals(250, StoreScrollLimits.products(List.of(product("far", 4, 0)), 2, 90, 10, 240));
        assertEquals(60, StoreScrollLimits.linear(10, 26, 200));
        assertEquals(0, StoreScrollLimits.linear(3, 26, 200));
    }

    private static StoreViewDto.ProductDto product(String id, int row, int column) {
        return new StoreViewDto.ProductDto(id, id, List.of(), 0, 0, 0, row, column,
                false, "Купить", null, null, null);
    }
}
