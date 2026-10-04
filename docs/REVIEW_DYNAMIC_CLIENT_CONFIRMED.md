# Независимое ревью настоящего динамического TCP-прогона

**Подтвержден ограниченный стенд движения и рендера одного native Sable-объекта при неподвижной spectator-камере.** Проверен только `settled-tcp-20261004T220951556`. Это независимое чтение готовых исходников и артефактов, просмотр настоящих PNG и отдельный пересчет геометрии/телеметрии средствами PowerShell и C#. В ходе ревью Java, Gradle и Minecraft не запускались; runtime-исходники, допуски и ledger не изменялись.

`numeric-pixel-validation.json` сохраняет исходный статус `NUMERIC_PIXEL_PASSED_PENDING_MANUAL_REVIEW`, `visualAcceptance=NOT_EVALUATED`, девять выполненных проверок. Этот документ добавляет независимое визуальное подтверждение в указанной ниже области. Предыдущие сессии со статусом FAILED остаются FAILED.

## Идентичность и происхождение

| Поле | Значение |
|---|---|
| runId | `settled-tcp-20261004T220951556` |
| sessionNonce | `ce4c7b93a63e41a69ea5a9a1dc7a3dd6` |
| native body UUID | `4abe6a61-b939-46ad-9a5d-9815b94ce627` |
| server runtime nonce | `7a5fe16e-5cc1-48ae-895d-97676c41d52e` |
| client runtime nonce | `72191f66-05d8-4686-8891-fdd8a057180e` |
| server | PID `34504`, начало `2026-10-04T22:11:18.2010984Z`, EXITED, exit `0` |
| client | PID `30176`, начало `2026-10-04T22:11:46.355276Z`, EXITED, exit `0` |
| runtime | Minecraft `1.21.1`, NeoForge `21.1.251`, Sable `2.0.5`, Java `21.0.12.1+1-LTS` |
| transport | `TCP_CONFIGURED_FIXTURE`; actual common/client attempt UDP — `false` |
| endpoint всех кадров | `127.0.0.1/127.0.0.1:25575` |

Заново прочитаны и вычислены SHA256 **всех 91 файлов** из `artifactSha256`: отсутствующих файлов и несовпадений нет. Проверены исходники mapper/oracle/validator, descriptors, telemetry, PNG и sidecar, nonce, запуск с замороженными VM/program argument files, отдельные supervisors, completion и cleanup. Все 21 frameId и имена файлов уникальны; снимки относятся к одному UUID и sessionNonce. Время захвата: `1791151941813..1791151944675` ms.

Существенная граница provenance: запуск использует `-Dfml.modFolders` и `build/classes/java/{main,sableClientTest}` с `build/resources`. Alpha.5 JAR учтен по хешу, **но загруженный клиент — development profile**, а не проверка установки упакованного JAR. Текущий ledger фиксирует исходники и argument files; отдельно запечатанного набора всех загруженных `.class` и dependency JAR в нем нет. Не следует превращать этот результат в подтверждение packaged-JAR gameplay.

## Геометрия и камера

Один реальный Rapier body: панель 3×3 из четырех цветных и пяти белых opaque vanilla cubes, sea lantern над центром — десять блоков. Ordinary fixture: 121 smooth-stone блок пола x/z=-5..5 при Y80, семь magenta-блоков x=2/Y84..90/Z=-4 и cyan concrete landmark (-4,83,-6), всего 129 неподвижных блоков. Серверные descriptors подтверждают actual block states.

Все 21 sidecar содержат одну камеру `(0,95.61999988555908,-12)`, одну camera quaternion, одинаковые M/P; игрок `(0,94,-12)`, yaw `0`, pitch `30`. Фактический вертикальный FOV `69.999999076641°`. Независимое вычисление FOV из сериализованного `P[5]` дает `70.000000254°`; разница объясняется округлением float в JSON. Во время capture base FOV70/effect0, затем исходный effect1 восстановлен в памяти и `options.txt`. До первого запомненного кадра пропущено ровно **одно** world событие для штатного сглаживания FOV; `matrixReady=true`, cleanup error пустой.

Mapper не принимает PNG: `world = q * ((storage - rotationPoint) * scale) + position`; column-major M/P проецируют `world-camera`, PNG Y перевернут. Общие соседние faces исключаются; software depth учитывает остальные цветные, белые и lantern faces, неподвижную стену и пол. Inset применяется к объединенной маске, не к отдельным row polygons. Проверены отказ при nonfinite/near-plane/behind-camera и сравнение NDC depth. Небо не определяет ROI; строгий BLUE-классификатор `b>1.80*r && b>1.55*g` исключает ранее выявленный pale-blue sky.

Отдельный C#/PowerShell расчет corner projection известного cyan cube из baseline sidecar, без вызова Java mapper, воспроизвел внутреннюю union mask с inset2: **1696 px**, centroid **(596.0707547169811, 509.6880896226415)**. Все эти 1696 пикселей в настоящем baseline PNG классифицируются cyan. Это независимая проверка порядка матриц, направления камеры и PNG Y, а не только доверие полям ledger.

## Измерения и неизмененные gates

| Проверка | Фактический результат | Gate |
|---|---|---|
| native simulation | 42 шага × .025 s = 1.05 s; четыре setup/resume dt0 строки | 42 положительных dt |
| displacement | `(2.0061893463,-2.8762435913,0)` m | dx≥1.4 m; final Y≥83 |
| final Y / native rotation | `85.2096405029` / `.5015483830` rad | ≥83 / ≥.25 rad |
| независимый trapezoid integral | `(2.0039361075,-2.8648356699,0)` m | discrepancy≤.04 m |
| displacement/integral error | `.01162831696` m | ≤.04 m |
| native V endpoint continuity | максимальная компонентная ошибка `0` | непрерывность предыдущего postV и следующего previousV |
| held | 12 PNG, четыре стадии × три; ошибки position/angle `0/0` | ≤.10 m / .03 rad |
| live | девять PNG, три на каждый интервал | changing actual client pose и совместное совпадение с timestamped native sample |
| максимальное live расхождение | age `133` ms; position `.2440315261` m; angle `.06096716018` rad | ≤250 ms / .25 m / .08 rad |
| live first→last | displacement `2.4802036693` m; rotation `.4247094998` rad | ≥.5 m / .1 rad |

Независимый пересчет native integral из `previousNativeV`, `postNativeV`, `dt` воспроизвел ledger; положительные шаги и сумма времени пересчитаны отдельно. После начального native velocity input V=(2,3,0), omega=(0,.5,0) траектория развивается под native g=(0,-11,0) и drag≈.09. Между тремя интервалами используются native pause/hold; физическое торможение, pose teleport, ручная замена клиентского pose и synthetic rendering для этого прогона не применялись.

Данные ниже одинаковы во всех трех held PNG соответствующей стадии. Количество — площадь геометрически заданной внутренней маски / совпавшие actual color pixels; occupancy у всех eligible markers и cyan **100%**, centroid error **0 px**.

| Stage / sim s | RED | BLUE | LIME | YELLOW | Предсказано marker за стеной / actual magenta | Color leaks |
|---|---:|---:|---:|---:|---:|---:|
| 0 / 0 | 812 | 792 | 160 | 125 | 0 / 0 | 0 |
| 1 / .35 | 764 | 444 | 112 | 142 | 162 / 162 | 0 |
| 2 / .70 | 669 | 26 | 77 | не eligible | 729 / 729 | 0 |
| 3 / 1.05 | 535 | не eligible | 47 | не eligible | 912 / 912 | 0 |

Минимум два eligible visible markers сохраняются **во всех четырех стадиях**; minimum projected/actual area≥12, occupancy≥.70, centroid tolerance12 px. Cyan во всех 12 PNG — 1696/1696, tolerance4 px. Wall interior — 6762/6762 magenta без leaks; непустая глубинная проверка имеет ≥80 hidden px в трех стадиях, превышая минимум двух стадий, occupancy≥.90 и leaks≤2. Скрытые BLUE/YELLOW не используются как якобы видимые endpoint markers.

Одинаковые RED и LIME от baseline до final смещаются соответственно **61.38387743 px** и **74.18926069 px**, при минимуме20 px для каждого endpoint pairing. Их базис меняется с −90° до −115.37507020°, то есть **25.37507020°**, при минимуме8°; observed совпадает с predicted во всех четырех стадиях. Circular average предотвращает ошибку на границе ±180°.

100% occupancy и нулевая centroid error относятся к color pixels **внутри заранее спроецированной маски**. Это не попиксельное равенство всей сцены и не отдельная проверка полного silhouette, всех освещенных/теневых faces или любых shader combinations. Поэтому ниже отдельно зафиксирован ручной просмотр настоящих PNG.

## Независимый просмотр framebuffer

Просмотрены четыре held изображения: baseline40, stage1/frame76, stage2/frame115, final152; и **все девять moving изображения**:48/52/56,84/88/92,124/128/132. Размер960×540. Видны настоящие textured vanilla cubes, пять white cubes, sea lantern, cyan landmark и opaque magenta column. Панель движется влево, поднимается в первом интервале, затем падает и поворачивается; lantern поворачивается вместе с панелью. Пол, колонна и cyan остаются на месте. BLUE/YELLOW постепенно скрываются колонной; в final RED и тонкая видимая LIME поверхность остаются справа от нее. Это согласуется с actual pose и depth masks.

Меню, карта, proxy planet/model или нарисованный вместо world тестовый экран не наблюдаются. Обычные Minecraft toasts про chat verification и Social Interactions находятся справа сверху и не пересекают проверяемые surfaces. Лог сообщает `Using Vanilla renderer mixins`; Iris отсутствует в loaded mod list, optional Iris target дает WARN, а не runtime failure. Никакого подтверждения Photon/cloud/custom shader backend этот просмотр не дает. В исходнике capture используется `Screenshot.takeScreenshot(mc.getMainRenderTarget())`; pose получен из действительного `body.renderPose()` с renderer timer TRUE. Пользовательская отрисовка marker proxy в capture отсутствует.

Native cleanup: UUID unregistered; ticket released; ordinary blocks/forced chunks remaining `0/0`; callbacks stopped, listener unregistered, pause и fixture policy restored; error пустой. Оба JVM orderly exited0. В логах присутствуют известные optional-class WARN; ERROR/FATAL и аварийной остановки не найдено.

## Хеши основных доказательств

Все значения — SHA256. Полный список 91 совпавшего файла находится в неизмененном ledger.

| Артефакт | SHA256 |
|---|---|
| numeric-pixel-validation.json | `eb1379a2a98a4f8fff68643b4fc31333a04ca450c553cadc4db2e27b34a1cd3f` |
| session.json | `4dd313e8cb34b16d5576cfa92d397ab41fb7e4c26ae02a71b5d4464b5a1130ba` |
| dynamic-telemetry.json | `c75905aebce04d4d64c11adccd59b2901f4b92bad95381877f97b5b88350caec` |
| dynamic-cleanup.json | `147892f3024c4f24f1063fc0770f4f70ac6ae4ba281c4e9798bcbedc028e74ab` |
| DynamicGeometryMapper.java | `65b250ec37717f6ac160aef1c9a92590f9e9991cfa8ce27b24b7d2262ee481ec` |
| DynamicPixelOracle.java | `010a350464cd70943e99bdcb314ce99d28ae274309a23c47190b1d1ec9bc3e28` |
| DynamicProbeValidator.java | `f9d497862973f2013bc24c90d88ca908a3218091067344a1c4f00cb5f6239a26` |
| tracked universe-0.1.0-alpha.5.jar | `d807cd1f1e3d6f04abb47ab3be6692428a520a3a9d939bc9cef829ee55583ebc` |
| stage-0-frame-40.png | `c8f24482b93b077a1f58ee86850eac345ebbc45d69923042aa336d49898ac04d` |
| stage-1-frame-76.png | `f2e9e5ace2d0e95a85d9dc2c7a5e5285efa7b57b5656886ee9204a8a43914f3e` |
| stage-2-frame-115.png | `e91ac95d3f15097989e959f13f5f2f2b3490d0c99679589884a4bced23949584` |
| stage-3-frame-152.png | `629851438b898acda51266ee88291acd3b05c7ba3f25d1862f6a449e2fea1ab0` |
| stage-100-frame-48.png | `e955c994e84a55ee17deb88a82ca81b97e1e208ed9f6d24303a3d494a7f8b7f5` |
| stage-100-frame-52.png | `5cadda0a09c36ebbe76333db5edaff84df560312dfec9a1c14765d7a0581e7cd` |
| stage-100-frame-56.png | `1e5d4e7894c36c9fef15b17be3125dd4e3f6c2cfff67ba4188a62cdc6a5d0c64` |
| stage-101-frame-84.png | `54a52e379498198f587d3567fad7e7b3a1d456a11283365ad3d88bc2412b5f53` |
| stage-101-frame-88.png | `c3fb68d74bdfb1f0f5b20d82ae4d885ba45131a73d79ab4828f3d432b2e858fe` |
| stage-101-frame-92.png | `95eab64457af78006112535f3fa224c460436d288280630ee97280eb053344e7` |
| stage-102-frame-124.png | `92c7b1dbb9bfdfa869535c5abbee23cc269aaf71f9c183cabafa8c0a7cd5c0f7` |
| stage-102-frame-128.png | `a47d7d223311931bffe47de4fbf98039c4cfe29b162bf5ab1280d66111cf2d51` |
| stage-102-frame-132.png | `1596c838f903547ab83af5f6c8c158ca5fdc62dacf83857285fdf814804f2464` |

## Область подтверждения

Подтвержден этот disposable loopback **TCP** development fixture: один native body с десятью opaque vanilla blocks, стартовая скорость, native gravity/drag, три коротких интервала движения, native held boundaries, неподвижная настроенная камера, корректная наблюдаемая геометрия/окклюзия и завершение owned ресурсов. Live-пиксели просмотрены вручную; численные pixel masks применены к 12 held PNG. Это не тест движущейся камеры.

Из результата не следуют исправление UDP/reordered STOP, production pause guarantee, смена измерения/планеты, переходы уровня детализации, пассажиры, стыковка двух объектов, multiplayer correctness, huge ships/FPS/TPS/performance, 5000-блочный registry, Photon/атмосфера/облака или готовая игровая функция alpha.5. Они требуют своих свежих сценариев и доказательств.
