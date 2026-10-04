# Текущие задачи Universe Core

Дата: 5 октября 2026 года. Текущая alpha.5 core: 106 JUnit, 19 core и 10 native Sable GameTest; полный runner прошел. Партия IDEA-004..006 продолжается: оставшиеся lifecycle и client/production gates открыты. Первый настоящий client static capture просмотрен root; final dedicated stop audit PASSED; actual chunk unload/reload и новая JVM saved world также прошли (три exit-0 runs). Задачи отправлены существующим чатам; новые чаты не создаются. Владельцы пишут только назначенные файлы, общий Gradle/runtime запускает оркестратор.

| Чат / роль | Задача | Владение | Зависимость |
| --- | --- | --- | --- |
| 01 — планеты | Bounded versioned snapshot codec; порядок, ID, seeds и профильные ссылки сохраняются; reject corrupt/unknown | api/planet/PlanetSnapshotCodec.java и соответствующий тест | Независима, необходимый следующий этап PLAN |
| 02 — Photon | Проверить реальные возможности/пины VFX, cloud/sky границы и lifecycle backend | docs/PHOTON_INTEGRATION_CONTRACT.md | Реализация после выбора проверенного контракта |
| 03 — гравитация | IDEA-005 A: opt-in adapter реального vanilla моба и required траектория/release/conflict GameTest | integration/gravity/* и gametest/LivingGravityGameTests.java | Pure gravity API alpha.3 уже готов |
| 04 — LOD | Разбор bounds/stale/caches/hotpaths, план честного runtime benchmark | docs/LOD_RUNTIME_BUDGET_REVIEW.md | Производительные adapters еще не все реализованы |
| 05 — клиент | Настоящий dynamic tracking/render harness, PNG/pose/marker evidence | .tooling/proposals/dynamic-client/**, docs/CLIENT_RUNTIME_TEST_CONTRACT.md; текущие исходники root | Static capture/stop PASSED; dynamic candidate готовится, multiplayer pending |
| 06 — физика Sable | A/B и gameTime hardening прошли; финальное evidence | sableTest/SableGravityCompensationTests.java, docs/SABLE_PHYSICS_CALIBRATION.md | Native final подтвержден; production zero-g еще нет |
| 07 — lifecycle | Domain, real stop/chunk/restart проверены; кандидаты отмененных реальных событий | .tooling/proposals/gravity-callback-tests/**, docs/GRAVITY_LIFECYCLE_IMPLEMENTATION.md; main/runtime теперь root | Два canceled callbacks + runtime unregister прошли; безопасный dynamic whole-Level API не найден |
| 08 — единственный автор идей | Пакет 2 уже записан; следующая партия после результатов текущей | IDEAS.md, только предложения | Пока ожидание результатов, без генерации дубликатов |
| 09 — переходы | Velocity probe прошел; исследуются реальные passenger/family API и следующие gates | docs/SABLE_PASSENGER_TRANSFER_CONTRACT.md, .tooling/proposals/passenger-transfer/**; текущие native tests root | Публичные API и ограничения проверяются по pinned sources |
| 10 — независимое ревью | Source/provenance/evidence настоящих runtime gates | docs/REVIEW_GRAVITY_RUNTIME.md и предыдущие review | Узкие mob chunk/restart gates подтверждены; большие gates открыты |
| 00 — оркестратор | Интеграция, решения по API, реальный запуск тестов, устранение ошибок и сборка | build/version, UniverseMod, базовый native transfer probe, IDEAS statuses, VALIDATION | Подбирает независимую работу при временном блокере |

## Приемка ближайшей партии

Калибровка не считается пройденной при no-op getter/impulse, nonfinite mass или несовпадении направления. Opt-in gravity не должна менять обычные миры без явной регистрации; base/foreign modifiers и NoGravity сохраняются. Отказ не оставляет собственного modifier. Игроки остаются неподдержанными до client sync проверки. Codec не заменяет damaged data пустым каталогом.

Фактические результаты записываются в VALIDATION.md. Проверенная alpha.5 core, narrow native probe и первый static client/stop run не закрывают весь проект и production transfer. Пассажиры, стыкованные группы, recovery и большие структуры по-прежнему обязательны для полной приемки. Сборка и тесты идут последовательно одним владельцем, чтобы не смешивать логи/временные миры. Кандидаты будущего core-этапа держатся вне main до интеграции.
