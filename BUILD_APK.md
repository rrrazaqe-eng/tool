# Получение APK

В GitHub откройте **Actions → Build Tools Strong APK → Run workflow → main**. При push в `main` запуск происходит автоматически.

После зелёного результата скачайте artifact **Tools-Strong-APK**. Внутри:

- `Tools-Strong-1.0.0-debug.apk`
- `Tools-Strong-1.0.0-debug.apk.sha256`

APK содержит ARM64-библиотеки для Galaxy S24 Ultra и подписан debug-ключом среды сборки. Приватный release-keystore и GitHub Secrets не требуются.

Локально: JDK 17, SDK 36, Build Tools 36.0.0, затем `./gradlew :app:assembleDebug`. Результат — `app/build/outputs/apk/debug/app-debug.apk`.

Для стабильной подписи будущих выпусков ключ следует хранить вне публичного репозитория. В текущем проекте release-подпись не настроена.
