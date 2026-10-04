# Независимый review gravity unload/restart runtime

Дата: 5 октября 2026 года, Asia/Bishkek. Область: test-only `GravityRuntimeProbe.java`, `tools/gravity-runtime-profile.gradle`, `tools/run-gravity-runtime.ps1` и фактические artifacts трех запусков. Reviewer выполнил чтение и пересчет SHA-256; Java/Gradle не запускались, core и другие файлы не изменялись. Проверяется alpha.5 с одним NoAI vanilla Pig, без клиента/Sable.

## Проверенные запуски

Reports: `.tooling/gravity-runtime-probes/<runId>/report.json`; process audit/logs: `.tooling/gravity-runtime-runs/<runId>/server/`.

| RunId | Supervisor / JVM | Runtime report |
| --- | --- | --- |
| unload-20261004T201625907 | exit 0, PID 29168 | PASSED_ACTUAL_UNLOAD_RELOAD |
| writer-20261004T201817386 | exit 0, PID 22232 | PASSED_REAL_SAVE_AND_STOP_WRITER |
| reader-20261004T201955817 | exit 0, PID 22436 | PASSED_NEW_PROCESS_SAVED_WORLD_READ |

Все три supervisor status совпадают с соответствующим report. Серверные stdout заканчиваются штатным сохранением всех dimensions; stderr пустые. Supervisor проверяет ошибки runtime/shutdown, process exit, fresh report timestamps, scenario/runId/session nonce/PID и runtime UUID. Writer runtime nonce `fea378f7-8d4a-48df-908a-7c815aee10c7` и reader `0a546373-2044-4299-a11f-3c81651bcbd3` различны; session nonce совпадает намеренно для цепочки сохраненного мира. Reader report связывает writerRunId и writerPid, а не просто произвольный UUID.

## Реальный unload/reload

Source удерживает отдельный terrain chunk (1024,1024), далеко от spawn, без игроков и чужого force ticket. После 40 ticks активного ownership выполняется настоящее `saveEverything`, затем снимается собственный ticket. Observe использует identity original Pig и original LevelChunk, а не событие любого соседнего чанка.

Final report показывает leave event tick 41 с `leaveEventRemovalReason="null"`, отдельный ChunkEvent.Unload original chunk tick 43 и позднее `actualRemovalReason=UNLOADED_TO_CHUNK`. Source не требует removal reason в момент leave и не публикует synthetic events. Перед reload он проверяет одновременно отсутствие terrain в hasChunk/getChunkNow/visible chunk map, отсутствие UUID в loaded entity index и освобождение domain/token/собственного modifier. Report подтверждает terrainChunkAbsentBeforeReload, unloadCleanupObserved.

Повторная загрузка создает **новый terrain instance** и **новый Pig instance** с тем же UUID `b5bb4110-d22b-4c34-a9fe-db46b9de3d10`. После reload 40 ticks проверяется отсутствие Universe modifier/регистрации, сохранение foreign ADD_VALUE 0.02, base 0.08/effective 0.10, NoGravity=false и NoAI=true, правильный chunk. Это проверка фактического chunk/entity unload/reload; whole ServerLevel unload не выполнялся.

Первый `unload-20261004T201220554` сохранен как FAILED в WAIT_UNLOAD, ticks=2401, bounded timeout; в report уже был chunk unload tick 43. Предыдущее ошибочное ожидание leave removal reason исправлено на раздельные receipts. Сообщение root о mapped PersistentEntitySectionManager согласуется с final event-null/поздним actual reason. Сам mapped файл reviewer в этом срезе не перечитывал; успех финального теста не стирает исходный failed audit.

## Writer stop и новый reader

Writer сохраняет мир при активном transient modifier. HIGHEST ServerStopping наблюдает exact owned modifier, активные token/domain, живой original и одну запись перед core relay. LOWEST требует closed/entries=0 и modifier absent после relay. Writer не вызывает domain.close вместо наблюдаемого relay и никогда не discard Pig; после stopServer сохранения ServerStopped пишет marker PASSED_WRITE_STOP только при stopChecks и isStopped. Final writer report фиксирует writerStoppingCleanup=true.

Reader source ветка не вызывает EntityType.create/Pig.load/addFreshEntity: загружает terrain и ожидает `level.getEntity(expectedUuid)` из сохранения. Она проверяет writer marker/version/session/chunk и отдельные PID/runtime nonce. UUID `c6d76a03-058f-4951-8925-4b98bfac3be9` совпадает с writer; 40 ticks validateReload проверяют foreign/base/flags и отсутствие автоматической регистрации. В reader `newInstance=true` само по себе тривиально при original=null, но доказательство нового процесса дает отдельный PID/runtime nonce и отсутствие substitute creation, а не только этот boolean.

## Копирование мира и launch provenance

`run-gravity-runtime.ps1` требует завершенный успешный writer supervisor/report/marker и отсутствие живого прежнего JVM, новый пустой reader run directory, matching disposable fence. Копируется **весь остановленный world**, включая level.dat, entity/terrain region files, data, marker/fence и прочие файлы; не один Pig NBT. `copied-world.json` содержит 37 файлов с SHA-256. Reviewer пересчитал hashes исходного writer world: **0 расхождений** с manifest. Source supervisor перед reader launch проверяет каждый destination hash после Copy-Item. Поздний reader закономерно сохраняет/меняет destination, поэтому его нынешний hash не должен сравниваться с prelaunch digest как неизменный файл. Повторную копию reviewer не выполнял.

Gradle export создает уникальный launch bundle directory и копирует оба @argfiles из build/moddev туда. Все три запуска ссылаются на свои отдельные bundles. Reviewer пересчитал **6 argfile hashes**: совпадают с supervisor; пересчитал три source hashes для каждого запуска: **9/9 совпадают** с текущими назначенными исходниками. RunId/nonce/scenario и working directory дополнительно проверяются supervisor. «Immutable» здесь означает отдельную зафиксированную копию аргументов, не filesystem read-only ACL; classpath outputs остаются рабочими файлами, поэтому это provenance для контролируемого последовательного runner, не полная герметичная сборка.

Copy/launch path checks ограничивают операции disposable roots и отвергают reparse points. Fresh artifacts directory, marker и runtime nonce защищают от очевидного stale report. Supervisor сохраняет timeout/failure artifacts; успех не выводится только из наличия файла report.

## Заключение и scope

Новых доказанных дефектов в просмотренной узкой source/evidence цепочке не обнаружено. Подтверждены реальное освобождение gravity ownership при terrain/entity unload, reload нового instance с прежним UUID и foreign/base state, штатный stop с активным ownership и чтение полного сохраненного мира новым JVM без substitute Pig. Это закрывает указанные **узкие mob runtime gates** на данной версии/конфигурации.

Остаются открыты: whole-Level unload, canceled-event paths, players/client prediction, Sable transfer recovery/пассажиры/группы, аварийный crash restart и durability, несколько клиентов и реальные большие объекты. NoAI fixture не доказывает траекторию или client sync. Tick Pre-to-Post timings в reports — диагностические интервалы одного моба (unload p95 около 50.1 ms), не MSPT/performance acceptance и не нагрузочный PASS. Main alpha.5 не изменялся этим review; статусы чужих документов не менялись.
