package com.example.androidbuttons;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.HashMap;
// removed unused imports
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
 

class UsbUartManager {
    private final Context context;
    private final UsbManager usbManager;
    private final PendingIntent permissionIntent;
    interface StringConsumer { void accept(String s); }
    private final Runnable onStart;
    private final Runnable onStop;
    private final StringConsumer onData;
    private final StringConsumer onError;
    private final StringConsumer onStatus;

    private UsbSerialPort serialPort;
    private SerialInputOutputManager ioManager;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private int pendingBaud = 9600;
    private UsbDevice selectedDevice;
    private volatile long lastDataAt = 0L;
    private volatile boolean kickDone = false;
    private volatile boolean pendingConnect = false;

    UsbUartManager(Context context,
                   PendingIntent permissionIntent,
                   Runnable onStart,
                   Runnable onStop,
                   StringConsumer onData,
    // Auto mode
    private volatile boolean autoMode = false;
    private volatile int autoBaud = 9600;
    private java.util.concurrent.ScheduledFuture<?> autoTask;
    private volatile long lastPermissionRequestAt = 0L;
                   StringConsumer onError,
                   StringConsumer onStatus) {
        this.context = context.getApplicationContext();
        // Пропускаем, если уже подключены или идёт подключение
        if (isConnected() || pendingConnect) {
            log("UART: connect skipped (already connected/connecting)");
            return;
        }
        onStart.run();
        pendingBaud = baud;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        this.onData = onData;
        this.onError = onError;
        this.onStatus = onStatus;
    }
            if (!pendingConnect) return;

    // Auto mode
    private volatile boolean autoMode = false;
    private volatile int autoBaud = 9600;
    private java.util.concurrent.ScheduledFuture<?> autoTask;
    private volatile long lastPermissionRequestAt = 0L;
    private String ts() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss.SSS");
        return f.format(new java.util.Date());
                pendingConnect = false;
                return;
    private void log(String msg) { if (onStatus != null) onStatus.accept(ts() + " | " + msg); }

    // Упрощаем: discovery теперь внутренний шаг connect()

    // Фаза 2: CONNECT — выполняет discovery при необходимости, затем запрашивает разрешение и открывает порт
    void connect(int baud) {
        onStart.run();
        pendingBaud = baud;
        pendingConnect = true;
        log("UART: connect requested (" + baud + " bps)");
                long now = System.currentTimeMillis();
                if (now - lastPermissionRequestAt > 3000) {
                    lastPermissionRequestAt = now;
                    log("UART: requesting permission...");
                    usbManager.requestPermission(selectedDevice, permissionIntent);
                } else {
                    log("UART: permission request throttled");
                    // Сбросим флаг, чтобы авто‑режим смог попробовать снова позже
                    pendingConnect = false;
                }
            log("UART: DISCOVERY start (5000 ms)");
            UsbDevice found = scanForDevice(5000);
            if (!pendingConnect) return;
        // Пропускаем, если уже подключены или идёт подключение
        if (isConnected() || pendingConnect) {
            log("UART: connect skipped (already connected/connecting)");
            return;
        }
            if (found == null) {
                onStop.run();
                onError.accept("No USB devices found or compatible driver");
                return;
            }
            selectedDevice = found;
            log("UART: DISCOVERED device=" + found.getDeviceName() + " vid=" + found.getVendorId() + " pid=" + found.getProductId());
            boolean hasPerm = usbManager.hasPermission(selectedDevice);
        if (driver == null) { onStop.run(); onError.accept("No driver for device"); pendingConnect = false; return; }
        if (driver.getPorts().isEmpty()) { onStop.run(); onError.accept("No ports for device"); pendingConnect = false; return; }
                scheduler.schedule(() -> {
                    if (pendingConnect) openFirstPort(selectedDevice, pendingBaud);
                pendingConnect = false;
        if (connection == null) { onStop.run(); onError.accept("Open device failed"); pendingConnect = false; return; }
            } else {
                log("UART: requesting permission...");
                usbManager.requestPermission(selectedDevice, permissionIntent);
            }
        });
    }

    private UsbDevice scanForDevice(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start <= timeoutMs) {
                long now = System.currentTimeMillis();
                if (now - lastPermissionRequestAt > 3000) {
                    lastPermissionRequestAt = now;
                    log("UART: requesting permission...");
                    usbManager.requestPermission(selectedDevice, permissionIntent);
                } else {
                    log("UART: permission request throttled");
                    // Сбросим флаг, чтобы авто‑режим смог попробовать снова позже
                    pendingConnect = false;
                }
            UsbDevice found = findFirstCompatible(list);
            if (found != null) return found;
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
    boolean isConnected() {
        return serialPort != null && ioManager != null;
    }

        return null;
    }

    private UsbDevice findFirstCompatible(HashMap<String, UsbDevice> list) {
        for (UsbDevice d : list.values()) {
            UsbSerialDriver drv = UsbSerialProber.getDefaultProber().probeDevice(d);
            if (drv != null) return d;
        }
        return null;
    }

    void onUsbPermissionResult(boolean granted, UsbDevice deviceFromIntent) {
        log("UART: permission result granted=" + granted + ", deviceFromIntent=" + (deviceFromIntent != null));
        if (!granted) { onError.accept("USB permission denied"); pendingConnect = false; disconnect("permission-denied"); return; }
    UsbDevice deviceToOpen = deviceFromIntent != null ? deviceFromIntent : selectedDevice;
    if (deviceFromIntent != null) { selectedDevice = deviceFromIntent; }
        if (deviceToOpen == null) {
            onStop.run(); onError.accept("USB device not available after permission"); pendingConnect = false; return;
        }
        log("UART: will open device=" + deviceToOpen.getDeviceName());
        UsbDevice finalDeviceToOpen = deviceToOpen;
        scheduler.schedule(() -> {
            if (pendingConnect) openFirstPort(finalDeviceToOpen, pendingBaud);
        }, 400, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void openFirstPort(UsbDevice device, int baud) {
        log("UART: probing driver for " + device.getDeviceName());
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) { onStop.run(); onError.accept("No driver for device"); return; }
        if (driver.getPorts().isEmpty()) { onStop.run(); onError.accept("No ports for device"); return; }
        log("UART: ports found=" + driver.getPorts().size());
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) { onStop.run(); onError.accept("Open device failed"); return; }

        serialPort = driver.getPorts().get(0);
        try {
            log("UART: opening port...");
        if (driver == null) { onStop.run(); onError.accept("No driver for device"); pendingConnect = false; return; }
        if (driver.getPorts().isEmpty()) { onStop.run(); onError.accept("No ports for device"); pendingConnect = false; return; }
            log("UART: params set baud=" + baud);
            // Для Arduino/CH340 не сбрасываем, просто поднимаем линии
        if (connection == null) { onStop.run(); onError.accept("Open device failed"); pendingConnect = false; return; }
            try { serialPort.setDTR(false); } catch (Exception ignored) {}
            try { serialPort.setRTS(false); } catch (Exception ignored) {}
            // Очистим буферы перед стартом чтения
            try { serialPort.purgeHwBuffers(true, true); } catch (Exception ignored) {}
            log("UART: DTR/RTS kept low, buffers purged");
            // Небольшой «пинок»: отправим CRLF, чтобы прошивка начала отвечать
            scheduler.schedule(() -> {
                if (serialPort != null) {
                    try { serialPort.write("\r\n".getBytes(), 100); } catch (IOException ignored) {}
                }
            }, 200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            onError.accept("UART open error: " + e.getMessage());
            try { serialPort.close(); } catch (IOException ignored) {}
            serialPort = null;
            disconnect("open-error");
            return;
        }
        lastDataAt = 0L;
        kickDone = false;
        // После открытия многие Arduino/CH34x делают авто‑reset по DTR, дадим им время и только потом стартуем IO
        scheduler.schedule(this::startIo, 1200, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void startIo() {

    // Авто‑режим: периодически пытаемся подключиться, если не подключены
    void enableAutoConnect(int baud) {
        autoBaud = baud;
        autoMode = true;
        if (autoTask != null && !autoTask.isCancelled()) {
            autoTask.cancel(false);
        }
        autoTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!autoMode) return;
                if (!isConnected() && !pendingConnect) {
                    connect(autoBaud);
                }
            } catch (Throwable t) {
                // гасим исключения, чтобы задача не умерла
            }
        }, 0, 3, java.util.concurrent.TimeUnit.SECONDS);
        log("UART: auto-connect ENABLED (baud=" + baud + ")");
    }

    void disableAutoConnect() {
        autoMode = false;
        if (autoTask != null) {
            autoTask.cancel(false);
            autoTask = null;
        }
        log("UART: auto-connect DISABLED");
    }
        final long startIoAt = System.currentTimeMillis();
        ioManager = new SerialInputOutputManager(serialPort, new SerialInputOutputManager.Listener() {
            @Override public void onNewData(byte[] data) {
                lastDataAt = System.currentTimeMillis();
                // Фильтруем стартовый мусор в первые 750 мс
                long dt = lastDataAt - startIoAt;
                if (dt < 750) {
                    // Оставляем печатаемые ASCII, пробелы, CR/LF/TAB
                    StringBuilder sb = new StringBuilder();
                    for (byte b : data) {
                        int v = b & 0xFF;
                        if (v == 9 || v == 10 || v == 13 || (v >= 32 && v < 127)) sb.append((char) v);
                    }
                    if (sb.length() == 0) return; // полностью мусор — игнорируем
                    onData.accept(sb.toString());
                } else {
                    onData.accept(new String(data));
                }
            }
            @Override public void onRunError(Exception e) { onError.accept("UART IO: " + e.getMessage()); disconnect("io-error"); }
        });
        ioExecutor.submit(ioManager);
        log("UART: start IO");
        onStop.run();

        // Watchdog: если за 2 сек после старта нет данных — просто очистим буферы (без DTR пульса)
        scheduler.schedule(() -> {
            if (serialPort != null && !kickDone && lastDataAt == 0L) {
                kickDone = true;
                log("UART: no data in 2s, purge buffers");
                try { serialPort.purgeHwBuffers(true, true); } catch (Exception ignored) {}
    // Авто‑режим: периодически пытаемся подключиться, если не подключены
    void enableAutoConnect(int baud) {
        autoBaud = baud;
        autoMode = true;
        if (autoTask != null) {
            autoTask.cancel(false);
            autoTask = null;
        }
        autoTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!autoMode) return;
                if (!isConnected() && !pendingConnect) {
                    connect(autoBaud);
                }
            } catch (Throwable t) {
                // гасим исключения, чтобы задача не умерла
            }
        }, 0, 3, java.util.concurrent.TimeUnit.SECONDS);
        log("UART: auto-connect ENABLED (baud=" + baud + ")");
    }

    void disableAutoConnect() {
        autoMode = false;
        if (autoTask != null) {
            autoTask.cancel(false);
            autoTask = null;
        }
        log("UART: auto-connect DISABLED");
    }
            }
        }, 2000, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    void disconnect() { disconnect("manual"); }

    void disconnect(String reason) {
        // Идемпотентность: если уже всё закрыто — ничего не делаем
        boolean wasActive = ioManager != null || serialPort != null || pendingConnect;
        if (ioManager != null) { ioManager.stop(); ioManager = null; }
        if (serialPort != null) {
            try { serialPort.close(); } catch (IOException ignored) {}
            serialPort = null;
        }
        if (!wasActive) return;
        pendingConnect = false;
        log("UART: disconnect (" + reason + ")");
        onStop.run();
    }
}
