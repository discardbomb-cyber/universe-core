# Планеты Universe Core: API, поверхности, LOD и посадка Sable

Обновление реализации: alpha.3 добавляет `PlanetPhysicsBinding`, `PlanetPhysicsResolver` и `ResolvedPlanetPhysics`, разрешающие существующую планету и профили gravity/gas из стабильных host-снимков. Пройдено 6 новых JUnit. Это метаданные [IDEAS.md](../IDEAS.md), без создания измерений, terrain generation и допуска к посадке.

Дата: 4 октября 2026 года, Бишкек. Статус: проект следующего этапа библиотеки NeoForge 1.21.1; код в этой работе не изменяется. Основания: PLAN.md v0.6, существующие api/planet, SABLE_TRANSFER_RESEARCH.md, SABLE_TRANSFER_RECOVERY.md и SABLE_VALIDATION_MATRIX.md. Приоритет — библиотека для дополнений, без обязательного набора кораблей, миссий и готовых планет.

## 1. Выбранный MVP и границы доказанного

Выбирается **метаданный каталог + адаптер заранее объявленных постоянных измерений**. На демонстрационном стенде две плоские поверхности 16 384×16 384 блока и космос одной системы. Производственное ядро публикует контракты; demonstration addon предоставляет измерения, тестовые профили, карту и изображения. PlanetDefinition не создает ServerLevel. 5000 определений планет проверяют масштаб каталога; 5000 зарегистрированных Block проверяются отдельным контентным дополнением. Ни одно число не означает создание 5000 измерений или одновременную симуляцию всех планет.

Существующее API действительно предоставляет immutable PlanetDefinition с id, realm, seed, generationProfileId, atmosphereProfileId, surfaceBindingId; ограниченный PlanetRegistry, уникальность ID, freeze и снимки. Codec, разрешение ссылок, постоянные измерения, полноценный terrain sampler и надежная посадка Sable этим API еще не реализованы. Новые типы ниже — предложения, не уже доступные классы.

Обычная поверхность имеет вертикальную ось Y и ванильную блочную геометрию. Космический шар визуализирует планету, но не превращает поверхность в физическую сферу. Переход может показывать загрузку; края карты не зациклены. Динамические измерения, непрерывное пересечение атмосферы, произвольная направленная гравитация и перенос огромных Sable-семей требуют отдельных прототипов.

## 2. Расширение API без нарушения существующих определений

Не добавлять обязательные поля в конструктор уже опубликованного PlanetDefinition. Ввести сопутствующий ResolvedPlanetProfile по тому же planetId: schemaVersion, generatorVersion, projectionVersion, profile fingerprints, materialPaletteId, structureSetId, waterProfileId, effectsProfileId, gravityProfileId, framePolicyId и размеры chart. Это серверный immutable снимок после проверки ссылок; открытое API не импортирует клиентские классы.

GenerationProfile описывает алгоритм, параметры высот/климата/плотности, ограниченные правила кратеров и features. MaterialPalette выбирает материалы для ролей bedrock, deepRock, topsoil, shore, ore и задает LOD-описания. AtmosphereProfile разделяет серверные свойства среды и разрешенное клиентское изображение; WaterProfile задает существующую жидкость, уровень моря, правила заполнения и оттенок. GravityProfile и FramePolicy не являются скрытым обещанием совместимости любого backend.

SurfaceBinding описывает providerId, стабильный bindingId, planetId, dimensionId, chartId, горизонтальный диапазон, minY/height и версии. Начальный provider — permanent_dimension. Профиль без доступного binding остается каталожным объектом. Отдельные состояния: METADATA_ONLY, RESOLVED, SURFACE_UNAVAILABLE, VISITABLE, INCOMPATIBLE; наличие записи не дает права посещения.

planetId глобально уникален между realm. Для процедурного BodyKey манифест хранит постоянное соответствие BodyKey → planetId; пользовательская планета получает явный catalog placement без вычисления личности из текущей орбиты. ID не меняется при переименовании или перемещении изображения. Числовые registry indices используются только в текущей сетевой сессии, не как ключ сохранения.

## 3. Datapack, Codec и жизненный цикл

Выбран adapter с custom datapack registries: universe:planet, universe:generation_profile, universe:material_palette, universe:atmosphere_profile, universe:water_profile и universe:surface_binding. Пример пути для planet с ID addon:worlds/ice: data/addon/universe/planet/worlds/ice.json. ID берется из ключа записи; JSON-тело не дублирует его независимым полем. Java-регистрация дополнения проходит через отдельный bootstrap provider, не изменяет глобальный Minecraft registry посреди игры.

Официальная документация 1.21.1 подтверждает DataPackRegistryEvent.NewRegistry, регистрацию codec и необязательного сетевого codec, загрузку datapack registries при загрузке мира и путь custom registry. Она также подтверждает DeferredRegister/RegisterEvent для игровых объектов. Это разные стадии, и planet registry не регистрирует Block. [Registries 1.21.1](https://docs.neoforged.net/docs/1.21.1/concepts/registries/).

Для transport envelope использовать отдельный schemaVersion, для данных — RecordCodecBuilder, строгие диапазоны/лимиты и DataResult.error. JsonOps/NbtOps обслуживают JSON/NBT; registry-dependent Block/Fluid/Holder разрешаются через RegistryOps. Частичный результат ошибки не публикуется как успешная планета. Это подтвержденные механизмы сериализации, но собственные codecs требуется написать и скомпилировать. [Codecs 1.21.1](https://docs.neoforged.net/docs/1.21.1/datastorage/codecs/).

Порядок bootstrap:

1. Зарегистрировать типы generators/codecs и настоящие Block/Fluid дополнения в штатной фазе запуска.
2. Загрузить datapack записи в кандидатный набор при открытии мира. Внутри pack stack действуют обычные приоритеты ресурсов; конфликт между Java provider и итоговой datapack записью того же ID дает диагностируемый отказ, без зависимости от порядка обхода.
3. На сервере после доступности RegistryAccess разрешить зависимости. Отсортировать planetId перед наполнением нового PlanetRegistry: текущая ревизия считает записи, а не содержимое; fingerprint считается по канонически упорядоченным данным.
4. Проверить размеры, supported versions, материалы, структуры, binding bijection и наличие ожидаемого измерения перед разрешением посещений. Подготовка catalog не генерирует чанки.
5. Сопоставить кандидат с сохраненным world manifest, заморозить world-scoped registry и атомарно опубликовать снимок. Уведомления addon происходят только после успеха; произвольный callback не исполняется на потоках terrain workers.

Не хранить один mutable static PlanetRegistry на весь процесс: разные сохранения имеют разные seed/манифесты. Существующий freeze не отменять. /reload не считать способом замены worldgen или создания dimension. MVP требует перезапуска для структурных изменений; клиентские ресурсы могут перезагружаться отдельно. Изменение seed, алгоритма, палитры генерации, размеров/привязки существующего мира блокируется до явной миграции. Удаленная или неизвестная запись не перезаписывает сохраненные блоки. Расширенный hot-reload позднее строит новый кандидат и проверяет его до публикации, не мутирует текущий реестр.

Не передавать весь server codec клиенту: seed, закрытые структуры и приватные настройки остаются серверными. Карта получает сокращенные snapshots по запросам с лимитами, эпохой сессии и политикой открытий.

## 4. Постоянные измерения и альтернатива patches

Для MVP каждая посещаемая планета навсегда связана с собственным заранее объявленным dimension; один binding не назначается новой планете после ухода игроков. Конкретные dimension_type, dimension generator codec, height и world-border adapter подтверждаются компиляцией и запуском dedicated server. Существование datapack definition само по себе не доказывает работоспособность нового ServerLevel или перенос Sable.

Альтернатива — SurfacePatchProvider: множество постоянных, непересекающихся прямоугольников в одном измерении. Он потребует устойчивого allocator, planet-local → dimension transform, guard bands, запрета пересечения borders, маршрутизации sampler по patch, изоляции жидкостей/структур, spawn/portal/weather и tickets. Patch никогда не переназначается, пока есть его сохраненные чанки. Разная gravity/height/sky в одном vanilla dimension не получится автоматически.

Patches уменьшают число dimension, но усложняют совместимость worldgen и модов, считающих dimension единым миром. Поэтому это следующий эксперимент, а не скрытая реализация MVP. Виртуализация через выгрузку одной планеты и замену ее слотами другой отклонена: конфликтует с несколькими игроками, сохранениями и постоянными backend bindings. Runtime dimension registry остается исследовательской альтернативой.

## 5. Terrain, кратеры и структуры без швов регионов

Один TerrainSampler принимает planetId, frozen profile, planet seed и абсолютные planet-local X/Y/Z. Чанк — 16×16 столбец; регион — 32×32 чанка, 512×512 блока. Регион индексирует данные и LOD, но не перезапускает noise. floorDiv/floorMod применяются на отрицательных координатах. Версия sampler и seed закрепляются также для еще не посещенных чанков.

Seed каждого поля независим: namespace алгоритма + версия + planetId + seed + абсолютный адрес/ячейка + метка поля. Текущий StableSeed кодирует SectorKey; его нельзя молча использовать как полную адресацию пользовательской поверхности. Нужен дополнительный версионированный planet-seed codec с закрепленным golden vector. Изменение шума облаков не должно изменить кратер или руду.

Кратер имеет глобальную origin cell, stable featureId, центр, радиус и ограниченный falloff. Для sampling точки перечисляются только origin cells, чей максимальный footprint может ее покрыть. Радиус/число кандидатов ограничены профилем; кратер пересекает region boundary аналитически, без загрузки соседних chunks. Пересечения разрешаются фиксированными операциями и порядком featureId, не порядком запросов.

Структура и жила получают общий descriptor с bounding box и seed от origin. Чанк размещает только свою пересекающую часть. Размер структуры и halo конечны; запрещен неограниченный поиск соседей. Декоративный этап, liquids и vanilla structure integration нуждаются в игровых тестах: отдельное чтение соседнего chunk не должно породить рекурсивную генерацию. Не смешивать независимый ручной placement с vanilla placement того же объекта. Единый owner feature и детерминированный ordinal исключают двойной сундук при повторном раскрытии региона.

Для воды MVP использует заранее зарегистрированную Fluid, фиксированный sea level и согласованные sampling rules; волны/облака не двигают серверные water blocks. Ocean fill строится глобальным правилом, а не локальной высотой каждого региона. Аквиферы сложной формы и erosion — расширения. Terrain occupancy зависит от стабильной material palette; отсутствующий Block не заменяется молча на air.

## 6. Поверхность, масштаб и карта

Канонический SurfaceAddress = planetId + chartId + X/Y/Z; производные RegionKey/ChunkKey добавляют realm и profile generation metadata через world manifest. MVP chartId=0, X/Z ∈ [−8192,8192), Y проверяется binding. Это не шесть физических граней и не закольцованный мир.

Карта отображает плоский rectangle непосредственно, затем переносит его цвет/рельеф на орбитальную сферу эквидистантным UV. Предлагаемое соглашение: longitude = 2π(X+8192)/16384 − π; latitude = −πZ/16384. Начальный meridian, ориентация north и projectionVersion фиксируются. Обратное преобразование применяется до округления блока; радиус изображения и scale космического мира хранятся отдельно. Planet render radius не равен 8192 блокам и не превращается в размер BlockPos.

Сфера и плоскость имеют неизбежное различие масштабов/искажение у полюсов; неподвижная структура в далеком изображении не обязана совпасть с локальной физической сеткой без специального преобразования. MVP разрешает посадку только при |latitude|≤80° и внутри rectangle с запасом для полного корпуса. Полярные области доступны пешком на chart, но не через orbital landing. Meridians не сшивают игровые края; обе стороны шва представлены явными различными граничными адресами. При нехватке surface data карта показывает approximate, не выдает точный landing clearance.

Orbit → chart выполняется логическим переходом, а не попыткой деформировать реальный rigid body по сферической projection Jacobian. Локальная landing frame ортонормальна. Корпус сохраняет размер и форму; выбранная scale policy преобразует только положение системы, не растягивает блоки. Автоматическая посадка проверяет volume в настоящих чанках, не в proxy mesh.

## 7. Пять LOD и сохраненные изменения

| Уровень | Данные и ответственность |
| --- | --- |
| L0 | Штатные blocks/chunks; точные столкновения, fluids и BE в активной поверхности |
| L1 | Региональные height/material summaries и меши; halo из sampler/сохраненных сводок; без физики |
| L2 | Орбитальная сфера и atlas планеты; surface coverage, ограниченные marks построек и cloud envelope |
| L3 | Система: тела, орбитальные изображения, ссылки доступности; нет ticking всех surfaces |
| L4 | Ограниченный каталог галактических секторов; страницы/открытия, без генерации block chunks |

Удаление наблюдателя освобождает derived caches, не уничтожает L0. Parent mesh остается до готовности children; соседние L1 resolutions требуют skirts либо stitch templates. Сводка получает planetId, profile fingerprint, sourceRevision, request generation и session epoch; поздний результат старой версии не принимается. Thresholds и CPU/GPU/network budgets сохраняются из PLAN, затем измеряются. Vanilla chunks не входят в обещанный лимит собственного кэша.

delta saves в MVP означают **измененное относительно procedural base состояние в штатном chunk save**, не новый авторитетный per-block journal. Материализованный чанк, BE, entities и scheduled ticks после ухода читаются из Minecraft save; запрещено заново создавать их только по seed. Дополнительный sparse delta index нужен для dirty footprints и LOD invalidation, но его потеря не уничтожает базу игрока. Если позже отдельный patch store станет авторитетным, он должен сохранять все эти категории, включая удаление и перемещение объектов.

Межуровневые метаданные хранятся в SavedData и проверяются при рестарте. Документация подтверждает computeIfAbsent и setDirty; setDirty не является подтверждением durable commit. [Saved Data 1.21.1](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/).

Для distant image нужен resumable dirty-region queue. Изменения fluids, explosions, growth, сторонних модов и Sable-посадки учитываются; пока полное отслеживание не доказано, регион помечается stale. На рестарте невалидированные сводки пересчитываются по бюджету. Нельзя загрузить всю планету ради одной иконки или пересчитать ее целиком при постановке блока.

## 8. Реальная посадка Sable, gravity и вращающиеся frames

SurfaceLandingService — предлагаемый coordinator над NativeTransferBackend, не телепорт игрока. Он резервирует landing volume, проверяет доступность binding, profile/frame versions, права и лимиты; ограниченно готовит chunks и повторно проверяет их перед commit. Snapshot terrain revision служит основанием повторной проверки, но не заменяет блокировку конфликтующих landing requests.

Выбран прототип собственного SablePlotTransferBackend; локальное исследование Sable 2.0.5 подтвердило allocate, plot save/load, userData и handle velocities, но не готовый changeDimension. Plot.save захватывает загруженное содержимое: полнота source требует проверки. Staging target должен быть изолирован от игроков, physics и ticking BE до commit. Наличие API не доказывает возможность такой изоляции. [Локальное исследование](SABLE_TRANSFER_RESEARCH.md).

Операция следует durable journal/recovery design: capture source/roster → проверенный snapshot → staging allocation/load → сравнение blocks/BE/entities → commit binding epoch → перенос roster → retire source → restore verified physics. Долговечность нескольких файлов не предполагается атомарной. Stable ObjectId/ShipId сохраняется; native UUID/plot меняются только через проверенное соответствие и эпоху. Прямой SablePortLibrary teleportFamily не выбран, поскольку исследование установило разрушение source до успешного target. [Recovery contract](SABLE_TRANSFER_RECOVERY.md).

Для MVP переносится один поддержанный sublevel. Connected family возвращает UNSUPPORTED до любого разрушения; это ограничение первой реализации, **не выполнение всей пользовательской приемки**, которая требует также группы. Тестовый milestone одиночного объекта не закрывает переход стыкованных объектов.

FrameSnapshot содержит frameId/version, origin, orthonormal orientation Q, linear origin velocity V и angular frame velocity Ω на одном tick/epoch. Для положения r в local frame: inertial position = O + Qr; inertial velocity = V + Qv + Ω×Qr. При переходе сначала перейти из source frame в общий inertial frame, затем вычесть движение target origin и Ωtarget×offset и применить Qtarget inverse. Angular velocity требует явного backend convention: в world frame это относительная angular velocity, повернутая Q, плюс Ω; нельзя вращать/добавлять ее по предположению о единицах Sable. Нормализация quaternion, единицы времени и handedness фиксируются тестами.

MVP frame policy — статические orbits, Ω=0, down=(0,−1,0), постоянная проверенная magnitude; посадочная команда может отдельно задать торможение, которое документируется и тестируется, а не маскируется под сохранение velocity. Обычная rigid rotation basis сохраняет корпус. Масштаб космических координат не применяется к mesh тела при transfer.

GravityProvider получает конкретную frame/позицию и возвращает ускорение в однозначных единицах. В следующем прототипе изменение magnitude проверяется отдельно для vanilla entities, Sable mass/forces, projectiles, fluids и walking. Простая сила на корабль не изменяет автоматически поведение игроков. Rotation of camera не меняет коллизию, pathfinding и fluid down. Radial gravity на плоском chart не делает землю сферической.

При вращающейся reference frame полноценная динамика включает centrifugal/Coriolis/Euler terms; MVP их не добавляет декоративно. До доказательства backend соглашений выбирается inertial physics или неподвижный landing frame. Snapshot frame epoch блокируется на подготовке, проверяется перед commit; при движении назначения выше допуска операция перепланируется. Произвольная gravity/rotation adapter является optional capability с диагностикой отказа.

## 9. Материалы и 5000+ настоящих блоков дополнения

MaterialDefinition — логический стабильный ID; Block registry ID и properties задают разрешенный BlockState. Профиль planet выбирает существующие материалы, не клонирует всю палитру на каждую планету. Palette compilation разрешает ID после завершения block registration и создает компактные per-profile lookup arrays. Numeric indices не сохраняются без таблицы соответствий.

Для простых пород использовать общие классы без BE/ticker и минимум states. Water и atmosphere независимы: наличие голубого sky не значит breathable gas, цвет жидкости не значит water mechanics. Сервер проверяет exposure/hazards, клиент рисует atmosphere/clouds через optional addon. Физическая density/friction материала для Sable требует отдельного adapter registry и теста массы/контакта после transfer.

LOD material descriptor содержит bounded color/roughness/emissive и разрешенный texture reference; не нужно держать тысячи полных block models в каждом L1 mesh. Клиентский ресурсный pipeline может запекать все standard models при старте, поэтому ленивость catalog не обещает ленивость texture atlas. Datagen создает blockstates, model parents, loot, tags, lang и items; настоящий content addon регистрирует ≥5000 Block с устойчивыми ID, не миллиард states от одного свойства.

При удалении addon отсутствующие registry IDs вызывают управляемую диагностику и recovery policy до открытия опасного мира; автоматическая замена материала на air не считается миграцией. Смена palette влияет на future generation только через новую согласованную версию; посещенные blocks не перекрашиваются генератором.

## 10. Порядок прототипов и критерии приемки

Все строки ниже — TODO/PENDING, а не результаты текущих игровых тестов.

| Проверка | Обязательный результат |
| --- | --- |
| Codec/bootstrap | JSON/NBT round trip; invalid IDs, NaN, excessive lists, unknown versions и unresolved refs дают отказ; java/datapack conflicts воспроизводимы |
| World lifecycle | Два сохранения в одном процессе не делят mutable registry; restart сохраняет ID/seed/binding/fingerprints; /reload не меняет генератор действующей поверхности |
| Catalog scale | 5000 metadata records, bounded queries и snapshots без создания dimension/chunks; peak heap и загрузка задокументированы |
| Material addon | ≥5000 настоящих Block и нужные items; client/server IDs совпадают, нет missing models/loot; измерены startup/reload, heap/VRAM/jar |
| Border terrain | Чанки вокруг −1/0, −32/−33 и region seam раскрываются в разном порядке и параллельно; одинаковые block-state hashes; crater/ore/structure пересекают шов без дублей |
| Projection/scale | Chart→sphere→chart round trip в допустимой области ≤1 блока после rounding; poles/meridian/края дают явный отказ или адрес; quaternion и units сохраняют размер корпуса |
| Persistence | Добыча, fluids, piston, explosion, BE/inventory, штатный restart и удаление LOD-кэша сохраняют мир; смена profile не регенерирует старые chunks |
| Five LOD | Нет одновременного отображения противоречивых detail generations; stale reply rejected; карта не загружает distant L0; ограниченные CPU/GPU caches после 100 переходов |
| Landing | Асимметричный настоящий Sable объект со supported BE, грузом и пассажирами проходит 100 surface→orbit→surface cycles; совпадают ID, contents, seats, pose и physics |
| Failure/recovery | Занятое место, timeout, disconnected pilot, collision изменения и restart на каждой стадии не создают два канонических объекта; limits/durability явно записаны |
| Frames/gravity | Nonzero linear/angular velocity, поворот базиса и движущийся origin проверены independently; unsupported gravity дает отказ, не тихий fallback |
| Family transfer | Два состыкованных реальных объекта и пассажиры: единый recoverable commit либо documented unsupported; одиночный тест не заменяет эту строку |

Сначала реализовать datapack snapshot/manifest и постоянные bindings, затем детерминированный sampler и border tests. Параллельно отдельный Sable transfer prototype доказывает staging и сохранения на небольшом объекте. После этого подключать landing coordinator, LOD и map/render consumer. Материальный addon benchmark идет отдельным треком и не блокирует проверку простого sampler на стандартных blocks.

Блокеры выпуска посадки: неподтвержденный safe transfer backend и staging isolation; полнота сохранения unloaded plot/BE/passengers; durable recovery и connected-family semantics; подтвержденные convention velocities/frames/gravity; настоящий multiplayer стенд. Блокеры worldgen extension: pinned codecs/dimension lifecycle, immutable profile persistence и разрешение addon materials. Сфера vanilla мира и 5000 runtime dimensions не входят в критерии этого MVP.
