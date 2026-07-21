package ru.xrshop.common.config;

public final class ServerConfig {
    public int schema_version = 1;
    public long max_balance = 9_000_000_000_000_000L;
    public int session_timeout_seconds = 120;
    public int editor_timeout_seconds = 900;
    public int rate_limit_window_seconds = 5;
    public int max_purchase_requests_per_window = 4;
    public int sqlite_queue_size = 256;
    public int sqlite_busy_timeout_ms = 5_000;
    public boolean sqlite_wal = true;
    public int max_backups = 32;

    public Limits limits = new Limits();

    public static final class Limits {
        public int max_id_length = 64;
        public int max_title_length = 128;
        public int max_description_lines = 16;
        public int max_description_line_length = 256;
        public int max_categories = 128;
        public int max_products_per_category = 512;
        public int max_commands_per_product = 16;
        public int max_command_length = 1024;
        public int max_permission_length = 128;
        public int max_nbt_bytes = 4096;
        public int max_nbt_depth = 8;
        public int max_json_bytes = 8 * 1024 * 1024;
        public int max_packet_payload_bytes = 24 * 1024;
        public int max_transfer_bytes = 8 * 1024 * 1024;
        public int max_chunks = 512;
        public int max_dimension = 4096;
        public int max_coordinate = 100_000;
        public long max_limit_period_seconds = 365L * 24 * 60 * 60;
        public long max_cooldown_seconds = 30L * 24 * 60 * 60;
    }
}
