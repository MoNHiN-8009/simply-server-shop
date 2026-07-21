# XR Shop 0.1

XR Shop — серверно-авторитетный магазин для Minecraft Forge 1.20.1 и Java 17. Категории, товары, цены, оформление и команды выдачи существуют только в серверном `shop.json`; клиент получает временный отфильтрованный DTO и ничего не сохраняет на диск.

## Быстрый старт

1. Соберите мод командой `gradlew.bat clean build` (Windows) или `./gradlew clean build` (Linux/macOS).
2. Скопируйте `build/libs/xrshop-0.1-all.jar` в `mods` сервера и каждого клиента. Вариант без `-all` является служебным slim-JAR и не содержит SQLite JDBC.
3. Запустите сервер. Будут созданы `config/xrshop/shop.json`, `server.json`, `messages.json`, каталог `backups` и `data/xrshop/xrshop.db`.
4. Настройте магазин через `/ms admin editor` либо отредактируйте серверный `shop.json`, затем выполните `/ms admin reload`.
5. Игрок открывает магазин командами `/ms` или `/ms open`.

Готовый пустой набор файлов лежит в `distribution/config/xrshop`. Он необязателен: мод создаёт такой набор сам. Демонстрационный файл находится только в `examples/shop.example.json` и никогда не устанавливается автоматически.

## Команды

- `/ms`, `/ms open`, `/ms balance`;
- `/ms buy category <category_id> slot <slot_id>`;
- `/ms admin editor|editor_extension|reload|validate|backup`;
- `/ms admin xr get|add|remove|set ...`;
- `/ms admin history [player]`;
- `/ms admin review list|show|refund|resolve ...`.

Права: `minecraftshop.open`, `.buy`, `.balance`, `.admin.editor`, `.admin.reload`, `.admin.validate`, `.admin.backup`, `.admin.currency`, `.admin.history`, `.admin.review`. Открытие, покупка и баланс разрешены по умолчанию; административные права по умолчанию требуют уровень оператора 2. Непустые права категории/товара по умолчанию требуют оператора и могут быть переопределены Forge-совместимым обработчиком Permission API.

## Документация

- [Архитектура](docs/ARCHITECTURE.md)
- [Серверные JSON и создание категорий/товаров](docs/CONFIGURATION.md)
- [Визуальный редактор](docs/EDITOR.md)
- [Расширенный технический редактор](docs/EDITOR_EXTENSION.md)
- [XR, транзакции и REVIEW_REQUIRED](docs/XR_AND_TRANSACTIONS.md)
- [Безопасность, эксплуатация и тесты](docs/OPERATIONS.md)
- [Фактические результаты сборки и smoke-test](docs/TEST_RESULTS.md)
- [Матрица 20 критических инвариантов](docs/INVARIANTS.md)

## Лицензия

MIT. SQLite JDBC упаковывается внутрь итогового JAR средствами Forge Jar-in-Jar.

