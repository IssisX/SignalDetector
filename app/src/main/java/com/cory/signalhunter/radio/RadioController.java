package com.cory.signalhunter.radio;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

public final class RadioController {
    private final Activity activity;
    private final RadioSink sink;
    private final BleSource ble;
    private final WifiSource wifi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean active;
    private boolean automaticWifi = true;

    public RadioController(Activity activity, RadioSink sink) {
        this.activity = activity;
        this.sink = sink;
        ble = new BleSource(activity, sink);
        wifi = new WifiSource(activity, sink);
    }

    public boolean active() { return active; }
    public boolean automaticWifi() { return automaticWifi; }

    public boolean hasPermissions() {
        if (activity.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 31) {
            if (activity.checkSelfPermission(
                    Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) return false;
            if (activity.checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return false;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(
                Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    public String[] permissions() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        out.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            out.add(Manifest.permission.BLUETOOTH_SCAN);
            out.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            out.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        return out.toArray(new String[0]);
    }

    public void start() {
        if (active) return;
        if (!hasPermissions()) {
            sink.status("System", "Radio permissions required");
            return;
        }
        active = true;
        wifi.start();
        ble.start();
        wifi.request();
        handler.postDelayed(wifiTick, 30000);
    }

    public void stop() {
        active = false;
        handler.removeCallbacks(wifiTick);
        ble.stop();
        wifi.stop();
        sink.status("System", "Scanning stopped");
    }

    public void wifiNow() { wifi.request(); }

    public void setAutomaticWifi(boolean enabled) {
        automaticWifi = enabled;
        sink.status("Wi-Fi", enabled ? "Periodic requests enabled"
            : "Periodic requests disabled");
    }

    private final Runnable wifiTick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (automaticWifi) wifi.request();
            handler.postDelayed(this, 30000);
        }
    };
}
