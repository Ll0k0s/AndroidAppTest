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
                uiBuffer.offer("[USB-BC] permission: granted=" + granted + ", device=" + (device != null ? device.getDeviceName() : "null") + "\n");
                if (granted) {
                    uiBuffer.offer("[UART] USB permission GRANTED by user\n");
                    runOnUiThread(() -> toast("Доступ к USB-устройству предоставлен"));
                }
                usbUartManager.onUsbPermissionResult(granted, device);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                android.hardware.usb.UsbDevice device = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice.class);
                } else {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }
                uiBuffer.offer("[USB-BC] ATTACHED: device=" + (device != null ? device.getDeviceName() : "null") + "\n");
                if (binding.switchUART.isChecked()) {
                    int baud;
                    try { baud = Integer.parseInt(String.valueOf(binding.valueBaudRate.getText())); } catch (Exception e) { baud = 9600; }
                    usbUartManager.connect(baud);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                android.hardware.usb.UsbDevice device = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice.class);
                } else {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }
                uiBuffer.offer("[USB-BC] DETACHED: device=" + (device != null ? device.getDeviceName() : "null") + "\n");
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
        error -> runOnUiThread(() -> toast("TCP error: " + error))
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
    data -> uiBuffer.offer("[UART→] " + data),
    error -> runOnUiThread(() -> toast("UART error: " + error)),
    status -> uiBuffer.offer("[UART] " + status + "\n")
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

        // Switches
        binding.switchTCP.setOnCheckedChangeListener((b, checked) -> {
            if (checked) {
                String host = String.valueOf(binding.valueAddrTCP.getText()).trim();
                String portStr = String.valueOf(binding.valuePortTCP.getText()).trim();
                int port;
                try { port = Integer.parseInt(portStr); } catch (Exception e) { port = -1; }
                if (host.isEmpty() || port < 1 || port > 65535) {
                    toast("Invalid TCP address/port");
                    binding.switchTCP.setChecked(false);
                    return;
                }
                tcpManager.connect(host, port);
            } else {
                tcpManager.disconnect();
            }
        });

        binding.switchUART.setOnCheckedChangeListener((b, checked) -> {
            if (checked) {
                int baud;
                try { baud = Integer.parseInt(String.valueOf(binding.valueBaudRate.getText())); } catch (Exception e) { baud = 9600; }
                uiBuffer.offer("[UART-UI] toggle=ON baud=" + baud + "\n");
                usbUartManager.connect(baud);
            } else {
                uiBuffer.offer("[UART-UI] toggle=OFF\n");
                usbUartManager.disconnect("user-toggle-off");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tcpManager.disconnect();
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
