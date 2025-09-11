package com.example.androidbuttons;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private EditText valueBaudRate;
    private Switch switchUART;
    private TextView textConsole;

    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private SerialInputOutputManager ioManager;
    private PendingIntent permissionIntent;
    private static final String ACTION_USB_PERMISSION = "com.example.androidbuttons.USB_PERMISSION";

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            connectSerial(device);
                        }
                    } else {
                        Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show();
                        switchUART.setChecked(false);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                disconnectSerial();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (switchUART.isChecked()) {
                    discoverAndConnect();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        valueBaudRate = findViewById(R.id.value_baudRate);
        switchUART = findViewById(R.id.switch_UART);
        textConsole = findViewById(R.id.text_console);
        textConsole.setMovementMethod(new ScrollingMovementMethod());

        permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);

        switchUART.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                discoverAndConnect();
            } else {
                disconnectSerial();
            }
        });
    }

    private void discoverAndConnect() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            Toast.makeText(this, "No USB device found", Toast.LENGTH_SHORT).show();
            switchUART.setChecked(false);
            return;
        }
        UsbDevice device = deviceList.values().iterator().next();
        if (usbManager.hasPermission(device)) {
            connectSerial(device);
        } else {
            usbManager.requestPermission(device, permissionIntent);
        }
    }

    private void connectSerial(UsbDevice device) {
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            Toast.makeText(this, "No USB driver", Toast.LENGTH_SHORT).show();
            switchUART.setChecked(false);
            return;
        }
        UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
        if (connection == null) {
            Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show();
            switchUART.setChecked(false);
            return;
        }
        serialPort = driver.getPorts().get(0);
        try {
            serialPort.open(connection);
            int baud = Integer.parseInt(valueBaudRate.getText().toString());
            serialPort.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            startIoManager();
            Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            switchUART.setChecked(false);
        }
    }

    private void startIoManager() {
        if (serialPort != null) {
            ioManager = new SerialInputOutputManager(serialPort, dataListener);
            Executors.newSingleThreadExecutor().submit(ioManager);
        }
    }

    private void stopIoManager() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
    }

    private final SerialInputOutputManager.Listener dataListener = new SerialInputOutputManager.Listener() {
        @Override
        public void onNewData(byte[] data) {
            runOnUiThread(() -> {
                textConsole.append(new String(data));
                int scrollAmount = textConsole.getLayout().getLineTop(textConsole.getLineCount()) - textConsole.getHeight();
                if (scrollAmount > 0) textConsole.scrollTo(0, scrollAmount);
                else textConsole.scrollTo(0, 0);
            });
        }

        @Override
        public void onRunError(Exception e) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "IO Error", Toast.LENGTH_SHORT).show());
        }
    };

    private void disconnectSerial() {
        stopIoManager();
        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (IOException ignored) {
            }
            serialPort = null;
        }
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
        disconnectSerial();
    }
}