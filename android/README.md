# GalaxyBot для Android

Android-версия бота из `server.js`: подключается к `wss://cs.mobstudio.ru:6672/`,
проходит авторизацию по RECOVER-коду и показывает весь обмен с сервером в логе.

## Что умеет

- Поле для **RECOVER-кода** (запоминается между запусками).
- Кнопка **Подключиться / Отключиться** и индикатор состояния.
- **Лог** в стиле консоли: `<<` — от сервера, `>>` — серверу, плюс служебные строки
  (webhash, REGISTER, JOIN sent, разрывы и т.д.). Лог можно скопировать или очистить.
- Поле для отправки **произвольной команды** серверу.
- **Автопереподключение** через 5 секунд после разрыва (можно выключить).

## Схема обмена (как в server.js)

```
>> :ru IDENT 352 -2 4030 1 2 :GALA
<< HAAAPSI <seed>                    webhash = md5(seed) → reverse → join('0') → substr(5, 10)
>> RECOVER <код>
<< REGISTER <id> <pass> <nick>
>> USER <id> <pass> <nick> <webhash>
<< 999
>> FWLISTVER 311
>> ADDONS 251824 1
>> MYADDONS 251824 1
>> PHONE 1920 1080 0 2 :chrome 151.0.0.0
>> JOIN
   … через 2 с:  >> REMOVE 96
   … ещё через 3 с:  >> OBJ_ACT 5 15170420 1 go_to_bed
<< PING  →  >> PONG
```

## Готовый APK

Собранный debug-APK лежит в репозитории: [`android/apk/GalaxyBot-debug.apk`](apk/GalaxyBot-debug.apk)
(на странице файла в GitHub — кнопка *Download raw file*). Установка: скопировать файл
на телефон, открыть, разрешить установку из этого источника, нажать *Установить*.
Требуется Android 8.0+.

## Как собрать

**Вариант 1 — GitHub Actions.** При любом изменении в папке `android/` workflow
`Android APK` собирает `app-debug.apk` и прикладывает его как артефакт
`GalaxyBot-debug-apk` (вкладка *Actions* → нужный запуск → *Artifacts*).
Запустить сборку можно и вручную через *Run workflow*.

**Вариант 2 — Android Studio.** Открыть папку `android/` как проект
(*File → Open*), дождаться синхронизации Gradle и нажать *Run*.

**Вариант 3 — командная строка** (нужны JDK 17 и Android SDK):

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Структура

| Файл | Назначение |
| --- | --- |
| `GalaxyClient.kt` | Порт логики `server.js`: WebSocket (OkHttp), обработка HAAAPSI / REGISTER / PING / 999, переподключение |
| `WebHash.kt` | Порт функции `parse()` (webhash из HAAAPSI) |
| `UnsafeTls.kt` | Аналог `rejectUnauthorized: false` |
| `MainViewModel.kt` | Хранит клиент и лог, переживает поворот экрана |
| `MainActivity.kt`, `res/layout/*.xml` | Экран: код, кнопка, статус, лог, ручная команда |

Минимальная версия Android — 8.0 (API 26).
