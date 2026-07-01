package com.example.mdpgroup1project;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;

    private final List<BluetoothDevice> availableDevices = new ArrayList<>();
    private final List<String> availableDevicesNames = new ArrayList<>();
    private ArrayAdapter<String> availableAdapter;

    private final List<BluetoothDevice> pairedDevicesList = new ArrayList<>();
    private final List<String> pairedDevicesNames = new ArrayList<>();
    private ArrayAdapter<String> pairedAdapter;

    private TextView tvStatus;
    private GridLayout mapGrid;
    private RadioGroup rgSelectionMode;
    private final ImageView[][] gridCells = new ImageView[20][20];
    private final Map<ImageView, Long> lastClickTimeMap = new HashMap<>();

    private int targetCount = 0;
    private int currentX = 0;
    private int currentZ = 0;
    private int lastSentX = -9999;
    private int lastSentZ = -9999;
    
    private boolean isForward = false, isBackward = false, isLeft = false, isRight = false;
    private long fwdStart = 0, bwdStart = 0, leftStart = 0, rightStart = 0;
    
    private final Handler commandHandler = new Handler(Looper.getMainLooper());
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private static final int WATCHDOG_INTERVAL = 2000;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialize UI
        tvStatus = findViewById(R.id.tvStatus);
        ListView lvAvailable = findViewById(R.id.lvBluetoothDevices);
        ListView lvPaired = findViewById(R.id.lvPairedDevices);
        Button btnScan = findViewById(R.id.btnScan);
        Button btnSettings = findViewById(R.id.btnSettings);
        Button btnForward = findViewById(R.id.btnForward);
        Button btnBackward = findViewById(R.id.btnBackward);
        Button btnLeft = findViewById(R.id.btnLeft);
        Button btnRight = findViewById(R.id.btnRight);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnResetGrid = findViewById(R.id.btnResetGrid);
        Button btnAuto = findViewById(R.id.btnAuto);
        Button btnStart = findViewById(R.id.btnStart);
        mapGrid = findViewById(R.id.mapGrid);
        rgSelectionMode = findViewById(R.id.rgSelectionMode);

        setupGrid();

        availableAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, availableDevicesNames);
        lvAvailable.setAdapter(availableAdapter);

        pairedAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, pairedDevicesNames);
        lvPaired.setAdapter(pairedAdapter);

        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        updatePairedDevices();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(receiver, filter);

        btnScan.setOnClickListener(v -> startDiscovery());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));

        lvAvailable.setOnItemClickListener((parent, view, position, id) -> availableDevices.get(position).createBond());
        lvPaired.setOnItemClickListener((parent, view, position, id) -> connectToDevice(pairedDevicesList.get(position)));

        btnResetGrid.setOnClickListener(v -> resetGrid());

        setupMovementButton(btnForward, "f");
        setupMovementButton(btnBackward, "b");
        setupMovementButton(btnLeft, "l");
        setupMovementButton(btnRight, "r");
        
        btnStop.setOnClickListener(v -> {
            currentX = 0;
            currentZ = 0;
            sendCommand("0,0\n");
        });

        btnAuto.setOnClickListener(v -> sendCommand("auto\n"));
        btnStart.setOnClickListener(v -> sendCommand("start\n"));

        commandHandler.post(commandRunnable);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupMovementButton(Button btn, String type) {
        btn.setOnTouchListener((v, event) -> {
            long now = System.currentTimeMillis();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (type.equals("f")) { isForward = true; fwdStart = now; }
                    else if (type.equals("b")) { isBackward = true; bwdStart = now; }
                    else if (type.equals("l")) { isLeft = true; leftStart = now; }
                    else if (type.equals("r")) { isRight = true; rightStart = now; }
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (type.equals("f")) isForward = false;
                    else if (type.equals("b")) isBackward = false;
                    else if (type.equals("l")) isLeft = false;
                    else if (type.equals("r")) isRight = false;
                    v.setPressed(false);
                    return true;
            }
            return false;
        });
    }

    private final Runnable commandRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            
            if (isForward) {
                int accel = (int)((now - fwdStart) / 150) * 15;
                currentX = Math.min(1000, currentX + 50 + accel);
            } else if (isBackward) {
                int accel = (int)((now - bwdStart) / 150) * 15;
                currentX = Math.max(-1000, currentX - 50 - accel);
            } else {
                currentX = 0;
            }

            if (isLeft) {
                int accel = (int)((now - leftStart) / 150) * 15;
                currentZ = Math.min(1000, currentZ + 50 + accel);
            } else if (isRight) {
                int accel = (int)((now - rightStart) / 150) * 15;
                currentZ = Math.max(-1000, currentZ - 50 - accel);
            }

            if (currentX != lastSentX || currentZ != lastSentZ) {
                sendCommand(currentX + "," + currentZ + "\n");
                lastSentX = currentX;
                lastSentZ = currentZ;
            }
            
            commandHandler.postDelayed(this, 50);
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (bluetoothSocket != null) {
                if (!bluetoothSocket.isConnected()) {
                    handleDisconnect();
                } else {
                    // Send a "keep-alive" ping to check if the link is truly active
                    // The RPi should ignore empty lines or single newlines
                    sendCommand("\n");
                    watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL);
                }
            }
        }
    };

    private void handleDisconnect() {
        watchdogHandler.removeCallbacks(watchdogRunnable);
        try { if (bluetoothSocket != null) bluetoothSocket.close(); } catch (IOException ignored) {}
        bluetoothSocket = null;
        outputStream = null;
        runOnUiThread(() -> {
            tvStatus.setText("Status: Disconnected");
            tvStatus.setTextColor(Color.RED);
        });
    }

    private void setupGrid() {
        mapGrid.post(() -> {
            int totalWidth = mapGrid.getWidth();
            int cellSize = totalWidth / 20;
            mapGrid.removeAllViews();
            for (int r = 0; r < 20; r++) {
                for (int c = 0; c < 20; c++) {
                    ImageView cell = new ImageView(this);
                    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                    params.width = cellSize - 2;
                    params.height = cellSize - 2;
                    params.setMargins(1, 1, 1, 1);
                    cell.setLayoutParams(params);
                    cell.setBackgroundColor(Color.LTGRAY);
                    cell.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    final int row = r;
                    final int col = c;
                    cell.setOnClickListener(v -> handleCellInteraction(cell, row, col));
                    gridCells[r][c] = cell;
                    mapGrid.addView(cell);
                }
            }
        });
    }

    private void handleCellInteraction(ImageView cell, int r, int c) {
        long currentTime = System.currentTimeMillis();
        long lastClickTime = lastClickTimeMap.getOrDefault(cell, 0L);
        lastClickTimeMap.put(cell, currentTime);

        if (currentTime - lastClickTime < 300) {
            if ("TARGET".equals(cell.getTag())) targetCount--;
            cell.setImageDrawable(null);
            cell.setTag(null);
            cell.setRotation(0);
            return;
        }

        int checkedId = rgSelectionMode.getCheckedRadioButtonId();
        Object tag = cell.getTag();

        if ("OBSTACLE".equals(tag)) {
            cell.setRotation(cell.getRotation() + 90);
            return;
        }

        if (tag != null) return;

        if (checkedId == R.id.rbObstacle) {
            cell.setImageResource(R.drawable.ic_obstacle);
            cell.setTag("OBSTACLE");
        } else if (checkedId == R.id.rbVehicle) {
            clearTag("START");
            cell.setImageResource(R.drawable.ic_car);
            cell.setTag("START");
        } else if (checkedId == R.id.rbTarget) {
            if (targetCount < 6) {
                cell.setImageResource(R.drawable.ic_target);
                cell.setTag("TARGET");
                targetCount++;
            } else {
                Toast.makeText(this, "Max 6 targets", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void clearTag(String targetTag) {
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 20; j++) {
                if (targetTag.equals(gridCells[i][j].getTag())) {
                    gridCells[i][j].setImageDrawable(null);
                    gridCells[i][j].setTag(null);
                }
            }
        }
    }

    private void resetGrid() {
        for (int r = 0; r < 20; r++) {
            for (int c = 0; c < 20; c++) {
                gridCells[r][c].setImageDrawable(null);
                gridCells[r][c].setTag(null);
                gridCells[r][c].setRotation(0);
            }
        }
        targetCount = 0;
        lastClickTimeMap.clear();
    }

    @SuppressLint("MissingPermission")
    private void updatePairedDevices() {
        pairedDevicesList.clear();
        pairedDevicesNames.clear();
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : pairedDevices) {
            pairedDevicesList.add(device);
            pairedDevicesNames.add(device.getName() + "\n" + device.getAddress());
        }
        pairedAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission")
    private void startDiscovery() {
        if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        availableDevices.clear();
        availableDevicesNames.clear();
        availableAdapter.notifyDataSetChanged();
        bluetoothAdapter.startDiscovery();
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        new Thread(() -> {
            try {
                if (bluetoothSocket != null) {
                    try { bluetoothSocket.close(); } catch (IOException ignored) {}
                }
                
                // Cancel discovery before connecting is mandatory
                bluetoothAdapter.cancelDiscovery();
                // Add a tiny delay to allow the adapter to settle
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}

                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                
                runOnUiThread(() -> {
                    tvStatus.setText("Status: Connected to " + device.getName());
                    tvStatus.setTextColor(Color.GREEN);
                    Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show();
                    
                    watchdogHandler.removeCallbacks(watchdogRunnable);
                    watchdogHandler.post(watchdogRunnable);
                });
            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                // Try fallback connection if standard fails
                try {
                    bluetoothSocket = (BluetoothSocket) device.getClass()
                            .getMethod("createRfcommSocket", new Class[]{int.class})
                            .invoke(device, 1);
                    bluetoothSocket.connect();
                    outputStream = bluetoothSocket.getOutputStream();
                    runOnUiThread(() -> {
                        tvStatus.setText("Status: Connected (Alt) to " + device.getName());
                        tvStatus.setTextColor(Color.GREEN);
                        watchdogHandler.removeCallbacks(watchdogRunnable);
                        watchdogHandler.post(watchdogRunnable);
                    });
                } catch (Exception e2) {
                    handleDisconnect();
                    runOnUiThread(() -> {
                        tvStatus.setText("Status: Connection Failed");
                        tvStatus.setTextColor(Color.RED);
                    });
                }
            }
        }).start();
    }

    private void sendCommand(String command) {
        if (outputStream == null) return;
        try {
            outputStream.write(command.getBytes());
        } catch (IOException e) {
            Log.e(TAG, "Error sending", e);
            handleDisconnect();
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getName() != null && !availableDevices.contains(device)) {
                    availableDevices.add(device);
                    availableDevicesNames.add(device.getName() + "\n" + device.getAddress());
                    availableAdapter.notifyDataSetChanged();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                tvStatus.setText("Status: Scanning...");
                tvStatus.setTextColor(Color.GRAY);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (bluetoothSocket == null || !bluetoothSocket.isConnected()) {
                    tvStatus.setText("Status: Disconnected");
                    tvStatus.setTextColor(Color.RED);
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_BONDED) updatePairedDevices();
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                handleDisconnect();
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
        try { if (bluetoothSocket != null) bluetoothSocket.close(); } catch (IOException ignored) {}
    }
}
