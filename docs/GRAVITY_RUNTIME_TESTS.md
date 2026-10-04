# Настоящие unload/reload и process restart: alpha.5

5 октября 2026 года. Оркестратор выполнил три обычных dedicated-server запуска Minecraft 1.21.1 / NeoForge 21.1.251 / Java 21.0.12.1+1. Это отдельный opt-in test mod без Sable/client, GameTestHelper и ручной публикации событий. Main alpha.5 не изменялся; полный runner остается 106 JUnit, 17 core и 10 native required.

Итоговый ledger: `.tooling/gravity-runtime-validation.json`, статус `PASSED_ACTUAL_MOB_CHUNK_UNLOAD_AND_NEW_PROCESS_RESTART`. Все три JVM завершились с exit 0. VM/program argfiles заморожены по runId; source/argument/report/log hashes сохранены. Reports: `.tooling/gravity-runtime-probes/<runId>/report.json`. Logs и supervisor: `.tooling/gravity-runtime-runs/<runId>/server/`.

| Реальный запуск | PID | Результат | Wall time JVM |
| --- | --- | --- | --- |
| `unload-20261004T201625907` | 29168 | PASSED_ACTUAL_UNLOAD_RELOAD | 21.19 s |
| `writer-20261004T201817386` | 22232 | PASSED_REAL_SAVE_AND_STOP_WRITER | 18.34 s |
| `reader-20261004T201955817` | 22436 | PASSED_NEW_PROCESS_SAVED_WORLD_READ | 17.21 s |

Один persistent NoAI/invulnerable Pig в chunk (1024,1024), далеко от spawn и без игроков, получил opt-in регистрацию. Fixture сохранил мир и снял только свой forced ticket. Tracking-end receipt пришел на tick 41 с null removal reason; exact ChunkEvent.Unload исходного LevelChunk — на tick 43. Позже обычный entity manager присвоил UNLOADED_TO_CHUNK. До re-force требовались обе receipts, настоящая removal reason, hasChunk=false, getChunkNow=null, visible holder=null и отсутствие UUID в loaded index. Старые token/own modifier/manager entry сняты core, без ручной подмены cleanup стендом.

После штатной загрузки LevelChunk и Pig — новые instances, UUID Pig прежний `b5bb4110-d22b-4c34-a9fe-db46b9de3d10`. Еще 40 обычных тиков подтверждают отсутствие transient modifier и auto-registration. Сохранны base gravity 0.08, foreign permanent ADD_VALUE 0.02, effective gravity 0.10, NoAI и NoGravity=false. Только после приемки fixture удален и собственный ticket освобожден. Probe удерживает старые references для сравнения: это не доказательство освобождения heap или whole-Level unload.

Writer сохраняет моба с активным владением. HIGHEST настоящего ServerStopping проверяет живой Pig, active token, exact modifier и обе entries=1 до core relay; LOWEST проверяет closed domain/entries=0/modifier absent и снимает только свой force ticket до final save. Pig не discard-ится. После ServerStopped и выхода JVM supervisor копирует весь stopped world в новый reader-run: **37 файлов** с проверенными SHA-256, включая level.dat, region/entities/data, marker и fence. Перед recursive copy проверяются все path ancestors и source children на reparse points; writer marker/report/state согласованы. Digest — reader `copied-world.json`.

Writer/reader имеют разные PID и runtime nonce, общий session nonce. Reader штатно загружает UUID `c6d76a03-058f-4951-8925-4b98bfac3be9` из copied marker. В reader branch нет EntityType.create, Pig.load или addFreshEntity; substitute Pig запрещен. Еще 40 тиков проверяют persisted foreign/base/flags, отсутствие own transient и регистрации. Это новая JVM, а не новый MinecraftServer в той же JVM.

Измерен ограниченный ServerTickEvent.Pre → Post интервал, включая cold setup, observer и save/load затраты. Он не объявляется полным MSPT или большим нагрузочным benchmark.

| Сценарий | Samples | Mean ms | P50 ms | P95 ms | Max ms |
| --- | --- | --- | --- | --- | --- |
| Unload/reload | 100 | 12.596 | 1.329 | 50.086 | 85.717 |
| Writer | 40 | 7.582 | 1.488 | 31.841 | 84.891 |
| Reader | 43 | 4.459 | 1.073 | 28.905 | 39.677 |

Открыты whole-Level dynamic unload, canceled events, same-key replacement, игроки/client prediction, Sable persistence/пассажиры/группы, crash recovery и большие объекты. NoAI fixture не проверяет траекторию. Native leaks и storage durability barriers не измерены.

Первый `unload-20261004T201220554` сохранен как FAILED: стенд требовал UNLOADED_TO_CHUNK непосредственно в раннем tracking-end callback. Pinned PersistentEntitySectionManager сначала переводит visibility в HIDDEN и прекращает tracking, затем сохраняет/удаляет entity без второго tracking callback. Исправлены наблюдения; требование настоящей removal reason сохранено. Первый export failure из-за Groovy multiline expression также сохранен; сервер тогда не запускался.

Core JAR повторно проверен verifyCoreJar; `.tooling/gravity-runtime-core-jar-verify.log`. SHA-256 прежний `d807cd1f1e3d6f04abb47ab3be6692428a520a3a9d939bc9cef829ee55583ebc`; runtime/client/native fixture classes в нем отсутствуют. Независимое evidence review: [REVIEW_GRAVITY_RUNTIME.md](REVIEW_GRAVITY_RUNTIME.md).
