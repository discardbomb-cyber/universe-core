# Опубликованная экспериментальная alpha.5

Пользователь попросил новый проект на GitHub, затем подготовку тестовой сборки примерно за шесть минут. За этот этап подготовлена **экспериментальная библиотечная alpha**, окончательная готовность проекта не объявлена.

Локальный пакет: `release/universe-core-0.1.0-alpha.5/package-20261004T213150297Z-51ed37aa285a`.

- `universe-0.1.0-alpha.5.jar`, 191606 bytes, SHA256 d807cd1f1e3d6f04abb47ab3be6692428a520a3a9d939bc9cef829ee55583ebc.
- `universe-core-0.1.0-alpha.5-source.zip`, 286710 bytes, SHA256 1dc5b84b25565936679ac01a4e2b1ecbd707b528ad8b983ef23906d779a80965.
- RELEASE_NOTES.md, SHA256SUMS.txt, manifest.json, исходники и docs/PLAYER_TESTING.md.

Исходный ZIP содержит 173 файла, исключает .git/.tooling/.codex/.agents/build/logs/reports/test-results и внутренние координационные документы. Публичный README явно описывает ограничения; лицензия пока не выбрана. Повторно сверены JAR и ZIP, оригинальный JAR неизменен. RELEASE_ALPHA_5_REVIEW.md подтверждает экспериментальный срез: 106 JUnit, 125 ресурсных проверок, 19 core и 10 native GameTest.

Пользователь отдельно разрешил push через Git и загрузку release JAR. Создан новый публичный репозиторий [discardbomb-cyber/universe-core](https://github.com/discardbomb-cyber/universe-core); Git push ветки main выполнен и remote SHA сверена: `e221714ab382edf2641acf83d9082272f2251d17`. Для чистого первого коммита использован отдельный Git-каталог package/source; рабочие внутренние файлы не опубликованы. Публичный коммит дополнительно содержит .gitignore и RELEASE_NOTES.md, код соответствует исходному ZIP.

[Prerelease v0.1.0-alpha.5](https://github.com/discardbomb-cyber/universe-core/releases/tag/v0.1.0-alpha.5) опубликован 2026-10-04T21:44:31Z; Release ID 403222328, draft=false, prerelease=true. JAR, исходный ZIP и SHA256SUMS.txt загружены в Assets. GitHub API digest/size/state каждого файла сверены; все три файла затем скачаны без авторизации и повторно проверены по SHA256 и размеру. Remote tag указывает на тот же SHA первого коммита. Локальные подтверждения: .tooling/github-push.json и .tooling/github-release.json.

Использована существующая авторизация Git Credential Manager, профиль GitHub предварительно сверялся. Учетные данные были только в памяти процесса для официального GitHub API, не выводились и не сохранялись. Вход через браузер не понадобился. Глобальные настройки Git и автоматика не изменялись.

Automation universe-core имеет PAUSED. Пользователь явно выбрал «Оставить паузу; продолжить текущую работу». Не возобновлять ее без разрешения. Текущий активный этап допускает завершение упаковки/публикации; максимум библиотеки не достигнут.

TCP клиентский стенд собрал все 21 реальных PNG и штатно очистился, но offline validator отказал из-за FOV77 против ожидаемых70. Это остается FAILED/pending, не включено в заявленные успешные проверки alpha. После выпуска экспериментального среза остаются обязательные gameplay/persistence/performance задачи по PLAN.md; план окончательного релиза не сокращен.
