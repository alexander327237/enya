# Enya Torrent

Простой торрент-загрузчик для Android (minSdk 26, Kotlin + Jetpack Compose, движок libtorrent4j).

## Возможности

- добавление по magnet-ссылке, info-hash или URL на `.torrent`;
- открытие `.torrent` из файлового менеджера / браузера и magnet-ссылок из других приложений;
- список загрузок с прогрессом, скоростью, числом пиров и сидов;
- пауза, продолжение, удаление (только из списка или вместе с файлами);
- foreground-сервис с уведомлением, чтобы загрузки продолжались в фоне;
- список торрентов восстанавливается после перезапуска.

Файлы сохраняются в `Android/data/com.enya.torrent/files/Download` на внешнем хранилище.

## Сборка

```bash
cd torrent
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Готовый debug APK лежит в `dist/enya-torrent-debug.apk` и пересобирается CI при каждом изменении в `torrent/`.
