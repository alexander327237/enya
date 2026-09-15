# Enya Torrent

Простой торрент-загрузчик для Android (minSdk 26, Kotlin + Jetpack Compose, движок libtorrent4j).

## Возможности

- добавление по magnet-ссылке, info-hash или URL на `.torrent`;
- открытие `.torrent` из файлового менеджера / браузера и magnet-ссылок из других приложений;
- список загрузок с прогрессом, скоростью, числом пиров и сидов;
- пауза, продолжение, удаление (только из списка или вместе с файлами);
- foreground-сервис с уведомлением, чтобы загрузки продолжались в фоне;
- список торрентов восстанавливается после перезапуска.

Файлы сохраняются в системную папку `Download/Torrents`. Для этого приложение просит доступ к хранилищу (на Android 11+ это «Доступ ко всем файлам» в настройках). Без разрешения файлы попадают в `Android/data/com.enya.torrent/files/Download`.

## Сборка

```bash
cd torrent
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-<abi>-debug.apk
```

Готовые debug APK лежат в `dist/` и пересобираются CI при каждом изменении в `torrent/`:

- `enya-torrent-arm64-debug.apk` — большинство современных телефонов (рекомендуется);
- `enya-torrent-armv7-debug.apk` — старые 32-битные устройства;
- `enya-torrent-debug.apk` — универсальный APK со всеми архитектурами.
