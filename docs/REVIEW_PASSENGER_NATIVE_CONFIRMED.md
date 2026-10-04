# Независимое подтверждение native passenger-стенда

**Подтверждены три ограниченных серверных GameTest с настоящим стационарным vanilla Villager на изолированных Sable-корпусах: roundtrip, compensating return и настоящий travel-hook refusal.** Вердикт относится только к `passenger-20261004T223717148-32256` и указанной ревизии тестового исходника. Это самостоятельное read-only ревью готовых лога/ledger/source; JVM, Gradle и Minecraft ревьюером не запускались. Исходники, original ledger и прежние FAILED evidence не изменены.

## Точные артефакты

| Поле | Значение |
|---|---|
| runId | `passenger-20261004T223717148-32256` |
| начало / конец UTC | `2026-10-04T22:37:17.1545721Z` / `2026-10-04T22:37:52.0566596Z` |
| original status | `PASSED_NATIVE_STATIONARY_MOB_PROBE` |
| Gradle exit | `0` |
| GameTest time | `6.167 s`; BUILD SUCCESSFUL in34s |
| runtime из actual log | Java `21.0.12.1+1-LTS`, Minecraft `1.21.1`, NeoForge `21.1.251`, Sable `2.0.5`, RapierPhysicsPipelineProvider |
| status.json SHA256 | `a6ead6f6838a8da063e66cc568188ab8cd44df82f5a4bed24a2f7881582d2760` |
| native-gametest.log SHA256 | `c7e96c47d00608e9225acb3993aa93bc95c3c87be71e13a893520341b8724aa4` |
| SableStandingPassengerProbe.java SHA256 | `587652f2bccf2234fcff697feba93ade350e549d6c62e1c8b758219eb7b1c973` |
| tracked alpha.5 JAR SHA256 | `d807cd1f1e3d6f04abb47ab3be6692428a520a3a9d939bc9cef829ee55583ebc` |

Log и текущий exact source заново хешированы и совпадают с original ledger. Ledger расположен в `.tooling/passenger-native-runs/passenger-20261004T223717148-32256/status.json`, лог рядом. Это opt-in `sableTest` development GameTest, а не клиентский или packaged-JAR gameplay smoke. Сuffix32256 относится к runner PID, **не доказывает PID игрового JVM**: отдельные PID/runtime nonce/compiled-class snapshot в этом ledger не записаны. Alpha.5 artifact hash отслеживается; полное sealed provenance всех загруженных runtime binaries этим ревью не заявлено.

## Фактический запуск всех обязательных тестов

Независимый anchored parsing actual лога установил:

- **13** строк `Running test batch '<name>:0' (1 tests)...`, 13 уникальных имен, каждое присутствует ровно один раз; набор совпал с 13 `@GameTest` текущих `sableTest` sources.
- Ровно одна итоговая строка `13 GAME TESTS COMPLETE IN 6.167 s` и ровно одна `All 13 required tests passed :)`.
- Нет `required tests failed`, `SABLE_PASSENGER_FAILED`, ERROR/FATAL, BUILD FAILED или failed run-task summary. После успеха идут штатные `Stopping server`, сохранение трех измерений, `Game test server shutting down` и BUILD SUCCESSFUL.
- `runSableGameTestServer` фактически выполнен; UP-TO-DATE строки относятся к подготовке/зависимостям. `compileSableTestJava` также выполнен. Итог не принят по одному BUILD SUCCESSFUL.

Три новые actual batch: `sable_passenger_compensating_return`, `sable_passenger_standing_roundtrip`, `sable_passenger_travel_refusal`. Остальные десять: `sable_gravity_compensation_one_step`, `sable_gravity_compensation_four_steps`, `sable_native_roundtrip`, `sable_native_occupied`, `sable_native_unsupported`, `sable_velocity_roundtrip`, `sable_velocity_unavailable`, `sable_calibration_impulse_identity`, `sable_calibration_impulse_yaw90`, `sable_calibration_velocity_world`.

Отдельный source audit общего runner выявил слабые acceptance predicates: счетчик completion/required failures только записывался, batch проверялся substring, существующий каталог не отвергался. Root усиливает runner отдельно. **Подтверждение этого exact run основано на непосредственной проверке полных actual строк и хешей**, а не на предположении, что прежний runner уже исключал любые stale/contradictory logs. Original ledger не переписывается под будущий runner.

## Четыре настоящих переноса

Во всех четырех `SABLE_PASSENGER` receipts: `sourceShipAlive=true`, `oldEntityRemoved=true`; local anchor `(20481029.5,65,20481029.5)`. Сохраняется UUID самого Villager; новый entity object создается обычным vanilla lifecycle, а native target carrier имеет свой UUID.

| Сценарий / leg | Passenger UUID | logicalShip | target carrier UUID | Готовность до операции |
|---|---|---|---|---|
| compensation outward → End | `e05f9182-54a6-4bb5-97e7-25ecdfbf0a20` | `8bcb2d9c-7665-4e7f-8fd9-9e79fdc488b5` | `3e6ef147-bad6-404c-8dbc-ab69ec2ac0b8` | waited72, entitiesLoaded/entityTicking true |
| compensating return → Overworld | тот же | тот же | `720d2a22-d189-4fa2-8f62-417d9749f589` | waited0, оба true |
| roundtrip outward → End | `023b1194-e5c9-45a3-b5d0-41ed15f4b2d4` | `9681ece7-bd00-4624-a005-c0327c1aecf2` | `cdcba502-474a-4421-860b-47eaa991153c` | waited68, оба true |
| roundtrip return → Overworld | тот же | тот же | `c77ae0a0-52ab-46c7-b28c-277cc2f5d30e` | waited0, оба true |

Четыре target carrier UUID различны. Actual global destinations: compensation outward `(192.02662977525074,160.47660344189936,191.99133442558974)`, compensation return `(66.4769287109375,160.9951629638672,65.30657196044922)`; roundtrip outward тот же End destination, return `(64.02476944488652,160.47660344189936,64.01306566912864)`.

Source выполняет bounded ordinary-tick `worldReady` **до** `changeDimension`; actual world bbox chunks должны пройти `areEntitiesLoaded` и `isPositionEntityTicking`. В preflight оба predicates проверяются снова после свежей capture. Эти readiness gates относятся к world-позиции моба, а не storage-grid чанку корпуса; WaitLimit80, GameTest timeout900 ticks. Лог показывает их реальное прохождение без ослабления immediate target lookup.

Обычный API `old.changeDimension(DimensionTransition)` должен вернуть другой exact Villager object с тем же UUID/OWNER. Проверяются old removal reason `CHANGED_DIMENSION`, отсутствие UUID в source visible lookup, exact target registration, immediate world position/local mapping <1e-5, speed <1e-6, yaw/pitch <1e-4. Затем пять последовательных **естественных** entity tick receipts с `onGround` и exact `Sable.HELPER.getTrackingSubLevel(mob)==ship` предшествуют commit удаления исходного native carrier. Raw tracking setter, manual entity ticking и fabricated collision отсутствуют.

Villager AI/gravity остаются включены; fixture base movement speed0. Проверяются здоровье17, UUID/OWNER, имя/visibility, invulnerable/persistence и inventory: wheat13 вslot0, emerald5 вslot1, остальные шесть пусты. Native carrier содержит 25 stone deck blocks и chest —26 blocks, один BE; chest diamond7 вslot0, iron19 вslot8, остальные пусты. Состояние/binding перепроверяются на staged и retained carriers. Это selected-field preservation; произвольные Brain/Offers/attachments и все виды NBT этим не покрыты.

Compensation сохраняет оригинальный source carrier и переносит Villager назад на baseline local anchor **новым vanilla изменяющим вызовом**, после чего удаляет staged target. Это успешная compensating return при удержанных isolated scenes, **не атомарный rollback**, durable recovery или восстановление после аварии/ошибки регистрации.

## Настоящий отказ travel hook

Actual refusal UUID: `31f5699d-98b1-4e28-9775-5a1baffb3fad`; End readiness waited52, оба public predicates true. Лог: `SABLE_PASSENGER_REFUSAL callbacks=1 ... sourceSameObject=true targetMobAbsent=true originalCarrierAlive=true`.

В exact source этот receipt достижим после вызова того же vanilla API с HIGHEST listener, проверок **одного первоначально uncancelled** owned `EntityTravelToDimensionEvent`, фактического `setCanceled(true)` и `returned==null`. Исходный Villager не клонируется/не оживляется/не пополняется. Синхронно сравниваются полный source NBT, pose/speed, native carrier pose и chest NBT; затем еще пять естественных standing ticks, selected state, source registration, target absence и исходный carrier. Travel и non-cancelling join listeners снимаются в finally; повторный callback/утекший listener блокирует succeed.

## Очистка и границы приемки

`finish()` вызывает `cleanup()` **перед** `helper.succeed()`. Cleanup снимает временные listeners, discards только известные exact owned entity references, освобождает native tickets, ставит native removal и независимо дренирует оба touched контейнера через public `processSubLevelRemovals`, снимает owned region tickets и вызывает восстановление исходного pause. Перед slot reuse проходит хотя бы один ordinary tick и bounded UUID/slot absence barrier. Успех требует пустых owned body/ticket/world-ticket records, снятых listener references и отсутствия passenger UUID в public lookups; любые cleanup exceptions приводят к FAIL. Отдельного detailed cleanup sidecar и getter readback восстановленного pause/ticket-manager состояния в этом run нет; это source-gated successful cleanup, а не расширенный ресурсный мониторинг.

`ServerLevel.getEntity(UUID)` и `getAllEntities()` наблюдают **visible entity storage**. `oneLiveOwner` ограничен4096 осмотренными visible entities; он не сканирует persistent hidden sections/knownUuids и не является доказательством глобального отсутствия всех скрытых UUID owners. Readiness закрывает нормальный target world path, а scoped non-cancelling join observer сохраняет только точную owned target reference для cleanup, даже при canceled join. Injected exception после partial registration и cleanup/recovery в таком случае runtime здесь не проверялись.

Подтвержден серверный development witness одного ordinary stationary Villager, isolated yaw-only carriers, выбранных inventory/NBT fields, native staged blocks/chest, readiness, двух-legged roundtrip, compensating return и genuine refusal. **Не подтверждены:** ServerPlayer/LocalPlayer и connection, пассажир движущегося/вращающегося корабля, client tracking/render при переносе, docking group/family, множество пассажиров, hidden-section global uniqueness, произвольные entity types/inventory layouts/attachments, crash/restart recovery, production passenger adapter, packaged-JAR installation, performance/огромные корабли. Предыдущий `passenger-20261004T222149565` сохраняет FAILED; этот свежий успех его не заменяет.
