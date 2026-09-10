package com.cory.signalhunter.radio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.SparseArray;
import com.cory.signalhunter.core.Observation;
import com.cory.signalhunter.core.SignalMath;
import java.util.List;
import java.util.Map;

public final class BleSource {
    private final Context context;
    private final RadioSink sink;
    private BluetoothLeScanner scanner;
    private boolean running;

    public BleSource(Context context, RadioSink sink) {
        this.context = context;
        this.sink = sink;
    }

    public boolean running() { return running; }

    @SuppressLint("MissingPermission")
    public void start() {
        if (running) return;
        if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            sink.status("BLE", "Bluetooth scan permission required");
            return;
        }
        BluetoothManager manager = (BluetoothManager)
            context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            sink.status("BLE", "Bluetooth hardware unavailable");
            return;
        }
        if (!adapter.isEnabled()) {
            sink.status("BLE", "Bluetooth is switched off");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            sink.status("BLE", "BLE scanner unavailable");
            return;
        }
        try {
            scanner.startScan(null, new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0).build(), callback);
            running = true;
            sink.status("BLE", "Scanning advertisements");
        } catch (SecurityException | IllegalStateException e) {
            sink.status("BLE", e.getClass().getSimpleName()
                + ": " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        if (!running) return;
        running = false;
        try {
            if (scanner != null) scanner.stopScan(callback);
        } catch (SecurityException | IllegalStateException e) {
            sink.status("BLE", "Stop failed: " + e.getMessage());
        }
        sink.status("BLE", "Stopped");
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            accept(result);
        }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) accept(result);
        }
        @Override public void onScanFailed(int code) {
            running = false;
            sink.status("BLE", "Scan failed - Android error " + code);
        }
    };

    @SuppressLint("MissingPermission")
    private void accept(ScanResult result) {
        if (!running) return;
        try {
            long nowNs = SystemClock.elapsedRealtimeNanos();
            long nowMs = System.currentTimeMillis();
            long source = result.getTimestampNanos();
            if (source <= 0 || source > nowNs) {
                sink.status("BLE", "Rejected invalid platform timestamp");
                return;
            }
            ScanRecord record = result.getScanRecord();
            String name = record == null ? null : record.getDeviceName();
            StringBuilder info = new StringBuilder();
            info.append("Address type: ");
            info.append("Not exposed by scan result");
            info.append("\nConnectable: ");
            if (Build.VERSION.SDK_INT >= 26) {
                info.append(result.isConnectable());
            } else info.append("Unknown");
            info.append("\nPHY: ").append(result.getPrimaryPhy());
            info.append(" / ").append(result.getSecondaryPhy());
            info.append("\nAdvertising SID: ").append(result.getAdvertisingSid());
            info.append("\nTx power (platform): ").append(result.getTxPower());
            if (record != null) {
                info.append("\nAdvertised name: ").append(name);
                info.append("\nAdvertised Tx power: ")
                    .append(record.getTxPowerLevel());
                List<ParcelUuid> uuids = record.getServiceUuids();
                if (uuids != null) {
                    for (ParcelUuid uuid : uuids) {
                        info.append("\nService UUID: ").append(uuid);
                    }
                }
                SparseArray<byte[]> manufacturer =
                    record.getManufacturerSpecificData();
                for (int i = 0; i < manufacturer.size(); i++) {
                    info.append("\nManufacturer ID: 0x")
                        .append(Integer.toHexString(manufacturer.keyAt(i)))
                        .append(" / ")
                        .append(SignalMath.hex(manufacturer.valueAt(i)));
                }
                for (Map.Entry<ParcelUuid, byte[]> entry :
                        record.getServiceData().entrySet()) {
                    info.append("\nService data ").append(entry.getKey())
                        .append(": ").append(SignalMath.hex(entry.getValue()));
                }
            }
            sink.observation(new Observation("BLE",
                result.getDevice().getAddress(), name, result.getRssi(),
                0, source, SignalMath.wallTime(source, nowNs, nowMs),
                nowMs, android.provider.Settings.Global.getInt(
                context.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1),
                info.toString(), record == null ? "" :
                SignalMath.hex(record.getBytes())));
        } catch (RuntimeException e) {
            sink.status("BLE", "Observation rejected: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
