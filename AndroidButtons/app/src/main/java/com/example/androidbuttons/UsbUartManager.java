package com.example.androidbuttons;

import android.app.PendingIntent;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.*;

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
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private int pendingBaud = 9600;
    private UsbDevice selectedDevice;
    private volatile long lastDataAt = 0L;
    private volatile boolean kickDone = false;
    private volatile boolean pendingConnect = false;

    // Auto mode
    private volatile boolean autoMode = false;
    private volatile int autoBaud = 9600;
    private ScheduledFuture<?> autoTask;
    private volatile long lastPermissionRequestAt = 0L;

    UsbUartManager(Context context,
                   PendingIntent permissionIntent,
                   Runnable onStart,
                   Runnable onStop,
                   StringConsumer onData,
                   StringConsumer onError,
                   StringConsumer onStatus) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.permissionIntent = permissionIntent;
        this.onStart = onStart;
        this.onStop = onStop;
        this.onData = onData;
        this.onError = onError;
        this.onStatus = onStatus;
    }

    private String ts() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss.SSS");
        return f.format(new java.util.Date());
    }
    private void log(String msg) { if (onStatus != null) onStatus.accept(ts() + " | " + msg); }

    // CONNECT — discovery (1s), затем permission/open
    void connect(int baud) {
        if (isConnected() || pendingConnect) {
            log("UART: connect skipped (already connected/connecting)");
            return;
        }
        pendingBaud = baud;
        pendingConnect = true;
        log("UART: connect requested (" + baud + " bps)");
        ioExecutor.submit(() -> {
            log("UART: DISCOVERY start (1000 ms)");
            UsbDevice found = scanForDevice(1000);
            if (!pendingConnect) return;
            if (found == null) {
                onError.accept("No USB devices found or compatible driver");
                pendingConnect = false;
                return;
            }
            // Устройство найдено — теперь показываем индикатор
            onStart.run();
            selectedDevice = found;
            log("UART: DISCOVERED device=" + found.getDeviceName() + " vid=" + found.getVendorId() + " pid=" + found.getProductId());
            boolean hasPerm = usbManager.hasPermission(selectedDevice);
            log("UART: selected device=" + selectedDevice.getDeviceName() + ", hasPermission=" + hasPerm);
            if (hasPerm) {
                scheduler.schedule(() -> {
                    if (pendingConnect) openFirstPort(selectedDevice, pendingBaud);
                }, 400, TimeUnit.MILLISECONDS);
            } else {
                long now = System.currentTimeMillis();
                if (now - lastPermissionRequestAt > 3000) {
                    lastPermissionRequestAt = now;
                    log("UART: requesting permission...");
                    usbManager.requestPermission(selectedDevice, permissionIntent);
                } else {
                    log("UART: permission request throttled");
                    pendingConnect = false;
                }
            }
        });
    }

    boolean isConnected() { return serialPort != null && ioManager != null; }

    void setAutoBaud(int baud) {
        // Обновляем значения для авто‑режима и для текущего/следующего подключения
        this.autoBaud = baud;
        this.pendingBaud = baud;
        // Если порт уже открыт — применяем скорость немедленно, не разрывая соединение
        scheduler.execute(() -> {
            if (serialPort != null) {
                try {
                    serialPort.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                    log("UART: baud updated to " + baud);
                    try { serialPort.purgeHwBuffers(true, true); } catch (Exception ignored) {}
                    // Лёгкий "kick" после смены скорости — поможет увидеть, что новая скорость активна
                    scheduler.schedule(() -> {
                        if (serialPort != null) {
                            try { serialPort.write("\r\n".getBytes(), 100); } catch (IOException ignored) {}
                        }
                    }, 150, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    onError.accept("UART: set baud failed: " + e.getMessage());
                }
            } else {
                log("UART: baud set for next connect: " + baud);
            }
        });
    }

    private UsbDevice scanForDevice(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start <= timeoutMs) {
            HashMap<String, UsbDevice> list = usbManager.getDeviceList();
            UsbDevice found = findFirstCompatible(list);
            if (found != null) return found;
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
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
        scheduler.schedule(() -> { if (pendingConnect) openFirstPort(finalDeviceToOpen, pendingBaud); }, 400, TimeUnit.MILLISECONDS);
    }

    private void openFirstPort(UsbDevice device, int baud) {
        log("UART: probing driver for " + device.getDeviceName());
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) { onStop.run(); onError.accept("No driver for device"); pendingConnect = false; return; }
        if (driver.getPorts().isEmpty()) { onStop.run(); onError.accept("No ports for device"); pendingConnect = false; return; }
        log("UART: ports found=" + driver.getPorts().size());
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) { onStop.run(); onError.accept("Open device failed"); pendingConnect = false; return; }

        serialPort = driver.getPorts().get(0);
        try {
            log("UART: opening port...");
            serialPort.open(connection);
            serialPort.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            log("UART: params set baud=" + baud);
            try { serialPort.setDTR(false); } catch (Exception ignored) {}
            try { serialPort.setRTS(false); } catch (Exception ignored) {}
            try { serialPort.purgeHwBuffers(true, true); } catch (Exception ignored) {}
            log("UART: DTR/RTS kept low, buffers purged");
            scheduler.schedule(() -> { if (serialPort != null) { try { serialPort.write("\r\n".getBytes(), 100); } catch (IOException ignored) {} } }, 200, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            onError.accept("UART open error: " + e.getMessage());
            try { serialPort.close(); } catch (IOException ignored) {}
            serialPort = null;
            disconnect("open-error");
            return;
        }
        lastDataAt = 0L;
        kickDone = false;
        scheduler.schedule(this::startIo, 1200, TimeUnit.MILLISECONDS);
    }

    private void startIo() {
        if (!pendingConnect || serialPort == null) {
            // Соединение отменено или порт уже закрыт — ничего не делаем
            pendingConnect = false;
            onStop.run();
            return;
        }
        final long startIoAt = System.currentTimeMillis();
        ioManager = new SerialInputOutputManager(serialPort, new SerialInputOutputManager.Listener() {
            @Override public void onNewData(byte[] data) {
                lastDataAt = System.currentTimeMillis();
                long dt = lastDataAt - startIoAt;
                if (dt < 750) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : data) {
                        int v = b & 0xFF;
                        if (v == 9 || v == 10 || v == 13 || (v >= 32 && v < 127)) sb.append((char) v);
                    }
                    if (sb.length() == 0) return;
                    onData.accept(sb.toString());
                } else {
                    onData.accept(new String(data));
                }
            }
            @Override public void onRunError(Exception e) { onError.accept("UART IO: " + e.getMessage()); disconnect("io-error"); }
        });
        ioExecutor.submit(ioManager);
        log("UART: start IO");
    // Теперь индикатор можно скрыть, подключение завершено
    onStop.run();

        scheduler.schedule(() -> {
            if (serialPort != null && !kickDone && lastDataAt == 0L) {
                kickDone = true;
                log("UART: no data in 2s, purge buffers");
                try { serialPort.purgeHwBuffers(true, true); } catch (Exception ignored) {}
            }
        }, 2000, TimeUnit.MILLISECONDS);
    }

    void disconnect() { disconnect("manual"); }

    void disconnect(String reason) {
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

    // Авто‑режим: пытаемся подключаться раз в 1 секунду
    void enableAutoConnect(int baud) {
        autoBaud = baud;
        autoMode = true;
        if (autoTask != null) { autoTask.cancel(false); autoTask = null; }
        autoTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!autoMode) return;
                if (!isConnected() && !pendingConnect) {
                    connect(autoBaud);
                }
            } catch (Throwable t) { /* suppress */ }
        }, 0, 1, TimeUnit.SECONDS);
        log("UART: auto-connect ENABLED (baud=" + baud + ")");
    }

    void disableAutoConnect() {
        autoMode = false;
        if (autoTask != null) { autoTask.cancel(false); autoTask = null; }
        log("UART: auto-connect DISABLED");
    }

    // Публичный метод для отправки данных в UART
    void send(String data) {
        if (data == null || data.isEmpty()) return;
        // Используем отдельный поток планировщика, чтобы не блокироваться на IO-исполнителе
        scheduler.execute(() -> {
            if (serialPort == null) return;
            try {
                serialPort.write(data.getBytes(), 200);
            } catch (IOException ignored) {
            }
        });
    }
}
