# Изолированный native probe Sable

Дата: 5 октября 2026 года. Проверки и запуски выполняет оркестратор проекта. Текущий native profile содержит 10 required GameTest; финальный лог `.tooling/alpha4-native-final.log` подтверждает все 10 и exit 0. Ledger: `.tooling/alpha4-native-final-status.json`. Основное ядро alpha.4 проверено отдельно: 106 JUnit и 10 core GameTest.

`src/sableTest` — отдельный тестовый мод `universe_sable_test`. Его классы, структуры и зависимости не входят в распространяемый Universe Core. Профиль включается флагом Gradle `-PsableTests=true`; обычная сборка ядра не требует Sable.

## Закрепленный стенд

| Компонент | Версия |
| --- | --- |
| Java | 21 |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.251 |
| Sable NeoForge | 2.0.5 |
| Sable Companion | 1.6.0, вложен в Sable; отдельно только compile API |
| Veil | 4.3.2, вложен в Sable |
| Sable Rapier | 2.0.5, вложен в Sable |
| Create | 6.0.10-280 |
| Ponder | 1.0.82+mc1.21.1 |
| Flywheel | 1.0.6 |
| Registrate | MC1.21-1.3.0+67 |

Sable JAR SHA-256: `c8710f85bc780bbf523e6726ceaaf76dd0b8c61479db497b382c6bb34317e7ab`. Это один стенд, а не обещание совместимости со всеми прошлыми или будущими версиями. Версии и состав зависимостей сверены с локальными POM/module, `META-INF/neoforge.mods.toml`, jarjar metadata и [официальной сборкой Sable 2.0.5](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.5-neoforge/neoforge/build.gradle).

## Запуск

Основной воспроизводимый runner:

```powershell
./tools/test.ps1 -Sable
```

Для диагностики отдельного профиля после настройки Java 21 и Gradle:

```powershell
gradle compileSableTestJava -PsableTests=true --console=plain
gradle runSableGameTestServer -PsableTests=true --console=plain
```

Названия задач соответствуют Gradle-профилю в `build.gradle`. Игровой каталог — `run-sable-gametest`; это одноразовый тестовый мир. Для итогового результата нужен непустой отчет GameTest и строка `All N required tests passed` с `N > 0`. Один только `BUILD SUCCESSFUL` недостаточен: NeoForge может завершить процесс с кодом 0 после ошибки `No test functions were given!`.

В этом профиле namespace теста и локального шаблона равен `universe_sable_test`. `@GameTestHolder` обнаруживается сканером NeoForge. Не задавать `templateNamespace="universe"` при включенном только `universe_sable_test`: фильтр регистрации GameTest использует именно namespace шаблона, поэтому тест будет пропущен. `empty.nbt` скопирован из собственного пустого шаблона Universe; SHA-256 обеих копий совпадает (`7a6a35909196c68c3fd0e538d16f68528d5a6dbe88bf0013e16e18e4722592d2`).

## Что проверяет `blocksAndChestRoundTrip`

Тест создает настоящее `ServerSubLevel` в Overworld и один native plot chunk. Он размещает асимметричный набор из четырех блоков: камень, железный блок, дубовую ступень верхней половины с направлением WEST и сундук с направлением NORTH. В сундуке находятся 7 алмазов в слоте 0 и 19 железных слитков в слоте 8; остальные слоты должны быть пустыми.

После появления валидного native `RigidBodyHandle` тест сохраняет plot через публичный `ServerLevelPlot.save()`. В End он выделяет отдельный staging sublevel с новым UUID и загружает snapshot через `ServerLevelPlot.load()`. Исходный sublevel должен оставаться зарегистрированным и содержать исходные блоки до полной проверки staging. Только после совпадения block states, инвентаря, имени и UUID-маркера тест удаляет источник. Затем выполняется такой же перенос End → Overworld; проверяется удаление предыдущего источника, регистрация возвращенного объекта и отсутствие дополнительных непустых блоков или block entities.

Тест использует один и тот же локальный plot slot в обоих контейнерах и требует одинаковых plot origins и размеров. Сериализатор Sable 2.0.5 хранит chunk sections по индексам массива, зависящим от minY измерения. Probe меняет только эти индексы для сохранения исходного мирового Y и удаляет heightmap envelope, чтобы native load заново вычислил heightmaps в целевом измерении. Палитры блоков, BE NBT и native serializer не переписаны и не скопированы из сторонних проектов. Произвольный перенос в другой storage slot этим тестом не проверяется.

При обнаружении любых сущностей в storage/world bounds, contraptions, дополнительных chunks, неизвестного блока или BE probe отказывает до стадий загрузки/удаления источника. Whitelist ограничен четырьмя указанными типами блоков и vanilla chest BE. В завершении probe удаляет только созданные им тестовые объекты. При ошибке такая очистка — очистка одноразового стенда, а не доказательство production rollback.

Два дополнительных required GameTest проверяют границы probe в отдельных batches. `occupiedDestinationPreservesSource` заранее занимает целевой plot slot другим тестовым объектом: отказ должен сохранить источник, четыре block states, сундук, UUID-маркер и occupant; количество объектов не меняется. `unsupportedBlockEntityPreservesSource` добавляет к источнику barrel BE с 11 медными слитками в слоте 2: отказ должен сохранить его вместе с основными блоками и сундуком, без создания staging. Проверяется конкретная причина отказа, чтобы случайная ошибка physics или fixture не засчиталась как успешная защита.

## Дополнительные проверки alpha.4

Три `SablePhysicsCalibrationTests` подтверждают local impulse J/m на массах 6/12, identity/yaw90 и additive world V/ω по native getters и реальному движению за обычные игровые тики. Положения fixtures локальные, |X/Z| ≤ 320: закрепленный Rapier использует float32, а запуск при X=8192 обнаружил существенную потерю точности. Политика production origin/rebasing отдельно не реализована.

Два `SableGravityCompensationTests` временно изменяют live scene N=1/4 только в disposable сервере, подают один local impulse на публичный pre-step, проверяют dt, gameTime, четыре обычных тика, нетронутые control bodies и восстановление original N=2. Сравнение N=1/N=4 подтверждено для 8 случаев. При zero target endpoint V близка к нулю, но поза реально поднимается примерно на 0.05194/0.01294 блока за 0.2 s. Подробности и границы в [SABLE_PHYSICS_CALIBRATION.md](SABLE_PHYSICS_CALIBRATION.md); это не готовый невесомый controller.

Два новых сценария существующего transfer probe: `nonzeroVelocityRoundTrip` и `unavailableVelocityPreservesSource`. Первый сохраняет полный chest NBT и ненулевые V/ω на двух legs, измеряя actual motion после активации. На return делается новый capture. Второй инъецирует реальный invalid handle в capture resolver и проверяет отказ до staging с сохранным источником; это capability fault injection. Остановка целых physics systems используется только для изоляции одноразового стенда. Реально отложенный callback после cleanup проверяет stopped guard; независимая очистка сохраняет незавершенное владение для retry. Подробности: [SABLE_VELOCITY_TRANSFER_IMPLEMENTATION.md](SABLE_VELOCITY_TRANSFER_IMPLEMENTATION.md).

## Границы текущего результата

Успешное выполнение этого теста подтверждает узкий native save/load round trip блоков и сундука в одной закрепленной связке версий. Это не production backend и не телепортация готового корабля вместе со всеми системами. Sable UUID меняется при staging; стабильный UUID здесь — только собственный маркер теста, не окончательная привязка `ShipId` ядра.

Первый неподвижный пассажир теперь проверен отдельными тремя GameTest: [живой Villager roundtrip, компенсирующий возврат и travel-hook refusal](SABLE_PASSENGER_PROBE_IMPLEMENTATION.md). Игроки, движущиеся пассажиры, физические constraints/стыковки, moving contraptions, произвольные BE/capabilities/attachments, scheduled ticks, жидкости, production frame transitions, клиентский трекинг переноса, крупные структуры, 100 циклов и восстановление после аварии требуют следующих реальных проверок. V/ω корпуса подтверждены только узким identity-frame сценарием выше. Валидность physics handle сама по себе не подтверждает качество клиентского рендера.

Исторический результат alpha.2/alpha.3: `.tooling/sable-safety-final.log`, все 3 required, exit 0. Финальный результат alpha.4: `.tooling/alpha4-native-final.log`, `All 10 required tests passed`, exit 0, включая последующее усиление cleanup/gameTime. Подробности и границы результата — в [VALIDATION.md](VALIDATION.md). Полная приемка игровых переходов остается открытой.

Новый test-only результат 5 октября: `.tooling/passenger-native-runs/passenger-20261004T223717148-32256/status.json`, `All 13 required tests passed`, exit 0. Для строгой проверки этого состава: `./tools/test-sable-passengers.ps1`. Исходный failed passenger run сохраняется; готовность world entity sections ожидается до первого side effect переноса. Используется одноразовый сервер с пустыми native scenes и pause всей сцены. Основной JAR alpha.5 не изменен.
