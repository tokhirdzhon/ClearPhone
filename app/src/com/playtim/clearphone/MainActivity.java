package com.playtim.clearphone;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 10;
    private static final int REQ_SHIZUKU = 11;
    private static final long MB = 1024L * 1024L;

    private int screen = 0;
    private TextView output;
    private Shizuku.OnRequestPermissionResultListener permissionListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionListener = (requestCode, grantResult) -> {
            if (requestCode == REQ_SHIZUKU) {
                toast(grantResult == PackageManager.PERMISSION_GRANTED
                        ? "Доступ Shizuku выдан" : "Доступ Shizuku не выдан");
                if (screen == 5) showSpeed();
            }
        };
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener);
        } catch (Throwable ignored) {
        }
        showHome();
    }

    @Override
    protected void onDestroy() {
        try {
            if (permissionListener != null) {
                Shizuku.removeRequestPermissionResultListener(permissionListener);
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screen == 0) showHome();
    }

    @Override
    public void onBackPressed() {
        if (screen != 0) showHome();
        else super.onBackPressed();
    }

    private void showHome() {
        screen = 0;
        LinearLayout c = root();
        addTitle(c, "ClearPhone");
        addText(c, "Личная уборка телефона", 13, false);

        long[] st = storageInfo();
        LinearLayout card = card(c);
        addText(card, "Хранилище", 16, true);
        addText(card, "Занято " + fmt(st[1]) + " из " + fmt(st[0]), 14, false);
        addText(card, "Свободно " + fmt(st[2]), 14, false);
        addText(card, "ОЗУ: " + ramInfo(), 13, false);

        if (!hasAllFiles()) {
            LinearLayout warn = card(c);
            addText(warn, "Нет доступа ко всем файлам", 14, true);
            addText(warn, "Без этого приложение не сможет искать большие файлы и дубликаты.", 12, false);
            addButton(warn, "Дать доступ ко всем файлам", v -> requestAllFiles());
        }

        addButton(c, "Большие файлы", v -> showLargeFiles());
        addButton(c, "Дубликаты", v -> showDuplicates());
        addButton(c, "Размер папок", v -> showFolders());
        addButton(c, "Приложения", v -> showApps());
        addButton(c, "Ускорение и кэш", v -> showSpeed());
        addButton(c, "Обновить", v -> showHome());
        show(c, false);
    }

    private void showLargeFiles() {
        screen = 1;
        LinearLayout c = root();
        addTitle(c, "Большие файлы");
        TextView status = addText(c, "Сканирование...", 13, false);
        show(c, true);

        new Thread(() -> {
            List<File> files = new ArrayList<>();
            collectFiles(Environment.getExternalStorageDirectory(), 20 * MB, true, files::add);
            files.sort((a, b) -> Long.compare(b.length(), a.length()));
            if (files.size() > 300) files = new ArrayList<>(files.subList(0, 300));
            final List<File> result = files;
            runOnUiThread(() -> {
                if (screen != 1) return;
                LinearLayout v = root();
                addTitle(v, "Большие файлы");
                long total = 0;
                for (File f : result) total += f.length();
                TextView head = addText(v, "Найдено " + result.size() + " файлов от 20 МБ, всего " + fmt(total), 13, true);
                if (result.isEmpty()) {
                    addText(v, "Ничего не найдено.", 13, false);
                }
                for (File f : result) {
                    LinearLayout card = card(v);
                    addText(card, fmt(f.length()), 15, true);
                    addText(card, shortPath(f), 11, false);
                    LinearLayout buttons = new LinearLayout(MainActivity.this);
                    buttons.setOrientation(LinearLayout.HORIZONTAL);
                    Button open = new Button(MainActivity.this);
                    open.setAllCaps(false);
                    open.setText("Открыть");
                    open.setOnClickListener(b -> openFile(f));
                    Button del = new Button(MainActivity.this);
                    del.setAllCaps(false);
                    del.setText("Удалить");
                    del.setOnClickListener(b -> confirm("Удалить файл?", shortPath(f), () -> {
                        if (f.delete()) {
                            card.setVisibility(View.GONE);
                            toast("Удалено: " + fmt(f.length()));
                        } else {
                            toast("Не удалось удалить файл");
                        }
                    }));
                    buttons.addView(open, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    buttons.addView(del, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    card.addView(buttons);
                }
                addButton(v, "← Назад", b -> showHome());
                replace(v);
            });
        }).start();
        status.setText("Поиск файлов от 20 МБ...");
    }

    private void showDuplicates() {
        screen = 2;
        LinearLayout c = root();
        addTitle(c, "Дубликаты");
        TextView status = addText(c, "Сканирование... Это может занять пару минут.", 13, false);
        show(c, true);

        new Thread(() -> {
            Map<Long, List<File>> bySize = new HashMap<>();
            collectFiles(Environment.getExternalStorageDirectory(), 64 * 1024, true, f -> {
                List<File> list = bySize.get(f.length());
                if (list == null) {
                    list = new ArrayList<>();
                    bySize.put(f.length(), list);
                }
                list.add(f);
            });

            List<List<File>> candidates = new ArrayList<>();
            for (List<File> list : bySize.values()) {
                if (list.size() > 1) candidates.add(list);
            }
            candidates.sort((a, b) -> Long.compare(b.get(0).length(), a.get(0).length()));

            List<DupGroup> groups = new ArrayList<>();
            int[] hashed = {0};
            for (List<File> list : candidates) {
                Map<String, List<File>> byHash = new HashMap<>();
                for (File f : list) {
                    String h = md5(f);
                    if (h != null) {
                        List<File> same = byHash.get(h);
                        if (same == null) {
                            same = new ArrayList<>();
                            byHash.put(h, same);
                        }
                        same.add(f);
                    }
                    hashed[0]++;
                    if (hashed[0] % 20 == 0) {
                        final int done = hashed[0];
                        runOnUiThread(() -> status.setText("Проверено файлов: " + done));
                    }
                }
                for (List<File> same : byHash.values()) {
                    if (same.size() > 1) {
                        DupGroup g = new DupGroup();
                        g.size = same.get(0).length();
                        g.files = same;
                        groups.add(g);
                    }
                }
            }
            groups.sort((a, b) -> Long.compare(b.wasted(), a.wasted()));
            if (groups.size() > 100) groups = new ArrayList<>(groups.subList(0, 100));
            final List<DupGroup> result = groups;
            runOnUiThread(() -> renderDuplicates(result));
        }).start();
    }

    private void renderDuplicates(List<DupGroup> groups) {
        if (screen != 2) return;
        LinearLayout c = root();
        addTitle(c, "Дубликаты");
        long wasted = 0;
        for (DupGroup g : groups) wasted += g.wasted();
        long[] wastedTotal = {wasted};
        TextView head = addText(c, "Групп: " + groups.size() + " · можно освободить " + fmt(wasted), 14, true);
        if (groups.isEmpty()) {
            addText(c, "Дубликатов не найдено.", 13, false);
        } else {
            addButton(c, "Удалить все лишние копии", v -> confirm(
                    "Удалить все лишние копии?",
                    "Будет удалено " + fmt(wastedTotal[0]) + ". В каждой группе останется один файл.",
                    () -> {
                        int deleted = 0;
                        for (DupGroup g : groups) {
                            for (int i = 1; i < g.files.size(); i++) {
                                if (g.files.get(i).delete()) deleted++;
                            }
                        }
                        toast("Удалено копий: " + deleted);
                        showDuplicates();
                    }));
        }
        for (DupGroup g : groups) {
            LinearLayout card = card(c);
            addText(card, fmt(g.size) + " × " + g.files.size() + " копии", 15, true);
            addText(card, "Лишних: " + (g.files.size() - 1) + " · " + fmt(g.wasted()), 12, false);
            for (File f : g.files) addText(card, "• " + shortPath(f), 11, false);
            Button del = new Button(this);
            del.setAllCaps(false);
            del.setText("Удалить лишние (" + (g.files.size() - 1) + ")");
            del.setOnClickListener(v -> confirm("Удалить лишние копии?", "Останется один файл из группы.", () -> {
                int deleted = 0;
                for (int i = 1; i < g.files.size(); i++) {
                    if (g.files.get(i).delete()) deleted++;
                }
                card.setVisibility(View.GONE);
                wastedTotal[0] -= g.wasted();
                head.setText("Дубликаты · можно освободить " + fmt(Math.max(0, wastedTotal[0])));
                toast("Удалено копий: " + deleted);
            }));
            card.addView(del);
        }
        addButton(c, "← Назад", v -> showHome());
        replace(c);
    }

    private void showFolders() {
        screen = 3;
        LinearLayout c = root();
        addTitle(c, "Размер папок");
        TextView status = addText(c, "Подсчёт...", 13, false);
        show(c, true);

        new Thread(() -> {
            final File rootDir = Environment.getExternalStorageDirectory();
            List<FolderRow> rows = new ArrayList<>();
            File[] children = rootDir.listFiles();
            if (children != null) {
                for (File d : children) {
                    if (!d.isDirectory()) continue;
                    long size = dirSize(d, 0);
                    rows.add(new FolderRow(d.getName(), size));
                    runOnUiThread(() -> status.setText("Считаю: " + d.getName() + " (" + fmt(size) + ")"));
                }
            }
            rows.sort((a, b) -> Long.compare(b.size, a.size));
            final List<FolderRow> result = rows;
            runOnUiThread(() -> {
                if (screen != 3) return;
                LinearLayout v = root();
                addTitle(v, "Размер папок");
                long total = 0;
                for (FolderRow r : result) total += r.size;
                addText(v, "Всего в памяти пользователя: " + fmt(total), 14, true);
                for (FolderRow r : result) {
                    LinearLayout card = card(v);
                    addText(card, r.name, 15, true);
                    addText(card, fmt(r.size), 13, false);
                }
                addButton(v, "← Назад", b -> showHome());
                replace(v);
            });
        }).start();
    }

    private void showApps() {
        screen = 4;
        LinearLayout c = root();
        addTitle(c, "Приложения");
        addText(c, "Загрузка списка...", 13, false);
        show(c, true);

        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<AppRow> rows = new ArrayList<>();
            boolean statsOk = true;
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (system && !updated) continue;
                long size = appSize(ai.packageName);
                if (size < 0) statsOk = false;
                rows.add(new AppRow(pm.getApplicationLabel(ai).toString(), ai.packageName, size));
            }
            rows.sort((a, b) -> Long.compare(b.size, a.size));
            final List<AppRow> result = rows;
            final boolean stats = statsOk;
            runOnUiThread(() -> {
                if (screen != 4) return;
                LinearLayout v = root();
                addTitle(v, "Приложения (" + result.size() + ")");
                if (!stats) {
                    LinearLayout warn = card(v);
                    addText(warn, "Нет доступа к статистике", 14, true);
                    addText(warn, "Размеры приложений не показываются. Разрешите «Доступ к истории использования».", 12, false);
                    addButton(warn, "Открыть настройки доступа", b -> openUsageAccess());
                }
                for (AppRow r : result) {
                    LinearLayout card = card(v);
                    addText(card, r.label, 15, true);
                    addText(card, r.pkg, 11, false);
                    addText(card, r.size >= 0 ? fmt(r.size) : "размер недоступен", 13, false);
                    LinearLayout buttons = new LinearLayout(MainActivity.this);
                    buttons.setOrientation(LinearLayout.HORIZONTAL);
                    Button un = new Button(MainActivity.this);
                    un.setAllCaps(false);
                    un.setText("Удалить");
                    un.setOnClickListener(b -> uninstall(r.pkg));
                    Button det = new Button(MainActivity.this);
                    det.setAllCaps(false);
                    det.setText("Настройки");
                    det.setOnClickListener(b -> appDetails(r.pkg));
                    buttons.addView(un);
                    buttons.addView(det);
                    card.addView(buttons);
                }
                addButton(v, "← Назад", b -> showHome());
                replace(v);
            });
        }).start();
    }

    private void showSpeed() {
        screen = 5;
        LinearLayout c = root();
        addTitle(c, "Ускорение и кэш");

        boolean ready = shizukuReady();
        boolean allowed = shizukuAllowed();
        LinearLayout st = card(c);
        addText(st, ready ? "Shizuku: запущен" : "Shizuku: не запущен", 15, true);
        if (ready) {
            addText(st, allowed ? "Доступ приложения: выдан" : "Доступ приложения: не выдан", 13, false);
        } else {
            addText(st, "Установите Shizuku и запустите службу через ADB или беспроводную отладку.", 12, false);
        }

        if (!ready) {
            addButton(st, "Открыть Shizuku", v -> openShizuku());
            addButton(st, "Как включить Shizuku", v -> showShizukuHelp());
        } else if (!allowed) {
            addButton(st, "Запросить доступ Shizuku", v -> requestShizuku());
        }

        if (ready && allowed) {
            addButton(c, "Очистить кэш приложений", v -> runShellAsync("Очистка кэша",
                    "before=$(df /data | tail -1 | awk '{print $4}'); pm trim-caches 128G; after=$(df /data | tail -1 | awk '{print $4}'); echo \"Свободно до: $before КБ\"; echo \"Свободно после: $after КБ\""));
            addButton(c, "Остановить фоновые приложения", v -> runShellAsync("Остановка фоновых", "am kill-all; echo Готово"));
            addButton(c, "Отключить рекламу MIUI и Facebook", v -> runShellAsync("Отключение рекламы",
                    "for p in com.miui.msa.global com.miui.analytics com.facebook.appmanager com.facebook.services com.facebook.system; do pm uninstall --user 0 $p; done"));
            addButton(c, "Оптимизировать приложения (dexopt)", v -> runShellAsync("Оптимизация",
                    "cmd package bg-dexopt-job; echo Готово"));
            addButton(c, "Анимации 0.5x (может не работать на MIUI)", v -> runShellAsync("Анимации",
                    "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5; settings get global window_animation_scale"));
            addButton(c, "Вернуть рекламные модули", v -> runShellAsync("Возврат модулей",
                    "for p in com.miui.msa.global com.miui.analytics com.facebook.appmanager com.facebook.services com.facebook.system; do pm install-existing --user 0 $p; done"));
        }

        addButton(c, "Настройки анимаций (вручную)", v -> openDevSettings());
        addButton(c, "← Назад", v -> showHome());

        output = addText(c, "", 12, false);
        output.setTextIsSelectable(true);
        show(c, false);
    }

    private void runShellAsync(String label, String cmd) {
        if (output != null) output.setText(label + "...");
        new Thread(() -> {
            String result = runShell(cmd);
            runOnUiThread(() -> {
                if (output != null) output.setText(label + ":\n" + result);
            });
        }).start();
    }

    private String runShell(String cmd) {
        try {
            ShizukuRemoteProcess p = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            StringBuilder sb = new StringBuilder();
            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = out.readLine()) != null) sb.append(line).append('\n');
            BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((line = err.readLine()) != null) sb.append(line).append('\n');
            int code = p.waitFor();
            sb.append("Код выхода: ").append(code);
            return sb.toString().trim();
        } catch (Throwable t) {
            return "Ошибка: " + t;
        }
    }

    private boolean shizukuReady() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean shizukuAllowed() {
        try {
            return shizukuReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestShizuku() {
        try {
            if (!shizukuReady()) {
                toast("Shizuku не запущен");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("Доступ уже выдан");
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            toast("Ошибка Shizuku: " + t);
        }
    }

    private void openShizuku() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (i == null) throw new ActivityNotFoundException();
            startActivity(i);
        } catch (Throwable t) {
            toast("Shizuku не установлен");
        }
    }

    private void showShizukuHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Как включить Shizuku")
                .setMessage("1. Установите Shizuku.\n2. Включите беспроводную отладку.\n3. В Shizuku нажмите «Запустить» и выполните сопряжение.\n4. Вернитесь сюда и нажмите «Запросить доступ Shizuku».\n\nЕсли Shizuku уже запущен через ADB, просто запросите доступ.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    private void openDevSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable t) {
            toast("Не удалось открыть настройки для разработчиков");
        }
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Throwable t) {
            toast("Не удалось открыть настройки");
        }
    }

    private void uninstall(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        } catch (Throwable t) {
            toast("Не удалось открыть удаление");
        }
    }

    private void openFile(File file) {
        try {
            Uri uri = Uri.parse("content://" + FileContentProvider.AUTHORITY + "/"
                    + Uri.encode(file.getAbsolutePath()));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getContentResolver().getType(uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("", uri));
            startActivity(Intent.createChooser(intent, "Открыть файл"));
        } catch (ActivityNotFoundException e) {
            toast("Нет приложения для просмотра этого файла");
        } catch (Throwable t) {
            toast("Не удалось открыть: " + t.getMessage());
        }
    }

    private void appDetails(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
        } catch (Throwable t) {
            toast("Не удалось открыть настройки приложения");
        }
    }

    private void requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Throwable t2) {
                    toast("Откройте Настройки → Приложения → ClearPhone → Доступ ко всем файлам");
                }
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private long appSize(String pkg) {
        try {
            StorageStatsManager ssm = (StorageStatsManager) getSystemService(STORAGE_STATS_SERVICE);
            if (ssm == null) return -1;
            StorageStats stats = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle());
            return stats.getAppBytes() + stats.getDataBytes() + stats.getCacheBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    private long[] storageInfo() {
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            long total = sf.getTotalBytes();
            long free = sf.getAvailableBytes();
            return new long[]{total, total - free, free};
        } catch (Throwable t) {
            return new long[]{0, 0, 0};
        }
    }

    private String ramInfo() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return fmt(mi.availMem) + " свободно из " + fmt(mi.totalMem);
        } catch (Throwable t) {
            return "недоступно";
        }
    }

    private void collectFiles(File dir, long minSize, boolean skipAppData, Consumer<File> cb) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String name = f.getName();
                if (skipAppData && f.getParentFile() != null
                        && "Android".equals(f.getParentFile().getName())
                        && ("data".equals(name) || "obb".equals(name))) {
                    continue;
                }
                collectFiles(f, minSize, skipAppData, cb);
            } else if (f.isFile() && f.length() >= minSize) {
                cb.accept(f);
            }
        }
    }

    private long dirSize(File dir, int depth) {
        if (depth > 40) return 0;
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) total += dirSize(f, depth + 1);
            else total += f.length();
        }
        return total;
    }

    private static String md5(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private LinearLayout root() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(14), dp(14), dp(14), dp(28));
        ll.setBackgroundColor(0xFFF2F3F5);
        return ll;
    }

    private void addTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(8));
        parent.addView(tv);
    }

    private TextView addText(LinearLayout parent, String text, int sizeSp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(0xFF202124);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(2), 0, dp(2));
        parent.addView(tv);
        return tv;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(10));
        ll.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        parent.addView(ll, lp);
        return ll;
    }

    private Button addButton(LinearLayout parent, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        parent.addView(b, lp);
        return b;
    }

    private void show(LinearLayout content, boolean back) {
        if (back) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText("← Назад");
            b.setOnClickListener(v -> showHome());
            content.addView(b, 0);
        }
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
    }

    private void replace(LinearLayout content) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Удалить", (d, w) -> action.run())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String shortPath(File f) {
        String p = f.getAbsolutePath();
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (p.startsWith(root)) p = p.substring(root.length());
        if (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    private static String fmt(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f ГБ", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        if (bytes >= MB) {
            return String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024) {
            return String.format(Locale.US, "%.0f КБ", bytes / 1024.0);
        }
        return bytes + " Б";
    }

    private static class DupGroup {
        long size;
        List<File> files;
        long wasted() {
            return size * (files.size() - 1);
        }
    }

    private static class FolderRow {
        final String name;
        final long size;
        FolderRow(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private static class AppRow {
        final String label;
        final String pkg;
        final long size;
        AppRow(String label, String pkg, long size) {
            this.label = label;
            this.pkg = pkg;
            this.size = size;
        }
    }
}
