# Независимое ревью dynamic geometry и PNG oracle

Дата: 5 октября 2026. Проверены исходники `DynamicGeometryMapper`, `DynamicPixelOracle`, `DynamicProbeValidator` и сохраненные sidecar/PNG прогона `tcp-20261004T212513611`. Использованы только чтение файлов, просмотр PNG и отдельный численный анализ PowerShell/.NET; Java, Gradle и Minecraft ревьюер не запускал. Runtime source, допуски и старые результаты не изменены.

**Старый прогон остается FAILED.** Его immutable `numeric-pixel-validation.json` сообщает `Pinned actual FOV 70 required`: sidecar первого кадра содержит фактический вертикальный FOV≈76.9999926°. Независимый validator успел подтвердить process/source/argument provenance и actual fixture, но не завершил остальные numeric/pixel gates. Оба exit0 и наличие двенадцати held PNG не заменяют эти проверки. Предлагаемые/интегрированные ремонты требуют новой сессии.

## Конкретные findings и доказательства

1. **Старый BLUE predicate принимал настоящее небо.** В `stage-3-frame-151.png` пиксель `(300,200)` имеет RGB `(175,204,252)`; `(480,210)`, `(500,250)`, `(470,300)`, `(600,300)` — `(177,206,255)`. Они проходят старые `B>1.35R`, `B>1.20G`, `R>0`, `G/R<1.5`. Геометрическая маска сама по себе не исправляет такую неоднозначность: если ожидаемый синий блок не отрисовался и за ним видно подходящее небо, occupancy и centroid могут оказаться правдоподобными.
2. **Требования старой стены конфликтовали с видимостью маркеров.** В конце двухколоночная стена X=1..2/Y84..90/Z=-4 закрывает почти всю панель: просмотр последнего PNG показывает лишь узкую красную полоску. Независимый геометрический расчет дает на stage3 inset2-площади RED=73, остальные цвета=0. Изменение FOV не снимает физическую occlusion. Поэтому эта сцена не может выполнить требование двух eligible colors на каждой стадии.
3. **Обнаруженный ранее wrap-around базиса устранен.** В текущем oracle углы усредняются через сумму sin/cos, проверяется результирующая длина≥2.9 и длина каждого ожидаемого/измеренного базиса≥1px. Обычного среднего градусов возле ±180° больше нет.

Усиление BLUE до `B>1.80R && B>1.55G` отвергает указанные пиксели неба. Настоящий видимый синий бетон проверен по отдельному сохраненному static PNG `static-20261004T194503688/client/frames/static-0.png`: `(420,315)` RGB35/36/113, `(425,320)` и `(432,326)` RGB36/37/114, `(420,301)` RGB44/46/141, `(432,300)` RGB42/44/137. Все проходят усиленный predicate; B/R≈3.17..3.26 и B/G≈3.07..3.14 дают запас. Это диагностические цветовые образцы, не новая оценка старого dynamic прогона. Проверка полного нового изображения остается обязательной.

## Матрицы и независимый landmark

Порядок преобразований согласован с закрепленным render API:

```text
world = q.transform((storage - rotationPoint) * scale) + position
clip = actualProjection * actualModelViewRotation * (world - actualCamera, 1)
PNG.x = (clip.x/clip.w + 1) * 960/2
PNG.y = (1 - clip.y/clip.w) * 540/2
```

Матрицы импортируются как column-major; camera translation применяется один раз. Y совпадает с уже перевернутым `Screenshot` PNG. RotationPoint не прибавляется после вращения. Статическая геометрия не получает pose движущегося тела.

Для неизменного старого `stage-0-frame-40.png.json` отдельно спроецированы три видимые внешние грани обычного cyan cube `(-4,83,-6)` через записанные M/P и camera. ROI получен из геометрического polygon union, затем erode2; пиксели изображения для определения ROI не использовались. Получены 1259 геометрических пикселей и expected centroid `(582.192613,481.119539)`. В настоящем PNG все 1259 пикселей проходят cyan classifier; measured centroid совпадает, error=0px. Это узкая положительная проверка порядка M/P, camera translation и PNG-origin для данного landmark/кадра при реальном FOV77. Она не отменяет провал FOV70 и не подтверждает остальные gates.

По исходникам front-face winding, screen-space barycentric NDC depth и top-left raster согласованы. Все десять opaque moving cubes участвуют в depth, включая WHITE/SEA_LANTERN. Общие грани удаляются по отдельным moving/static occupancy maps; скрытая белым блоком, фонарем или другим цветом грань не становится hidden-by-wall evidence. Маски и центроиды вычисляются из геометрических pixel centers, `Rectangle` служит лишь границей обхода. Near/far/behind-camera, nonfinite, несовместимый model-view и неоднозначная глубина вызывают отказ.

## Минимальный ремонт геометрии

Одна настоящая колонка X=2/Y84..90/Z=-4 сохраняет общую RED/LIME пару. Это семь wall cubes и 129 stationary cubes в целом: 121 floor + 7 wall + 1 cyan. Ниже — **дизайн-расчет**, использующий неизменные старые poses/M/P77°, полный opaque fixture, front/neighbor culling, два depth buffer и inset2/4. Это не кадры новой сцены и не client PASS.

| Стадия | Visible interior RED / BLUE / LIME / YELLOW, px | Derived hidden-marker-over-wall interior, px |
| --- | --- | --- |
| 0 | 580 / 579 / 108 / 78 | 0 |
| 1 | 569 / 323 / 75 / 95 | 106 |
| 2 | 497 / 11 / 50 / 0 | 519 |
| 3 | 390 / 0 / 30 / 0 | 661 |

RED/LIME остаются eligible на всех четырех стадиях; расчетное endpoint displacement их центроидов≈54.5/65.7px, изменение базиса≈25.5°. Derived overlap превышает80px в трех стадиях. Отдельный root artifact `.tooling/proposals/fixed-fixture/design-only.json` с предполагаемым FOV70 также явно помечен `DESIGN_ONLY_NOT_CLIENT_EVIDENCE`: в endpoint RED=535/LIME=47px, overlap912px. Реальные новые poses, M/P, PNG и classifier occupancy должны проверяться заново.

При заключительном чтении root уже согласовал текущие source: mapper имеет stationary129/wallX2, oracle — BLUE1.80R/1.55G и circular basis, validator — wallMinX2, actual FOV70 и восстановление camera option. Старый immutable run не пересчитывался. Исторический proposal mapper с двухколоночной стеной не является текущим fixture contract.

## Gates новой приемки

- Свежие session/run/runtime nonces, actual distinct owned PID и exit0; неизменные source/argument/core hashes, безопасные пути, точные ACK epochs, PNG hashes, global unique file/frame IDs и порядок времен. Source/current fixture должны быть заморожены до запуска.
- Actual base FOV70, owned fovEffectScale0 и реальная projection70±.1; восстановленные option memory/options.txt. M/P и render pose снимаются в одном AFTER_LEVEL кадре, scale unit и quaternion уже unit.
- Все12 held PNG: pose error≤.10m/.03rad; каждый содержит минимум два projected eligible colors≥12px, actual occupancy≥70%, минимум12 classified pixels, centroid error≤12px. Cyan≥12px/70%, error≤4px.
- Не меньше трех genuine live PNG: changing actual client pose и совместный native sample≤250ms/≤.25m/.08rad. Автоматический pixel oracle применяется к held PNG; визуальный просмотр настоящих live PNG обязателен отдельно.
- Все42 native dt=.025s/1.05s, независимый trapezoid discrepancy≤.04m, dx≥1.4m, quaternion rotation≥.25rad, finalY≥83. Наличие server-side флага `numericMotionPassed` не заменяет независимый ledger.
- Два одинаковых цвета смещаются≥20px между всеми endpoint парами; одна общая пара имеет projected и measured basis rotation≥8° и agreement≤8° в≥3 стадиях с endpoints.
- В≥2 стадиях derived hidden interior≥80px; именно этот overlap имеет magenta≥90% и≤2 marker leaks. Visible wall interior отдельно≥100px/90%/≤2 leaks. Проверка произвольного magenta rectangle недостаточна.
- Completion receipt после client report; cleanup освобождает UUID/body/ticket/listener/pause/config/ordinary blocks/chunks без ошибки. Offline статус остается pending manual review до просмотра baseline/middle/final/live PNG и независимой проверки границ вывода.

При этом ревью не найден дополнительный конкретный math/culling/mask false-PASS в текущем исправленном source. Это не runtime APPROVE новой сессии и не подтверждение production transfer, пассажиров, UDP repair или производительности больших объектов.
