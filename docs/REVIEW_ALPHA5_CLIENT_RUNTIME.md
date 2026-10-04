# Alpha.5: независимый review настоящего client/dedicated runtime

Дата: 5 октября 2026 года, Asia/Bishkek. Срез: `static-20261004T194503688`. Прочитаны reports/metadata и финальный `validation.json` в `.tooling/client-probes/static-20261004T194503688/`, logs/properties в соответствующем `.tooling/client-runs/` и финальные test-only исходники `src/sableClientTest`. Reviewer не запускал Java/Gradle, не менял core и другие файлы. PNG не оценивались визуально этим reviewer; отдельная ручная статическая приемка root теперь завершена.

## Evidence и согласованность сессии

`session.json`, серверные/клиентский reports и все три frame metadata имеют одинаковые runId и sessionNonce `c07e085c8b374325a2504f256becce89`. Server runtime nonce `a86118d9-9dc1-42c0-b2f9-5dcb5af9acd6` согласован между manifest, серверными отчетами и shutdown request; клиентский runtime nonce отдельный и одинаков во всех кадрах. Это исключает очевидное смешение файлов разных сессий, но не является криптографической аттестацией.

Серверный READY report и клиентские report/frames/native cleanup указывают один Sable UUID `0903523c-34dd-4c74-a280-afaecb29620a`. Gravity Pig имеет другой UUID: он отдельная fixture для аудита ownership, не пассажир корабля. Endpoint кадров — `127.0.0.1:25575`; properties подтверждают loopback и offline disposable server. Клиентский журнал подтверждает настоящее remote connection/UDP authentication Sable и renderer initialization.

| Проверка | Фактические данные | Независимая оценка |
| --- | --- | --- |
| Client tracking/capture | `client/report.json`: trackingEvidence=true, 44 clientTicks, 80 worldFrames, error пустой, три capture paths | Подтвержден finalized сбор evidence на настоящем клиенте для совпадающего UUID |
| Frame provenance | Кадры 40/60/80; matching UUID/nonces; одинаковая static pose/camera | Последовательные кадры одной статической сцены, не доказательство движения |
| PNG files | static-0/1/2 существуют, размеры 278309/267993/257406 bytes | Capture files присутствуют; пиксельное качество reviewer не проверял |
| Client visual status | CAPTURED_PENDING_VISUAL_REVIEW; visualAcceptance=NOT_EVALUATED | Визуальный PASS не выставлять на основании tracking или наличия PNG |
| Финальная ручная приемка | `validation.json`: PASSED_STATIC_CLIENT_SMOKE_AND_REAL_SHUTDOWN; visualScope=MANUAL_STATIC_GEOMETRY_ONLY | Root просмотрел все три PNG: четыре цветных угла и sea lantern видны, loading UI не перекрывает fixture. Это отдельное ручное заключение, не автоматический pixel acceptance и не визуальная оценка этого reviewer |
| Gravity stop | `server/gravity-stop.json`: PASSED_REAL_SHUTDOWN_AUDIT, assertionsPassed=true | Реальный положительный shutdown audit в заданных условиях |
| Native cleanup | ticketReleased=true, nativeUnregistered=true, remainingSublevels=0, matching UUID | Собственный объект снят с регистрации, ticket освобожден; не тест persisted reload |
| Завершение | shutdown request CLIENT_TERMINAL_REPORT; клиентский `Stopping!` и закрытие UDP, сервер сохранил все dimensions | Журналы согласуются со штатным завершением обоих процессов; числовой process exit code эти прочитанные отчеты отдельно не содержат |

Финальный `validation.json` дополнительно фиксирует `bothTestProcessesExited=true`, matching native UUID/sessionNonce, три кадра 960 × 540 с SHA-256, core jar/source hashes и ссылку на core ledger. Reviewer прочитал эти записанные evidence; их хэши не пересчитывались этим review. Сырой клиентский report намеренно остается `NOT_EVALUATED`: он отражает автоматический capture, а итог ручной визуальной оценки хранится отдельно. Это согласованные уровни evidence, не противоречие статусов.

Серверный журнал включает сохранение обычных и четырех Universe dimensions. Это реальное создание/работа server levels в данном запуске, но не посещение каждой поверхности, ее генерация или безопасная посадка игроком.

## Shutdown audit: source и результат

`ProbeGravityStop` создает реального Pig на dedicated server, удерживает chunk, регистрирует один domain/token и сохраняет точный instance modifier. NoAI установлен намеренно; report прямо содержит `trajectoryAcceptance=NOT_EVALUATED_NOAI_FIXTURE`. Следовательно, длительное сохранение регистрации не является trajectory test или доказательством client gravity sync.

600 наблюдений live pre-tick закончились без ownershipLost; перед stop token активен. HIGHEST ServerStopping handler проверяет beforeStoppingOwned и exact modifier instance **до** core relay. LOWEST handler только наблюдает: domainClosed=true, tokenInactive=true, scope/domain entries=0, modifierAbsent=true. Он не закрывает domain вместо core, поэтому результат не замаскирован ручным release. Временные отметки lastLive → HIGHEST → LOWEST → stopped упорядочены.

В ServerStopped test используется новый уникальный host ID, поэтому отказ не может быть объяснен duplicate host. Report подтверждает isStopped=true, stoppedOnServerThread=true, точное сообщение `Server gravity lifecycle is stopped`, postStopOpenRejected=true и stoppedScopeEntries=0. Это подтверждает ранее pending **штатный stop** с активным ownership для одного настоящего dedicated процесса. Restart этого мира/нового процесса здесь не выполнялся; combined stop/restart gate остается частично открытым.

`ProbeServer` инициирует `server.halt(false)` после принятия terminal report, а не kill процесса или вручную posted lifecycle events. Native cleanup использует собственный ticket/object и проверяет отсутствие UUID в контейнере после removal. Report remainingSublevels=0 относится к тестовому контейнеру, не к доказательству отсутствия native ресурсов всех возможных миров.

## Предыдущие failures и границы причинного вывода

Root сообщил о сохраненных failed audits: `spawn-animals=false` приводил к удалению Pig; последующий диагностический DISCARDED согласуется с pinned ServerLevel.shouldDiscardEntity. В этом review разрешен только final run: прежние файлы и pinned mapped source отдельно не читались, поэтому точная причинная цепочка прежних запусков не объявляется независимо воспроизведенной.

Final `server.properties` непосредственно показывает `spawn-animals=true`; финальный live audit подтверждает 600 ticks без преждевременной потери ownership. Это новая успешная fixture-конфигурация, не основание стирать прежние failures или утверждать, что domain предотвращает серверное discard животных. Ошибки setup не следует выдавать за lifecycle PASS.

## Заключение

В просмотренном source/evidence новых доказанных дефектов не обнаружено. Подтверждены настоящее подключение одного клиента и finalized static tracking/capture evidence, реальный shutdown cleanup gravity с активным pre-stop владением, native unregister/ticket release и штатная последовательность остановки. Отдельная ручная визуальная приемка root завершена: итоговый статус **PASSED_STATIC_CLIENT_SMOKE_AND_REAL_SHUTDOWN** записан в `validation.json`. Мой независимый review подтверждает согласованность source/reports и этого финального evidence; пиксели лично не оценивались.

Не закрыты этим запуском: движение и orientation/velocity sync на клиенте, полет/межсистемный production transfer, пассажиры и группы/constraints, клиентская gravity prediction для игроков (players unsupported), persistence-restart и crash recovery, настоящий Level unload и canceled-event paths, multiplayer с несколькими клиентами, performance/timings больших структур. 80 worldFrames — счетчик evidence, не измерение FPS, latency, GPU cost или стабильности под нагрузкой. Статусы чужих документов не изменены.
