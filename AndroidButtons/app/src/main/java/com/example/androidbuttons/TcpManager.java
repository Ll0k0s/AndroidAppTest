package com.example.androidbuttons;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
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
                    if (n > 0) onData.accept(new String(buf, 0, n));
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
}
