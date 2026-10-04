# Независимая проверка выпуска alpha.5

Проверено 5 октября 2026: Universe Core **0.1.0-alpha.5 — экспериментальная библиотека/ядро расширений** для Minecraft 1.21.1, протестированной связки NeoForge 21.1.251 и Java 21. Это выпуск существующих API и интеграционных заготовок; production-переходы Sable, посещаемые планеты и пассажиры не приняты.

## Проверенный артефакт

`build/libs/universe-0.1.0-alpha.5.jar`: **191606 байт**, SHA-256 `d807cd1f1e3d6f04abb47ab3be6692428a520a3a9d939bc9cef829ee55583ebc`. Архив независимо прочитан через .NET ZIP API без Java/Gradle: 148 записей, 118 `.class`; версия внутри `META-INF/neoforge.mods.toml` совпадает.

Обязательные зависимости в фактическом TOML — только `minecraft [1.21.1,1.21.2)` и `neoforge [21.1.251,)`, обе стороны. Более новые NeoForge этим диапазоном разрешены, но отдельная проверка их совместимости не заявляется. Sable и Photon не являются обязательными зависимостями: Sable используется отдельными opt-in тестовыми профилями, конкретный Photon/backend пока не интегрирован. Вложенных dependency JAR и прямых native Sable/Photon/Create/Flywheel class references при чтении архива не обнаружено.

В JAR есть planet/material/environment/gravity API, snapshot codec, каталог миров, atmosphere/cloud DTO, effect budget, bounded LOD scheduler, network/persistence и корабельные метаданные/модели. `LivingGravityAdapter`, `LivingGravityDomain` и lifecycle relay входят в библиотеку; применение гравитации требует явного opt-in host. Игроки этим адаптером не поддержаны.

В фактическом архиве **отсутствуют** клиентские demo screens, GameTest, native Sable/client/gravity runtime fixtures, demo dimensions/types/structures/vacuum biome и вложенные сторонние JAR. Файлов `.tooling`, `.git`, `.codex`, `.agents`, локальных настроек сервера, журналов, ключей или launch scripts не обнаружено. Архив содержит только классы ядра, mod metadata, языковые JSON и pack metadata; пустые worldgen-каталоги контента не добавляют.

## Подтверждённые проверки

Фактически прочитан ledger `.tooling/test-runs/20261004T203523132Z-33440/status.json` и журналы стадий:

| Проверка | Результат и граница |
| --- | --- |
| JUnit | 106 тестов, 19 suites, 0 failures/errors/skipped. |
| Статические ресурсы | 125 проверок, 0 failures. |
| Build и verifyCoreJar | Успешны; все четыре стадии общего runner имеют exit 0. |
| Core GameTest | Журнал явно подтверждает все 19 required tests. |
| Native Sable GameTest | Журнал явно подтверждает все 10 required tests; маленький серверный probe, не production adapter/passengers/client acceptance. |
| Статический настоящий клиент | Отдельная ручная приемка root трёх PNG и штатной остановки описана в [VALIDATION.md](VALIDATION.md); этот независимый аудит заново изображения не принимал. |

Динамическая клиентская приемка **не закрыта**. `dynamic-20261004T210422716/client/report.json` имеет `FAILED`, `visualAcceptance=NOT_EVALUATED`, ошибку `Held actual client/server pose mismatch`, acknowledgedStage=0. Более поздний TCP diagnostic `tcp-20261004T212513611/server/report.json` имеет `DYNAMIC_CAPTURED_PENDING_VALIDATION`/`NOT_EVALUATED`: серверный numeric witness не заменяет клиентскую pixel/pose приемку, фактические завершения обоих процессов и ручное review.

## Допустимые заявления выпуска

Alpha.5 можно предоставлять для ручной проверки загрузки библиотеки и работы опубликованных API. 5000 metadata definitions не означают 5000 зарегистрированных игровых Block или готовые поверхности планет. DTO облаков и эффекты не означают готовый Photon renderer. Планетарная генерация, выход/посадка на орбиту, игровые пассажиры, connected families, production docking/transfer, player gravity, восстановление Sable после аварии и производительность огромных кораблей остаются открытыми. Статус и история проверок: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md), [VALIDATION.md](VALIDATION.md), [SABLE_VALIDATION_MATRIX.md](SABLE_VALIDATION_MATRIX.md).

Этот аудит не запускал Java/Gradle, не менял исходники или JAR и не публиковал repository/Release. Он одобряет только описанный экспериментальный срез и сохранность проверенного архива.
