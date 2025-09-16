package com.example.androidbuttons;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.androidbuttons.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private TcpManager tcpManager;
    private UsbUartManager usbUartManager;
    private DataBuffer uiBuffer;
    // Аккумулятор для последней незавершённой строки консоли
    private final StringBuilder consoleRemainder = new StringBuilder();
    // Текущий выбранный локомотив (1..8) для фильтра TCP
    private final AtomicInteger selectedLoco = new AtomicInteger(1);
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
                int baud;
                try { baud = Integer.parseInt(String.valueOf(binding.valueBaudRate.getText())); } catch (Exception e) { baud = 9600; }
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

        // UI
        binding.textConsole.setMovementMethod(new ScrollingMovementMethod());
        binding.progressBarTCP.setVisibility(View.GONE);
        binding.progressBarUART.setVisibility(View.GONE);

    uiBuffer = new DataBuffer(256, data -> runOnUiThread(() -> appendToConsole(data)));

    // Наполняем spinner_num значениями Loco1..Loco8
    String[] locoItems = new String[8];
    for (int i = 0; i < 8; i++) locoItems[i] = "Loco" + (i + 1);
        ArrayAdapter<String> locoAdapter = new ArrayAdapter<>(
        this,
        android.R.layout.simple_spinner_item,
        locoItems
    );
    locoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    binding.spinnerNum.setAdapter(locoAdapter);
        binding.spinnerNum.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedLoco.set(position + 1);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { /* keep previous */ }
        });

    tcpManager = new TcpManager(
                () -> runOnUiThread(() -> binding.progressBarTCP.setVisibility(View.VISIBLE)),
                () -> runOnUiThread(() -> binding.progressBarTCP.setVisibility(View.GONE)),
        data -> {
            if (data == null || data.isEmpty()) return;
            // Фильтруем только строки с совпадающим локомотивом
            String[] lines = data.split("\n");
            int locoTarget = selectedLoco.get();
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

                    if (cmdVal >= 0 && swNo >= 1 && swNo <= 6) {
                        // Локальный лог в требуемом формате
                        String state = (cmdVal == 0x01) ? "on" : (cmdVal == 0x00 ? "off" : ("0x" + Integer.toHexString(cmdVal)));
                        uiBuffer.offer("[#TCP_RX#]" + "Rx: loco" + val + " - " + swNo + " " + state + "\n");

                        final int relayNo = swNo;
                        final boolean turnOn;
                        if (cmdVal == 0x00) turnOn = false; else if (cmdVal == 0x01) turnOn = true; else continue; // поддерживаем только 0x00/0x01

                        runOnUiThread(() -> {
                            // Обновляем соответствующий свитч без вызова отправки по Wi‑Fi
                            android.widget.Switch swView = getSwitchByRelayNo(relayNo);
                            if (swView != null) {
                                suppressSwitchCallback = true;
                                try { swView.setChecked(turnOn); } finally { suppressSwitchCallback = false; }
                            }
                            // Отправляем команду по UART согласно полученному кадру
                            usbUartManager.sendFramed(turnOn ? 0x01 : 0x00, relayNo);
                        });
                    }
                }
            }
        },
    error -> { /* no toast */ },
    status -> runOnUiThread(() -> {
        boolean connected = "connected".equals(status);
        binding.switchTCP.setChecked(connected);
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
        () -> runOnUiThread(() -> binding.progressBarUART.setVisibility(View.VISIBLE)),
        () -> runOnUiThread(() -> binding.progressBarUART.setVisibility(View.GONE)),
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
        // Показываем только тосты включения/отключения, статус в консоль не выводим
        if (status.contains("start IO")) {
            runOnUiThread(() -> binding.switchUART.setChecked(true));
        } else if (status.contains("disconnect")) {
            runOnUiThread(() -> binding.switchUART.setChecked(false));
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

        // Слушатель видимости клавиатуры: при скрытии убираем каретку (снимаем фокус) со всех трёх полей
        final android.view.View rootView = binding.getRoot();
        keyboardListener = new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            private boolean wasVisible = false;
            @Override public void onGlobalLayout() {
                android.graphics.Rect r = new android.graphics.Rect();
                rootView.getWindowVisibleDisplayFrame(r);
                int screenHeight = rootView.getRootView().getHeight();
                int heightDiff = screenHeight - r.height();
                // Порог ~128dp
                int threshold = (int) (128 * getResources().getDisplayMetrics().density);
                boolean isVisible = heightDiff > threshold;
                if (wasVisible && !isVisible) {
                    // Клавиатура только что скрылась — убираем каретку/фокус со всех полей
                    clearFocusAllInputs();
                }
                wasVisible = isVisible;
            }
        };
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);

    // Switches
    // TCP теперь тоже в авто-режиме
    binding.switchTCP.setChecked(false);
        binding.switchTCP.setEnabled(false);

        // Стартуем авто‑подключение с текущими значениями
        String initHost = String.valueOf(binding.valueAddrTCP.getText()).trim();
        int initPort;
        try { initPort = Integer.parseInt(String.valueOf(binding.valuePortTCP.getText()).trim()); }
        catch (Exception e) { initPort = -1; }
        tcpManager.enableAutoConnect(initHost, initPort);

    // При изменении адреса/порта — просто ставим на паузу и рвём соединение. Возобновление — только после скрытия клавиатуры
    tcpDebounceHandler = new android.os.Handler(getMainLooper());
        tcpManager.pauseAuto(false);

        binding.valueAddrTCP.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                tcpManager.pauseAuto(true);
                // Полностью блокируем текущие попытки/соединение на период редактирования
                tcpManager.disconnect();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        binding.valuePortTCP.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                tcpManager.pauseAuto(true);
                // Полностью блокируем текущие попытки/соединение на период редактирования
                tcpManager.disconnect();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        // Резюмируем поиск после того, как пользователь сам спрятал клавиатуру (IME action Done)
        android.widget.TextView.OnEditorActionListener doneListener = (v, actionId, event) -> {
            hideKeyboardAndClearFocus();
            resumeTcpAuto();
            return false;
        };
        binding.valueAddrTCP.setOnEditorActionListener(doneListener);
        binding.valuePortTCP.setOnEditorActionListener(doneListener);

        // При потере фокуса (обычно когда клавиатура спрятана пользователем) — возобновляем поиск
        android.view.View.OnFocusChangeListener blurListener = (v, hasFocus) -> { if (!hasFocus) resumeTcpAuto(); };
        binding.valueAddrTCP.setOnFocusChangeListener(blurListener);
        binding.valuePortTCP.setOnFocusChangeListener(blurListener);

        // UART теперь работает в авто-режиме, свитч не требуется
        int initBaud;
        try { initBaud = Integer.parseInt(String.valueOf(binding.valueBaudRate.getText())); }
        catch (Exception e) { initBaud = 9600; }
        usbUartManager.enableAutoConnect(initBaud);

        // Подписка на изменения baud: применяем немедленно, но только если ввод валидный int
        binding.valueBaudRate.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String text = String.valueOf(s).trim();
                if (text.isEmpty()) return; // не трогаем, пока поле пустое
                try {
                    int b = Integer.parseInt(text);
                    usbUartManager.setAutoBaud(b);
                } catch (Exception ignored) {
                    // игнорируем нечисловой ввод, чтобы не сбрасывать скорость
                }
            }
        });
    // Визуально отключаем свитч; состояние будет выставляться колбэками статуса
    binding.switchUART.setChecked(false);
        binding.switchUART.setEnabled(false);

        // Управление выходами L1–L6: отправляем команды по UART при переключении
        wireRelaySwitch(binding.switchL1, "L1");
        wireRelaySwitch(binding.switchL2, "L2");
        wireRelaySwitch(binding.switchL3, "L3");
        wireRelaySwitch(binding.switchL4, "L4");
        wireRelaySwitch(binding.switchL5, "L5");
        wireRelaySwitch(binding.switchL6, "L6");
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

    private void appendToConsole(@NonNull String text) {
        // Копим в аккумулятор и обрабатываем построчно — это предотвращает неверную подсветку,
        // когда пачка содержит части строк с разными префиксами
        consoleRemainder.append(text);
        int idx;
        while ((idx = indexOfNewline(consoleRemainder)) >= 0) {
            String line = consoleRemainder.substring(0, idx + 1);
            consoleRemainder.delete(0, idx + 1);

            int color = -1;
            boolean removePrefix = false;
            int removeLen = 0;
            if (line.startsWith("[UART→]")) {
                color = 0xFF90EE90; // LightGreen: TX
                removePrefix = true; removeLen = "[UART→]".length();
            } else if (line.startsWith("[UART←]")) {
                color = 0xFF006400; // DarkGreen: RX
                removePrefix = true; removeLen = "[UART←]".length();
            } else if (line.startsWith("[#TCP_TX#]")) {
                color = 0xFF87CEFA; // LightSkyBlue: TCP TX
                removePrefix = true; removeLen = "[#TCP_TX#]".length();
            } else if (line.startsWith("[#TCP_RX#]")) {
                color = 0xFF0000FF; // Blue: TCP RX
                removePrefix = true; removeLen = "[#TCP_RX#]".length();
            }

            if (removePrefix && removeLen > 0) {
                line = line.substring(removeLen);
            }
            if (color != -1) {
                android.text.SpannableString ss = new android.text.SpannableString(line);
                ss.setSpan(new android.text.style.ForegroundColorSpan(color), 0, ss.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                binding.textConsole.append(ss);
            } else {
                binding.textConsole.append(line);
            }
        }
        int scrollAmount = binding.textConsole.getLayout() != null
                ? binding.textConsole.getLayout().getLineTop(binding.textConsole.getLineCount()) - binding.textConsole.getHeight()
                : 0;
        if (scrollAmount > 0) binding.textConsole.scrollTo(0, scrollAmount);
    }

    private static int indexOfNewline(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '\n') return i;
        }
        return -1;
    }

    private void toast(String msg) { /* no-op, toasts disabled */ }

    private void hideKeyboardAndClearFocus() {
        if (binding == null) return;
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View v1 = binding.valueAddrTCP;
        View v2 = binding.valuePortTCP;
        View v3 = binding.valueBaudRate;
        if (v1 != null) { v1.clearFocus(); if (imm != null) imm.hideSoftInputFromWindow(v1.getWindowToken(), 0); }
        if (v2 != null) { v2.clearFocus(); if (imm != null) imm.hideSoftInputFromWindow(v2.getWindowToken(), 0); }
        if (v3 != null) { v3.clearFocus(); if (imm != null) imm.hideSoftInputFromWindow(v3.getWindowToken(), 0); }
    }

    private void resumeTcpAuto() {
        String host = String.valueOf(binding.valueAddrTCP.getText()).trim();
        int port;
        try { port = Integer.parseInt(String.valueOf(binding.valuePortTCP.getText()).trim()); }
        catch (Exception e) { port = -1; }
        tcpManager.updateTarget(host, port);
        tcpManager.pauseAuto(false);
        // Форсируем переподключение к новой цели
        tcpManager.disconnect();
    }

    private void clearFocusAllInputs() {
        if (binding == null) return;
        View v1 = binding.valueAddrTCP;
        View v2 = binding.valuePortTCP;
        View v3 = binding.valueBaudRate;
        if (v1 != null) v1.clearFocus();
        if (v2 != null) v2.clearFocus();
        if (v3 != null) v3.clearFocus();
    }

    private android.widget.Switch getSwitchByRelayNo(int relayNo) {
        switch (relayNo) {
            case 1: return binding.switchL1;
            case 2: return binding.switchL2;
            case 3: return binding.switchL3;
            case 4: return binding.switchL4;
            case 5: return binding.switchL5;
            case 6: return binding.switchL6;
            default: return null;
        }
    }

    private void wireRelaySwitch(android.widget.Switch switchView, String name) {
        if (switchView == null) return;
        // Новая семантика: cmd = 0x01 (включить) или 0x00 (выключить),
        // data = номер реле (1..6)
        final int relayNo;
        switch (name) {
            case "L1": relayNo = 1; break;
            case "L2": relayNo = 2; break;
            case "L3": relayNo = 3; break;
            case "L4": relayNo = 4; break;
            case "L5": relayNo = 5; break;
            case "L6": relayNo = 6; break;
            default: return;
        }
        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitchCallback) return; // программное изменение из TCP — ничего не отправляем здесь
            int cmd = isChecked ? 0x01 : 0x00;
            usbUartManager.sendFramed(cmd, relayNo);
            // Также отправляем по Wi‑Fi (TCP) тот же смысл: cmd=0x00/0x01, data=[loco(1..8), switch(1..6)]
            int loco = selectedLoco.get();
            tcpManager.sendControl(cmd, loco, relayNo);
            // Локальный лог TX только при активном соединении
            if (tcpManager.connectionActive()) {
                String state = cmd == 0x01 ? "on" : "off";
                uiBuffer.offer("[#TCP_TX#]" + "Tx: loco" + loco + " - " + relayNo + " " + state + "\n");
            }
            // И сразу же логируем UART TX в едином формате и зелёным цветом
            {
                String state = cmd == 0x01 ? "on" : "off";
                uiBuffer.offer("[UART→]" + "Tx: loco" + loco + " - " + relayNo + " " + state + "\n");
            }
        });
    }
}
