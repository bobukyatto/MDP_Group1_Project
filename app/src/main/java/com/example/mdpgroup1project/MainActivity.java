package com.example.mdpgroup1project;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ScrollView;
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

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    // Custom SPP UUID advertised by the Pi's "MDP-Server" service (nanocar_control_v5.py).
    private static final UUID SPP_UUID = UUID.fromString("94f39d29-7d6d-437d-973b-fba39e49d4ee");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private Thread readThread;

    private TextView tvRobotLog;
    private ScrollView svRobotLog;
    private TextView tvLastDetected;
    private TextView tvRobotLogMap;
    private ScrollView svRobotLogMap;
    private TextView tvLastDetectedMap;
    private static final int MAX_LOG_LINES = 300;
    private final List<String> robotLogLines = new ArrayList<>();

    private final List<BluetoothDevice> availableDevices = new ArrayList<>();
    private final List<String> availableDevicesNames = new ArrayList<>();
    private ArrayAdapter<String> availableAdapter;

    private final List<BluetoothDevice> pairedDevicesList = new ArrayList<>();
    private final List<String> pairedDevicesNames = new ArrayList<>();
    private ArrayAdapter<String> pairedAdapter;

    private TextView tvStatus;
    
    // Persistent Map State
    private final String[][] mapData = new String[20][20]; // Stores "OBSTACLE"
    private final float[][] mapRotation = new float[20][20];
    private final boolean[][] hasFace = new boolean[20][20];
    private final Map<String, Long> lastClickTimeMap = new HashMap<>();

    private int obstacleCount = 0;
    private int currentX = 0;
    private int currentZ = 0;
    private int lastSentX = -9999;
    private int lastSentZ = -9999;
    
    private boolean isCalibrationRunning = false;
    private boolean isSwapped = false;

    private final Handler commandHandler = new Handler(Looper.getMainLooper());

    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private static final int WATCHDOG_INTERVAL = 2000;

    private final Handler dragHandler = new Handler(Looper.getMainLooper());
    private static final long DRAG_HOLD_MS = 100; // short hold instead of the system long-press delay (~500ms)

    private static class DragState { Runnable startDrag; boolean started; }

    // Measured: X=100, Z=1000 held for 5s turns the car 90 degrees.
    private static final int CALIB_TURN_X = 100;
    private static final int CALIB_TURN_Z = 1000;
    private static final double CALIB_DEG_PER_SEC = 90.0 / 5.0;

    // Measured: X=500 held for 11s covers ~470cm (confirmed linear within ~2% at X=130/10s).
    private static final double CALIB_CM_PER_SEC_PER_X = (470.0 / 11.0) / 500.0;

    private final Handler calibHandler = new Handler(Looper.getMainLooper());
    private Runnable calibStopRunnable;

    // Arena is 200x200cm, grid is 20x20 cells (10cm/cell). Start zone is the bottom-left 3x3 cells.
    private static final int ARENA_SIZE_CM = 200;
    private static final int GRID_CELLS = 20;
    private static final int CELL_SIZE_CM = ARENA_SIZE_CM / GRID_CELLS;
    private static final int START_ZONE_ROW_MIN = GRID_CELLS - 3;
    private static final int START_ZONE_COL_MAX = 2;
    private static final double START_X_CM = 15.0;
    private static final double START_Y_CM = 15.0;

    private double carXcm = START_X_CM;
    private double carYcm = START_Y_CM;
    private double carHeadingDeg = 0.0;

    // ic_car's artwork faces down (south) by default, but heading 0 means facing north/up.
    private static final float ICON_FRONT_OFFSET_DEG = 180f;

    private ImageView carOverlay;
    private TextView tvMapStatus;
    private int mapGridPixelSize = 0;
    private int carIconSizePx = 0;

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
        tvRobotLog = v.findViewById(R.id.tvRobotLog);
        svRobotLog = v.findViewById(R.id.svRobotLog);
        tvLastDetected = v.findViewById(R.id.tvLastDetected);
        v.findViewById(R.id.btnScan).setOnClickListener(view -> startDiscovery());
        v.findViewById(R.id.btnSettings).setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));

        renderRobotLog();

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
        EditText etAngle = v.findViewById(R.id.etCalibAngle);
        v.findViewById(R.id.btnSendCalib).setOnClickListener(view ->
            executeTimedCommand(etTime.getText().toString(), etX.getText().toString(), etZ.getText().toString()));
        v.findViewById(R.id.btnCalibTurn).setOnClickListener(view -> turnToAngle(etAngle.getText().toString()));
        v.findViewById(R.id.btnCalibFwd).setOnClickListener(view -> executeTimedCommand("1.0", "500", "0"));
        v.findViewById(R.id.btnCalibStop).setOnClickListener(view -> stopCalibration());
    }

    private void turnToAngle(String angleStr) {
        if (angleStr.isEmpty()) { Toast.makeText(this, "Enter an angle", Toast.LENGTH_SHORT).show(); return; }
        try {
            double angle = Double.parseDouble(angleStr);
            double timeSec = Math.abs(angle) / CALIB_DEG_PER_SEC;
            int z = angle >= 0 ? CALIB_TURN_Z : -CALIB_TURN_Z;
            executeTimedCommand(String.valueOf(timeSec), String.valueOf(CALIB_TURN_X), String.valueOf(z));
        } catch (NumberFormatException e) { Toast.makeText(this, "Invalid angle", Toast.LENGTH_SHORT).show(); }
    }

    private void executeTimedCommand(String t, String x, String z) {
        if (t.isEmpty() || x.isEmpty() || z.isEmpty()) return;
        try {
            int timeMs = (int) (Double.parseDouble(t) * 1000);
            if (calibStopRunnable != null) calibHandler.removeCallbacks(calibStopRunnable);
            isCalibrationRunning = true;
            currentX = Integer.parseInt(x);
            currentZ = Integer.parseInt(z);
            calibStopRunnable = () -> {
                isCalibrationRunning = false;
                currentX = 0; currentZ = 0;
                Toast.makeText(this, "Calibration complete", Toast.LENGTH_SHORT).show();
            };
            calibHandler.postDelayed(calibStopRunnable, timeMs);
        } catch (Exception e) { Toast.makeText(this, "Invalid input", Toast.LENGTH_SHORT).show(); }
    }

    private void stopCalibration() {
        if (calibStopRunnable != null) { calibHandler.removeCallbacks(calibStopRunnable); calibStopRunnable = null; }
        isCalibrationRunning = false;
        currentX = 0; currentZ = 0;
        sendCommand("stop\n");
    }

    private void setupMapTab(View v) {
        GridLayout mapGrid = v.findViewById(R.id.mapGrid);
        ImageView imgDragObstacle = v.findViewById(R.id.imgDragObstacle);
        carOverlay = v.findViewById(R.id.imgCarOverlay);
        tvMapStatus = v.findViewById(R.id.tvMapStatus);
        tvRobotLogMap = v.findViewById(R.id.tvRobotLog);
        svRobotLogMap = v.findViewById(R.id.svRobotLog);
        tvLastDetectedMap = v.findViewById(R.id.tvLastDetected);
        renderRobotLog();

        v.findViewById(R.id.btnResetGrid).setOnClickListener(view -> resetGrid(mapGrid));
        v.findViewById(R.id.btnSendObstacles).setOnClickListener(view -> {
            String msg = buildObstacleMessage();
            if (msg == null) { Toast.makeText(this, "No obstacles placed", Toast.LENGTH_SHORT).show(); return; }
            sendCommand(msg);
        });

        imgDragObstacle.setOnTouchListener((view, event) -> handlePaletteTouch(view, event, "OBSTACLE"));

        mapGrid.post(() -> {
            int cellSize = mapGrid.getWidth() / GRID_CELLS;
            mapGridPixelSize = mapGrid.getWidth();
            mapGrid.removeAllViews();
            for (int r = 0; r < GRID_CELLS; r++) {
                for (int c = 0; c < GRID_CELLS; c++) {
                    ImageView cell = new ImageView(this);
                    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                    params.width = cellSize - 2; params.height = cellSize - 2;
                    params.setMargins(1, 1, 1, 1);
                    cell.setLayoutParams(params);
                    cell.setBackgroundColor(isStartZone(r, c) ? Color.parseColor("#C8E6C9") : Color.LTGRAY);
                    cell.setScaleType(ImageView.ScaleType.FIT_CENTER);

                    final int row = r; final int col = c;
                    refreshCellUI(cell, row, col);

                    cell.setOnClickListener(view -> handleCellTap(cell, row, col));
                    cell.setOnTouchListener((view, event) -> handleCellTouch(view, event, row, col));
                    cell.setOnDragListener((view, event) -> {
                        if (event.getAction() == DragEvent.ACTION_DROP) {
                            handleDrop(mapGrid, cell, row, col, event.getClipData().getItemAt(0).getText().toString());
                            return true;
                        }
                        return true;
                    });
                    mapGrid.addView(cell);
                }
            }
            ViewGroup.LayoutParams carParams = carOverlay.getLayoutParams();
            carParams.width = cellSize; carParams.height = cellSize;
            carOverlay.setLayoutParams(carParams);
            carIconSizePx = cellSize;
            updateMapOverlay();
        });
    }

    private boolean isStartZone(int r, int c) {
        return r >= START_ZONE_ROW_MIN && c <= START_ZONE_COL_MAX;
    }

    private void updateMapOverlay() {
        if (carOverlay == null || mapGridPixelSize <= 0) return;
        float scale = mapGridPixelSize / (float) ARENA_SIZE_CM;
        int half = carIconSizePx / 2;
        carOverlay.setTranslationX((float) (carXcm * scale) - half);
        carOverlay.setTranslationY(mapGridPixelSize - (float) (carYcm * scale) - half);
        carOverlay.setRotation((float) carHeadingDeg + ICON_FRONT_OFFSET_DEG);

        if (tvMapStatus != null) {
            double headingNorm = ((carHeadingDeg % 360) + 360) % 360;
            tvMapStatus.setText(String.format(Locale.US,
                "Position: (%.1f, %.1f) cm   Heading: %.0f°  [estimated]", carXcm, carYcm, headingNorm));
        }
    }

    private boolean handlePaletteTouch(View view, MotionEvent event, String type) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            DragState state = new DragState();
            state.startDrag = () -> {
                state.started = true;
                ClipData data = ClipData.newPlainText("type", type + ";-1;-1");
                view.startDragAndDrop(data, new View.DragShadowBuilder(view), null, 0);
            };
            view.setTag(state);
            dragHandler.postDelayed(state.startDrag, DRAG_HOLD_MS);
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            Object tag = view.getTag();
            if (tag instanceof DragState) dragHandler.removeCallbacks(((DragState) tag).startDrag);
            view.setTag(null);
        }
        return true;
    }

    private boolean handleCellTouch(View view, MotionEvent event, int row, int col) {
        if (mapData[row][col] == null) return false; // empty cell: let click/double-tap handling proceed normally

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            DragState state = new DragState();
            state.startDrag = () -> {
                state.started = true;
                String type = mapData[row][col];
                ClipData data = ClipData.newPlainText("type", type + ";" + row + ";" + col);
                view.startDragAndDrop(data, new View.DragShadowBuilder(view), null, 0);
            };
            view.setTag(state);
            dragHandler.postDelayed(state.startDrag, DRAG_HOLD_MS);
            return false; // don't consume yet, so a quick tap still reaches onClick
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            Object tag = view.getTag();
            if (tag instanceof DragState) {
                DragState state = (DragState) tag;
                dragHandler.removeCallbacks(state.startDrag);
                view.setTag(null);
                return state.started; // if drag already fired, swallow the UP so it doesn't also trigger a tap
            }
        }
        return false;
    }

    private void placeAt(ImageView cell, int r, int c, String type) {
        if (isStartZone(r, c)) { Toast.makeText(this, "Reserved for robot start", Toast.LENGTH_SHORT).show(); return; }
        if ("OBSTACLE".equals(type) && mapData[r][c] == null) {
            if (obstacleCount < 6) {
                mapData[r][c] = "OBSTACLE";
                mapRotation[r][c] = 0;
                hasFace[r][c] = false;
                obstacleCount++;
            } else {
                Toast.makeText(this, "Max 6 obstacles reached", Toast.LENGTH_SHORT).show();
            }
        }
        refreshCellUI(cell, r, c);
    }

    private void handleDrop(GridLayout mapGrid, ImageView destCell, int row, int col, String clipText) {
        String[] parts = clipText.split(";");
        String type = parts[0];
        int srcR = Integer.parseInt(parts[1]);
        int srcC = Integer.parseInt(parts[2]);
        boolean isRelocation = srcR >= 0 && srcC >= 0;

        if (isRelocation && srcR == row && srcC == col) return; // dropped back on itself

        if (isStartZone(row, col)) {
            Toast.makeText(this, "Reserved for robot start", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mapData[row][col] != null) {
            Toast.makeText(this, "Cell occupied", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isRelocation) {
            mapData[row][col] = type;
            mapRotation[row][col] = mapRotation[srcR][srcC];
            hasFace[row][col] = hasFace[srcR][srcC];

            mapData[srcR][srcC] = null;
            mapRotation[srcR][srcC] = 0;
            hasFace[srcR][srcC] = false;
            ImageView srcCell = (ImageView) mapGrid.getChildAt(srcR * GRID_CELLS + srcC);
            if (srcCell != null) refreshCellUI(srcCell, srcR, srcC);

            refreshCellUI(destCell, row, col);
        } else {
            placeAt(destCell, row, col, type);
        }
    }

    private void handleCellTap(ImageView cell, int r, int c) {
        if (isStartZone(r, c)) return;

        long now = System.currentTimeMillis();
        String key = r + "," + c;
        long last = lastClickTimeMap.getOrDefault(key, 0L);
        lastClickTimeMap.put(key, now);

        if (now - last < 300) { // Double Tap to Remove
            if ("OBSTACLE".equals(mapData[r][c])) obstacleCount--;
            mapData[r][c] = null; mapRotation[r][c] = 0; hasFace[r][c] = false;
            cell.setImageDrawable(null); cell.setRotation(0);
            return;
        }

        if (!"OBSTACLE".equals(mapData[r][c])) return;

        if (!hasFace[r][c]) {
            hasFace[r][c] = true;
            mapRotation[r][c] = 0;
        } else {
            mapRotation[r][c] = (mapRotation[r][c] + 90) % 360;
            // Drag -> No Face. Tap -> N. Tap -> E. Tap -> S. Tap -> W. Tap -> No Face.
            if (mapRotation[r][c] == 0) hasFace[r][c] = false;
        }
        refreshCellUI(cell, r, c);
    }

    private void refreshCellUI(ImageView cell, int r, int c) {
        String type = mapData[r][c];
        if (type == null) { cell.setImageDrawable(null); cell.setRotation(0); return; }
        if ("OBSTACLE".equals(type)) {
            if (hasFace[r][c]) cell.setImageResource(R.drawable.ic_obstacle);
            else cell.setImageResource(R.drawable.ic_obstacle_no_face);
        }
        cell.setRotation(mapRotation[r][c]);
    }

    private void resetGrid(GridLayout grid) {
        for (int r = 0; r < GRID_CELLS; r++) for (int c = 0; c < GRID_CELLS; c++) {
            mapData[r][c] = null; mapRotation[r][c] = 0; hasFace[r][c] = false;
            ImageView v = (ImageView) grid.getChildAt(r * GRID_CELLS + c);
            if (v != null) { v.setImageDrawable(null); v.setRotation(0); }
        }
        obstacleCount = 0; lastClickTimeMap.clear();

        carXcm = START_X_CM; carYcm = START_Y_CM; carHeadingDeg = 0.0;
        updateMapOverlay();
    }

    private String buildObstacleMessage() {
        List<String> obsStrs = new ArrayList<>();
        int id = 1;
        for (int r = 0; r < GRID_CELLS; r++) {
            for (int c = 0; c < GRID_CELLS; c++) {
                if ("OBSTACLE".equals(mapData[r][c])) {
                    int xCm = c * CELL_SIZE_CM;
                    int yCm = (GRID_CELLS - 1 - r) * CELL_SIZE_CM;
                    String entry = id + "," + xCm + "," + yCm;
                    if (hasFace[r][c]) entry += "," + getFacing(mapRotation[r][c]);
                    obsStrs.add(entry);
                    id++;
                }
            }
        }
        if (obsStrs.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("OBS:");
        for (int i = 0; i < obsStrs.size(); i++) {
            if (i > 0) sb.append(";");
            sb.append(obsStrs.get(i));
        }
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
        v.findViewById(R.id.btnStop).setOnClickListener(view -> { currentX = 0; currentZ = 0; sendCommand("stop\n"); });
        v.findViewById(R.id.btnManualAuto).setOnClickListener(view -> sendCommand("auto\n"));
        v.findViewById(R.id.btnManualStart).setOnClickListener(view -> sendCommand("manual\n"));
        v.findViewById(R.id.btnFindTarget).setOnClickListener(view -> sendCommand("find\n"));
        v.findViewById(R.id.btnScanImage).setOnClickListener(view -> sendCommand("recognise\n"));
    }

    private void updateJoystickRoles(JoystickView left, JoystickView right) {
        if (!isSwapped) {
            left.setLabel("VELOCITY (X)"); left.setMode(true, false);
            left.setListener(new JoystickView.JoystickListener() {
                @Override public void onMoved(float x, float y) { currentX = (int)(y * 1000); }
                @Override public void onReleased() { currentX = 0; }
            });
            right.setLabel("STEERING (Z)"); right.setMode(false, true);
            right.setListener(new JoystickView.JoystickListener() {
                @Override public void onMoved(float x, float y) { currentZ = (int)(-x * 1000); }
                @Override public void onReleased() { currentZ = 0; }
            });
        } else {
            left.setLabel("STEERING (Z)"); left.setMode(false, true);
            left.setListener(new JoystickView.JoystickListener() {
                @Override public void onMoved(float x, float y) { currentZ = (int)(-x * 1000); }
                @Override public void onReleased() { currentZ = 0; }
            });
            right.setLabel("VELOCITY (X)"); right.setMode(true, false);
            right.setListener(new JoystickView.JoystickListener() {
                @Override public void onMoved(float x, float y) { currentX = (int)(y * 1000); }
                @Override public void onReleased() { currentX = 0; }
            });
        }
    }

    private static final long COMMAND_INTERVAL_MS = 50;

    private final Runnable commandRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentX != lastSentX || currentZ != lastSentZ) {
                sendCommand(currentX + "," + currentZ + "\n");
                lastSentX = currentX; lastSentZ = currentZ;
            }
            updateDeadReckoning(COMMAND_INTERVAL_MS / 1000.0);
            commandHandler.postDelayed(this, COMMAND_INTERVAL_MS);
        }
    };

    private void updateDeadReckoning(double dtSec) {
        if (currentX == 0 && currentZ == 0) return;

        double headingRateDegPerSec = CALIB_DEG_PER_SEC
            * (currentX / (double) CALIB_TURN_X) * (currentZ / (double) CALIB_TURN_Z);
        carHeadingDeg += headingRateDegPerSec * dtSec;

        double speedCmPerSec = currentX * CALIB_CM_PER_SEC_PER_X;
        double headingRad = Math.toRadians(carHeadingDeg);
        carXcm += speedCmPerSec * dtSec * Math.sin(headingRad);
        carYcm += speedCmPerSec * dtSec * Math.cos(headingRad);
        carXcm = Math.max(0, Math.min(ARENA_SIZE_CM, carXcm));
        carYcm = Math.max(0, Math.min(ARENA_SIZE_CM, carYcm));

        updateMapOverlay();
    }

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
        bluetoothSocket = null; outputStream = null; inputStream = null; readThread = null;
        runOnUiThread(() -> {
            tvStatus.setText("Status: Disconnected"); tvStatus.setTextColor(Color.RED);
            appendRobotLog("--- disconnected ---");
        });
    }

    private void startReadThread() {
        final BluetoothSocket socketForThisThread = bluetoothSocket;
        final InputStream streamForThisThread = inputStream;
        readThread = new Thread(() -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(streamForThisThread, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String received = line;
                    runOnUiThread(() -> appendRobotLog(received));
                }
            } catch (IOException e) {
                Log.w(TAG, "Read loop ended: " + e.getMessage());
            }
            // Only clean up if this thread's connection is still the active one
            // (a newer connection may have already replaced it, e.g. on reconnect).
            if (bluetoothSocket == socketForThisThread) handleDisconnect();
        });
        readThread.start();
    }

    private void appendRobotLog(String line) {
        robotLogLines.add(line);
        while (robotLogLines.size() > MAX_LOG_LINES) robotLogLines.remove(0);
        renderRobotLog();
        parseAndUpdateDetection(line);
    }

    private void parseAndUpdateDetection(String line) {
        String detectedClass = null;
        int classIdx = line.indexOf("class=");
        if (classIdx >= 0) {
            int start = classIdx + "class=".length();
            int end = start;
            while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
            detectedClass = line.substring(start, end);
        } else {
            int foundIdx = line.indexOf("image found:");
            if (foundIdx >= 0) detectedClass = line.substring(foundIdx + "image found:".length()).trim();
        }
        if (detectedClass != null && !detectedClass.isEmpty()) {
            String text = "Last Detected: " + detectedClass;
            if (tvLastDetected != null) tvLastDetected.setText(text);
            if (tvLastDetectedMap != null) tvLastDetectedMap.setText(text);
        }
    }

    private void renderRobotLog() {
        String text = String.join("\n", robotLogLines);
        if (tvRobotLog != null) {
            tvRobotLog.setText(text);
            if (svRobotLog != null) svRobotLog.post(() -> svRobotLog.fullScroll(View.FOCUS_DOWN));
        }
        if (tvRobotLogMap != null) {
            tvRobotLogMap.setText(text);
            if (svRobotLogMap != null) svRobotLogMap.post(() -> svRobotLogMap.fullScroll(View.FOCUS_DOWN));
        }
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
                inputStream = bluetoothSocket.getInputStream();
                startReadThread();
                runOnUiThread(() -> {
                    tvStatus.setText("Status: Connected to " + device.getName());
                    tvStatus.setTextColor(Color.GREEN);
                    watchdogHandler.post(watchdogRunnable);
                    appendRobotLog("--- connected to " + device.getName() + " ---");
                });
            } catch (IOException e) {
                try {
                    bluetoothSocket = (BluetoothSocket) device.getClass().getMethod("createRfcommSocket", new Class[]{int.class}).invoke(device, 1);
                    bluetoothSocket.connect();
                    outputStream = bluetoothSocket.getOutputStream();
                    inputStream = bluetoothSocket.getInputStream();
                    startReadThread();
                    runOnUiThread(() -> {
                        tvStatus.setText("Status: Connected (Alt) to " + device.getName());
                        tvStatus.setTextColor(Color.GREEN);
                        watchdogHandler.post(watchdogRunnable);
                        appendRobotLog("--- connected (alt) to " + device.getName() + " ---");
                    });
                } catch (Exception e2) {
                    handleDisconnect();
                    runOnUiThread(() -> { tvStatus.setText("Status: Connection Failed"); tvStatus.setTextColor(Color.RED); });
                }
            }
        }).start();
    }

    private void sendCommand(String command) {
        if (outputStream == null) {
            Log.w(TAG, "Cannot send command: outputStream is null");
            return;
        }
        try { 
            outputStream.write(command.getBytes());
            Log.d(TAG, "Sent: " + command.trim());
        }
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
