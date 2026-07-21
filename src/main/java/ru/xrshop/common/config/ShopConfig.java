package ru.xrshop.common.config;

import java.util.ArrayList;
import java.util.List;

/** Mutable Gson model. Runtime code publishes it only through immutable ShopSnapshot copies. */
public final class ShopConfig {
    public int schema_version = 1;
    public long revision = 1;
    public UiDefinition ui = new UiDefinition();
    public StyleDefinition style = new StyleDefinition();
    public List<CategoryDefinition> categories = new ArrayList<>();

    public static ShopConfig empty() {
        return new ShopConfig();
    }

    public static final class UiDefinition {
        public String title = "Магазин";
        public String empty_message = "В магазине пока нет категорий";
        public String background_color = "#CC101018";
        public String panel_color = "#EE181820";
        public String text_color = "#FFFFFFFF";
        public String accent_color = "#FF55AAFF";
        public String error_color = "#FFFF5555";
        public String success_color = "#FF55FF55";
        public int tab_width = 120;
        public int card_width = 90;
        public int card_height = 105;
        public int horizontal_gap = 8;
        public int vertical_gap = 8;
        public int margin = 12;
        public int grid_left_padding = 8;
        public int grid_rows = 0;
        public int grid_columns = 0;
        public boolean pagination_enabled = true;
        public boolean scrolling_enabled = true;
        public boolean confirmation_enabled = true;
        public boolean dim_world = true;
        public String purchase_success_text = "Покупка выполнена";
        public String insufficient_funds_text = "Недостаточно XR";
        public SoundDefinition open_sound = new SoundDefinition();
        public SoundDefinition success_sound = new SoundDefinition();
        public SoundDefinition error_sound = new SoundDefinition();
    }

    public static final class StyleDefinition {
        public String background_color = "";
        public String panel_color = "";
        public String category_list_color = "";
        public String tab_color = "";
        public String tab_hover_color = "";
        public String tab_active_color = "";
        public String card_color = "";
        public String card_hover_color = "";
        public String button_color = "";
        public String border_color = "";
        public String text_color = "";
        public String price_color = "";
        public String balance_color = "";
        /** Legacy all-purpose texture kept for reading existing schema-1 configurations. */
        public String texture = "";
        public TextureDefinition background_texture = new TextureDefinition();
        public TextureDefinition panel_texture = new TextureDefinition();
        public TextureDefinition category_list_texture = new TextureDefinition();
        public TextureDefinition tab_texture = new TextureDefinition();
        public TextureDefinition tab_hover_texture = new TextureDefinition();
        public TextureDefinition tab_active_texture = new TextureDefinition();
        public TextureDefinition card_texture = new TextureDefinition();
        public TextureDefinition card_hover_texture = new TextureDefinition();
        public TextureDefinition button_texture = new TextureDefinition();
        public int border_width = 1;
        public int padding = 4;
        public int opacity = 255;
    }

    public static final class CategoryDefinition {
        public String category_id = "";
        public String title = "";
        public List<String> description = new ArrayList<>();
        public boolean enabled = true;
        public int order = 0;
        public String permission = "";
        public IconDefinition icon = new IconDefinition();
        public StyleDefinition style = new StyleDefinition();
        public int tab_width = 0;
        public int padding = 0;
        public int product_icon_size = 16;
        public List<ProductDefinition> products = new ArrayList<>();
    }

    public static final class ProductDefinition {
        public String slot_id = "";
        public String title = "";
        public List<String> description = new ArrayList<>();
        public boolean enabled = true;
        public long price_xr = 0;
        public int order = 0;
        public int page = 0;
        public int row = 0;
        public int column = 0;
        public String permission = "";
        public Boolean confirmation_enabled = null;
        public String purchase_button_text = "Купить";
        public long purchase_limit = 0;
        public long limit_period_seconds = 0;
        public long cooldown_seconds = 0;
        public String success_message = "";
        public String error_message = "";
        public IconDefinition icon = new IconDefinition();
        public SoundDefinition sound = new SoundDefinition();
        public StyleDefinition style = new StyleDefinition();
        public List<String> purchase_commands = new ArrayList<>();
    }

    public static final class IconDefinition {
        public String type = "ITEM";
        public String item = "minecraft:barrier";
        public int count = 1;
        public int size = 16;
        public String display_nbt = "";
        public String texture = "";
    }

    public static final class SoundDefinition {
        public String sound = "";
        public float volume = 1.0F;
        public float pitch = 1.0F;
    }

    public static final class TextureDefinition {
        public String path = "";
        public String mode = "STRETCH";
        public String anchor = "CENTER";
        public int offset_x = 0;
        public int offset_y = 0;
        public int scale = 100;
    }
}
