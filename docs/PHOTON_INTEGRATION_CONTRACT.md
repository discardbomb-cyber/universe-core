# Photon: контракт следующего клиентского этапа

Дата проверки: 5 октября 2026 года. Статус: исследование официальной документации, исходников и уже имеющихся локальных артефактов; рендерер не реализован, игровые тесты не выполнялись. Gradle, загрузка зависимостей и чужие проекты не изменялись. Этот документ определяет следующий прототип для оценки основным чатом, а не утверждает завершение атмосферы/облаков.

## 1. Какой Photon исследован

Есть два независимых продукта:

- **Low-Drag-MC Photon / Photon2** — VFX-библиотека с собственными FX-ресурсами, emitters и runtime; опирается на LDLib2 и в исследованной версии KilaGraph. Это кандидат для первого локального VFX-прототипа. [Официальный репозиторий](https://github.com/Low-Drag-MC/Photon).
- **SixthSurge Photon** — shader pack с небом/облаками. Его установка не равна Java-интеграции Photon2 и не предоставляет автоматически параметры произвольной планеты Universe. В данной работе его конкретная версия и совместимая связка Iris не выбраны. [Официальный репозиторий](https://github.com/sixthsurge/photon).

Нейтральные `EffectBackend` и `AtmosphereBridgeDto` в Universe остаются контрактами. Наличие этих интерфейсов не означает, что один из продуктов уже стал окончательным backend. Предложение: сначала отдельный optional client adapter Photon2 для локальных эффектов; решение о полном небе принять после двух маленьких visual tests и отдельного sky/depth spike. Такой прототип сам по себе не выполняет весь пользовательский запрос планетарных облаков.

## 2. Точные версии и доказательства

Read-only проверены `D:\EX-twins\gradle.properties` и строки зависимостей `build.gradle`. Это **настроенная фактическая связка соседнего проекта**, а не утверждение, что здесь проведен ее runtime-тест:

| Компонент | Pin | Координаты / подтверждение |
| --- | --- | --- |
| Minecraft | 1.21.1 | `minecraft_version` |
| NeoForge | 21.1.251 | `neo_version` |
| Photon | 2.2.7 | `com.lowdragmc.photon:photon-neoforge-1.21.1:2.2.7:all`, `transitive=false` |
| LDLib2 | 2.2.41 | `com.lowdragmc.ldlib2:ldlib2-neoforge-1.21.1:2.2.41:all` |
| KilaGraph | 21.1.0.15 | `com.lowdragmc.kilagraph:kilagraph-neoforge-1.21.1:21.1.0.15` |

Репозиторий зависимостей в EX-twins: `https://maven.firstdark.dev/snapshots`. Новые разрешения Maven не запускались. Уже имеющийся в Universe cache `photon-neoforge-1.21.1-2.2.7-all.jar` имеет SHA-256 `a8516cbb2754c17235398ce877b77c7f6533e6c8dbb86539c092f9bc890d7885`; `ldlib2-neoforge-1.21.1-2.2.41-all.jar` — `0dd60892b87997f1ef0d60340b7d7e86314626e92368851eda9194ef59b03af1`. Чтение JAR metadata и доступных сигнатур `javap` не равно полноценной компиляции адаптера; новые файлы в cache не создавались.

Локальный Photon TOML требует LDLib2 `[2.2.40,)` и KilaGraph `[21.1.0.15,)`, оба с `side=BOTH`; LDLib2 2.2.41 требует NeoForge `[21.1.216,)`. Настроенный NeoForge 21.1.251 удовлетворяет этим диапазонам, но это еще не доказательство графической совместимости. Photon metadata указывает Minecraft `1.21.1`. Не заменять pins автоматически на latest.

Официальная текущая ветка Photon `1.21` также указывает `mod_version=2.2.7`, LDLib2 2.2.40 и минимальный KilaGraph 21.1.0.15; ее собственный dev NeoForge — 21.1.217. Это объясняет отличие upstream dev pin от EX-twins 21.1.251. Ветка подвижна, и текущая документация содержит примеры 2.2.4: перед реализацией сверяем методы с выбранным JAR, не выводим версию или дату релиза из даты страницы. [Upstream properties](https://github.com/Low-Drag-MC/Photon/blob/1.21/gradle.properties), [официальный Java API](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/java-api/).

Отдельная неопределенность упаковки: локальный Photon 2.2.7 TOML обозначает GPL-3.0, а текущий README описывает CC BY-NC-SA 4.0. Это не основание придумать единую лицензию всех версий. Для первого прототипа использовать внешние моды, без jar-in-jar и копирования upstream реализации; перед распространением проверяется LICENSE именно выбранного артефакта/исходного commit. Собственные FX-ресурсы рассматриваются отдельно.

## 3. Что реально доступно, а что требует прототипа

Photon2 позволяет авторить particles/trails/beams, shader materials, mesh particles и post-processing. Поддержка graph/material/custom GPU streams дает инструменты для собственного эффекта, но не доказательство готового сферического cloud renderer. Различие material, Shader Graph и runtime data описано в [официальной документации shaders/GPU](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/shaders-and-gpu/).

| Задача Universe | Возможный путь | Уровень доказательства |
| --- | --- | --- |
| Малый клуб пыли, вспышка, локальная дымка | Авторский `.fx`, ParticleEmitter и ограниченный runtime | Загрузка/playback и emitter API подтверждены; наш asset еще не создан |
| Локальные облачные формы на нескольких высотах | Несколько небольших ограниченных emitters/mesh effects | Проектный тест слоев и глубины; не физический volumetric sky |
| Объем внутри локального облака | Custom shader/material или специальный проход | Требуется отдельная реализация интегрирования плотности/depth и проверка GPU cost |
| Орбитальный обод и конечная атмосферная оболочка | Собственный planetary renderer, возможно с Photon custom material/pass | Собственный алгоритм shell geometry/scattering, не готовый встроенный API |
| Сферические многоярусные облака планеты | Собственная density map + bounded shell/raymarch renderer | Полный отдельный spike; нельзя заменить миллионами particles |
| Полное небо через SixthSurge Photon | Отдельная shader-pack/Iris интеграция | Не исследована по точному pin; выбор еще открыт |

Высотные ярусы AtmosphereProfile — **геометрические диапазоны**; `renderer.orderInLayer` — порядок рендера. Их нельзя отождествлять. Штормовой слой пересекает другие высоты и требует согласования плотности/стоимости. Облака выше build height могут быть визуальными; доступный emitter не доказывает возможность игровой посадки/пролета на любой высоте.

## 4. Известные классы и lifecycle

Следующие классы присутствуют в локальном Photon 2.2.7 и относятся к клиентскому пути:

- `com.lowdragmc.photon.client.fx.FXHelper`: `getFX(ResourceLocation)`, `getFX(ResourceLocation, boolean)`, `listAllFX()`, `clearCache()`.
- `FX`: `createRuntime()`, `createRuntime(boolean)`; `createInternalRuntime()` не использовать для обычного gameplay.
- `FXRuntime`: `emit(IEffectExecutor)` / `(executor, delayTicks)`, `destroy(boolean)`, `isValid()`, `isFinished()`, `findObject(String)`, `findObjects(String)`.
- `BlockEffectExecutor(FX, Level, BlockPos).start()` и `EntityEffectExecutor` существуют для привязки. Базовый `FXEffectExecutor` предоставляет `getRuntime()`, `setOffset`, `setRotation`, `setScale`, `setOnFinished`.
- `IEffectExecutor`: `getLevel()`, `updateFXObjectTick`, `updateFXObjectFrame`, `getRandomSource()`, `postEffectSink()`; custom executor нужен для собственного lifecycle/seed/local frame.

ID `universe:test/dust` разрешается в `assets/universe/fx/test/dust.fx`; в ResourceLocation не включаются `fx/` и `.fx`. `getFX` может вернуть null при отсутствующем/сломавшемся ресурсе. Загружаем определение после resource availability, а не в common static initializer и не каждый кадр. `clearCache()` очищает определения, **не уничтожает** живые runtimes. [Официальный loading/cache contract](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/java-api/loading-listing-and-caching.html).

Рабочая последовательность адаптера: admission → загрузка FX → `createRuntime()` → проверка именованных объектов/настройка overrides → `emit(customExecutor)` → хранение handle. `destroy(false)` допускает затухание остаточных частиц; для logout/dimension change/disposal используется `destroy(true)`. Проверяем `isValid()` независимо от `isFinished()`: engine wipe может сделать handle невалидным, а looping effect не завершится сам. Повторный `emit` способен оживить runtime, поэтому gameplay не переиспользует disposed handle. [Официальный FXRuntime lifecycle](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/java-api/fxruntime-lifecycle.html), [upstream implementation](https://raw.githubusercontent.com/Low-Drag-MC/Photon/1.21/src/main/java/com/lowdragmc/photon/client/fx/FXRuntime.java).

Для `com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter` подтверждены `runtime()` и `getParticleAmount()`. `ParticleRuntime` содержит typed slots `maxParticles`, `prewarm`, `looping`, `duration`, `startLifetime`, `emission`, `customData`. Через `emitter.runtime().maxParticles.set(limit)` ограничивается экземпляр; значения shared authored `config` не меняем. API-facing emitter names уникальны; проверка типа через `instanceof` обязательна. Timeline и Java не должны писать одни и те же slots одновременно. [Runtime injection](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/java-api/runtime-data-injection.html), [Emitter core](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/particle-system/particle-emitter.html).

Локальный байткод `IEffectExecutor#getRandomSource()` по умолчанию возвращает `Level.random`. Для эффекта Universe custom executor должен владеть отдельным seeded RandomSource, который возвращается повторно, а не создается заново при каждом вызове. Это снимает зависимость от общей случайной последовательности мира; точное равенство всех графических кадров/parallel particles еще требует отдельного теста. Clock/weather seed и визуальный seed — связанные входы, не обещание одинаковых GPU pixels.

## 5. Изоляция основного JAR и ограничения

Предлагается отдельный optional client integration JAR/module: основной Universe содержит только pure DTO и backend interface, без `net.minecraft.client`, Photon/LDLib renderer signatures, наследования или static fields. Server получает authoritative environment/weather, клиент адаптирует уже проверенные snapshots; VFX не наносит урон и не начисляет exposure.

Client bootstrap и подписчики ограничиваются physical `Dist.CLIENT`; `Level.isClientSide` сам по себе не защищает class loading на dedicated server. Общая сетевой обработчик передает нейтральное сообщение через safe client boundary; типы Photon не появляются в common методах. Проверяется запуск dedicated server **без** Photon/LDLib/KilaGraph и загрузка всех common API classes. [NeoForge 1.21.1: Sides](https://docs.neoforged.net/docs/1.21.1/concepts/sides/). То, что Photon TOML помечает часть зависимостей BOTH, не дает Universe права импортировать клиентское playback API на сервере.

`EffectBackend.synchronize` сопоставляет admitted instance IDs с handles: unchanged обновляются, missing уничтожаются, новые стартуют один раз. У каждого handle session generation, identity текущего ClientLevel и frame/body context. Смена level, disconnect, reconnect, resource reload или `close()` уничтожает принадлежащие адаптеру runtimes и очищает references. Нельзя глобально очищать чужие particles/FX caches ради закрытия одного Universe эффекта. Асинхронный результат старой generation отбрасывается; reconnect не возрождает effect из старой сессии автоматически.

На первом прототипе клиентский tick проверяет `Minecraft.level` identity/null, закрывая старый контекст; именованные logout/unload hooks добавляются после проверки точных NeoForge сигнатур. Все world/render mutations — client-thread, включая обработку результата загрузки и `destroy`. Двойное самостоятельное ticking FXRuntime запрещено: после emit его объекты обслуживает Photon host.

Admission бюджет ядра не ограничивает неизвестный `.fx` автоматически. Нужен approved asset manifest: число emitters/objects, суммарные maxParticles, lifetime, emission/burst limits, trails, sub-emitters, prewarm, lights, meshes и fullscreen passes. В первом spike выключить looping/prewarm/sub-emitters/collision/lights/fullscreen post; отклонять неучтенные объекты. Массовый каталог определений не создает runtimes. Любое плавное затухание продолжает учитываться в live budget; при жестком лимите используется forced destroy. GPU overdraw и mesh vertices измеряются отдельно от числа частиц.

## 6. Два первых реальных клиентских теста

Тесты ниже — задания будущей реализации, не уже пройденные проверки. Требуются собственные маленькие `.fx`, сохраненные редактором Photon выбранной версии; не копируем готовые ship models.

**P1 — видимый локальный burst и завершение.** Asset `universe:test/dust`: один уникальный ParticleEmitter, простая заметная текстура, максимум 16 particles, ограниченный burst и particle lifetime; без loop/subemitters/prewarm. Тестовая client command/action создает ровно один admitted descriptor на фиксированном блоке в трех блоках перед камерой, TTL 60 ticks. Один runtime появляется в реальном мире, счетчик >0 и ≤16; скриншот/короткое видео подтверждает рисунок, диагностика — одно стартовое emit. После TTL forced cleanup: map handles пуст, captured runtime невалиден, видимых остатков нет. Повтор команды создает новый runtime, missing asset дает понятный диагностический результат без crash. Параметры — тестовый бюджет, не обещание производительности.

**P2 — два различимых высотных слоя и cleanup контекста.** Asset `universe:test/layer_probe`: два uniquely named emitters с разными цветами на локальных высотах +4 и +8 blocks, каждый ≤8 particles, конечный lifecycle без loop. Не называем это готовыми облаками. Передаем color/coverage через выделенный Custom Data slot, который Timeline не переписывает; разные LOW/HIGH выбирают заранее ограниченные варианты. Эффект действительно рисуется в двух видимых областях; обычная непрозрачная тестовая стена проверяет depth/occlusion. Затем выполняются смена измерения и отдельно disconnect→reconnect: старые handles принудительно уничтожены, references на прежний ClientLevel отсутствуют, после reconnect 0 активных пока не приходит новая action. Повторить 20 циклов; счетчики возвращаются к исходному уровню. Данные старой generation не создают новый эффект. Фиксируются screenshots, runtime/particle counts и отсутствие роста retainers; pure JUnit не заменяет эти проверки.

Эти два теста сначала выполняются без shader pack. После успеха отдельно измеряются resource reload, отсутствие Photon, совместимость выбранного Iris/шейдерпака, полет камеры через слой, Sable depth, орбитальная оболочка и облачный pass. Vanilla block depth не доказывает правильную глубину стороннего sublevel renderer. Первый milestone принимается только по реально появившимся/удаленным эффектам и dedicated-server isolation; общий atmosphere renderer остается следующим этапом.
