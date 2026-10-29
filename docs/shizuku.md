# Настройка Shizuku

Shizuku даёт приложению доступ к shell-командам (`pm`, `am`, `settings`) без root. Нужен только для раздела **«Ускорение и кэш»**.

## 1. Установка

Установите [Shizuku](https://shizuku.rikka.app/) из Google Play или с GitHub. Приложение проверено на Shizuku 13.6.0.

## 2. Запуск сервера

Сервер Shizuku запускается один раз после каждой перезагрузки телефона. Два способа:

### Беспроводная отладка (без компьютера)

1. Включите «Для разработчиков» → «Беспроводная отладка».
2. Откройте Shizuku → «Запустить» → «Сопряжение» и выполните шаги на экране.
3. Оставьте Shizuku запущенным.

### Через ADB с компьютера

```powershell
$adb = ".\platform-tools\adb.exe"

# узнать путь установленного Shizuku
$base = ((& $adb shell "dumpsys package moe.shizuku.privileged.api | grep codePath | head -1") -join "") -replace '.*codePath=',''
$base = $base.Trim()

# запустить сервер
& $adb shell ('kill $(pidof shizuku_server); sleep 1; LD_LIBRARY_PATH=' + $base + '/lib/arm64 ' + $base + '/lib/arm64/libshizuku.so')
```

## 3. Выдача доступа ClearPhone

1. Откройте ClearPhone.
2. Перейдите в «Ускорение и кэш».
3. Нажмите «Запросить доступ Shizuku» и разрешите в диалоге.

Если доступ уже выдавался, после обновления приложения с тем же ключом подписи он сохраняется.

## Особенности MIUI / HyperOS

- Включите **«Отладка по USB (безопасность)»** в «Для разработчиков». Без неё Shizuku не может выдать разрешение, а `settings put` и `input tap` не работают.
- `adb shell pm grant ...` может завершаться `SecurityException: GRANT_RUNTIME_PERMISSIONS` — это ограничение MIUI. Выдавайте разрешения через Shizuku или `appops`.
- Сервер Shizuku не переживает перезагрузку телефона — запустите его снова.

## Если Shizuku не видит приложение

В логах должно появиться `ShizukuProvider: binder received`:

```powershell
$adb = ".\platform-tools\adb.exe"
& $adb shell "logcat -d | grep -i -E 'ShizukuProvider|binder received' | tail -20"
```

Также проверьте, что в `AndroidManifest.xml` есть:

- `uses-permission moe.shizuku.manager.permission.API_V23`
- `meta-data moe.shizuku.client.V3_SUPPORT=true`
- `ShizukuProvider` с `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`

## Версия Java-стабов

Для сборки используются стабы **13.1.0**. В версиях 13.1.1+ удалён `Shizuku.newProcess()`, который вызывает `MainActivity.runShell()`. Не обновляйте стабы без переписывания этого метода.
