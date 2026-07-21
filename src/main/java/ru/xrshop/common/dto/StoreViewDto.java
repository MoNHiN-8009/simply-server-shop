package ru.xrshop.common.dto;

import java.util.List;
import java.util.UUID;

public record StoreViewDto(UUID sessionId, long revision, UiDto ui, StyleDto style,
                           List<CategoryDto> categories, long balance) {
    public StoreViewDto {
        categories = List.copyOf(categories);
    }

    public record UiDto(String title, String emptyMessage, String backgroundColor, String panelColor,
                        String textColor, String accentColor, String errorColor, String successColor,
                        int tabWidth, int cardWidth, int cardHeight, int horizontalGap, int verticalGap,
                        int margin, int gridLeftPadding, int gridRows, int gridColumns, boolean paginationEnabled,
                        boolean scrollingEnabled, boolean confirmationEnabled, boolean dimWorld,
                        String purchaseSuccessText, String insufficientFundsText,
                        SoundDto openSound, SoundDto successSound, SoundDto errorSound) {}

    public record CategoryDto(String categoryId, String title, List<String> description, int order,
                              IconDto icon, StyleDto style, int tabWidth, int padding,
                              List<ProductDto> products) {
        public CategoryDto {
            description = List.copyOf(description);
            products = List.copyOf(products);
        }
    }

    public record ProductDto(String slotId, String title, List<String> description, long priceXr,
                             int order, int page, int row, int column, boolean confirmationEnabled,
                             String purchaseButtonText, IconDto icon, SoundDto sound, StyleDto style) {
        public ProductDto { description = List.copyOf(description); }
    }

    public record IconDto(String type, String item, int count, int size, String displayNbt, String texture) {}

    public record SoundDto(String sound, float volume, float pitch) {}

    public record TextureDto(String path, String mode, String anchor, int offsetX, int offsetY, int scale) {}

    public record StyleDto(String backgroundColor, String panelColor, String categoryListColor,
                           String tabColor, String tabHoverColor, String tabActiveColor,
                           String cardColor, String cardHoverColor, String buttonColor,
                           String borderColor, String textColor, String priceColor,
                           String balanceColor, String legacyTexture,
                           TextureDto backgroundTexture, TextureDto panelTexture, TextureDto categoryListTexture,
                           TextureDto tabTexture, TextureDto tabHoverTexture, TextureDto tabActiveTexture,
                           TextureDto cardTexture, TextureDto cardHoverTexture, TextureDto buttonTexture,
                           int borderWidth, int padding, int opacity) {}
}
