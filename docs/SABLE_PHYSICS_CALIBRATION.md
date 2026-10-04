# IDEA-004: native Rapier calibration and gravity compensation

Дата: 5 октября 2026 года. **Статус: milestone A подтвержден двумя native runs; B подтвержден финальным native run после усилений привязки результатов к сцене и game-time boundaries: All 10 required tests passed.** Gradle/Java в этом чате не запускались; общий runner запускал root.

Milestone A использует `SablePhysicsCalibrationTests.java` и `NativePhysicsFixture.java`; B добавляет `SableGravityCompensationTests.java`. В текущем этапе изменены только новый B source и этот отчет. Config/build/native runner, ledger и release source set остаются во владении root.

## Проверенная связка API

Sable `sable-neoforge-1.21.1:2.0.5`, tag `mc1.21.1-2.0.5-neoforge`, commit `6966d2928340de7631abcecf8549904b877df0a8`. Локальный JAR проверен через `javap`; event bus 8.0.5 имеет `register(Object)` и `unregister(Object)`, JOML 1.10.5 — используемые `Vector3d.fma(double, Vector3dc)` и quaternion операции. Не вводится reflection fallback к другим версиям.

- `RigidBodyHandle.of(ServerSubLevel)`, `isValid()`, `getLinearVelocity(Vector3d)`, `getAngularVelocity(Vector3d)`, `applyLinearImpulse(Vector3dc)`, `addLinearAndAngularVelocity(Vector3dc, Vector3dc)`.
- `ServerSubLevel.getMassTracker().getMass()` и `getCenterOfMass()`; масса должна быть конечной и строго положительной.
- `ForgeSablePrePhysicsTickEvent` / `ForgeSablePostPhysicsTickEvent`: `getPhysicsSystem()`, `getTimeStep()`; слушатели фильтруют только систему своих fixtures.
- `SubLevelPhysicsSystem.getConfig().substepsPerTick`; система выполняет штатные подшаги и обновляет позу перед post event.
- `DimensionPhysicsData.getGravity(Level)`, `getUniversalDrag(ServerLevel)` используются для протоколирования текущего dimension profile, а не для изменения сцены.

[Java Rapier pipeline](https://github.com/ryanhcode/sable/blob/6966d2928340de7631abcecf8549904b877df0a8/sable_rapier/src/main/java/dev/ryanhcode/sable/physics/impl/rapier/RapierPhysicsPipeline.java) делегирует операции JNI. В [закрепленном Rust backend](https://github.com/ryanhcode/sable/blob/6966d2928340de7631abcecf8549904b877df0a8/sable_rapier/src/main/rust/rapier/src/lib.rs):

| Операция | Реальная реализация, задающая ожидания тестов |
| --- | --- |
| `applyLinearImpulse` → `applyForceAndTorque` | Вектор поворачивается quaternion тела, затем вызывается `rb.apply_impulse`; **локальный** J, без умножения на dt |
| `addLinearAndAngularVelocity` | Прямое добавление к `rb.linvel/angvel` через `set_linvel/set_angvel`; **мировые** векторы, без поворота тела |
| Native velocity getters | Возвращают `rb.linvel/angvel`; мировой базис |
| Universal drag | Назначается как linear/angular damping тела |
| Поза | Native `getPose`, затем штатный `SubLevelPhysicsSystem.updatePose` и post event |

Комментарии Java о силах/torque не подменяют семантику native impulse. Масса — единицы MassData (документация обозначает их kpg); проверяется именно отношение численного J к native массе. Линейные единицы предполагаются блок/метр на **simulation second**, angular — rad/s; это гипотезы, проверяемые конечными разностями фактической позы. `latestLinearVelocity` не используется вместо native getter.

## Expected required tests для root runner

Три метода `SablePhysicsCalibrationTests`, каждый `required=true`, `template="empty"`, `timeoutTicks=240`, собственный batch с одним тестом:

1. `impulseIdentityTwoMasses` — batch `sable_calibration_impulse_identity`.
2. `impulseYaw90TwoMasses` — batch `sable_calibration_impulse_yaw90`.
3. `additiveWorldVelocitiesBothOrientations` — batch `sable_calibration_velocity_world`.

Runner должен обнаружить и выполнить все три метода. Имена GameTest при регистрации могут приводиться к lowercase/prefix стандартным механизмом NeoForge; сверять фактический реестр, а не только число скомпилированных классов. Нужен существующий namespace `universe_sable_test` и native Rapier. Static/no-op pipeline — fail, а не skip/PASS.

Импульсные тесты создают два асимметричных корпуса: L из трех stone blocks и его двойной слой из шести. У каждого отдельное контрольное тело той же формы/массы, ориентации и высоты. Массы считываются в pre hook; второй корпус должен быть тяжелее первого минимум в 1,5 раза. Общий импульс обоим задается заранее фиксированным правилом **J = 2 × измеренная масса легкого тела**. Следовательно ожидаемая скорость легкого равна 2, тяжелого — J/m; это обеспечивает ненулевую измеримую реакцию при разной конфигурации плотности блоков. Одно и то же J обоим исключает подмену проверки массы одинаковыми заданными Δv. При identity local +X дает world +X; при +90° вокруг Y — world −Z.

Тест добавления скорости использует две пары L-корпусов, identity и +90° Y. В обеих ориентациях задаются одинаковые **мировые** приращения `V=(2, 0.4, −0.7)`, `ω=(0.15, 0.25, −0.1)`. Проверяются непосредственные изменения обоих getters и сохранение эффекта после штатных шагов. Quaternion delta `qAfter * inverse(qBefore)` сверяет мировой angular getter с измеренным вращением; quaternion знак нормализуется, чтобы избежать ложного поворота на 2π.

## Контроль условий и критерии

Fixtures имеют уникальные native UUID, разнесенные позиции и собственные force-load tickets. Перед созданием проверяется свободный объем 11³ блоков на высоте 240. Выбранные площадки разнесены также между тремя batch. Fixture разрешает лишь точный класс `dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline`. Native handles ожидаются не более 100 тиков; исчезновение объекта — fail. Teleport используется только для начального размещения fixture, до измерения; его результат не засчитывается как движение.

База гравитации не меняется. Контрольная пара переживает тот же интервал: ее Δv и Δposition вычитаются из реакции driven тела. Непосредственная проверка J/m перед следующим шагом отдельно исключает влияние damping. Измерение после шагов длится **0,1 simulation seconds**, сумма берется из фактических post dt, а не wall clock. Проверяется соответствие `dt=0.05/substepsPerTick`, парность pre/post hooks и связь с коротким game-time интервалом. Симуляция не вызывается дополнительно и live pipeline не переинициализируется.

Заранее зафиксированные допуски: векторная ошибка не больше **0,01 + 5% × |expected|**, scalar mass — аналогично. Тот же предел используется для скорости, angular velocity и displacement/time. Поза сверяется с интегралом измеренных скоростей; отдельно остается ненулевое ожидаемое направление, чтобы два нулевых результата не могли подтвердить друг друга. Разница displacement должна быть >0,001 блока, Δv >0,02, angular displacement >0,005 rad в additive тесте. Короткий интервал ограничивает damping/гироскопические эффекты; если реальная среда превышает допуск, это FAIL для анализа, а не автоматическое расширение допуска или смена ожидания после результата.

Выводы `SABLE_CALIBRATION start/measured` содержат UUID, массу, ориентацию, начальную скорость, ожидаемую Δv, общий J, профиль gravity/drag, substeps/dt, число steps, sumDt, measured Δv/Δposition и angular integral/finite difference. При ошибке есть отдельный error log. Эти строки должны сопровождать успешные GameTest results в отчете root; один только лог start не подтверждает приемку.

После завершения/исключения/внутреннего watchdog 210 тиков наблюдение немедленно отключается флагом. Снятие instance listener, удаление собственных tickets/UUID-owned bodies и вызовы `helper.fail/succeed` выполняются через `runAfterDelay(1, ...)` в фазе GameTest, после выхода из physics callback. Ошибка GameTest намеренно не перехватывается этим callback: framework фиксирует ее как тестовый FAIL. Чужие объекты, config и общий мир не очищаются. При остановке процесса callback cleanup не гарантируется; тестовый мир остается disposable. Constraints, пассажиры, transfer и произвольные игровые силы сюда не входят.

## Milestone B: подготовленные required tests

[SableServerConfig 2.0.5](https://github.com/ryanhcode/sable/blob/6966d2928340de7631abcecf8549904b877df0a8/common/src/main/java/dev/ryanhcode/sable/SableServerConfig.java): `SUB_LEVEL_SUBSTEPS_PER_TICK`, TOML key **`sub_level_substeps_per_tick`**, default 2, диапазон 1–10. Это server config Sable; расположение фактического файла выбранного runner определяет root. Комментарий config о шагах в секунду неточен: [SubLevelPhysicsSystem](https://github.com/ryanhcode/sable/blob/6966d2928340de7631abcecf8549904b877df0a8/common/src/main/java/dev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem.java) делает N подшагов **за игровой тик**, каждый `1/20/N` секунды. `PhysicsConfigData.updateFromConfig()` читает key при initialize.

Локальный JAR и pinned Java source подтверждают: `getConfig()` возвращает тот же mutable `PhysicsConfigData`, поле `substepsPerTick` public/non-final, а штатный `tickPipelinePhysics` читает его непосредственно и для границы цикла, и для `dt=0.05/N`. Поэтому B временно устанавливает это поле вне physics callback, без `initialize`, `onConfigUpdated` или дополнительного `physicsTick`. После успеха/ошибки/watchdog исходное N восстанавливается в отложенной GameTest-фазе. Это только isolated test scene, не production adapter и не изменение TOML. Solver iterations / max CCD substeps не подменяют N.

Два метода `SableGravityCompensationTests`, `required=true`, template `empty`, timeout 260, каждый в отдельном batch:

1. `compensateGravityOneSubstep` — `sable_gravity_compensation_one_step`, N=1.
2. `compensateGravityFourSubsteps` — `sable_gravity_compensation_four_steps`, N=4.

Оба должны выполняться в одном JVM последовательно и на одной physics system: второй завершившийся тест проверяет сохраненные результаты первого и пишет `SABLE_GRAVITY cross_substeps verified cases=8`. Pending measurements хранятся под weak key системы без ссылки на сцену в значениях; успешное сравнение удаляет всю пару, повтор того же N заменяет свой pending результат. Результаты другой сцены не участвуют. Отдельный запуск одного метода проверяет его абсолютные ожидания, но не подтверждает сравнение N=1/N=4. Suite блокирует собственные одновременные изменения config; общий runner обязан сохранять отдельные batch для остальных suites.

Каждый N проверяет две массы, identity и +90° вокруг Z (этот поворот меняет направление gravity в локальном базисе), сначала `aTarget=(0,0,0)`, затем world `(3,-4,2)`. У каждой driven fixture есть unmodified control. За каждый pre hook ровно один импульс `Jworld=m*(aTarget-gBase)*dt`, преобразованный обратным текущим quaternion в local J. Измерение длится 0,2 simulation seconds: четыре штатных game ticks, соответственно 4/16 парных подшагов. Проверяются непосредственный world Δv, число импульсов, суммарный J/m, target endpoint, ненулевая реакция control на gBase, отсутствие double counting и angular velocity, actual pose против native getter integral.

Импульс в начале подшага создает известное смещение траектории `bias=0.5*(aTarget-gBase)*Σdt²`: при нулевой target acceleration endpoint velocity близка к нулю, но внутри каждого подшага тело движется. Поэтому поза сравнивается с `0.5*aTarget*T²+bias`, а N=1/N=4 — после вычитания этого заранее определенного bias. Без поправки требование одинаковой позы противоречило бы выбранной импульсной дискретизации.

До первого B run зафиксированы ABS=0,01 и REL=5%, T=0,2, solverIterations=18, допустимый profile `1<|gBase|<32`, `0≤drag≤0,1`. Дополнительно используются явные физические верхние границы, а не подгонка tolerance: endpoint damping error driven ≤ `drag*T*(|aTarget|*T+|aTarget-gBase|*dt)`, control ≤ `drag*|gBase|*T²`; position damping bound ≤ endpoint bound × T. Внутренняя интеграция Rapier дает position bound `(|gBase|+|aTarget-gBase|)*Σdt²/(2*18)`. Сравнение actual pose с trapezoidal getter integral учитывает только `|gBase|*Σdt²/(2*18)`. Позиционные сравнения нормируются на T. Любой иной solver/profile — FAIL для анализа. Координаты |X/Z|≤192, Y=160; setup teleport только до измерения каждой фазы. Cleanup удаляет только owned fixtures/listener и восстанавливает исходный config; GameTest fail/succeed не вызываются внутри physics callback.

Каждый pre/post проверяет `gameTime-startGameTime=floor((ordinal-1)/N)`; последнее измерение требует разницу ровно 3, то есть четыре последовательных обычных игровых тика включительно. Повторные ручные stepping calls в одном game tick не могут набрать требуемые 4N подшагов и пройти эту проверку.

Логи `SABLE_GRAVITY start/impulse/step/measured/restored` содержат N, phase, массу/UUID, gravity/target/drag, dt/count/sumDt, world/local J, ориентацию, getters, pose, bias и аналитические bounds.

### Native результат B до последних усилений

`.tooling/alpha4-native-transfer-compensation.log`: **All 10 required tests passed**, включая оба B; свежая строка `cross_substeps verified cases=8 duration=0.2 N=1/N=4` и два восстановления `originalSubsteps=2 actual=2`. Для нулевого target конечная Y velocity около `2.4–4.9×10⁻⁷`, но measured motion вверх составляет **0,051940917969 блока при N=1** и **0,012939453125 при N=4** за 0,2 s. Предварительно заданный pre-impulse bias соответственно 0,055 и 0,01375 блока; actual pose отличается от trapezoidal getter integral в пределах фиксированной внутренней интеграционной границы. Для world target `(3,-4,2)` identity endpoint равен `(0,593310; -0,791080; 0,395540)` при N=1 и `(0,594301; -0,792401; 0,396201)` при N=4.

Это успешная калибровка Δv и дискретного интегратора. Raw trajectories N=1/N=4 различаются, и нулевая target acceleration здесь не означает неподвижную позу: готовый production zero-g controller этим результатом не подтверждается.

### Финальная проверка после hardening

Root повторил полный native набор в свежей JVM: `.tooling/alpha4-native-final.log`, **All 10 required tests passed**, process exit 0. Оба B прошли с включенными pre/post gameTime gates и финальным требованием четырех обычных тиков. Лог содержит два `restored originalSubsteps=2 actual=2` и свежий `cross_substeps verified cases=8 duration=0.2 N=1/N=4`. Таким образом, усиления weak scene ownership/consumption и game-time boundaries также подтверждены игровым запуском.

Независимое ревью: **APPROVE для свежей JVM с последовательной парой N=1/N=4**. Pending comparison привязан к physics system, но отдельного run epoch нет: интерактивный повтор в той же сцене после незавершенной пары может сравнить новый N с оставшимся результатом противоположного N из предыдущей попытки. Такой сценарий не покрыт утверждением о свежей паре; финальный runner использовал свежую JVM.

## Игровые результаты

Первый actual run: `.tooling/alpha4-native-calibration-1.log`. Rapier, две массы **6/12**, общий J **12**, default N=2, dt=0,025. Identity immediate J/m прошел; затем light body провалил finite-difference проверку: getter integral/time около **1,989**, measured displacement/time **2,109375**. Это FAIL, не подтверждение всех трех тестов. Прямой `helper.fail` из post event бросил `GameTestAssertException` в Sable и остановил сервер; такой путь исправлен отложенным завершением выше. Исправления root для независимой очистки ticket/body с сохранением owned до подтвержденного удаления, проверки reset-to-zero и initial angular zero сохранены.

### Численное объяснение и исправление стенда

В pinned [Marten Real](https://github.com/ryanhcode/sable/blob/6966d2928340de7631abcecf8549904b877df0a8/sable_rapier/src/main/rust/marten/src/lib.rs) задан `f32`. Native `teleportObject` приводит координаты к Real, `getPose` возвращает `rb.translation` после преобразования к Java double; утраченная точность от этого не возвращается. Native getter скорости не вычисляется из вычитания больших координат.

[Cargo](https://github.com/ryanhcode/sable/blob/6966d2928340de7631abcecf8549904b877df0a8/sable_rapier/src/main/rust/rapier/Cargo.toml) закрепляет Rapier commit `38e92f117590862481a53df6fc69a5d893e29186`. Его [island solver](https://github.com/ryanhcode/rapier/blob/38e92f117590862481a53df6fc69a5d893e29186/src/dynamics/solver/island_solver.rs) делит dt на число solver iterations; [velocity solver](https://github.com/ryanhcode/rapier/blob/38e92f117590862481a53df6fc69a5d893e29186/src/dynamics/solver/velocity_solver.rs) повторно интегрирует translation. Sable default solverIterations=18. Это внутренние интеграции, отличные от N=2 наружных Sable подшагов.

При world X=8192 float32 ULP равен **0,0009765625**. Инкремент light body за внутреннюю интеграцию примерно `1.99 × 0.025 / 18 ≈ 0.002764` округляется до **трех ULP = 0,0029296875**. Четыре наружных подшага по 18 внутренних дают `4 × 18 × 0.0029296875 = 0.2109375` блока, то есть **2,109375 блока/с** — совпадает с провалом. Отдельное воспроизведение float32 арифметики с damping воспроизводит это число; это объяснение численного механизма, не дополнительный игровой PASS. Диагностика нового run также печатает фактический solverIterations.

Площадки перенесены с X/Z≈8192–10752 ближе к нулю: `X=32+128×variant+32×pair`, Y=240, Z=−64 для driven и −32 для control. Максимальный |X/Z|=320; расстояния между парами/тестами и air-check сохранены. Численные приращения теряются существенно меньше. Ожидаемые скорости, правило выбора J, interval и допуски **не изменялись**. Первое размещение публикуется через два штатных тика до начала измерений; дальнейших teleport нет.

Каждый pre/post теперь печатает позиции start/current driven/control, прямую native pose рядом с logical pose, дельту каждого шага, v/ω обоих тел и обе ориентации. Компоненты выводятся с 12 знаками и Locale.ROOT вместо округленного JOML toString. Эти строки позволяют отличить float rounding от несовпадения фаз; проверка по-прежнему сравнивает весь measured displacement с getter integral.

### Подтвержденные повторные runs A

В `.tooling/alpha4-native-calibration-2.log` все шесть required tests общего набора прошли, включая три A и три существующих transfer tests. В `.tooling/test-runs/20261004T183858438Z-24432/sable-gametest.log` те же три A и три transfer tests вновь прошли; дополнительный `nonzerovelocityroundtrip` провалился на Diamond inventory. Поэтому второй общий run целиком не объявляется успешным. Оба лога подтверждают A после переноса площадок, без изменения исходных ожиданий/допусков.

Оба запуска воспроизвели профиль gravity `(0,-11,0)`, drag `0.09000000357627869`, массы 6/12, J=12, N=2, dt=0,025, четыре подшага/Σdt=0,1:

| Проверка | Измеренный результат |
| --- | --- |
| Identity, light/heavy Δv | world +X: 1,982 / 0,9911 |
| Identity, light/heavy Δposition | +X: 0,1993 / 0,09970; getter integral 0,1989 / 0,09944 |
| Yaw +90° | Те же величины по world −Z |
| Additive world V | `(1,982; 0,3964; -0,6937)` |
| Additive Δposition / getter integral | `(0,1994; 0,03986; -0,06976)` / `(0,1989; 0,03978; -0,06961)` |
| Angular identity integral / quaternion finite difference | `(0,01498; 0,02480; -0,01003)` / `(0,01501; 0,02485; -0,01005)` rad |
| Angular yaw integral / quaternion finite difference | `(0,01486; 0,02492; -0,009851)` / `(0,01489; 0,02498; -0,009873)` rad |

Это runtime подтверждение local impulse, world additive/getter frames, mass scaling и simulation-second единиц для проверенного профиля. Оно не подтверждает B, произвольные координаты/силы или production readiness.
