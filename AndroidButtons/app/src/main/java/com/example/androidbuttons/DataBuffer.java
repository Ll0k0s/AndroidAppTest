package com.example.androidbuttons;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;

// Буферизует частые короткие строки и выдаёт их пачками для снижения нагрузки на UI.
class DataBuffer implements AutoCloseable {
    interface StringConsumer { void accept(String s); }

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final int maxFlushBytes;
    private final StringConsumer consumer;
    private final Timer timer = new Timer("ui-buf", true);

    DataBuffer(int maxFlushBytes, StringConsumer consumer) {
        this.maxFlushBytes = Math.max(64, maxFlushBytes);
        this.consumer = consumer;
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() { flush(); }
        }, 100, 100);
    }

    void offer(String s) {
        if (s == null || s.isEmpty()) return;
        queue.offer(s);
    }

    private void flush() {
        if (queue.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        while (!queue.isEmpty() && sb.length() < maxFlushBytes) {
            String s = queue.poll();
            if (s == null) break;
            sb.append(s);
        }
        if (sb.length() > 0 && consumer != null) consumer.accept(sb.toString());
    }

    @Override
    public void close() {
        timer.cancel();
        flush();
    }
}
