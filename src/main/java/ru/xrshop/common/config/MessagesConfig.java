package ru.xrshop.common.config;

public final class MessagesConfig {
    public int schema_version = 1;
    public String no_permission = "Недостаточно прав.";
    public String player_only = "Команда доступна только игроку.";
    public String balance = "Баланс: {balance} XR";
    public String purchase_started = "Покупка обрабатывается…";
    public String purchase_success = "Покупка выполнена. Баланс: {balance} XR";
    public String insufficient_funds = "Недостаточно XR. Баланс: {balance} XR";
    public String invalid_product = "Категория или товар не найдены.";
    public String invalid_command = "Команда товара недействительна. Покупка отменена.";
    public String active_purchase = "Предыдущая покупка ещё обрабатывается.";
    public String rate_limited = "Слишком много запросов. Повторите позже.";
    public String store_updated = "Магазин обновлён. Откройте его заново.";
    public String limit_reached = "Лимит покупок этого товара исчерпан.";
    public String cooldown = "Товар снова будет доступен через {seconds} сек.";
    public String internal_error = "Внутренняя ошибка магазина. Средства не потеряны.";
    public String review_required = "Покупка требует проверки администратором. ID: {transaction_id}";
    public String editor_saved = "Конфигурация магазина сохранена.";
    public String editor_conflict = "Конфигурация изменилась. Закройте редактор и откройте его заново.";
    public String validation_ok = "Ошибок в конфигурации не найдено.";
}
