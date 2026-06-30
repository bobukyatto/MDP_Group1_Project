package com.example.mdpgroup1project;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class MainActivity extends AppCompatActivity {

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

        // Referred to https://developer.android.com/develop/connectivity/bluetooth
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            // Device doesn't support Bluetooth
//            Log.i("bluetooth", "bluetooth not working");
        } else {
            // Device supports bluetooth
//            Log.i("bluetooth", "bluetooth is working");

            // Ask to enable bluetooth
            if (!bluetoothAdapter.isEnabled()) {
                Log.i("bluetooth", "bluetooth is working fdsfdsf");
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, 0);

            }
        }

        // https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices

        // Register for broadcasts when a device is discovered.
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, filter);

// Start discovery
// Source - https://stackoverflow.com/a/10560412
// Posted by Alex Lockwood, modified by community. See post 'Timeline' for change history
// Retrieved 2026-06-30, License - CC BY-SA 3.0

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        bluetoothAdapter.startDiscovery();

        // Makes device discoverable on bluetooth
//        int requestCode = 1;
//        Intent discoverableIntent =
//                new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
//        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
//        startActivityForResult(discoverableIntent, requestCode);
    }

    HashMap<String, BluetoothDevice> devicesMap = new HashMap<>();
    List<String> devicesList = new ArrayList<String>();
    public int count = 0;
    // Create a BroadcastReceiver for ACTION_FOUND.
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
//            Log.i("bluetooth", "bluetooth is working Discovery");
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Discovery has found a device. Get the BluetoothDevice
                // object and its info from the Intent.
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                @SuppressLint("MissingPermission") String deviceName = device.getName();
                if(deviceName != null){
                    String deviceHardwareAddress = device.getAddress(); // MAC address
                    devicesMap.put(deviceName, device);
                    devicesList.add(deviceName);

//                    Log.i("bluetooth", "bluetooth is working" + devices);

                    // see https://www.youtube.com/watch?v=qfIc_pPg_t4
                    ListView listView = findViewById(R.id.lvBluetoothDevices);
                    ArrayAdapter<String> arrayAdapter = new ArrayAdapter<String>(
                            MainActivity.this, android.R.layout.simple_list_item_1, devicesList
                    );
                    listView.setAdapter(arrayAdapter);

                    listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                        @Override
                        public void onItemClick(AdapterView<?> parent, View view, int i, long id) {

                                try {
                                    BluetoothDevice btDevice = devicesMap.get(devicesList.get(i));
                                    Log.i("bluetooth", "bluetooth is working " + devicesList.get(i));
                                    createBond(btDevice);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }


                        }
                    });
                }

            }
        }
    };

    // Source - https://stackoverflow.com/a/46841793
    // Posted by nhoxbypass
    // Retrieved 2026-06-30, License - CC BY-SA 3.0
    //
    // To create a bluetooth connection between this device and btDevice
    public boolean createBond(BluetoothDevice btDevice)
            throws Exception
    {
        Class class1 = Class.forName("android.bluetooth.BluetoothDevice");
        Method createBondMethod = class1.getMethod("createBond");
        Boolean returnValue = (Boolean) createBondMethod.invoke(btDevice);
        return returnValue.booleanValue();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Don't forget to unregister the ACTION_FOUND receiver.
        unregisterReceiver(receiver);
    }

}

