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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.androidbuttons.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private TcpManager tcpManager;
    private UsbUartManager usbUartManager;
    private DataBuffer uiBuffer;

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

    tcpManager = new TcpManager(
                () -> runOnUiThread(() -> binding.progressBarTCP.setVisibility(View.VISIBLE)),
                () -> runOnUiThread(() -> binding.progressBarTCP.setVisibility(View.GONE)),
        data -> uiBuffer.offer("[TCP→] " + data),
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
    data -> uiBuffer.offer(data),
    error -> { /* без тостов и логов об ошибках UART */ },
    status -> {
        // Показываем только тосты включения/отключения, статус в консоль не выводим
        if (status.contains("start IO")) {
            runOnUiThread(() -> binding.switchUART.setChecked(true));
        } else if (status.contains("disconnect")) {
            runOnUiThread(() -> binding.switchUART.setChecked(false));
        }
    }
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
        binding.textConsole.append(text);
        int scrollAmount = binding.textConsole.getLayout() != null
                ? binding.textConsole.getLayout().getLineTop(binding.textConsole.getLineCount()) - binding.textConsole.getHeight()
                : 0;
        if (scrollAmount > 0) binding.textConsole.scrollTo(0, scrollAmount);
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

    private void wireRelaySwitch(android.widget.Switch switchView, String name) {
        if (switchView == null) return;
        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String cmd = "<" + name + (isChecked ? "+" : "-") + ">\r\n";
            usbUartManager.send(cmd);
        });
    }
}
