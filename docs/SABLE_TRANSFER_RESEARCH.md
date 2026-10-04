# Перенос реальных объектов Sable между измерениями

Дата исследования: 4 октября 2026 года. Статус: исследование API и опубликованного байткода; игровых переносов в этой работе не выполнялось. Сборка, зависимости и реализация Universe не изменялись. Телепортация одного игрока не считается переносом корабля.

## Вывод и рекомендуемый backend

Реальный перенос sublevel возможен через сериализацию plot, создание sublevel в целевом контейнере и загрузку содержимого. В исследованном Sable 2.0.5 нет отдельного публичного метода `changeDimension` у `ServerSubLevel`, `SubLevelContainer`, `ServerSubLevelContainer` или `PhysicsPipeline`. `RigidBodyHandle.teleport` перемещает тело в текущем физическом мире: это не смена его Minecraft-измерения.

**Предлагаемый проверяемый backend: собственный ограниченный `SablePlotTransferBackend` поверх публичных API Sable 2.0.5**, сначала для одного небольшого sublevel с разрешенным набором блоков/BE и простым составом экипажа. Это предложение следующей реализации, не уже работающий backend. SablePortLibrary 1.0.0 использовать как сравнительный экспериментальный кандидат и источник понимания форматов, а не объявлять готовым надежным переносчиком. Простая обертка над ее `teleportFamily` не устранит удаление исходного состояния до успешной загрузки назначения.

Если нужен именно сторонний backend, ближе всего **SablePortLibrary 1.0.0**, но допускать его в игровые перелеты только после исправления транзакции, пассажиров и скоростей, проверки точной связки версий и устранения неоднозначности лицензии. Dimensional-Sable 1.0.5 менее пригоден для сохранения идентичности и посадочных мест.

## Зафиксированные артефакты

| Компонент | Проверенная версия / источник |
| --- | --- |
| Minecraft / текущий проект NeoForge | 1.21.1 / 21.1.251, согласно локальному `gradle.properties`; совместный runtime не тестировался |
| Sable | `dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.5`, локальный JAR, SHA-256 `c8710f85bc780bbf523e6726ceaaf76dd0b8c61479db497b382c6bb34317e7ab` |
| Sable исходник соответствующего тега | `mc1.21.1-2.0.5-neoforge`, commit `6966d2928340de7631abcecf8549904b877df0a8` |
| Sable Companion | В кэше `sable-companion-common-1.21.1:1.6.0`; Sable TOML запрещает более новые версии Companion, а не обещает совместимость с ними |
| Dimensional-Sable | Исследован source commit `bb2211cfbb7af150245891276d79170e60d80c31`, версия 1.0.5; Modrinth `l9l5j4Zh`; публикация 23 июня 2026 года |
| SablePortLibrary | Опубликованный NeoForge JAR `sableportlibmod-1.0.0.jar`, Modrinth project `30Y05C73`, version `VSHMbEqb`; публикация 25 июля 2026 года по времени Бишкек |
| SablePortLibrary хеши | SHA-1 `1a48bbb9a0f0eeb478373704a5c5a727a2f7a7a5` совпал с метаданными Modrinth; SHA-256 `ac69ad5b293b3339110c9ab049fb4e16934afa0e280d94cc0f262309a7bea9d0` |
| SablePortLibrary исследованный source | Commit `d0f4912a1d5661cc3be6f18aaddf85afcdac80c2`, 8 августа 2026 года по времени Бишкек; он новее публикации JAR, поэтому совпадение исходника и артефакта целиком не предполагается |

Локальный Sable прочитан из `.tooling/gradle/caches/modules-2/files-2.1/dev.ryanhcode.sable/sable-neoforge-1.21.1/2.0.5/5f666e973d32baaaf405acb9bbed6615b909971/`. Через `javap` проверены сигнатуры; через `javap -c -p` — ключевые вызовы сериализации и опубликованного переносчика. JAR SablePortLibrary загружен только во временный исследовательский каталог, не установлен в проект. Различия дат означают, что выводы о JAR ниже основаны также на его байткоде.

## Доступные API Sable 2.0.5

Пакеты и сигнатуры из локального JAR:

```java
// dev.ryanhcode.sable.api.sublevel.SubLevelContainer
static ServerSubLevelContainer getContainer(ServerLevel level);
SubLevel allocateNewSubLevel(Pose3d pose);
SubLevel allocateSubLevel(UUID uuid, int plotX, int plotZ, Pose3d pose);
SubLevel getSubLevel(UUID uuid);
void removeSubLevel(SubLevel subLevel, SubLevelRemovalReason reason);

// dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot
CompoundTag save();
void load(CompoundTag tag);

// dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle
static RigidBodyHandle of(ServerSubLevel subLevel);
Vector3d getLinearVelocity(Vector3d dest);
Vector3d getAngularVelocity(Vector3d dest);
void addLinearAndAngularVelocity(Vector3dc linear, Vector3dc angular);
void teleport(Vector3dc position, Quaterniondc orientation);
boolean isValid();
```

`ServerSubLevel` предоставляет `getUniqueId`, `getPlot`, `getLevel`, `logicalPose`, `getName/setName`, `getUserDataTag/setUserDataTag`. `ServerSubLevelContainer` предоставляет physics system и force-load tickets. Это строительные элементы собственного переноса, не готовая транзакция. `allocateSubLevel` получает локальные координаты слота контейнера; `plotPos` и origin нельзя смешивать с внешней позой корабля. [Исходник контейнера закрепленной версии](https://github.com/ryanhcode/sable/blob/6966d2928340de7631abcecf8549904b877df0a8/common/src/main/java/dev/ryanhcode/sable/api/sublevel/SubLevelContainer.java).

В `PhysicsPipeline` доступны `resetVelocity`, `addLinearAndAngularVelocity`, `getLinearVelocity/getAngularVelocity`, `teleport`, `wakeUp`. Базовая реализация добавления скорости в интерфейсе пуста: наличие метода не доказывает поведение выбранного pipeline. Проверять фактический physics backend и валидность handle. Для установки желаемой скорости можно вычислять приращение `desired-current`, но результат нужно считывать после обработки физического шага. Скорости Sable не следует автоматически трактовать как единицы чистой космической модели Universe. [PhysicsPipeline 2.0.5](https://github.com/ryanhcode/sable/blob/6966d2928340de7631abcecf8549904b877df0a8/common/src/main/java/dev/ryanhcode/sable/api/physics/PhysicsPipeline.java).

`ServerLevelPlot.save()` сериализует **загруженные** chunks, block states, BE NBT, scheduled block/fluid ticks и chunk attachments. `load()` восстанавливает содержимое, регистрирует BE и перестраивает массу/физические секции. Это полноценное содержимое, не визуальный proxy, но первая загрузка проходит по ячейкам непустых секций. Нельзя переносить сюда выводы чистого benchmark о постоянной цене движения. До сохранения нужно доказать полноту загруженного plot; `load` требует совпадения `log_size` и допустимой версии данных. [ServerLevelPlot 2.0.5](https://github.com/ryanhcode/sable/blob/6966d2928340de7631abcecf8549904b877df0a8/common/src/main/java/dev/ryanhcode/sable/sublevel/plot/ServerLevelPlot.java).

## Dimensional-Sable 1.0.5

Реальный публичный entry point находится в `dev.egg.SubLevelWarper`:

```java
public static int WarpSubLevel(ServerSubLevel source, Level dimension);
public static int WarpSubLevel(ServerSubLevel source, Level dimension, Vector3d position);
```

README упоминает четвертый аргумент `warpConnected`, но в проверенном commit соответствующая перегрузка **private**. Публичный вызов переносит connected chain через `SubLevelHelper.getConnectedChain`. Возвращается число членов, а не новый root или карта UUID. Используются новые sublevel с новыми UUID; BE-ссылки переписываются через реестр fixers. Это требует отдельного восстановления привязки `ShipId` и портов. [SubLevelWarper](https://github.com/hollow-egg/Dimensional-Sable/blob/bb2211cfbb7af150245891276d79170e60d80c31/src/main/java/dev/egg/SubLevelWarper.java).

Содержимое копируется custom-шаблоном plot, а Create contraptions перед переносом разбираются. Выбор сущностей основан на AABB; вызов `inflate` не присваивает возвращенный box. `TeleportEntity` вызывает `unRide`, не восстанавливает посадочные места и не устанавливает сохраненные линейную/угловую скорости корабля. Название копируется, `userDataTag` явно не переносится в этом коде. Движущиеся механические поршни отмечены как проблемные. Поэтому «корабль перенесен» не равнозначно «все его системы и пассажиры сохранились». [Шаблон](https://github.com/hollow-egg/Dimensional-Sable/blob/bb2211cfbb7af150245891276d79170e60d80c31/src/main/java/dev/egg/SubLevelTemplate.java), [описание ограничений](https://github.com/hollow-egg/Dimensional-Sable/blob/bb2211cfbb7af150245891276d79170e60d80c31/README.md).

Source настроен на Sable 2.0.3, NeoForge 21.1.233, Create 6.0.10-280 и runtime Aeronautics 1.3.0. Это не тест совместимости с Sable 2.0.5. [Настройки](https://github.com/hollow-egg/Dimensional-Sable/blob/bb2211cfbb7af150245891276d79170e60d80c31/gradle.properties), [зависимости](https://github.com/hollow-egg/Dimensional-Sable/blob/bb2211cfbb7af150245891276d79170e60d80c31/build.gradle).

## SablePortLibrary: опубликованный API и границы сохранности

В JAR 1.0.0 проверен пакет `com.sableport.mod.API` — регистр `API` существенен:

```java
// com.sableport.mod.API.SablePortAPI
public static @Nullable ServerSubLevel teleportFamily(
    ServerSubLevel source, ServerLevel targetLevel,
    Vector3dc targetPosition, @Nullable Quaterniondc targetOrientation);
public static @Nullable ServerSubLevel getFamilyRoot(ServerSubLevel source);
public static List<ServerSubLevel> getFamily(ServerSubLevel source);
public static void registerTeleportStateAdapter(SubLevelTeleportStateAdapter<?> adapter);
```

Дополнительно доступны bounding box, center, minimum/maximum Y семейства. `null` orientation сохраняет исходную ориентацию; ненулевая задает изменение ориентации root и относительных поз остальных членов. Возвращается новый объект root; старые Java references нужно перечитать. [API исходника](https://github.com/jbookout98/sableportlibmod/blob/d0f4912a1d5661cc3be6f18aaddf85afcdac80c2/src/main/java/com/sableport/mod/API/SablePortAPI.java), [метаданные проверенного артефакта](https://api.modrinth.com/v2/version/VSHMbEqb).

| Данные | Что обнаружено / что нельзя обещать |
| --- | --- |
| Блоки и BE | Используются native `plot.save/load`; координаты NBT и ticks переписываются. Полнота зависит от загруженных chunks и схем конкретных BE |
| UUID и имя sublevel | Новый объект создается с исходным UUID; имя копируется. Runtime ID/ссылка объекта не являются прежними |
| Пользовательские данные | `userDataTag` копируется; произвольные ссылки на старые координаты внутри этих данных требуют отдельного адаптера |
| Линейная и угловая скорость корабля | Считываются перед переносом, но восстановление отсутствует: JAR вызывает `resetVelocity`, не вызывает `addLinearAndAngularVelocity` |
| Игроки | Выбираются по расширенным AABB, координаты преобразуются относительно root, yaw/pitch сохраняются; вычисленная преобразованная скорость не назначается |
| Обычные сущности | Сериализуются и пересоздаются, `deltaMovement` назначается в прежнем мировом направлении; поворот скорости относительно новой ориентации не применяется |
| Посадочные места и вложенные пассажиры | `loadEntityRecursive` и затем `addFreshEntity` не доказывают регистрацию всего дерева. Нет явного восстановления `startRiding` для игроков; возможен повторный захват пассажира отдельно от транспорта |
| Связанные объекты | Family определяется эвристическим поиском UUID в plot/userData, не исключительно настоящими физическими constraints |

Поведение скоростей и ключевые вызовы проверены в байткоде опубликованного JAR; расширенный анализ — по [переносчику](https://github.com/jbookout98/sableportlibmod/blob/d0f4912a1d5661cc3be6f18aaddf85afcdac80c2/src/main/java/com/sableport/mod/teleport/SubLevelDimensionTeleport.java), [обработчику сущностей](https://github.com/jbookout98/sableportlibmod/blob/d0f4912a1d5661cc3be6f18aaddf85afcdac80c2/src/main/java/com/sableport/mod/teleport/SubLevelEntityHandler.java), [иерархии](https://github.com/jbookout98/sableportlibmod/blob/d0f4912a1d5661cc3be6f18aaddf85afcdac80c2/src/main/java/com/sableport/mod/storage/SubLevelHierarchyPerDimension.java). Family-query сама вызывает сериализацию plot и сканирует данные; это не операция для каждого тика.

Адаптер состояния имеет `id()`, `isAvailable()`, `capture(ServerLevel, List<ServerSubLevel>, ServerSubLevelContainer)` и `restore(ServerLevel, T, Map<UUID, PlotTranslation>)`. Он подходит для дополнительных внешних систем, но не предоставляет подготовку/commit/rollback. Исключение `restore` возникает уже после удаления источника. [Контракт адаптера](https://github.com/jbookout98/sableportlibmod/blob/d0f4912a1d5661cc3be6f18aaddf85afcdac80c2/src/main/java/com/sableport/mod/teleport/state/SubLevelTeleportStateAdapter.java), [реестр](https://github.com/jbookout98/sableportlibmod/blob/d0f4912a1d5661cc3be6f18aaddf85afcdac80c2/src/main/java/com/sableport/mod/teleport/state/SubLevelTeleportStateRegistry.java).

## Критические ограничения стороннего backend

Байткод 1.0.0 подтверждает порядок: capture сущностей с удалением оригиналов → поиск свободных слотов → удаление source sublevel → allocate/load назначения. При нехватке slots возвращается `null` уже после удаления обычных сущностей. При ошибке загрузки записывается лог и продолжается выполнение; ненулевой root сам по себе не подтверждает целостность семьи. Нет долговременного журнала операции, идемпотентного UUID запроса и гарантированного rollback. Повтор вызова после сомнительного результата опасен.

Сдвиг section indices учитывает разные minY, но отрицательные indices выбрасываются; верхняя граница целевого массива секций не проверяется переводчиком. Перенос Overworld → Nether не гарантирует сохранение корпуса за пределами высоты назначения. Требуются одинаковые plot sizes и проверка всех занятых Y до вызова. [NBT translator](https://github.com/jbookout98/sableportlibmod/blob/d0f4912a1d5661cc3be6f18aaddf85afcdac80c2/src/main/java/com/sableport/mod/nbt/SubLevelNBTTranslator.java).

NBT-правила эвристические: произвольные int[3]/compound координаты могут ошибочно считаться BlockPos; неизвестные форматы, capabilities и ссылки на измерения не становятся совместимыми автоматически. Выбор игроков и сущностей по AABB способен захватить стоящих рядом посторонних. Same-dimension shortcut перемещает только root и сбрасывает его скорость; нельзя использовать его как доказательство переноса всей семьи.

Source SablePortLibrary настроен на Sable 2.0.3, Companion 1.6.0, NeoForge 21.1.228. В опубликованном TOML минимальный Sable указан как 1.2.2, что слишком широко для доказательства совместимости. Дополнительно Modrinth dependency `hyQUls27` указывает на Sable 2.0.3 **Fabric**, хотя сама библиотека NeoForge: нельзя автоматически использовать этот resolved dependency для тестовой сборки. Закреплять нужный Sable NeoForge явно. [Source настройки](https://github.com/jbookout98/sableportlibmod/blob/d0f4912a1d5661cc3be6f18aaddf85afcdac80c2/gradle.properties), [dependency metadata](https://api.modrinth.com/v2/version/hyQUls27).

## Предлагаемый контракт собственного переносчика

`ShipId` Universe остается стабильным; Sable UUID и dimension binding хранятся отдельно. Первая реализация допускает один sublevel без внешних constraints; сложная family, неподдержанные BE, активные contraptions и неподтвержденные passenger trees получают отказ до удаления чего-либо.

1. На серверном потоке заблокировать повторную операцию, собрать явный roster экипажа/сущностей и снимок поз/скоростей. Загрузить все нужные chunks с ограничением размера; проверить minY/height, plot size, slots, место прибытия и известные NBT-схемы.
2. Сохранить подготовленный журнал и snapshot до разрушительной стадии. Создать staging sublevel назначения с отдельным UUID, загрузить данные, проверить контрольные суммы блоков/BE и подготовить регистрацию сущностей. Внешние ссылки переписывать по проверенному реестру, не общим угадыванием координат.
3. Только после валидации выполнить commit: обновить привязку стабильного ShipId, перенести roster и восстановить посадочные места, удалить source, восстановить физические скорости через проверенный pipeline. Если задан поворот базиса `Q`, преобразовать линейную/угловую скорости по явно выбранной политике, а не случайно занулить их.
4. После рестарта сверить журнал и оба контейнера: выбрать единственное каноническое представление, заморозить конфликт и не копировать повторно. Crash-атомарность нескольких сохранений не предполагается; ее восстановительные правила требуют отдельного стенда.

Это будущая реализация. Публичные API позволяют собрать такой прототип, но не доказывают надежность стадий, глобальных UUID-кэшей, физической очереди или сохранения при аварии. Staging не должен быть доступен игрокам/физическому столкновению до commit; возможность надежно изолировать его — отдельный ранний тест.

## Проверки, которые выполняем сами

Стенд закрепляет Minecraft 1.21.1, NeoForge 21.1.251, Sable 2.0.5, Companion 1.6.0 и SHA backend. Начать с небольшой асимметричной структуры, затем 100 реальных циклов поверхность → космос → поверхность. После каждого сравнить block states, BE NBT инвентаря, жидкости, scheduled ticks, stable ShipId, список членов/сущностей и уникальность UUID. Подтверждать отсутствие источника и наличие полноценного назначения, не только положение игрока.

Отдельно: игрок пешком и в кресле, моб с пассажиром, вложенное дерево, рамка/картина, потеря подключения; ненулевые линейная/угловая скорости, поворот назначения, physics step; разные minY/height, край диапазона, заполненный контейнер, неподдержанный BE, внешняя связь; исключения capture/load/restore и рестарт на каждой стадии. Несовпадение приводит к отказу/блокировке, не к заявлению успеха. Два клиента проверяют трекинг, mesh и взаимодействие с BE после переноса; dedicated server проверяет сохранение и возврат.

`D:\EX-twins` прочитан только как локальное доказательство использования `SubLevel.getPlot()` и `RigidBodyHandle` в адаптере/дымовых тестах. Обнаруженные `player.teleportTo` там не доказывают межизмерительный перенос sublevel. Файлы этого проекта не менялись.

## Лицензии и распространение

Sable JAR и закрепленный source указывают **PolyForm Shield 1.0.0**. Это не MIT: лицензия содержит ограничения конкурирующих продуктов и требования уведомлений при распространении. Здесь фиксируются найденные условия, а не окончательная правовая квалификация Universe. Предпочитать зависимость и публичные API, не переносить Sable serializer в свой код как свободно лицензированный фрагмент. [Sable LICENSE](https://github.com/ryanhcode/sable/blob/6966d2928340de7631abcecf8549904b877df0a8/LICENSE.md).

Dimensional-Sable содержит MIT с copyright Hollow-Egg 2026; сохранять notice при использовании существенных частей. В custom template есть указание происхождения из другого проекта, поэтому общий MIT не следует автоматически приписывать всем заимствованным фрагментам. [Лицензия](https://github.com/hollow-egg/Dimensional-Sable/blob/bb2211cfbb7af150245891276d79170e60d80c31/LICENSE).

У SablePortLibrary **противоречивые признаки**: Modrinth заявляет MIT; `LICENSE.txt` оговаривает только файлы шаблона NeoForged MDK; `gradle.properties` и опубликованный `neoforge.mods.toml` содержат `All Rights Reserved`. Поэтому нельзя уверенно объявлять авторский transfer-код MIT или копировать/распространять измененный JAR на основании одной карточки Modrinth. До прояснения использовать артефакт лишь для собственного технического исследования и не встраивать его код в распространяемый Universe. [License source](https://github.com/jbookout98/sableportlibmod/blob/d0f4912a1d5661cc3be6f18aaddf85afcdac80c2/LICENSE.txt), [карточка проекта](https://modrinth.com/mod/sable-port-library).

Итог исследования: реальный перенос содержимого доказуем на уровне доступных механизмов и стороннего байткода, но адекватный игровой переход всех объектов, BE, пассажиров и скоростей еще требуется реализовать и проверить. Готовой безопасной межизмерительной транзакции в исследованных артефактах не установлено.
