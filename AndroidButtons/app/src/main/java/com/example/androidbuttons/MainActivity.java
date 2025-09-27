package com.example.androidbuttons;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import androidx.appcompat.content.res.AppCompatResources;
import android.widget.ImageView;
import android.view.MotionEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Locale;
// import android.provider.Settings; // overlay removed
// import android.net.Uri; // overlay removed

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.androidbuttons.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private TcpManager tcpManager;
    private UsbUartManager usbUartManager;
    private DataBuffer uiBuffer;
    // Выбранный локомотив берём из общего состояния, чтобы синхронизироваться с экраном настроек
    // Подавление отправок при программном изменении свитчей (по TCP)
    private volatile boolean suppressSwitchCallback = false;

    // TCP авто‑поиск: резюмируем только после того, как пользователь сам спрятал клавиатуру
    private android.os.Handler tcpDebounceHandler; // оставлен для совместимости, но не используется для автоспрятия
    private final Runnable tcpResumeRunnable = null; // больше не используем таймер для резюма
    private android.view.ViewTreeObserver.OnGlobalLayoutListener keyboardListener;

    private static final String ACTION_USB_PERMISSION = "com.example.androidbuttons.USB_PERMISSION";
    private PendingIntent permissionIntent;
    private boolean receiverRegistered = false;
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                android.hardware.usb.UsbDevice device = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice.class);
                } else {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }
                // Без логов и тостов: просто передаём результат
                usbUartManager.onUsbPermissionResult(granted, device);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                android.hardware.usb.UsbDevice device = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice.class);
                } else {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }
                // Берём скорость из SharedPreferences
                android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
                int baud = prefs.getInt(AppState.KEY_UART_BAUD, 115200);
                // Инициируем подключение сразу (менеджер сам отфильтрует, если уже подключается/подключен)
                usbUartManager.connect(baud);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                android.hardware.usb.UsbDevice device = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice.class);
                } else {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }
                usbUartManager.disconnect("usb-detached");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

    // UI (на главном экране остались только L1–L6 и кнопка настроек)

        // Кнопка перехода в настройки
        binding.btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        uiBuffer = new DataBuffer(256, data -> {
            // Отдаём в общий буфер, чтобы настройки могли видеть консоль (на главном экране консоль не показываем)
            AppState.consoleQueue.offer(data);
        });

    // На главном экране больше нет полей настроек — только L1–L6 и кнопка настроек

    tcpManager = new TcpManager(
                () -> runOnUiThread(() -> { AppState.tcpConnecting = true; }),
                () -> runOnUiThread(() -> { AppState.tcpConnecting = false; }),
        data -> {
            if (data == null || data.isEmpty()) return;
            // Фильтруем только строки с совпадающим локомотивом
            String[] lines = data.split("\n");
            int locoTarget = AppState.selectedLoco.get();
            for (String line : lines) {
                if (line == null) continue;
                String ln = line.trim();
                if (ln.isEmpty()) continue;
                int idx = ln.indexOf("loco=");
                if (idx < 0) continue; // пропускаем строки без указания локомотива
                int j = idx + 5; // после 'loco='
                int val = 0; boolean has = false;
                while (j < ln.length()) {
                    char c = ln.charAt(j);
                    if (c >= '0' && c <= '9') { val = val * 10 + (c - '0'); has = true; j++; }
                    else break;
                }
                if (has && val == locoTarget) {
                    // Попробуем распарсить cmd и switch, чтобы обновить свитч и отправить UART
                    int cmdVal = -1;
                    int swNo = -1;
                    // cmd=0x..
                    int idxCmd = ln.indexOf("cmd=0x");
                    if (idxCmd >= 0 && idxCmd + 6 < ln.length()) {
                        int k = idxCmd + 6; // после 'cmd=0x'
                        int v = 0; boolean got = false;
                        while (k < ln.length()) {
                            char ch = ln.charAt(k);
                            int d;
                            if (ch >= '0' && ch <= '9') d = ch - '0';
                            else if (ch >= 'a' && ch <= 'f') d = 10 + (ch - 'a');
                            else if (ch >= 'A' && ch <= 'F') d = 10 + (ch - 'A');
                            else break;
                            v = (v << 4) | d; got = true; k++;
                        }
                        if (got) cmdVal = v & 0xFF;
                    }
                    // switch=
                    int idxSw = ln.indexOf("switch=");
                    if (idxSw >= 0 && idxSw + 7 < ln.length()) {
                        int k = idxSw + 7; // после 'switch='
                        int v = 0; boolean got = false;
                        while (k < ln.length()) {
                            char ch = ln.charAt(k);
                            if (ch >= '0' && ch <= '9') { v = v * 10 + (ch - '0'); got = true; k++; }
                            else break;
                        }
                        if (got) swNo = v;
                    }

                    if (cmdVal >= 0 && swNo >= 1 && swNo <= 5) {
                        // Локальный лог в требуемом формате
                        String state = (cmdVal == 0x01) ? "on" : (cmdVal == 0x00 ? "off" : ("0x" + Integer.toHexString(cmdVal)));
                        uiBuffer.offer("[#TCP_RX#]" + "Rx: loco" + val + " - " + swNo + " " + state + "\n");

                        final int relayNo = swNo;
                        final boolean turnOn;
                        if (cmdVal == 0x00) turnOn = false; else if (cmdVal == 0x01) turnOn = true; else continue; // поддерживаем только 0x00/0x01

                        runOnUiThread(() -> {
                            // Игнорируем визуальное обновление до первого пользовательского касания полосы
                            if (turnOn && userInteracted && currentState != relayNo) {
                                applyStripState(relayNo, true, false); // обновляем визуально (без отправки назад)
                            }
                            usbUartManager.sendFramed(turnOn ? 0x01 : 0x00, relayNo);
                        });
                    }
                }
            }
        },
    error -> { /* no toast */ },
    status -> runOnUiThread(() -> {
        boolean connected = "connected".equals(status);
        AppState.tcpConnected = connected;
    })
        );

    Intent permIntent = new Intent(ACTION_USB_PERMISSION);
    permIntent.setPackage(getPackageName());
    permissionIntent = PendingIntent.getBroadcast(
        this,
        0,
        permIntent,
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0
    );
    usbUartManager = new UsbUartManager(
                this,
                permissionIntent,
    () -> runOnUiThread(() -> { AppState.uartConnecting = true; }),
    () -> runOnUiThread(() -> { AppState.uartConnecting = false; }),
    data -> {
        if (data == null || data.isEmpty()) return;
        String s = data.trim();
        if (s.isEmpty()) return;
        // Пытаемся распарсить как cmd/loco/switch для единого формата
        String ln = s;
        int loco = -1, sw = -1, cmdVal = -1;
        int idxL = ln.indexOf("loco=");
        if (idxL >= 0) {
            int j = idxL + 5; int v = 0; boolean has = false;
            while (j < ln.length()) { char c = ln.charAt(j); if (c >= '0' && c <= '9') { v = v*10 + (c-'0'); has = true; j++; } else break; }
            if (has) loco = v;
        }
        int idxS = ln.indexOf("switch=");
        if (idxS >= 0) {
            int j = idxS + 7; int v = 0; boolean has = false;
            while (j < ln.length()) { char c = ln.charAt(j); if (c >= '0' && c <= '9') { v = v*10 + (c-'0'); has = true; j++; } else break; }
            if (has) sw = v;
        }
        int idxC = ln.indexOf("cmd=0x");
        if (idxC >= 0 && idxC + 6 < ln.length()) {
            int j = idxC + 6; int v = 0; boolean has = false;
            while (j < ln.length()) {
                char ch = ln.charAt(j);
                int d; if (ch >= '0' && ch <= '9') d = ch - '0';
                else if (ch >= 'a' && ch <= 'f') d = 10 + (ch - 'a');
                else if (ch >= 'A' && ch <= 'F') d = 10 + (ch - 'A');
                else break; v = (v << 4) | d; has = true; j++;
            }
            if (has) cmdVal = v & 0xFF;
        }
        if (loco > 0 && sw > 0 && (cmdVal == 0x00 || cmdVal == 0x01)) {
            String state = cmdVal == 0x01 ? "on" : "off";
            uiBuffer.offer("[UART←]" + "Rx: loco" + loco + " - " + sw + " " + state + "\n");
        } else {
            // fallback — сырой текст
            uiBuffer.offer("[UART←]" + "Rx: " + s + "\n");
        }
    },
    error -> { /* без тостов и логов об ошибках UART */ },
    status -> {
        // Статус UART: "start IO" => connected, "disconnect" => not connected
        if (status.contains("start IO")) {
            AppState.uartConnected = true;
        } else if (status.contains("disconnect")) {
            AppState.uartConnected = false;
        }
    },
    hex -> { /* подавляем сырой HEX, чтобы не ломать единый формат консоли */ }
        );

        // Register USB broadcast receiver for the whole Activity lifetime to not miss permission result
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
        receiverRegistered = true;

        // На главном экране нет полей ввода — слушатель клавиатуры не нужен

        // Стартуем авто‑подключение с настройками из SharedPreferences (если есть)
        android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
        String initHost = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        int initPort = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
        tcpManager.enableAutoConnect(initHost, initPort);

        // UART теперь работает в авто-режиме, свитч не требуется
        int initBaud = prefs.getInt(AppState.KEY_UART_BAUD, 115200);
        usbUartManager.enableAutoConnect(initBaud);

    // Инициализация вертикальной полосы состояний
    initStateStrip();

    // Overlay удалён
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (keyboardListener != null && binding != null) {
            android.view.View rootView = binding.getRoot();
            rootView.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardListener);
            keyboardListener = null;
        }
        if (tcpDebounceHandler != null) {
            tcpDebounceHandler.removeCallbacks(tcpResumeRunnable);
        }
        tcpManager.disableAutoConnect();
        tcpManager.disconnect();
        usbUartManager.disableAutoConnect();
        usbUartManager.disconnect("activity-destroy");
        if (receiverRegistered) {
            try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        uiBuffer.close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Подхватим актуальные настройки
        android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
        String host = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        int port = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
        tcpManager.updateTarget(host, port);

        int b = prefs.getInt(AppState.KEY_UART_BAUD, 115200);
        usbUartManager.setAutoBaud(b);

    // Overlay удалён
    }

    // На главном экране консоль не отображается
    private void appendToConsole(@NonNull String text) { /* no-op */ }

    private static int indexOfNewline(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '\n') return i;
        }
        return -1;
    }

    private void toast(String msg) { /* no-op, toasts disabled */ }

    // Убраны вспомогательные методы для полей ввода с главного экрана

    // ---------------- Новая логика одной вертикальной полосы состояний ----------------
    private ImageView stateStrip;
    // 0 означает: состояние ещё не выбрано, не показываем принудительно зелёный при старте
    private int currentState = 0; // после первого выбора станет 1..5
    private boolean userInteracted = false; // станет true при первом ACTION_DOWN
    private int lastResId = 0; // ресурс предыдущего показанного состояния для корректного crossfade
    private static final long STRIP_ANIM_DURATION = 1000L; // длительность плавного перехода без затемнения
    private android.animation.ValueAnimator stripAnimator; // активный аниматор кроссфейда
    // Один временный слой для нового состояния (старое остаётся в самом stateStrip)
    private ImageView crossNewView; // overlay нового состояния

    private void initStateStrip() {
    stateStrip = findViewById(R.id.stateStrip);
    if (stateStrip == null) return;
    // Не задаём стартовую картинку здесь — ждём первого взаимодействия или входящих данных
        stateStrip.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                if (action == MotionEvent.ACTION_DOWN) {
                    userInteracted = true; // разрешаем применять входящие внешние состояния
                }
                int h = v.getHeight();
                if (h > 0) {
                    float y = ev.getY();
                    int zone = (int)(y / (h / 5f)) + 1; // 1..5
                    if (zone < 1) zone = 1; else if (zone > 5) zone = 5;
                    if (currentState == 0) {
                        // Теперь анимируем и первый показ (fade-in сверху), без затемнения
                        applyStripState(zone, true, true);
                    } else if (zone != currentState) {
                        applyStripState(zone, true, true);
                    }
                }
            }
            return true;
        });
    }

    // maybeStartOverlay() удалён

    private void applyStripState(int state, boolean animate, boolean send) {
        int res;
        switch (state) {
            case 1: res = R.drawable.state_01_green; break;
            case 2: res = R.drawable.state_02_yellow; break;
            case 3: res = R.drawable.state_03_red_yellow; break;
            case 4: res = R.drawable.state_04_red; break;
            case 5: res = R.drawable.state_05_white; break;
            default: res = R.drawable.state_01_green; break;
        }
        if (stateStrip == null) return;

        // Больше не отключаем анимацию на первом показе — используем чистый fade-in новой картинки

        Drawable newD = AppCompatResources.getDrawable(this, res);
        if (newD == null) return;

        if (!animate || currentState == 0 || stateStrip.getDrawable() == null) {
            stateStrip.setAlpha(1f);
            stateStrip.setImageDrawable(newD);
        } else {
            if (stripAnimator != null) { stripAnimator.cancel(); stripAnimator = null; }
            final Drawable oldDrawable = stateStrip.getDrawable();
            if (oldDrawable == null) {
                stateStrip.setImageDrawable(newD);
                return;
            }
            // Гарантируем разовую обёртку (при первой анимации)
            android.view.ViewParent parent = stateStrip.getParent();
            android.widget.FrameLayout frame;
            if (parent instanceof android.widget.FrameLayout) {
                frame = (android.widget.FrameLayout) parent;
            } else {
                android.view.ViewGroup vg = (android.view.ViewGroup) parent;
                int idx = vg.indexOfChild(stateStrip);
                vg.removeViewAt(idx);
                frame = new android.widget.FrameLayout(this);
                frame.setLayoutParams(stateStrip.getLayoutParams());
                frame.addView(stateStrip, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                vg.addView(frame, idx);
            }
            // Базовый ImageView показывает старое состояние, остаётся видимым
            stateStrip.setImageDrawable(oldDrawable);
            stateStrip.setAlpha(1f);
            // Чистим и создаём единственный overlay, если нужно
            if (crossNewView != null) frame.removeView(crossNewView);
            crossNewView = new ImageView(this);
            crossNewView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            crossNewView.setScaleType(ImageView.ScaleType.FIT_XY);
            crossNewView.setAdjustViewBounds(false);
            crossNewView.setImageDrawable(newD);
            crossNewView.setAlpha(0f);
            frame.setClipToPadding(false);
            frame.setClipChildren(false);
            frame.addView(crossNewView);
            // Двухфазный анти-затемняющий кроссфейд (phase1: new растёт, phase2: old гаснет)
            stripAnimator = android.animation.ValueAnimator.ofFloat(0f,1f);
            stripAnimator.setDuration(STRIP_ANIM_DURATION);
            stripAnimator.addUpdateListener(a -> {
                float t = (float)a.getAnimatedValue();
                if (t <= 0.5f) {
                    float local = t / 0.5f; // 0..1
                    float s = local * local * (3f - 2f * local);
                    crossNewView.setAlpha(s);
                    stateStrip.setAlpha(1f);
                } else {
                    float local = (t - 0.5f) / 0.5f;
                    float s = local * local * (3f - 2f * local);
                    crossNewView.setAlpha(1f);
                    stateStrip.setAlpha(1f - s);
                }
            });
            stripAnimator.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override public void onAnimationEnd(android.animation.Animator animation) { finishSingleOverlayCrossfade(frame, newD); }
                @Override public void onAnimationCancel(android.animation.Animator animation) { finishSingleOverlayCrossfade(frame, newD); }
            });
            stripAnimator.start();
        }

        if (send) {
            sendExclusiveRelays(state);
        }
    currentState = state; // overlay удалён, глобальная синхронизация не требуется
        lastResId = res;
    }

    private void finishSingleOverlayCrossfade(android.widget.FrameLayout frame, Drawable finalDrawable){
        if (crossNewView != null) frame.removeView(crossNewView);
        crossNewView = null;
        stateStrip.setImageDrawable(finalDrawable);
        stateStrip.setAlpha(1f);
    }

    private void sendExclusiveRelays(int active) {
        int loco = AppState.selectedLoco.get();
        // Основной источник управляющих команд (overlay ограничен TCP чтобы не дублировать UART)
        for (int i = 1; i <= 5; i++) {
            int cmd = (i == active) ? 0x01 : 0x00;
            usbUartManager.sendFramed(cmd, i);
            tcpManager.sendControl(cmd, loco, i);
            String state = cmd == 0x01 ? "on" : "off";
            if (tcpManager.connectionActive()) {
                uiBuffer.offer("[#TCP_TX#]" + "Tx: loco" + loco + " - " + i + " " + state + "\n");
            }
            uiBuffer.offer("[UART→]" + "Tx: loco" + loco + " - " + i + " " + state + "\n");
        }
    }
    // -------------------------------------------------------------------------------
}
