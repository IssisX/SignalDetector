package com.cory.signalhunter.radio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import com.cory.signalhunter.core.Observation;
import com.cory.signalhunter.core.SignalMath;
import java.util.List;

public final class WifiSource {
    private final Context context;
    private final RadioSink sink;
    private final WifiManager wifi;
    private boolean registered;
    private long lastRequestMs;
    private boolean pending;

    public WifiSource(Context context, RadioSink sink) {
        this.context = context;
        this.sink = sink;
        wifi = (WifiManager) context.getApplicationContext()
            .getSystemService(Context.WIFI_SERVICE);
    }

    public void start() {
        if (registered) return;
        if (wifi == null) {
            sink.status("Wi-Fi", "Wi-Fi hardware unavailable");
            return;
        }
        IntentFilter filter = new IntentFilter(
            WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
        } else context.registerReceiver(receiver, filter);
        registered = true;
        readResults("Existing platform scan cache");
    }

    public void stop() {
        if (!registered) return;
        context.unregisterReceiver(receiver);
        registered = false;
        pending = false;
    }

    @SuppressLint("MissingPermission")
    public void request() {
        if (wifi == null) {
            sink.status("Wi-Fi", "Wi-Fi hardware unavailable");
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            sink.status("Wi-Fi", "Precise location permission required");
            return;
        }
        if (!wifi.isWifiEnabled()) {
            sink.status("Wi-Fi", "Wi-Fi is switched off");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastRequestMs > 0 && now - lastRequestMs < 3000) {
            sink.status("Wi-Fi", "Scan request already made recently");
            return;
        }
        lastRequestMs = now;
        try {
            pending = wifi.startScan();
            if (pending) {
                sink.status("Wi-Fi", "Scan requested - awaiting Android");
            } else {
                sink.status("Wi-Fi", "Scan request rejected or throttled. "
                    + "Existing results remain available.");
                readResults("Existing platform scan cache");
            }
        } catch (RuntimeException e) {
            pending = false;
            sink.status("Wi-Fi", "Scan request failed: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(
                    intent.getAction())) return;
            boolean updated = intent.getBooleanExtra(
                WifiManager.EXTRA_RESULTS_UPDATED, false);
            pending = false;
            readResults(updated ? "Scan results updated by Android"
                : "Android reports no new scan results");
        }
    };

    @SuppressLint("MissingPermission")
    private void readResults(String label) {
        if (wifi == null) return;
        try {
            List<ScanResult> results = wifi.getScanResults();
            if (results == null) {
                sink.status("Wi-Fi", "Android returned no scan list");
                return;
            }
            long nowNs = SystemClock.elapsedRealtimeNanos();
            long nowMs = System.currentTimeMillis();
            int accepted = 0;
            for (ScanResult r : results) {
                long source = r.timestamp * 1000L;
                if (source <= 0 || source > nowNs || r.BSSID == null) {
                    continue;
                }
                String name = r.SSID == null ? "" : r.SSID;
                StringBuilder info = new StringBuilder();
                info.append("SSID: ").append(name.isEmpty()
                    ? "Not advertised / hidden" : name);
                info.append("\nBSSID: ").append(r.BSSID);
                info.append("\nCapabilities: ").append(r.capabilities);
                info.append("\nFrequency: ").append(r.frequency).append(" MHz");
                info.append("\nChannel width code: ").append(r.channelWidth);
                info.append("\nCenter frequency 0: ").append(r.centerFreq0);
                info.append("\nCenter frequency 1: ").append(r.centerFreq1);
                info.append("\nPlatform timestamp: ").append(r.timestamp)
                    .append(" microseconds since boot");
                if (Build.VERSION.SDK_INT >= 30) {
                    info.append("\nWi-Fi standard code: ").append(r.getWifiStandard());
                }
                // Android scan results are metadata, not raw 802.11 frames.
                // Preserve exposed fields; do not fabricate frame bytes.
                sink.observation(new Observation("Wi-Fi", r.BSSID,
                    name, r.level, r.frequency, source,
                    SignalMath.wallTime(source, nowNs, nowMs),
                    nowMs, android.provider.Settings.Global.getInt(
                context.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1),
                info.toString(), ""));
                accepted++;
            }
            sink.status("Wi-Fi", label + " - " + results.size()
                + " entries, " + accepted + " valid timestamps");
        } catch (RuntimeException e) {
            sink.status("Wi-Fi", "Results unavailable: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
