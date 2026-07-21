package ru.xrshop.client;

import ru.xrshop.common.dto.StoreViewDto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure scroll-bound calculations shared by rendering and input handling. */
final class StoreScrollLimits {
    private StoreScrollLimits() {}

    static int products(List<StoreViewDto.ProductDto> products, int columns, int cardHeight, int gap, int viewportHeight) {
        if (products == null || products.isEmpty()) return 0;
        int safeColumns = Math.max(1, columns);
        Set<Integer> used = new HashSet<>();
        int sequential = 0;
        int lastPosition = 0;
        for (StoreViewDto.ProductDto product : products) {
            int desired = product.row() * safeColumns + Math.min(product.column(), safeColumns - 1);
            int position = desired >= 0 && !used.contains(desired) ? desired : sequential;
            while (used.contains(position)) position++;
            used.add(position);
            sequential = Math.max(sequential, position + 1);
            lastPosition = Math.max(lastPosition, position);
        }
        int rows = lastPosition / safeColumns + 1;
        int contentHeight = rows * Math.max(1, cardHeight) + Math.max(0, rows - 1) * Math.max(0, gap);
        return Math.max(0, contentHeight - Math.max(1, viewportHeight));
    }

    static int linear(int count, int rowHeight, int viewportHeight) {
        return Math.max(0, Math.max(0, count) * Math.max(1, rowHeight) - Math.max(1, viewportHeight));
    }

    static int clamp(int value, int maximum) {
        return Math.max(0, Math.min(value, Math.max(0, maximum)));
    }
}
