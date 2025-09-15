package com.example.androidbuttons;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Arrays;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    // Элементы интерфейса
    public EditText valueBaudRate; // Строка для ввода скорости передачи UART
    public EditText valueAddrTCP; // Строка для ввода адреса TCP сервера
    public EditText valuePortTCP; // Строка для ввода порта TCP сервера
    public Switch switchUART; // Переключатель UART
    public Switch switchTCP; // Переключатель TCP
    public TextView textConsole; // Текстовое поле для консоли

    public ExecutorService wifiService = Executors.newSingleThreadExecutor(); // Фоновый поток для TCP клиента
    public Socket wifiSocket; // TCP сокет

    // private UsbManager usbManager;
    // private UsbSerialPort serialPort;
    // private SerialInputOutputManager ioManager;
    // private PendingIntent permissionIntent;
    // private static final String ACTION_USB_PERMISSION = "com.example.androidbuttons.USB_PERMISSION";

    
    
    // private OutputStream tcpOutput; // Выходной поток TCP клиента

    // private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
    //     @Override
    //     public void onReceive(Context context, Intent intent) {
    //         String action = intent.getAction();
    //         if (ACTION_USB_PERMISSION.equals(action)) {
    //             synchronized (this) {
    //                 UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    //                 if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
    //                     if (device != null) {
    //                         connectSerial(device);
    //                     }
    //                 } else {
    //                     Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show();
    //                     switchUART.setChecked(false);
    //                 }
    //             }
    //         } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
    //             disconnectSerial();
    //         } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
    //             if (switchUART.isChecked()) {
    //                 discoverAndConnect();
    //             }
    //         }
    //     }
    // };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Инициализация элементов интерфейса
        valueBaudRate = findViewById(R.id.value_baudRate); // Строка для ввода скорости передачи UART
        valueAddrTCP = findViewById(R.id.value_addrTCP); // Строка для ввода адреса TCP сервера
        valuePortTCP = findViewById(R.id.value_portTCP); // Строка для ввода порта TCP сервера
        switchUART = findViewById(R.id.switch_UART); // Переключатель UART
        switchTCP = findViewById(R.id.switch_TCP); // Переключатель TCP
        textConsole = findViewById(R.id.text_console); // Текстовое поле для консоли

        // textConsole.setMovementMethod(new ScrollingMovementMethod());
        // usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        

        // permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        // IntentFilter filter = new IntentFilter();
        // filter.addAction(ACTION_USB_PERMISSION);
        // filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        // filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        // registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        // Обработчики переключателя TCP
        switchTCP.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) { // Включение TCP
                startWifi();
            } else { // Выключение TCP
                stopWifi();
            }
        });

        // Обработчики переключателя UART
        switchUART.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) { // Включение UART
                startUart();
            } else { // Выключение UART
                stopUart();
            }
        });

    }

    // Запуск Wi-Fi соединения
    private void startWifi() {
        // Адресс точки доступа
        String host = valueAddrTCP.getText().toString();
        // Порт точки доступа
        int port = Integer.parseInt(valuePortTCP.getText().toString());

        // Создание сокета
        wifiService.submit(() -> { // Фоновый поток
            try { // Подключение к TCP серверу
                wifiSocket = new Socket();
                wifiSocket.connect(new InetSocketAddress(host, port), 100); // 100 миллисекунд таймаут
                runOnUiThread(() -> {
                    Toast.makeText(this, "TCP - connect: " + host + ":" + port, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) { // Ошибка подключения
                runOnUiThread(() -> { 
                    Toast.makeText(MainActivity.this, "TCP Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    switchTCP.setChecked(false);
                });
            }
        });
    }

    // Остановка Wi-Fi соединения
    private void stopWifi() {
        if (wifiSocket != null) {
            try {
                wifiSocket.close();
            } catch (IOException ignored) {
            }
            wifiSocket = null;
        }

        Toast.makeText(this, "TCP - disconnect", Toast.LENGTH_SHORT).show();
    }

    // Запуск USB-UART соединения
    private void startUart() {
        Toast.makeText(this, "UART - connect", Toast.LENGTH_SHORT).show();
    }

    // Остановка USB-UART соединения
    private void stopUart() {
        Toast.makeText(this, "UART - disconnect", Toast.LENGTH_SHORT).show();
    }

    // private void startTcpClient() {
    //     tcpExecutor = Executors.newSingleThreadExecutor();
    //     tcpExecutor.submit(() -> {
    //         try {
    //             String host = valueAddrTCP.getText().toString();
    //             int port = Integer.parseInt(valuePortTCP.getText().toString());
    //             tcpSocket = new Socket(host, port);
    //             tcpOutput = tcpSocket.getOutputStream();
    //             InputStream in = tcpSocket.getInputStream();
    //             runOnUiThread(() -> Toast.makeText(MainActivity.this, "TCP Client connected", Toast.LENGTH_SHORT).show());
    //             byte[] buffer = new byte[1024];
    //             int len;
    //             while ((len = in.read(buffer)) != -1 && !Thread.currentThread().isInterrupted()) {
    //                 if (serialPort != null) {
    //                     byte[] data = Arrays.copyOf(buffer, len);
    //                     try {
    //                         serialPort.write(data, 1000);
    //                     } catch (IOException ignored) {
    //                     }
    //                 }
    //             }
    //         } catch (IOException e) {
    //             runOnUiThread(() -> Toast.makeText(MainActivity.this, "TCP Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    //             runOnUiThread(() -> switchTCP.setChecked(false));
    //         }
    //     });
    // }

    // private void stopTcpClient() {
    //     if (tcpExecutor != null) {
    //         tcpExecutor.shutdownNow();
    //         tcpExecutor = null;
    //     }
    //     if (tcpSocket != null) {
    //         try {
    //             tcpSocket.close();
    //         } catch (IOException ignored) {
    //         }
    //         tcpSocket = null;
    //     }
    //     tcpOutput = null;
    // }



    // // Подключение к последовательному порту (UART)
    // private void connectSerial(UsbDevice device) {
    //     // Поиска драйвера для устройства
    //     UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
    //     if (driver == null) {
    //         Toast.makeText(this, "No USB driver", Toast.LENGTH_SHORT).show();
    //         switchUART.setChecked(false);
    //         return;
    //     }
    //     // Открытие соединения
    //     UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
    //     if (connection == null) {
    //         Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show();
    //         switchUART.setChecked(false);
    //         return;
    //     }
    //     serialPort = driver.getPorts().get(0);
    //     try {
    //         serialPort.open(connection);
    //         int baud = Integer.parseInt(valueBaudRate.getText().toString());
    //         serialPort.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
    //         startIoManager();
    //         Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show();
    //     } catch (Exception e) {
    //         Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
    //         switchUART.setChecked(false);
    //     }
    // }

    // private void disconnectSerial() {
    //     stopIoManager();
    //     stopTcpClient();
    //     if (switchTCP != null) {
    //         switchTCP.setChecked(false);
    //     }
    //     if (serialPort != null) {
    //         try {
    //             serialPort.close();
    //         } catch (IOException ignored) {
    //         }
    //         serialPort = null;
    //     }
    //     Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
    // }

    // // Поиск и подключение к последовательному порту (UART)
    // private void discoverAndConnect() {
    //     HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
    //     if (deviceList.isEmpty()) {
    //         Toast.makeText(this, "No USB device found", Toast.LENGTH_SHORT).show();
    //         switchUART.setChecked(false);
    //         return;
    //     }
    //     UsbDevice device = deviceList.values().iterator().next();
    //     if (usbManager.hasPermission(device)) {
    //         connectSerial(device);
    //     } else {
    //         usbManager.requestPermission(device, permissionIntent);
    //     }
    // }

    // // 
    // private void startIoManager() {
    //     if (serialPort != null) {
    //         ioManager = new SerialInputOutputManager(serialPort, dataListener);
    //         Executors.newSingleThreadExecutor().submit(ioManager);
    //     }
    // }

    // //
    // private void stopIoManager() {
    //     if (ioManager != null) {
    //         ioManager.stop();
    //         ioManager = null;
    //     }
    // }


    // private final SerialInputOutputManager.Listener dataListener = new SerialInputOutputManager.Listener() {
    //     @Override
    //     public void onNewData(byte[] data) {
    //         runOnUiThread(() -> {
    //             textConsole.append(new String(data));
    //             int scrollAmount = textConsole.getLayout().getLineTop(textConsole.getLineCount()) - textConsole.getHeight();
    //             if (scrollAmount > 0) textConsole.scrollTo(0, scrollAmount);
    //             else textConsole.scrollTo(0, 0);
    //         });
    //         if (tcpOutput != null) {
    //             try {
    //                 tcpOutput.write(data);
    //                 tcpOutput.flush();
    //             } catch (IOException ignored) {
    //             }
    //         }
    //     }

    //     @Override
    //     public void onRunError(Exception e) {
    //         runOnUiThread(() -> Toast.makeText(MainActivity.this, "IO Error", Toast.LENGTH_SHORT).show());
    //     }
    // };


    // @Override
    // protected void onDestroy() {
    //     super.onDestroy();
    //     unregisterReceiver(usbReceiver);
    //     disconnectSerial();
    // }

    // private void bridgeUartToTcp() {
    //     Executors.newSingleThreadExecutor().submit(() -> {
    //         byte[] buffer = new byte[1024];
    //         while (!Thread.currentThread().isInterrupted() && serialPort != null) {
    //             try {
    //                 int len = serialPort.read(buffer, 1000);
    //                 if (len > 0 && tcpOutput != null) {
    //                     tcpOutput.write(buffer, 0, len);
    //                     tcpOutput.flush();

    //                     // Также выводим в консоль
    //                     final String data = new String(buffer, 0, len);
    //                     runOnUiThread(() -> {
    //                         textConsole.append("[UART→TCP] " + data);
    //                     });
    //                 }
    //             } catch (IOException e) {
    //                 // Игнорируем ошибки чтения/записи
    //             }
    //         }
    //     });
    // }
}