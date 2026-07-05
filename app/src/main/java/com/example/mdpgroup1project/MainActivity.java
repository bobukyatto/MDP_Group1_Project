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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

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
    
    // Persistent Map State
    private final String[][] mapData = new String[20][20]; // Stores "START", "OBSTACLE", "TARGET"
    private final float[][] mapRotation = new float[20][20];
    private final Map<String, Long> lastClickTimeMap = new HashMap<>();

    private int targetCount = 0;
    private int currentX = 0;
    private int currentZ = 0;
    private int lastSentX = -9999;
    private int lastSentZ = -9999;
    
    private boolean isCalibrationRunning = false;
    private boolean isSwapped = false;
    
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
        
        tvStatus = findViewById(R.id.tvStatus);
        
        availableAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, availableDevicesNames);
        pairedAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, pairedDevicesNames);

        ViewPager2 viewPager = findViewById(R.id.viewPager);
        TabLayout tabLayout = findViewById(R.id.tabLayout);

        viewPager.setAdapter(new TabAdapter());
        viewPager.setOffscreenPageLimit(3); 
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0: tab.setText("BLUETOOTH"); break;
                case 1: tab.setText("CALIBRATE"); break;
                case 2: tab.setText("MAP"); break;
                case 3: tab.setText("MANUAL"); break;
            }
        }).attach();

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

        commandHandler.post(commandRunnable);
    }

    private class TabAdapter extends RecyclerView.Adapter<TabAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layoutId = 0;
            switch (viewType) {
                case 0: layoutId = R.layout.tab_bluetooth; break;
                case 1: layoutId = R.layout.tab_calibrate; break;
                case 2: layoutId = R.layout.tab_map; break;
                case 3: layoutId = R.layout.tab_manual; break;
            }
            View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
            return new ViewHolder(view, viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) { holder.bind(); }
        @Override
        public int getItemCount() { return 4; }
        @Override
        public int getItemViewType(int position) { return position; }

        class ViewHolder extends RecyclerView.ViewHolder {
            int type;
            ViewHolder(View itemView, int type) { super(itemView); this.type = type; }
            void bind() {
                switch (type) {
                    case 0: setupBluetoothTab(itemView); break;
                    case 1: setupCalibrateTab(itemView); break;
                    case 2: setupMapTab(itemView); break;
                    case 3: setupManualTab(itemView); break;
                }
            }
        }
    }

    private void setupBluetoothTab(View v) {
        ListView lvAvailable = v.findViewById(R.id.lvBluetoothDevices);
        ListView lvPaired = v.findViewById(R.id.lvPairedDevices);
        v.findViewById(R.id.btnScan).setOnClickListener(view -> startDiscovery());
        v.findViewById(R.id.btnSettings).setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        
        lvAvailable.setAdapter(availableAdapter);
        lvPaired.setAdapter(pairedAdapter);

        lvAvailable.setOnItemClickListener((p, view, pos, id) -> {
            BluetoothDevice device = availableDevices.get(pos);
            try { @SuppressLint("MissingPermission") boolean ignored = device.createBond(); } catch (Exception ignored) {}
        });
        lvPaired.setOnItemClickListener((p, view, pos, id) -> connectToDevice(pairedDevicesList.get(pos)));
    }

    private void setupCalibrateTab(View v) {
        EditText etTime = v.findViewById(R.id.etCalibTime);
        EditText etX = v.findViewById(R.id.etCalibX);
        EditText etZ = v.findViewById(R.id.etCalibZ);
        v.findViewById(R.id.btnSendCalib).setOnClickListener(view -> 
            executeTimedCommand(etTime.getText().toString(), etX.getText().toString(), etZ.getText().toString()));
        v.findViewById(R.id.btnCalibFwd).setOnClickListener(view -> executeTimedCommand("1.0", "500", "0"));
        v.findViewById(R.id.btnCalibLeft).setOnClickListener(view -> executeTimedCommand("1.0", "0", "500"));
    }

    private void executeTimedCommand(String t, String x, String z) {
        if (t.isEmpty() || x.isEmpty() || z.isEmpty()) return;
        try {
            int timeMs = (int) (Double.parseDouble(t) * 1000);
            isCalibrationRunning = true;
            currentX = Integer.parseInt(x);
            currentZ = Integer.parseInt(z);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                isCalibrationRunning = false;
                currentX = 0; currentZ = 0;
                Toast.makeText(this, "Calibration complete", Toast.LENGTH_SHORT).show();
            }, timeMs);
        } catch (Exception e) { Toast.makeText(this, "Invalid input", Toast.LENGTH_SHORT).show(); }
    }

    private void setupMapTab(View v) {
        GridLayout mapGrid = v.findViewById(R.id.mapGrid);
        RadioGroup rgMode = v.findViewById(R.id.rgSelectionMode);
        MaterialSwitch swReverse = v.findViewById(R.id.swAllowReverse);
        v.findViewById(R.id.btnResetGrid).setOnClickListener(view -> resetGrid(mapGrid));
        v.findViewById(R.id.btnSimulate).setOnClickListener(view -> sendCommand(constructMapMessage(true, swReverse.isChecked())));
        v.findViewById(R.id.btnGo).setOnClickListener(view -> sendCommand(constructMapMessage(false, swReverse.isChecked())));

        mapGrid.post(() -> {
            int cellSize = mapGrid.getWidth() / 20;
            mapGrid.removeAllViews();
            for (int r = 0; r < 20; r++) {
                for (int c = 0; c < 20; c++) {
                    ImageView cell = new ImageView(this);
                    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                    params.width = cellSize - 2; params.height = cellSize - 2;
                    params.setMargins(1, 1, 1, 1);
                    cell.setLayoutParams(params);
                    cell.setBackgroundColor(Color.LTGRAY);
                    cell.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    
                    // Restore visual state from persistent mapData
                    String type = mapData[r][c];
                    if (type != null) {
                        if ("START".equals(type)) cell.setImageResource(R.drawable.ic_car);
                        else if ("OBSTACLE".equals(type)) cell.setImageResource(R.drawable.ic_obstacle);
                        else if ("TARGET".equals(type)) cell.setImageResource(R.drawable.ic_target);
                        cell.setRotation(mapRotation[r][c]);
                    }

                    final int row = r; final int col = c;
                    cell.setOnClickListener(view -> handleCellInteraction(cell, row, col, rgMode));
                    mapGrid.addView(cell);
                }
            }
        });
    }

    private void handleCellInteraction(ImageView cell, int r, int c, RadioGroup rgMode) {
        long now = System.currentTimeMillis();
        String key = r + "," + c;
        long last = lastClickTimeMap.getOrDefault(key, 0L);
        lastClickTimeMap.put(key, now);

        if (now - last < 300) { // Double Tap
            if ("TARGET".equals(mapData[r][c])) targetCount--;
            mapData[r][c] = null; mapRotation[r][c] = 0;
            cell.setImageDrawable(null); cell.setRotation(0);
            return;
        }

        int id = rgMode.getCheckedRadioButtonId();
        if (mapData[r][c] != null && id != R.id.rbTarget) {
            mapRotation[r][c] = (mapRotation[r][c] + 90) % 360;
            cell.setRotation(mapRotation[r][c]);
            return;
        }
        if (mapData[r][c] != null) return;

        if (id == R.id.rbObstacle) {
            cell.setImageResource(R.drawable.ic_obstacle); mapData[r][c] = "OBSTACLE";
        } else if (id == R.id.rbVehicle) {
            clearPreviousStart(); 
            cell.setImageResource(R.drawable.ic_car); mapData[r][c] = "START";
        } else if (id == R.id.rbTarget) {
            if (targetCount < 6) {
                cell.setImageResource(R.drawable.ic_target); mapData[r][c] = "TARGET"; targetCount++;
            } else Toast.makeText(this, "Max 6 targets", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearPreviousStart() {
        for (int i = 0; i < 20; i++) for (int j = 0; j < 20; j++)
            if ("START".equals(mapData[i][j])) { mapData[i][j] = null; mapRotation[i][j] = 0; }
    }

    private void resetGrid(GridLayout grid) {
        for (int r = 0; r < 20; r++) for (int c = 0; c < 20; c++) {
            mapData[r][c] = null; mapRotation[r][c] = 0;
            ImageView v = (ImageView) grid.getChildAt(r * 20 + c);
            if(v != null) { v.setImageDrawable(null); v.setRotation(0); }
        }
        targetCount = 0; lastClickTimeMap.clear();
    }

    private String constructMapMessage(boolean isSim, boolean allowReverse) {
        StringBuilder sb = new StringBuilder("MAP;");
        String startStr = "";
        List<String> obsStrs = new ArrayList<>();
        for (int r = 0; r < 20; r++) {
            for (int c = 0; c < 20; c++) {
                if ("START".equals(mapData[r][c])) startStr = "START:" + c + "," + (19 - r) + "," + getFacing(mapRotation[r][c]);
                else if ("OBSTACLE".equals(mapData[r][c])) obsStrs.add("OBS:" + c + "," + (19 - r) + "," + getFacing(mapRotation[r][c]));
            }
        }
        if (startStr.isEmpty()) return "ERR:No Start Location";
        sb.append(startStr);
        for (String obs : obsStrs) sb.append(";").append(obs);
        sb.append(";MODE:").append(allowReverse ? "RS" : "DUBINS").append(";").append(isSim ? "SIM" : "GO");
        return sb.toString() + "\n";
    }

    private String getFacing(float rotation) {
        int r = (int) rotation % 360; if (r < 0) r += 360;
        if (r == 0) return "N"; if (r == 90) return "E"; if (r == 180) return "S"; if (r == 270) return "W";
        return "N";
    }

    private void setupManualTab(View v) {
        JoystickView joyL = v.findViewById(R.id.joystickLeft);
        JoystickView joyR = v.findViewById(R.id.joystickRight);
        updateJoystickRoles(joyL, joyR);
        v.findViewById(R.id.btnSwap).setOnClickListener(view -> { isSwapped = !isSwapped; updateJoystickRoles(joyL, joyR); });
        v.findViewById(R.id.btnStop).setOnClickListener(view -> { currentX = 0; currentZ = 0; sendCommand("0,0\n"); });
        v.findViewById(R.id.btnManualAuto).setOnClickListener(view -> sendCommand("auto\n"));
        v.findViewById(R.id.btnManualStart).setOnClickListener(view -> sendCommand("start\n"));
    }

    private void updateJoystickRoles(JoystickView left, JoystickView right) {
        if (!isSwapped) {
            left.setLabel("VELOCITY (X)"); left.setMode(true, false);
            left.setListener(new JoystickView.JoystickListener() {
                @Override public void onMoved(float x, float y) { currentX = (int)(y * 1000); }
                @Override public void onReleased() { 
                    currentX = 0; 
                    currentZ = 0; // Reset steering when car stops moving
                }
            });
            right.setLabel("STEERING (Z)"); right.setMode(false, true);
            right.setListener(new JoystickView.JoystickListener() {
                @Override public void onMoved(float x, float y) { currentZ = (int)(-x * 1000); } // Negated for Positive=Left
                @Override public void onReleased() { currentZ = 0; } // Auto-center steering
            });
        } else {
            left.setLabel("STEERING (Z)"); left.setMode(false, true);
            left.setListener(new JoystickView.JoystickListener() {
                @Override public void onMoved(float x, float y) { currentZ = (int)(-x * 1000); } // Negated for Positive=Left
                @Override public void onReleased() { currentZ = 0; }
            });
            right.setLabel("VELOCITY (X)"); right.setMode(true, false);
            right.setListener(new JoystickView.JoystickListener() {
                @Override public void onMoved(float x, float y) { currentX = (int)(y * 1000); }
                @Override public void onReleased() { 
                    currentX = 0; 
                    currentZ = 0; 
                }
            });
        }
    }

    private final Runnable commandRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentX != lastSentX || currentZ != lastSentZ) {
                sendCommand(currentX + "," + currentZ + "\n");
                lastSentX = currentX; lastSentZ = currentZ;
            }
            commandHandler.postDelayed(this, 50);
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (bluetoothSocket != null && !bluetoothSocket.isConnected()) handleDisconnect();
            else if (bluetoothSocket != null) watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL);
        }
    };

    private void handleDisconnect() {
        watchdogHandler.removeCallbacks(watchdogRunnable);
        try { if (bluetoothSocket != null) bluetoothSocket.close(); } catch (IOException ignored) {}
        bluetoothSocket = null; outputStream = null;
        runOnUiThread(() -> { tvStatus.setText("Status: Disconnected"); tvStatus.setTextColor(Color.RED); });
    }

    @SuppressLint("MissingPermission")
    private void updatePairedDevices() {
        pairedDevicesList.clear(); pairedDevicesNames.clear();
        Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice d : paired) { pairedDevicesList.add(d); pairedDevicesNames.add(d.getName() + "\n" + d.getAddress()); }
        if(pairedAdapter != null) pairedAdapter.notifyDataSetChanged();
    }

    @SuppressLint("MissingPermission")
    private void startDiscovery() {
        if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
        availableDevices.clear(); availableDevicesNames.clear();
        if(availableAdapter != null) availableAdapter.notifyDataSetChanged();
        bluetoothAdapter.startDiscovery();
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        new Thread(() -> {
            try {
                if (bluetoothSocket != null) { try { bluetoothSocket.close(); } catch (IOException ignored) {} bluetoothSocket = null; }
                bluetoothAdapter.cancelDiscovery();
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                runOnUiThread(() -> {
                    tvStatus.setText("Status: Connected to " + device.getName());
                    tvStatus.setTextColor(Color.GREEN);
                    watchdogHandler.post(watchdogRunnable);
                });
            } catch (IOException e) {
                try {
                    bluetoothSocket = (BluetoothSocket) device.getClass().getMethod("createRfcommSocket", new Class[]{int.class}).invoke(device, 1);
                    bluetoothSocket.connect();
                    outputStream = bluetoothSocket.getOutputStream();
                    runOnUiThread(() -> {
                        tvStatus.setText("Status: Connected (Alt) to " + device.getName());
                        tvStatus.setTextColor(Color.GREEN);
                        watchdogHandler.post(watchdogRunnable);
                    });
                } catch (Exception e2) {
                    handleDisconnect();
                    runOnUiThread(() -> { tvStatus.setText("Status: Connection Failed"); tvStatus.setTextColor(Color.RED); });
                }
            }
        }).start();
    }

    private void sendCommand(String command) {
        if (outputStream == null) return;
        try { outputStream.write(command.getBytes()); Log.d(TAG, "Sent: " + command.trim()); }
        catch (IOException e) { Log.e(TAG, "Error sending", e); handleDisconnect(); }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getName() != null && !availableDevices.contains(device)) {
                    availableDevices.add(device); availableDevicesNames.add(device.getName() + "\n" + device.getAddress());
                    if(availableAdapter != null) availableAdapter.notifyDataSetChanged();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                tvStatus.setText("Status: Scanning..."); tvStatus.setTextColor(Color.GRAY);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (bluetoothSocket == null || !bluetoothSocket.isConnected()) {
                    tvStatus.setText("Status: Disconnected"); tvStatus.setTextColor(Color.RED);
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_BONDED) updatePairedDevices();
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) { handleDisconnect(); }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
        try { if (bluetoothSocket != null) bluetoothSocket.close(); } catch (IOException ignored) {}
    }
}
