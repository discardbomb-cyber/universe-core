# Независимый review реальных отмененных gravity callbacks

Дата: 5 октября 2026 года, Asia/Bishkek. Прочитаны `LivingGravityCallbackGameTests.java`, общий ledger `.tooling/test-runs/20261004T203523132Z-33440/status.json`, core/native журналы и правила core jar. Reviewer не запускал Java/Gradle и не менял исходники. Это проверка двух узких required GameTest с настоящими vanilla действиями над NoAI Pig.

## Evidence

Ledger фиксирует alpha.5, Minecraft 1.21.1, NeoForge 21.1.251; stages resources/junit/core-build-gametest/sable-gametest завершились exitCode 0, общий PASSED. JUnit вызван с `--rerun-tasks`: 106 тестов, 0 failures/errors/skipped. Core log: verifyCoreJar, All 19 required tests passed, BUILD SUCCESSFUL. Native log: All 10 required tests passed, BUILD SUCCESSFUL; scope ledger остается PASSED_NATIVE_PROBE.

В source оба callback test объявлены required=true. Aggregate core успех совместно с source подтверждает прохождение их assertions в этом срезе; журнал не содержит отдельного детального receipts trace каждого успешного callback. Reviewer пересчитал текущий jar SHA-256: `d807cd1f1e3d6f04abb47ab3be6692428a520a3a9d939bc9cef829ee55583ebc`, совпадает с прежним client validation. `build.gradle` исключает gametest пакет из core jar и проверяет это через verifyCoreJar: добавление этих тестов не меняет библиотечный артефакт.

## Отмененная настоящая смерть

Тест вызывает `pig.hurt(...,1000)`, а не вручную posts LivingDeathEvent. Identity-filtered LOWEST consumer требует exactly one receipt и health<=0, отменяет событие и восстанавливает max health. Lease закрывается синхронно после hurt, до delayed observations. Immediately и через три ticks проверяются живой тот же Pig, прежние origin/UUID, active token/domain и **тот же instance** собственного modifier; значит post-tick sweep не снимает gravity у восстановленной живой сущности.

Foreign permanent ADD_VALUE 0.02, base 0.08 и NoGravity=false сохраняются. Target effective 0.04 проверяется до release; после token.close отсутствует Universe modifier и effective value возвращается к текущему foreign состоянию 0.10.

Отдельное runtime доказательство unregister: у **того же** Pig сбрасывается invulnerableTime, вызывается второе настоящее lethal hurt; Pig действительно перестает быть alive, а receipts остается 1. Это сильнее проверки boolean lease.closed: consumer не продолжает отменять или получать второе событие. Это не тест произвольных остальных death listeners, смерти игрока или траектории.

## Отмененный настоящий dimension request после core relay

Тест требует реальный Nether ServerLevel и отсутствие исходного UUID в destination. `changeDimension(DimensionTransition)` вызывает настоящее travel event. Core NORMAL relay должен освободить token/modifier **до** LOWEST test consumer; callback непосредственно проверяет это при прежнем origin и ожидаемом destination key, затем отменяет request. Synchronous result null, source жив/не removed в прежнем level/UUID, destination entity отсутствует.

После трех ticks source/context сохранены, old token не реактивирован. Fresh opt-in создает successor, прежний token.close/update не влияет на него, foreign/base state сохраняются. Закрытие successor возвращает 0.10. Это подтверждает заявленную fail-closed политику **отмены после relay**, а не сохранение владения при любом порядке отмены.

Runtime доказательство unregister: повторный настоящий changeDimension после закрытия lease успешно создает destination entity с тем же UUID, source removed, receipts не увеличивается. Объект назначения сохраняется для finally cleanup. Следовательно, второй запрос не маскируется старым отменяющим consumer. Это одно vanilla mob перемещение, не Sable transfer/посадка/пассажирский переход.

## Source hygiene и ограничения

`ListenerLease` регистрирует явный event type с LOWEST/receiveCanceled=false, хранит тот же Consumer instance и unregister в idempotent close. Try-with-resources снимает consumer при assertion/hurt/transition failure. Callback фильтруется по entity instance, поэтому параллельные тестовые мобы не получают отмену. Delayed finally закрывает domain и удаляет fixture/созданный destination; outer catch покрывает синхронный отказ. Не обнаружено нового доказанного source-дефекта в этих путях.

Scope PASS: отмененная реальная mob death с восстановленной health сохраняет exact ownership; отмененный после core relay dimension request освобождает его и требует fresh opt-in; оба временных consumers реально сняты, что проверено вторыми действиями. **Не покрыта** отмена travel до NORMAL relay или многократные конфликтующие consumer priority sequences. NoAI fixture не подтверждает движение, client sync или игрока. Whole-Level unload, player support, Sable passengers/groups/recovery и нагрузка не закрываются этими тестами. Подготавливаемые dynamic protocol/projection candidates в review не включались. Статусы других документов не изменялись.
