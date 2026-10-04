# Alpha.4: предварительный независимый review

Дата: 5 октября 2026 года. Область: IDEA-004/005/006 и PlanetSnapshotCodec. Основание: IDEAS.md, docs/WORK_ASSIGNMENTS.md и доступный срез назначенных исходников. Реализация другими чатами продолжается; это preliminary review и набор конкретных случаев для последующего review завершенного source. Gradle и runtime не запускались. **Ни одна проверка ниже не объявляется PASSED.** Статусы PLAN/IDEAS/VALIDATION не изменены.

## Наблюдаемые соглашения

`LivingGravityAdapter` явно ограничен milestone A: host вызывает apply/update/release, серверный поток, фиксированный world-Y frame, whitelist обычных мобов; игроки и специальные движения отвергаются. Владелец хранится по instance сущности, атрибута и modifier, а не только UUID/ID. Foreign effective baseline вычисляется отдельным AttributeInstance; запись проверяется чтением live value. Это обещания исходника, не подтвержденное runtime поведение.

`NativePhysicsFixture` требует Rapier backend, создает собственные тела/tickets, не шагает весь pipeline вручную. Доступная калибровка содержит пары driven/control, разные массы, identity/yaw90, pre/post dt, getter/displacement/quaternion сравнения и ненулевые нижние пороги. Жестко заданные ожидания: impulse local, additive velocities world, 20 ticks/s и dt=.05/substeps. Их нельзя считать доказанными до подтверждающего native запуска.

`PlanetSnapshotCodec` — deterministic big-endian in-memory формат, strict UTF-8, ограничение общего размера/числа записей/строк, закрытая schema, без fallback пустого каталога. Decoder делегирует дубликаты и immutable invariants Snapshot. Наличие тестовых методов не означает их прохождения.

## IDEA-005: обязательные риск-сценарии

| ID | Точный случай для source/runtime review | Требуемый инвариант |
| --- | --- | --- |
| A01 | apply 32→16→0→16, затем release; base и foreign modifiers всех трех операций | Один собственный transient modifier; исходные base/NoGravity не меняются, target effective value достигается либо собственная запись снимается |
| A02 | Foreign base/modifier меняется во время владения; reapply и release | Release раскрывает **текущее** foreign состояние, не восстанавливает старый снимок и не удаляет чужое |
| A03 | Чужой modifier занимает Universe ID до apply или заменяет собственный после apply | Refusal/release сохраняют чужой instance даже при одинаковом ID/amount; identity ownership не превращается в удаление по одному ID |
| A04 | Новый entity instance с повторным UUID; replacement AttributeInstance; два adapter на одном сервере | Старое владение не переносится на новый объект; второй adapter не крадет modifier; cleanup старого ownership проверяется отдельно |
| A05 | Off-thread apply/release при уже активном modifier; entity другого сервера и клиентского level | Никаких мутаций/удаления на неверном потоке/сервере; текущий source действительно проверяет gate раньше ownership доступа |
| A06 | Нулевой baseline→ненулевой target; ноль→ноль; clamp, отрицательная/неfinite foreign комбинация, превышение capacity | Явный отказ или проверенный результат; после same-thread refusal собственных следов нет; чужие данные сохраняются |
| A07 | Совпадающий frame ID, но rotating/ship frame; mismatch, nonvertical/upward vector, invalid sample | ID сам по себе не доказывает fixed world-Y. Host должен доказать допустимый frame; unsupported frame не применять |
| A08 | Player, mod subclass, вода/лава, пассажир/vehicle, elytra, levitation/slow falling, dead/removed, NoGravity | Support gates жесткие; NoGravity сохраняется; отказ при прежнем владении освобождает только свое |
| A09 | Dimension change, unload/reload, death/remove, host unregister | Milestone A не устанавливает lifecycle listeners; отсутствие permanent NBT modifier не доказывает освобождение strong references. Это открытый gate B, не причина заявлять production ownership |

Required trajectory tests должны сравнивать реального обычного моба с контрольной сущностью в воздухе при одинаковых AI/drag/начальных условиях: 32/16/0 м/с² дают ожидаемую разницу, не только значение атрибута. Отдельно проверять client sync до разрешения игроков; GameTest/fake player его не заменяет.

## IDEA-004: native калибровка

| ID | Точный случай | Что требуется доказать |
| --- | --- | --- |
| N01 | Две finite положительные реальные массы с одним J; контроль одинаковой массы рядом, identity/yaw90 | Измеренное Δv следует J/m; масса реально различается; вращение определяет local/world базис импульса, не подгоняется после результата |
| N02 | Additive V/ω с различными ненулевыми компонентами при обеих ориентациях | Getter меняется сразу **и** сохраняет ожидаемый эффект после native steps; pose/quaternion действительно двигаются; no-op не проходит |
| N03 | Base gravity/drag и начальная разница driven/control; короткий интервал | Вычитание контролей корректно для обеих масс. Скоростной drag может не сокращаться полностью: заранее фиксированный tolerance должен иметь объяснение, не расширяться до успеха |
| N04 | Pre/post ordering, положительный finite dt, другая substeps конфигурация | sumDt соответствует симуляционным секундам; нет второго шага pipeline; повторный/пропущенный hook и нулевое движение приводят к fail |
| N05 | Quaternion delta, поворот 90°, ненулевая ω; getter vs finite difference | Проверены порядок умножения, world/local angular basis, rad/s, знак shortest-arc; линейное суммирование малых rotation vectors допустимо только в заявленном коротком интервале |
| N06 | Reset velocity no-op, invalid handle, native timeout, частично созданная fixture, cleanup exception | Setup не маскирует дефект тестируемого additive API; не удаляются чужие тела; все свои tickets/listeners освобождаются; failure не превращается в succeed |

Специальный review-point: fixture проверяет воздушный объем у worldPosition, но реальные блоки создаются в plot coordinates с rotationPoint=centerOfMass. После готовности требуется подтвердить, что world bounds действительно находятся в проверенном объеме и не контактируют с другими телами. Контрольное тело должно иметь одинаковые shape/mass/physics параметры, кроме экспериментального воздействия.

## IDEA-006: gates до velocity round trip

Implementation после подтвержденной IDEA-004 A. В предварительном срезе завершенность transfer не оценивалась. Capture выполняется на однозначной границе подшага; source/staging изолированы по проверенной source политике, не существуют как две активные движущиеся копии. Для additive восстановления вычисляется desired−current, а не второй captured V. Проверяются разные ненулевые V и ω **после activation** и после возврата. Invalid/unsupported handle и невозможность изоляции отказывают до удаления source. Пока frame identity, произвольные вращающиеся frames не покрыты. Новый native UUID не равен доказательству постоянного ShipId. Пассажиры, family constraints и durable recovery остаются отдельными gates.

## PlanetSnapshotCodec: bounds, records, stale

- C01: input меньше header, total > limit, отрицательные/огромные count/string lengths и every-byte truncation: отказ до большой allocation; integer overflow не обходит long size и лимиты.
- C02: magic/schema/snapshot format, frozen вне 0/1, negative/mismatched revision, unknown realm, trailing bytes, malformed UTF-8: явный отказ без пустого replacement. Верхние границы и tighter Limits проверяются encode и decode симметрично.
- C03: duplicate ID при различном realm/seed/profile, reordered records, крайние long seeds, frozen/unfrozen empty: порядок/поля сохраняются; duplicate отказывает, исходный snapshot остается immutable и не зависит от input array после decode.
- C04: revision==count соответствует текущему append-only Snapshot contract; изменение семантики revision требует schema/migration, а не ослабления parser молча.
- C05: валидный старый snapshot может быть структурно допустим: codec не знает revision текущего host. Stale/replay отклоняет слой публикации/consumer; нельзя обещать anti-stale только успешным decode. Проверить это различие в финальной документации/test names.
- C06: profile ID синтаксически корректен, но lookup отсутствует: codec не выполняет resolver/bootstrap, не создает dimensions и не доказывает профиль доступным. Эта граница сохраняется.

## Перед окончательным review

Получить от root готовый срез и фактические журналы последовательных запусков; сверить cases с тестами и дать точные source paths/lines для обнаруженных дефектов. Пока это перечень рисков и границ, а не заключение о пройденной alpha.4. Новые идеи и изменения чужих исходников не внесены.

## Конкретный source review готового среза

Повторный read-only просмотр назначенных файлов выполнен 5 октября 2026 года после сообщения root о готовности. Gradle/Java не запускались. Номера строк относятся к прочитанному срезу; параллельные правки могут их сместить.

### Обнаруженный дефект

**[P2] Аварийная очистка теряет принадлежащие fixture тела.** `src/sableTest/java/dev/heiko/universe/sabletest/NativePhysicsFixture.java:75–83`: снятие ticket и удаление тела находятся в одном try. Если `removeForceLoadTicket` бросает исключение, `removeSubLevel` для этого тела вообще не вызывается. После обработки ошибок `owned.clear()` выполняется безусловно, поэтому повторный `cleanup()` из `SablePhysicsCalibrationTests.java:194–206` не может повторить удаление оставшегося тела. При отказе самого `removeSubLevel` владение также теряется. Тест завершается ошибкой, но его native тело/ticket могут остаться в общем physics system и повлиять на следующие batch.

Минимальное исправление: выполнять обе операции независимыми try, агрегируя исключения; удалять запись из `owned` только после подтверждения отсутствия тела в контейнере. Сохранить неочищенные записи для повторного close; предусмотреть повторное снятие ticket при идемпотентной политике. Это исправление касается только принадлежащих fixture объектов, не удаления всей сцены. Негативный тест должен инжектировать ошибку снятия ticket и удаления тела, проверяя независимую попытку второй операции и возможность повторной очистки.

### Gravity: выводы и непокрытые cases

- `LivingGravityAdapter.java:70–75,140–146`: server-thread gate предшествует доступу к world/ownership; wrong server не меняет состояние. `154–159` снимает modifier только при точном совпадении instance, а `106–108` отвергает чужой occupied ID. Доказанного удаления foreign modifier в этом срезе не найдено.
- `115–135`: baseline рассчитывается mapped AttributeInstance без собственного modifier, target переводится /400, собственный total multiplier заменяется, read-back mismatch освобождает только свое. Baseline уже clamped, поэтому ratio не гарантирует успех для исходного raw значения за пределами clamp; отказ `VALUE_MISMATCH` здесь соответствует заявленной узкой политике, а не дефекту. Base/foreign values не перезаписываются. Ноль→ноль допустим, ноль→ненулевой target отвергается.
- `27–28,94`: matching frame ID — host assertion, не геометрическая проверка. Адаптер не связывает frame с dimension/host token. Это допустимо только для milestone A с явным контрактом; нельзя объявлять автоматическое освобождение при context change или production B. Strong references `owners` и отсутствие releaseAll/lifecycle остаются заявленной границей.
- `LivingGravityGameTests.java:153–186`: off-thread case проверяет исходно пустой набор modifiers. Он не доказывает сохранение **уже активного** ownership после off-thread apply/release. Минимальное усиление: сначала apply на server thread, выполнить оба вызова вне потока, сравнить тот же modifier instance/value, затем release на server thread. Wrong-server и два adapter также требуют отдельного случая; это пробелы проверки, не обнаруженные мутации кода.
- `LivingGravityGameTests.java:41–77`: реальная trajectory сравнивается с control, AI отключен, floor/fluid контакты исключаются, повторный apply проверяется. `88–123` охватывает текущие foreign base/add-value/multiplied-base/total и transient сохранение; occupied-ID takeover также проверяется. Эти assertions выглядят содержательными, но runtime результат не установлен reviewer.

### Native: математика и ограничения доказательства

- `SablePhysicsCalibrationTests.java:121–147`: один J для двух измеренных масс, matching control mass и distinct mass gate; yaw90 задает ожидаемый local impulse заранее. Immediate и post-step getter проверки не заменяются teleport. Additive V/ω имеет ненулевые компоненты; `183,189` не допускают нулевого motion PASS.
- `150–188`: dt из paired hooks интегрируется в секунды, control baseline вычитается для линейного displacement/getter. Quaternion `current * inverse(previous)` задает world angular delta; shortest arc корректно выбран. Angular pose измеряется только для driven, тогда как angular getter integral вычитает control. Это корректно при нулевой контрольной ω, но тест прямо этого не утверждает. Минимальное усиление: перед измерением assert finite near-zero ω обоих после reset; либо вычитать измеренную quaternion rotation control тем же способом. Иначе ненулевое контрольное вращение может дать ложный FAIL, не надежную калибровку базиса.
- `NativePhysicsFixture.java:63–68` использует тестируемый additive API для reset, но не проверяет near-zero после reset. `SablePhysicsCalibrationTests.java:99–108` дает два тика публикации teleport, затем повторно reset. Реальные ненулевые motion assertions защищают от полного no-op; неполный reset и поддержка sleep требуют фактических логов, а не предположения.
- `NativePhysicsFixture.java:32–50`: проверяется air только ванильного level, не collision с другими sublevels; граница rotationPoint/plot должна быть подтверждена world bounds. Удаленные позиции и разные variant уменьшают вероятность контакта, но source не гарантирует отсутствие чужого native тела. Это ограничение изоляции стенда; не объявлять calibration PASS на загрязненной сцене.
- `117`: dt=.05/configuredSubsteps строго проверяется, а не измеряется независимо от config. При несовпадении тест верно падает; успешный запуск одной конфигурации не доказывает другие substep counts. Не расширять tolerance после запуска без объяснения drag/integrator ошибки.

### Codec: проверка allocation и invariant

`PlanetSnapshotCodec.java:31–38,46–61,80–99`: tighter Limits нельзя расширить, total проверяется до output allocation, input ограничен до decoder allocation; size накапливается long. Count ограничен 65536 и remaining/33, строки — 256 bytes и remaining. Strict UTF-8, unknown schema/format/realm/frozen и trailing bytes отклоняются. Snapshot повторно валидирует duplicate IDs/revision и копирует list; decoder не сохраняет buffer/input. Доказанного bypass bounds, integer overflow или пустого fallback не найдено. Worst-case count/list/strings ограничены форматом, хотя лимит 16 MiB не является обещанием пикового heap ≤16 MiB.

`revision==count` соответствует текущему append-only contract Snapshot; stale относительно другого host snapshot принципиально не определяется этим codec. Lookup профильных ссылок и atomic host publication вне его области. Decode корректного старого revision не является ошибкой parser. Наличие JUnit cases strict UTF-8/duplicate/limits/truncation — свидетельство структуры тестов, не результат их запуска.

Итог source review: один конкретный дефект failure cleanup; остальные перечисленные пункты — границы или усиления проверки. Source review не устанавливает runtime PASS для gravity, native калибровки, velocity transfer или alpha.4.

## Read-only review нового velocity transfer режима

Срез `src/sableTest/java/dev/heiko/universe/sabletest/SableNativeTransferTests.java` прочитан во время исправления автором, 5 октября. Root сообщил: calibration-2 — 6 native прошли; запуск `20261004T183858438Z-24432` — 104 JUnit/10 core GameTest прошли, native 7/8, velocity round trip failed после leg0 с Diamond inventory mismatch. Последняя ошибка непосредственно видна в `sable-gametest.log:87`. Это результат прежнего запуска, **не** нового диагностического source с full chest NBT. Причина потери inventory по одному сообщению не установлена; успешный повторный запуск нового режима reviewer не выполнял.

### Конкретные дефекты failure paths

1. **[P2] Остановленная session продолжает отложенные callbacks.** `SableNativeTransferTests.java:707–712`: `guarded` не проверяет `stopped`, хотя `cleanup:693` ставит его true. Callback warmup `311–338` уже мог быть поставлен до ошибки/timeout; после cleanup он снова меняет pause flags, restore/observing и регистрирует listener. То же относится к запланированному `afterMotion:361` и ожиданиям `662–678`. При отказе с еще ожидающими callbacks session может повторно мутировать очищенную сцену и нарушить восстановленную паузу. Минимальное исправление: `if (stopped) return` в начале guarded и явно терминальное состояние успеха/ошибки; все deferred entry points должны использовать этот gate. Тест timeout до выполнения warmup должен подтвердить отсутствие повторной регистрации/мутаций после cleanup.
2. **[P2] Cleanup прекращается на первом неудачном remove.** `692–703`: `commitRemoval:687–689` объединяет ticket release и remove; исключение первой операции пропускает вторую, а исключение любой операции прерывает цикл по `owned` и `processSubLevelRemovals`. Finally восстанавливает pause, но оставшиеся тела не удаляются. Повтор cleanup может снова остановиться на том же теле и оставить остальные. Минимальное исправление: независимые best-effort операции для каждого своего тела, продолжение цикла/дренирования, aggregate exceptions; не считать ошибку cleanup успешным probe. Это тот же класс дефекта, что предыдущий fixture finding, в другом назначенном файле.

### Capture/activation и pause: пределы доказательства

`248–255` сохраняет и меняет pause **всего physics system двух измерений**, а не конкретного объекта. `310,336–337` принудительно включает их даже если исходный originalPause был true; finally возвращает исходные флаги. Это допустимо только для эксклюзивного тестового мира без другого владельца pause/параллельного probe. Source не проверяет эксклюзивность или отсутствие чужих sublevels. Поэтому данный механизм нельзя переносить в production или заявлять per-object isolation. Минимальный gate стенда: подтвержденная последовательность batches и контролируемые/отсутствующие чужие тела; иначе fail до pause, не глобальная перенастройка живого мира.

Capture `284–290` происходит в серверном callback при paused systems; это не непосредственный physics event capture. Отсутствие solver progression до capture зависит от реальной семантики `setPaused`. Source control V/ω проверяется после staging `320–323`. Native source orientation читается из pipeline `324`; target teleport/updatePose и desired−current restore `325–327` избегают повторного additive captured V. Обе системы активируются только для измеренного интервала; post hooks требуют .025 и ровно .1 секунды, source остается control до проверки. Commit вынесен из body iteration `360–361,401–403`. Это содержательная узкая identity-frame проверка, но не доказательство атомарного production transfer: обе копии активны в разных измерениях до удаления source, tickets/actor ticks продолжаются во время pause.

### Первый и второй save/load, reuse slot

Stage `491–540` проверяет source до save, делает `.copy()` снимка, сравнивает native serialized chest с full live NBT и full destination NBT после load, сохраняет name/marker. На обратном leg вызывается **новый** stage/save, а не повторно первый snapshot. Это правильно обнаруживает потери второй сериализации. Фактический NBT зависит от будущего запуска диагностического кода; нынешнее inventory failure не разрешено самим добавлением assertions.

После commit `401–404` проверяется отсутствие source UUID, затем немедленно снова используется первоначальный slot `408,498,515–516`. UUID/slot occupancy отсутствие не доказывает освобождение связанных chunk/BE caches. Именно здесь следует сопоставить новые diagnostics: chunk identity, loaded/pending BE и NBT до save/после load/после level lookup на leg1. Source не доказывает, что старый chest cache очищен; **это подозреваемый механизм, не установленный дефект Sable**. Не исправлять mismatch инвентарем из отдельного liveChest снимка или ручным refill: такой bypass скрыл бы непригодность публичного native save/load.

`processSubLevelRemovals` вызывается контейнерным lifecycle при pause; нельзя приравнивать это к доказанному освобождению всех native/storage ресурсов без просмотра lifecycle/логов. Success gate должен включать оба полных NBT после return и отсутствие потерянных/дублированных BE, как текущие проверки намерены делать.

### Unavailable-capability guard: что реально проверяется

`412–442` создает и удаляет отдельную native fixture, требует invalid handle и инжектирует его resolver в **тот же** `stageVelocity` guard. Проверки source/count/V/ω/contents доказывают отказ до stage allocation при resolver, возвращающем invalid handle. Это честный негативный тест guard, не симуляция исчезновения physics capability у живого source. Он не покрывает valid-but-no-op getter, несовместимый backend, потерю capability после guard или setter failure при activation. `captureHandle:272–280` проверяет регистрацию source, valid/finite getters и mass; не связывает возвращенный handle с source, поэтому injected handle semantics должны остаться test seam, не публичным production resolver обещанием. Native nonzero calibration отдельно необходима для no-op операций.

Итог нового review: два конкретных failure-path дефекта; whole-dimension pause и reused plot slot имеют обозначенные gates/ограничения. Inventory root cause пока не доказан. Нового PASS или завершения IDEA-006 этот отчет не устанавливает.

## Финальная оценка alpha.4 по исправленному source и evidence

5 октября 2026 года reviewer выполнил только чтение последних исходников и предоставленных журналов. Предыдущие findings выше сохранены как история; их актуальный статус указан здесь. Новых Gradle/Java запусков reviewer не выполнял.

**Все три ранее найденных P2 закрыты по текущему source.** `NativePhysicsFixture.close` независимо пытается снять ticket и удалить каждое свое тело, не очищает `owned` безусловно, сохраняет неочищенные записи. `SableNativeTransferTests.java:745–790` продолжает best-effort cleanup остальных тел/измерений, отдельно дренирует removal, агрегирует ошибки и независимо восстанавливает pause/listener. `799–804` содержит `if (stopped) return`, поэтому запланированные guarded callbacks больше не возвращают остановленную session к работе. Есть отдельный stopped-callback regression в native стенде. Это source-подтверждение устранения описанных путей; не обещание успеха любых исключений backend.

Калибровка теперь проверяет reset V/ω near-zero. Ее `complete` немедленно ставит finished, а cleanup и helper.fail/succeed выполняет через `runAfterDelay(1)` вне Sable event/body iteration. Native transfer после leg0 также перед повторным использованием plot slot проходит запланированный обычный level/GameTest tick: `405–418` сохраняет проверку removal и дополнительно вызывает waitForRemoval с отложенным continuation. Это lifecycle barrier для текущего стенда, а не искусственное изменение gameTime или ручной solver step. Само успешное прохождение не доказывает, какой внутренний cache вызвал прежний Diamond mismatch; причинная формулировка ограничена «немедленное reuse заменено на lifecycle yield, повторный тест прошел».

### Непосредственно прочитанные доказательства

| Evidence | Подтвержденный результат | Граница |
| --- | --- | --- |
| `.tooling/test-runs/20261004T184653688Z-14552/status.json` | alpha.4, Minecraft 1.21.1, NeoForge 21.1.251; 106 JUnit, 0 failures/errors/skipped; resources/junit/core-build-gametest exitCode 0; общий status PASSED | Sable в этом запуске явно NOT_REQUESTED, поэтому native evidence берется отдельно |
| Тот же каталог, `resources.log` | 125 checks, 0 failures, 11 JSON | Статическая проверка, не полная codec/registry/runtime приемка |
| Тот же каталог, `core-build-gametest.log` | `verifyCoreJar`; All 10 required tests passed; BUILD SUCCESSFUL | Серверный core GameTest, не настоящий клиент |
| `.tooling/alpha4-native-final.log:583,600` | All 10 required tests passed; BUILD SUCCESSFUL; root сообщил завершение процесса exit 0 | Один native стенд/версия, не production интеграция и не все версии Sable |
| Native final log:194,217 | leg0 и leg1 по .1 simulation second; ненулевые captured/post V и ω, реальный displacement; новый capture на return | Узкий whitelist, identity-frame и controlled two-substeps probe |

Логи первого и второго leg содержат разные скорости после действующих gravity/drag: return не проверяется как неизменное V в течение всего движения, а сравнивается с одновременным no-transfer control. Прежний inventory failure относится к предыдущему запуску; финальный required round trip прошел текущие проверки содержимого и identity. Codec/ownership assertions входят в подтвержденные suites/required тесты, но глобальная сумма тестов не расширяет их scope.

**Финальное review-заключение:** в просмотренном исправленном срезе нет остающихся обнаруженных P2 из этого отчета; alpha.4 имеет фактическое подтверждение перечисленной сборки, core tests и узкого native probe. Это не основание объявлять production transfer готовым. Whole-dimension pause остается исключительно механизмом контролируемого test world, injected unavailable handle — проверкой capture guard, host frame assertion — контрактом milestone A.

Открытые gates: production exporter/transaction/lifecycle; реальные пассажиры и client prediction/tracking; стыкованные группы/constraints; durable crash recovery и rollback в production; настоящие клиенты; нагрузки больших реальных объектов и аппаратно зафиксированные timings. Эти проверки не закрываются 106 JUnit, 10 core GameTest или 10 native required tests. Статусы чужих PLAN/IDEAS/VALIDATION этим review не изменены.
