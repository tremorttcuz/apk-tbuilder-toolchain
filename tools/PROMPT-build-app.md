Ты работаешь на моём компьютере (Linux). Задача — собрать Android-приложение «APK TBuilder» из исходников в архиве `apk-tbuilder.zip`, получить готовый `.apk` и (если подключён телефон по ADB) установить его. Действуй сам, по шагам, и в конце коротко отчитайся.

## Что за проект
Kotlin, обычные View без AndroidX, minSdk 24, targetSdk 28 (так сделано намеренно: иначе Android запрещает запуск скачанного aapt2 — НЕ повышай targetSdk), namespace `com.builder`, applicationId `com.builder.apkbuilder`. Gradle 8.7 через wrapper, JDK 17. Подпись — постоянный `app/debug.keystore` (пароль `android`, alias `androiddebugkey`) — не меняй и не удаляй, иначе новые сборки не встанут поверх старых.

## Шаг 0. Подготовка
1. Распакуй архив в чистую папку, перейди в `apk-builder/`.
2. Проверь, что есть `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, `app/build.gradle.kts`, `app/debug.keystore`, `.github/workflows/build.yml`.
3. `java -version` должен показать 17 (подойдёт 17–21). Если нет — установи JDK 17 (например, `sudo apt install openjdk-17-jdk` или SDKMAN) и спроси меня, если нужен sudo.

## Шаг 1. Локальная сборка (предпочтительно)
1. Нужен Android SDK: платформа `android-34` (или та, что указана в `compileSdk` в `app/build.gradle.kts`), `build-tools` соответствующей версии, `platform-tools`.
   - Если переменной `ANDROID_HOME`/`ANDROID_SDK_ROOT` нет — поставь command-line tools с https://developer.android.com/studio#command-line-tools-only в `~/Android/Sdk`, затем `sdkmanager --licenses` (принять) и `sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"`.
   - Создай `local.properties` с `sdk.dir=/абсолютный/путь/к/Sdk` (файл в `.gitignore`, не коммить).
2. Запусти: `chmod +x gradlew && ./gradlew assembleDebug --stacktrace`.
3. Результат: `app/build/outputs/apk/debug/app-debug.apk`. Скопируй его в папку рядом как `apk-tbuilder.apk` и покажи путь и размер (`ls -la`).
4. Если сборка упала — прочитай ошибку, исправь причину в исходниках минимально (не переписывай логику, не трогай targetSdk и ключ), повтори. Если ошибка в окружении (SDK, сеть, лицензии) — исправь окружение. Если не получается за 3 попытки — остановись и покажи мне точный текст ошибки.

## Шаг 2. Запасной путь — GitHub Actions (если локально не выходит или я попрошу)
1. Нужен репозиторий на GitHub. Если `gh` установлен и авторизован (`gh auth status`) — `gh repo create apk-tbuilder --private --source=. --push`. Иначе скажи мне, что нужна ссылка на пустой репозиторий и токен/вход, и жди ответа. Никогда не печатай и не сохраняй мои токены в файлах.
2. Убедись, что в репозиторий попали скрытая папка `.github` и `gradle/wrapper/gradle-wrapper.jar` (`git ls-files | grep -E "workflows|wrapper"`).
3. Workflow «Build APK» стартует при пуше; ждать так: `gh run watch` (или `gh run list`). Время ~3–6 минут.
4. Скачай результат: `gh run download --name apk-builder` — внутри `apk-tbuilder-<номер>-<хеш>.apk`.
5. Релиз по тегу (если я попрошу): `git tag v1.0 && git push origin v1.0` — APK появится в Releases.

## Шаг 3. Проверка APK
1. Размер больше нуля, `unzip -l <apk>` содержит `classes.dex`, `AndroidManifest.xml`, `META-INF/`.
2. Если есть `apksigner` (в `build-tools`): `apksigner verify --verbose <apk>` — должна быть подпись v1/v2.
3. Если есть `aapt2`/`aapt`: `aapt2 dump badging <apk> | head` — должно быть `package: name='com.builder.apkbuilder'`, `application-label:'APK TBuilder'`, иконка `mipmap`.

## Шаг 4. Установка на телефон (только если подключён по ADB)
1. `adb devices` — телефон должен быть в списке как `device` (не `unauthorized`; если так — скажи мне подтвердить отладку на экране).
2. `adb install -r <apk>`. Если ошибка `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — спроси меня, прежде чем удалять старую версию (`adb uninstall com.builder.apkbuilder` сотрёт её данные).
3. Запусти: `adb shell am start -n com.builder.apkbuilder/com.builder.MainActivity`.
4. Если приложение вылетает — сними лог: `adb logcat -d -s AndroidRuntime:E | tail -60` и покажи мне стек.

## Правила
- Ничего не удаляй вне рабочей папки, не меняй системные настройки и не отправляй мои файлы никуда, кроме репозитория, который я назвал.
- Не повышай targetSdk, не заменяй `debug.keystore`, не добавляй сторонние зависимости без причины.
- Приложение на реальном телефоне раньше не запускалось — если на устройстве что-то не так (вылет, пустой экран, неровная вёрстка), не угадывай: приложи лог или скриншот (`adb exec-out screencap -p > shot.png`).

## Итоговый отчёт (коротко)
- где лежит `.apk` и его размер;
- какой способ сборки сработал (локально или Actions);
- результат проверки подписи и `package`;
- установилось ли на телефон и запускается ли;
- что пришлось исправить в исходниках (если пришлось), списком файлов.
