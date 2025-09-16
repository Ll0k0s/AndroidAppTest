package com.example.androidbuttons;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

class TcpManager {
    interface Callback {
        void onStart();
        void onStop();
        void onData(String data);
        void onError(String message);
    }

    interface StringConsumer { void accept(String s); }
    private final Runnable onStart;
    private final Runnable onStop;
    private final StringConsumer onData;
    private final StringConsumer onError;
    private final StringConsumer onStatus;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Отдельный исполнитель для записи, чтобы долгий цикл чтения не блокировал отправку
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private Future<?> task;
    private Socket socket;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Auto reconnect logic
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> autoTask;
    private volatile boolean autoMode = false;
    private volatile boolean autoPaused = false;
    private volatile String targetHost = null;
    private volatile int targetPort = -1;
    private volatile boolean connecting = false;
    private volatile boolean searching = false;

    // --- Framed protocol state for RX ---
    private byte[] rxBuf = new byte[2048];
    private int rxSize = 0;

    private void setSearching(boolean s) {
        if (searching == s) return;
        searching = s;
        if (s) onStart.run(); else onStop.run();
    }

    TcpManager(Runnable onStart, Runnable onStop,
               StringConsumer onData,
               StringConsumer onError,
               StringConsumer onStatus) {
        this.onStart = onStart;
        this.onStop = onStop;
        this.onData = onData;
        this.onError = onError;
        this.onStatus = onStatus;
    }

    synchronized void connect(String host, int port) {
        // Валидация цели
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) return;
        // Не стартуем параллельные попытки и не рвём активное соединение
        if (connecting || isConnected()) return;

        // Очистим предыдущие хвосты, если были
        disconnect();
        connecting = true;
        running.set(true);
        setSearching(true);
        task = executor.submit(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 2000);
                // Соединение установлено — поиск завершён
                setSearching(false);
                if (onStatus != null) onStatus.accept("connected");
                InputStream in = new BufferedInputStream(socket.getInputStream());
                byte[] buf = new byte[512];
                while (running.get()) {
                    int n = in.read(buf);
                    if (n == -1) break;
                    if (n > 0) {
                        feedRx(buf, 0, n);
                        drainFrames();
                    }
                }
            } catch (IOException e) {
                onError.accept(e.getMessage());
            } finally {
                closeQuietly();
                running.set(false);
                connecting = false;
                if (onStatus != null) onStatus.accept("disconnected");
            }
        });
    }

    synchronized void disconnect() {
        running.set(false);
        if (task != null) task.cancel(true);
        closeQuietly();
        connecting = false;
        if (onStatus != null) onStatus.accept("disconnected");
    }

    private void closeQuietly() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
    }

    synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    synchronized boolean connectionActive() { return isConnected(); }

    // Построить кадр управления
    byte[] buildControlFrame(int cmd, int loco, int sw) {
        int c = cmd & 0xFF;
        int l = Math.max(1, Math.min(255, loco));
        int s = Math.max(1, Math.min(255, sw));
        final byte START = 0x7E;
        byte[] payload = new byte[] { (byte) l, (byte) s };
        int len = payload.length;
        byte lenHi = (byte) ((len >> 8) & 0xFF);
        byte lenLo = (byte) (len & 0xFF);
        byte[] crcBuf = new byte[3 + len];
        crcBuf[0] = (byte) c;
        crcBuf[1] = lenHi;
        crcBuf[2] = lenLo;
        if (len > 0) System.arraycopy(payload, 0, crcBuf, 3, len);
        byte crc = crc8(crcBuf, 0, crcBuf.length);
        byte[] frame = new byte[1 + crcBuf.length + 1];
        frame[0] = START;
        System.arraycopy(crcBuf, 0, frame, 1, crcBuf.length);
        frame[frame.length - 1] = crc;
        return frame;
    }

    String controlFrameHex(int cmd, int loco, int sw) {
        byte[] frame = buildControlFrame(cmd, loco, sw);
        return toHex(frame, 0, frame.length);
    }

    // ---- TX: send framed control (cmd=0x00/0x01, data=[loco(1..8), switch(1..6)]) ----
    void sendControl(int cmd, int loco, int sw) {
        if (!isConnected()) return;
        byte[] frame = buildControlFrame(cmd, loco, sw);
        writer.submit(() -> {
            try {
                Socket sck;
                synchronized (this) { sck = socket; }
                if (sck == null || sck.isClosed() || !sck.isConnected()) return;
                sck.getOutputStream().write(frame);
                sck.getOutputStream().flush();
            } catch (IOException e) {
                if (onError != null) onError.accept("TCP TX error: " + e.getMessage());
            }
        });
    }

    // ---- Auto connect API ----
    void enableAutoConnect(String host, int port) {
        targetHost = host;
        targetPort = port;
        autoMode = true;
        if (autoTask != null) { autoTask.cancel(false); autoTask = null; }
        autoTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!autoMode || autoPaused) { setSearching(false); return; }
                String h = targetHost; int p = targetPort;
                if (h == null || h.trim().isEmpty() || p < 1 || p > 65535) return;
                if (isConnected()) { setSearching(false); return; }
                if (!connecting) {
                    setSearching(true);
                    connect(h, p);
                }
            } catch (Throwable t) { /* suppress */ }
        }, 0, 1, TimeUnit.SECONDS);
    }

    void disableAutoConnect() {
        autoMode = false;
        if (autoTask != null) { autoTask.cancel(false); autoTask = null; }
        setSearching(false);
    }

    void pauseAuto(boolean paused) {
        this.autoPaused = paused;
        if (paused) setSearching(false);
    }

    void updateTarget(String host, int port) {
        this.targetHost = host;
        this.targetPort = port;
    }

    // ---- Framed protocol parsing: 0x7E | cmd(1) | len(2 BE) | data(N) | crc8(cmd+len+data) ----
    private void feedRx(byte[] src, int off, int len) {
        ensureCapacity(rxSize + len);
        System.arraycopy(src, off, rxBuf, rxSize, len);
        rxSize += len;
    }

    private void ensureCapacity(int need) {
        if (need <= rxBuf.length) return;
        int cap = rxBuf.length;
        while (cap < need) cap *= 2;
        byte[] nb = new byte[cap];
        System.arraycopy(rxBuf, 0, nb, 0, rxSize);
        rxBuf = nb;
    }

    private void drainFrames() {
        final byte START = 0x7E;
        int i = 0;
        while (true) {
            // Найти стартовый байт
            while (i < rxSize && rxBuf[i] != START) i++;
            if (i >= rxSize) { // всё отброшено
                rxSize = 0;
                return;
            }
            // Сдвигаем буфер так, чтобы кадр начинался с 0
            if (i > 0) {
                System.arraycopy(rxBuf, i, rxBuf, 0, rxSize - i);
                rxSize -= i;
                i = 0;
            }
            // Ждём заголовок минимум 1+1+2+1 = 5 байт (с пустыми данными)
            if (rxSize < 5) return;
            int cmd = rxBuf[1] & 0xFF;
            int len = ((rxBuf[2] & 0xFF) << 8) | (rxBuf[3] & 0xFF);
            int total = 1 + 1 + 2 + len + 1;
            if (len < 0 || len > 4096) { // защита от мусора
                // пропускаем стартовый байт и пытаемся снова
                consume(1);
                continue;
            }
            if (rxSize < total) return; // ждём остальные байты

            // Проверка CRC
            byte crcExpected = rxBuf[total - 1];
            byte crc = crc8(rxBuf, 1, 3 + len); // cmd+lenHi+lenLo+data
            if (crc != crcExpected) {
                // Плохой кадр — пропускаем стартовый и ищем дальше
                consume(1);
                continue;
            }

            // Валидный кадр — обработаем
            if (len == 2) {
                int loco = rxBuf[4] & 0xFF;     // 1..8
                int sw = rxBuf[5] & 0xFF;       // 1..6
                String line = String.format(Locale.US, "cmd=0x%02X loco=%d switch=%d\n", cmd, loco, sw);
                safeOnData(line);
            } else {
                // Незнакомая длина — просто выведем информацию о кадре
                String line = String.format(Locale.US, "cmd=0x%02X len=%d data=%s\n", cmd, len, toHex(rxBuf, 4, len));
                safeOnData(line);
            }

            // Съедаем кадр и продолжаем
            consume(total);
        }
    }

    private void consume(int n) {
        if (n >= rxSize) { rxSize = 0; return; }
        System.arraycopy(rxBuf, n, rxBuf, 0, rxSize - n);
        rxSize -= n;
    }

    private void safeOnData(String s) {
        try { if (onData != null) onData.accept(s); } catch (Exception ignored) {}
    }

    private static byte crc8(byte[] buf, int off, int len) {
        int crc = 0x00;
        for (int i = off; i < off + len; i++) {
            crc ^= (buf[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x80) != 0) crc = ((crc << 1) ^ 0x31) & 0xFF; else crc = (crc << 1) & 0xFF;
            }
        }
        return (byte) (crc & 0xFF);
    }

    private static String toHex(byte[] buf, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            sb.append(String.format(Locale.US, "%02X", buf[off + i] & 0xFF));
            if (i + 1 < len) sb.append(' ');
        }
        return sb.toString();
    }
}
