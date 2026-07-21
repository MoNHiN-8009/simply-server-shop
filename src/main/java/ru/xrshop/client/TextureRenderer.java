package ru.xrshop.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import ru.xrshop.common.config.ShopConfig;
import ru.xrshop.common.dto.StoreViewDto;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Draws configured texture layers with clipping, aspect handling, anchors and tiling. */
public final class TextureRenderer {
    private final Map<ResourceLocation, Dimensions> dimensions = new HashMap<>();

    public void draw(GuiGraphics graphics, StoreViewDto.TextureDto texture, int x, int y, int width, int height) {
        if (texture == null) return;
        draw(graphics, texture.path(), texture.mode(), texture.anchor(), texture.offsetX(), texture.offsetY(), texture.scale(),
                x, y, width, height);
    }

    public void draw(GuiGraphics graphics, ShopConfig.TextureDefinition texture, int x, int y, int width, int height) {
        if (texture == null) return;
        draw(graphics, texture.path, texture.mode, texture.anchor, texture.offset_x, texture.offset_y, texture.scale,
                x, y, width, height);
    }

    public void draw(GuiGraphics graphics, String path, String mode, String anchor, int offsetX, int offsetY, int scale,
                     int x, int y, int width, int height) {
        if (path == null || path.isBlank() || width <= 0 || height <= 0) return;
        ResourceLocation id = ResourceLocation.tryParse(path);
        if (id == null) return;
        Dimensions size = dimensions.computeIfAbsent(id, this::readDimensions);
        TextureLayout.Placement placement = TextureLayout.calculate(mode, anchor, size.width, size.height,
                x, y, width, height, offsetX, offsetY, scale);
        graphics.enableScissor(x, y, x + width, y + height);
        if (placement.tiled()) drawTiles(graphics, id, size, placement, x, y, width, height);
        else blit(graphics, id, size, placement.x(), placement.y(), placement.width(), placement.height());
        graphics.disableScissor();
    }

    private static void drawTiles(GuiGraphics graphics, ResourceLocation id, Dimensions size,
                                  TextureLayout.Placement placement, int x, int y, int width, int height) {
        int tileW = Math.max(4, placement.width()), tileH = Math.max(4, placement.height());
        int startX = placement.x(), startY = placement.y();
        while (startX > x) startX -= tileW;
        while (startY > y) startY -= tileH;
        int right = x + width, bottom = y + height;
        int rows = 0;
        for (int py = startY; py < bottom && rows++ < 2048; py += tileH) {
            int columns = 0;
            for (int px = startX; px < right && columns++ < 2048; px += tileW)
                blit(graphics, id, size, px, py, tileW, tileH);
        }
    }

    private static void blit(GuiGraphics graphics, ResourceLocation id, Dimensions size,
                             int x, int y, int width, int height) {
        graphics.blit(id, x, y, width, height, 0, 0, size.width, size.height, size.width, size.height);
    }

    private Dimensions readDimensions(ResourceLocation id) {
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(id).orElseThrow();
            try (InputStream stream = resource.open(); NativeImage image = NativeImage.read(stream)) {
                return new Dimensions(Math.max(1, image.getWidth()), Math.max(1, image.getHeight()));
            }
        } catch (Exception ignored) { return new Dimensions(16, 16); }
    }

    private record Dimensions(int width, int height) {}
}
