# Alpha.5: независимый review gravity lifecycle

Дата: 5 октября 2026 года, Asia/Bishkek. Reviewer прочитал текущие `LivingGravityDomain`, `LivingGravityLifecycleEvents`, adapter hooks, семь lifecycle GameTest и evidence общего запуска. Исходники и чужие документы не изменялись; Java/Gradle не запускались. Вывод относится к проверенному срезу alpha.5, не к будущим правкам.

## Подтвержденные evidence

Каталог запуска: `.tooling/test-runs/20261004T191313577Z-34656/`. Время в status.json указано UTC; запуск соответствует 5 октября по времени пользователя.

| Источник | Прочитанный результат | Что доказывает |
| --- | --- | --- |
| `status.json` | Minecraft 1.21.1, NeoForge 21.1.251, mod 0.1.0-alpha.5; общий PASSED, exitCode 0 | Все запрошенные стадии общего runner завершились успешно |
| `status.json`, JUnit | 106 тестов, 19 suites, 0 failures/errors/skipped | Pure/unit suites прошли; это не клиентский runtime |
| `core-build-gametest.log:15,66,80` | verifyCoreJar, All 17 required tests passed, BUILD SUCCESSFUL | Сборка/проверка jar и core серверный GameTest, включая семь lifecycle cases |
| `sable-gametest.log:583,600`, status | All 10 required tests passed, BUILD SUCCESSFUL; PASSED_NATIVE_PROBE, exitCode 0 | Узкий native probe остается рабочим в составе alpha.5; production adapter/пассажиры/клиенты не покрыты |
| `status.json`, resources | PASSED, exitCode 0 | Статический ресурсный этап успешен; не полная загрузка обычного игрового мира |

## Source review: ownership и контекст

`LivingGravityDomain.open` сначала проверяет серверный поток, host ID, stopped/stopping и лимиты, затем публикует domain. `SCOPES` использует identity server keys и synchronized map; внутренний scope доступен только на соответствующем server thread. Host IDs уникальны внутри сервера, число domains ограничено 128; host и общий server ownership ограничены 1024. Sweep перечисляет bounded registrations, не всех мобов мира.

Регистрация opt-in: domain не выбирает сущности автоматически. Общая identity map запрещает второй host для того же instance; adapter дополнительно проверяет server/movement/player/frame и actual modifier ownership. Только успешный apply создает token. `Registration.update` требует прежний origin ServerLevel, живую не removed сущность, exact attribute/modifier; иначе освобождает регистрацию. Новый frame требует закрыть и повторно зарегистрировать token. Равенство строкового frame остается host assertion fixed world-Y, не доказательством допустимости вращающейся палубы.

`Registration.end` idempotent; проверка точного token в обеих maps не позволяет прежнему token закрыть successor. `close` и off-thread операции не мутируют состояние до thread gate. Adapter `releaseForLifecycle` намеренно проверяет только server thread: он может освободить старый owned attribute после выхода entity из origin, тогда как обычный public release проверяет текущий server level. `ownsExact` учитывает instance атрибута, сущности и modifier. Foreign takeover с тем же ID остается нетронутым; текущие base/foreign modifiers и NoGravity не восстанавливаются из старого снимка.

Фактические семь cases охватывают token/domain close и successor; реальную смерть/removed entity; реальную vanilla dimension transition с новым instance; NBT round trip без собственного transient modifier; foreign takeover; host cap/off-thread close/host conflict; shared server cap 1024 с сохранением unrelated owners. Это содержательные серверные проверки с реальными мобами, а не доказательство поддержки игроков.

## Events и пределы их гарантий

Relay подписан на main game bus. Leave и Level unload используют old/origin level для поиска ownership. Travel освобождает на **запросе** перехода: если поздний listener отменяет его, host обязан reacquire. Это заявленная fail-closed политика, не подтверждение успешного перемещения. Обработчик travel не запрашивает receiveCanceled; уже отмененное до него событие может быть пропущено. При этом entity остается в origin и token сохраняется — это не нарушение контекста, но порядок отмены влияет на конкретный результат. Обе последовательности отмены требуют runtime проверки и не должны описываться как один гарантированный исход.

Death не освобождается по потенциально отменяемому death callback: post-server-tick sweep проверяет фактическое alive/removed и неподдержанное движение. При отмененной смерти с восстановленной health ожидается сохранение token. End-of-tick cleanup означает, что изменения движения/NoGravity могут оставить собственный modifier до ближайшего sweep; мгновенная синхронная гарантия до этого события не заявлена.

Stopping помечает scope stopping и закрывает domains; stopped удаляет static scope. Повторный open проверяет server.isStopped и scope.stopping, поэтому последующие listeners не должны заново активировать ownership. Это source-механизм; штатное сохранение entity NBT без transient modifier не заменяет фактический stop/restart тест процесса. Level unload handler тоже пока проверен чтением, не реальным unload конкретного ServerLevel. Player logout/clone/dimension handlers — defensive cleanup; players по-прежнему отвергаются adapter.

## Заключение и открытые gates

В просмотренном срезе **новых доказанных source-дефектов не обнаружено**. Это результат независимого review, а не утверждение, что все lifecycle/runtime ветки прошли. Фактический PASS относится к 106 JUnit, 17 core required и 10 native required из указанного общего запуска.

Runtime **PENDING**:

- Настоящий stop/restart процесса с активными domain/token; очистка static scope и отсутствие собственного persisted modifier после загрузки.
- Реальный Level unload с active ownership; cleanup old origin и отсутствие strong-reference остатка.
- Cancelled travel до и после relay, cancelled death с restored health; различия event priority/order и последующий reacquire.
- Игроки и client attribute prediction/sync: **unsupported**, не закрываются mob GameTest или defensive player events.
- Production Sable transfer, пассажиры, группы/constraints, durable recovery, реальные клиенты и нагрузочные timings остаются вне этой alpha-приемки.

Host все еще обязан явно открыть domain, зарегистрировать допустимые mobs, обновлять sample при изменении target и закрыть свою регистрацию. Lifecycle не включает автоматическое распространение gravity на обычные миры и не доказывает произвольные rotating frames. Финальный отчет не меняет статусы PLAN/IDEAS/VALIDATION.
