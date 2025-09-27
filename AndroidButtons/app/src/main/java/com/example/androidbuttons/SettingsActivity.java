package com.example.androidbuttons;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.appcompat.app.AppCompatActivity;

import com.example.androidbuttons.databinding.ActivitySettingsBinding;

public class SettingsActivity extends AppCompatActivity {
    private ActivitySettingsBinding binding;
    private final java.util.Timer timer = new java.util.Timer("settings-console", true);
    private final StringBuilder consoleRemainder = new StringBuilder();
    private final java.util.Timer statusTimer = new java.util.Timer("settings-status", true);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Консоль делаем прокручиваемой
        binding.textConsole.setMovementMethod(new ScrollingMovementMethod());

        // Инициализируем поля из SharedPreferences
        android.content.SharedPreferences prefs = getSharedPreferences(AppState.PREFS_NAME, MODE_PRIVATE);
        String host = prefs.getString(AppState.KEY_TCP_HOST, "192.168.2.6");
        int port = prefs.getInt(AppState.KEY_TCP_PORT, 9000);
        int baud = prefs.getInt(AppState.KEY_UART_BAUD, 115200);
        binding.valueAddrTCP.setText(host);
        binding.valuePortTCP.setText(String.valueOf(port));
        binding.valueBaudRate.setText(String.valueOf(baud));

        // Сохраняем изменения полей в SharedPreferences для реакции MainActivity
        binding.valueAddrTCP.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString(AppState.KEY_TCP_HOST, String.valueOf(s).trim()).apply();
            }
        });
        binding.valuePortTCP.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                try {
                    int p = Integer.parseInt(String.valueOf(s).trim());
                    prefs.edit().putInt(AppState.KEY_TCP_PORT, p).apply();
                } catch (Exception ignored) {}
            }
        });
        binding.valueBaudRate.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                try {
                    int b = Integer.parseInt(String.valueOf(s).trim());
                    prefs.edit().putInt(AppState.KEY_UART_BAUD, b).apply();
                } catch (Exception ignored) {}
            }
        });

        // Наполняем spinner значениями Loco1..Loco8
        String[] locoItems = new String[8];
        for (int i = 0; i < 8; i++) locoItems[i] = "Loco" + (i + 1);
        ArrayAdapter<String> locoAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                locoItems
        );
        locoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerNum.setAdapter(locoAdapter);
        binding.spinnerNum.setSelection(Math.max(0, AppState.selectedLoco.get() - 1));
        binding.spinnerNum.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AppState.selectedLoco.set(position + 1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { /* keep prev */ }
        });

        // Периодически сливаем очередь лога в text_console
        timer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                while (!AppState.consoleQueue.isEmpty()) {
                    String s = AppState.consoleQueue.poll();
                    if (s == null) break;
                    sb.append(s);
                }
                if (sb.length() > 0) {
                    String out = sb.toString();
                    runOnUiThread(() -> appendColored(out));
                }
            }
        }, 200, 200);

        // Обновление индикаторов статуса TCP/UART
        statusTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override public void run() {
                runOnUiThread(() -> {
                    binding.switchUARTIndicator.setChecked(AppState.uartConnected);
                    binding.progressBarUARTIndicator.setVisibility(AppState.uartConnecting ? View.VISIBLE : View.GONE);
                    binding.switchTCPIndicator.setChecked(AppState.tcpConnected);
                    binding.progressBarTCPIndicator.setVisibility(AppState.tcpConnecting ? View.VISIBLE : View.GONE);
                });
            }
        }, 200, 200);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timer.cancel();
        statusTimer.cancel();
    }

    private void appendColored(String text) {
        consoleRemainder.append(text);
        int idx;
        while ((idx = indexOfNewline(consoleRemainder)) >= 0) {
            String line = consoleRemainder.substring(0, idx + 1);
            consoleRemainder.delete(0, idx + 1);

            int color = -1;
            int removeLen = 0;
            if (line.startsWith("[UART→]")) {
                color = 0xFF90EE90; removeLen = "[UART→]".length();
            } else if (line.startsWith("[UART←]")) {
                color = 0xFF006400; removeLen = "[UART←]".length();
            } else if (line.startsWith("[#TCP_TX#]")) {
                color = 0xFF87CEFA; removeLen = "[#TCP_TX#]".length();
            } else if (line.startsWith("[#TCP_RX#]")) {
                color = 0xFF0000FF; removeLen = "[#TCP_RX#]".length();
            }

            if (removeLen > 0 && removeLen <= line.length()) {
                line = line.substring(removeLen);
            }
            if (color != -1) {
                SpannableString ss = new SpannableString(line);
                ss.setSpan(new ForegroundColorSpan(color), 0, ss.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
            if (sb.charAt(i) == '\n') return i;
        }
        return -1;
    }
}
