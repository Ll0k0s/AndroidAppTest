package com.example.androidbuttons;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> task;
    private Socket socket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    // Управление показом индикатора: защищаем от повторных вызовов
    private final Object uiLock = new Object();
    private boolean progressShown = false;

    TcpManager(Runnable onStart, Runnable onStop,
               StringConsumer onData,
               StringConsumer onError) {
        this.onStart = onStart;
        this.onStop = onStop;
        this.onData = onData;
        this.onError = onError;
    }

    synchronized void connect(String host, int port) {
        disconnect();
        running.set(true);
        showProgress();
        task = executor.submit(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 2000);
                // Соединение установлено — скрываем индикатор загрузки,
                // далее идёт уже рабочий режим чтения данных
                hideProgress();
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
                // На случай, если произошла ошибка ещё до установки соединения,
                // убедимся, что индикатор скрыт
                hideProgress();
                closeQuietly();
                running.set(false);
            }
        });
    }

    synchronized void disconnect() {
        running.set(false);
        if (task != null) task.cancel(true);
        closeQuietly();
        hideProgress();
    }

    private void closeQuietly() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
    }

    private void showProgress() {
        synchronized (uiLock) {
            if (progressShown) return;
            progressShown = true;
        }
        try { onStart.run(); } catch (Throwable ignored) {}
    }

    private void hideProgress() {
        synchronized (uiLock) {
            if (!progressShown) return;
            progressShown = false;
        }
        try { onStop.run(); } catch (Throwable ignored) {}
    }
}
