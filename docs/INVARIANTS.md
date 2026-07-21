# Подтверждение критических инвариантов

1. Категории и товары создаются сервером: универсальные модели находятся в `ShopConfig`, операции — в `EditorService`, предустановленных реализаций нет.
2. Содержимое, цены, позиции, стиль и команды находятся в серверном `config/xrshop/shop.json`: путь и загрузка задаются `ConfigManager`.
3. В Java/lang/client resources нет конкретных категорий/товаров: это подтверждено статическим аудитом; единственный пример находится вне runtime в `examples/shop.example.json`.
4. Используются только универсальные `CategoryDefinition`/`ProductDefinition` в `ShopConfig`; enum конкретного содержимого отсутствует.
5. Первый запуск вызывает `ShopConfig.empty()` и создаёт `categories: []`; это проверено `ConfigManagerTest` и runtime smoke-test.
6. Клиент не получает серверный файл целиком и ничего не пишет на диск: `StoreViewFactory` строит DTO, `ClientState` хранит только память; в client package нет файлового I/O.
7. Каждый `/ms` или `/ms open` вызывает `ServerRuntime.openStore`, создаёт новую сессию и новый DTO.
8. DTO формируется из текущего проверенного `ShopSnapshot` классом `StoreViewFactory`.
9. `StoreScreen` полностью строит категории, страницы, карточки, цвета, иконки и позиции из `StoreViewDto`.
10. `StoreScreen.removed`, `ClientState.closeStore` и logout очищают DTO, session/revision, категории, товары и баланс.
11. `StoreViewDto.ProductDto` не имеет команд; это дополнительно проверяет `StoreViewFactoryTest`.
12. Команды доступны только в `EditorDto` после `ModPermissions.EDITOR`; `EditorScreen.removed`, `AdvancedEditorScreen.removed` и `ClientState.clearEditorMemory` удаляют их.
13. `StoreScreen.buy` отправляет только строку `ms buy category <id> slot <id>` через стандартный `ClientPacketListener.sendCommand`.
14. Кнопка не отправляет цену, баланс, команду, ItemStack или результат; сетевого purchase-пакета не существует.
15. `PurchaseService.buy` повторно находит пару ID в текущем `ShopSnapshot`.
16. `PurchaseService` получает шаблоны команд только из серверного `ProductEntry` и выполняет их `MinecraftServer.getCommands()` с console source.
17. Проверки, rate limit, revision, balance, SQL reserve, списание и состояния выполняются `PurchaseService`, `CurrencyService` и `SqliteDatabase`.
18. Клиентский `StoreScreen`/`LayoutCalculator` выполняет только раскладку, scroll/page, hover, tooltip, icon cache и render.
19. Игровые/финансовые/административные решения находятся в server package; C2S editor packets повторно проверяются `EditorService`.
20. JSON хранит конфигурацию, SQLite — только balances/ledger/purchases/results/limits/audit; схема зафиксирована в `V1__initial.sql`.
