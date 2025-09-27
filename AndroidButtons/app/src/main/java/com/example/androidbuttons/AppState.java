package com.example.androidbuttons;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.LinkedBlockingQueue;

public final class AppState {
    private AppState() {}
    public static final AtomicInteger selectedLoco = new AtomicInteger(1);
    // Очередь логов для передачи между экранами
    public static final LinkedBlockingQueue<String> consoleQueue = new LinkedBlockingQueue<>();

    // SharedPreferences: ключи настроек
    public static final String PREFS_NAME = "androidbuttons_prefs";
    public static final String KEY_TCP_HOST = "tcp_host";
    public static final String KEY_TCP_PORT = "tcp_port";
    public static final String KEY_UART_BAUD = "uart_baud";

    // Статусы подключения
    public static volatile boolean uartConnecting = false;
    public static volatile boolean uartConnected = false;
    public static volatile boolean tcpConnecting = false;
    public static volatile boolean tcpConnected = false;
}
