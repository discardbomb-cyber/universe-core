# Настоящий неподвижный пассажир Sable

5 октября 2026: `PASSED_NATIVE_STATIONARY_MOB_PROBE`. Три required GameTest проверили одного живого vanilla Villager на неподвижной палубе Sable 2.0.5. Это отдельный тестовый профиль; production passenger adapter пока отсутствует.

## Проверенное поведение

`oneStandingVillagerRoundTrip` перенес жителя Overworld → End → Overworld через публичный `Entity.changeDimension(DimensionTransition)`. Vanilla создает новый Java object с сохранным UUID. Исходный корпус удаляется только после проверки назначения и пяти последовательных обычных тиков с настоящими `onGround` и Sable deck tracking.

`abortBeforeShipCommitCompensatesVillager` перенес того же пассажира в End и вернул обратно до удаления первоначального корпуса. Это компенсирующая операция, которая сама может отказать; атомарный rollback или crash recovery этим не доказаны.

`actualTravelHookRefusalPreservesOriginalPassenger` отменил настоящий `EntityTravelToDimensionEvent` ровно один раз. API вернул null; исходный объект, полное NBT непосредственно вокруг вызова, позиция, скорость, корпус и сундук сохранились. После обычных тиков житель продолжил стоять на исходной палубе; целевой пассажир не появился.

Житель имеет включенные AI и gravity, movement-speed base 0, health 17, собственный UUID/owner marker, 13 пшеницы и 5 изумрудов в первых двух слотах. Проверяются остальные шесть пустых слотов, выбранные сохраняемые поля NBT, регистрация и полный local/global pose transform. Vanilla `SimpleContainer` уплотняет sparse inventory при загрузке: witness использует contiguous slots и не доказывает сохранность произвольного расположения предметов.

Корпус содержит 25 каменных блоков и сундук с 7 алмазами в слоте 0 и 19 железными слитками в слоте 8. Проверяются все block states, один BE, каждый слот сундука и user-data binding. `plot.save/load` переносит корпус; пассажир переносится отдельно штатным entity API. Подмена tracking, ручной `entity.tick` и клонирование/refill инвентаря не используются.

## Исправленный отказ

Первый прогон `passenger-20261004T222149565` остается **FAILED**: из 13 required два новых перехода остановились на `Destination registration/UUID mismatch`; отмена и прежние десять прошли. `getEntity(UUID)` читает visible entity storage. FULL chunk и наличие ticket не гарантируют, что секции сущностей уже доступны и тикают.

Теперь перед свежим capture и каждым переходом стенд обычными тиками ожидает публичные `areEntitiesLoaded` и `isPositionEntityTicking` для целевой bounding volume. Собственный region ticket удерживает назначение; preflight повторно проверяет source/target readiness. Лимит ожидания 80 тиков, общий GameTest timeout 900. Строгие немедленные проверки UUID, уровня, позиции и скорости сохранены. Успешные outward ожидания: 72/68/52 тика, возвраты: 0 дополнительных тиков после первого отложенного callback.

Временный non-cancelling join observer принимает точную собственную целевую сущность по типу, UUID, owner и уровню для cleanup при исключении во время регистрации. Listener снимается в finally. Это source-reviewed cleanup path; injected partial-throw runtime сценарий пока не выполнен. `getAllEntities` перечисляет видимые сущности, поэтому счетчик одного UUID не доказывает уникальность среди всех hidden sections.

## Доказательства

- Run: `.tooling/passenger-native-runs/passenger-20261004T223717148-32256/status.json`, `PASSED_NATIVE_STATIONARY_MOB_PROBE`.
- Лог: `native-gametest.log`, SHA-256 `c7e96c47d00608e9225acb3993aa93bc95c3c87be71e13a893520341b8724aa4`.
- Source: `src/sableTest/java/dev/heiko/universe/sabletest/SableStandingPassengerProbe.java`, SHA-256 `587652f2bccf2234fcff697feba93ade350e549d6c62e1c8b758219eb7b1c973`.
- Minecraft 1.21.1, NeoForge 21.1.251, Sable 2.0.5, Java 21.0.12.1+1. Все 13 required прошли, включая прежние 10. Игровой отчет: 6.167 s; Gradle exit 0, штатная остановка/сохранение. Продолжительность теста не является нагрузочным benchmark.
- Независимое подтверждение: [REVIEW_PASSENGER_NATIVE_CONFIRMED.md](REVIEW_PASSENGER_NATIVE_CONFIRMED.md).

```powershell
./tools/test-sable-passengers.ps1
```

Runner отказывает при совпадении каталога runId, требует ровно 13 завершенных/успешных required, отсутствие failure/error summary и каждый из трех реальных single-test batches. Обновленные acceptance gates отдельно приняты на фактическом логе и отклонили девять отрицательных текстовых вариантов; второй JVM ради этой проверки не запускался. Исходный успешный ledger сохраняется. Основной JAR alpha.5 не изменен; test sources исключены из него.

## Открытые gates

Настоящий подключенный ServerPlayer и клиентский ACK/трекинг; движение и ненулевые V/ω вместе с пассажиром; seat/passenger tree; два стыкованных native корпуса; отказ одного участника; disconnect, повторный запрос и restart/recovery. Сейчас используются pause всей изолированной physics scene, новый native UUID и тот же plot slot. Production quarantine, stable ShipId mapping, произвольные BE/capabilities и большие структуры остаются открытыми.
