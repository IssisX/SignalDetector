package com.cory.signalhunter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import com.cory.signalhunter.core.DeviceState;
import com.cory.signalhunter.core.Observation;
import com.cory.signalhunter.core.SignalMath;
import com.cory.signalhunter.radio.RadioController;
import com.cory.signalhunter.store.Repository;
import com.cory.signalhunter.store.Store;
import com.cory.signalhunter.ui.DeviceAdapter;
import com.cory.signalhunter.ui.Palette;
import com.cory.signalhunter.ui.TraceView;
import com.cory.signalhunter.ui.Ui;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity
        implements Repository.Listener {
    private static final int PERMISSIONS = 100;
    private static final int EXPORT = 101;
    private static final int ANALYST_EXPORT = 102;
    private static final int ANALYST_IMPORT = 103;
    private String analystExport;
    private com.cory.signalhunter.ui.AnalystConsole analyst;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat date = new SimpleDateFormat(
        "MMM d, HH:mm:ss", Locale.getDefault());
    private Repository repository;
    private RadioController radios;
    private LinearLayout root;
    private FrameLayout content;
    private TextView status, counts, scanButton, recordButton;
    private DeviceAdapter adapter;
    private TraceView trace;
    private TextView reading, statistics, detailText;
    private LinearLayout detailHost;
    private final Map<String, String> labels = new HashMap<>();
    private String tab = "Analyst";
    private String filter = "All";
    private String sort = "Recent";
    private String search = "";
    private String selectedKey;
    private boolean desiredScanning = true;
    private boolean wide;
    private boolean resumed;
    private long selectedSession;
    private long exportSession;
    private long historyRequest;
    private List<Observation> history = new ArrayList<>();
    private boolean historyLoaded;
    private List<Observation> sessionSamples = new ArrayList<>();
    private String sessionKey;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Palette.BG);
        getWindow().setNavigationBarColor(Palette.BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (state != null) {
            tab = state.getString("tab", "Discover");
            selectedKey = state.getString("key");
            desiredScanning = state.getBoolean("scanning", true);
        }
        repository = new Repository(this, this);
        radios = new RadioController(this, repository);
        repository.load();
        buildShell();
        if (!radios.hasPermissions()) requestRadioPermissions();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("tab", tab);
        out.putString("key", selectedKey);
        out.putBoolean("scanning", desiredScanning);
        super.onSaveInstanceState(out);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        buildShell();
    }

    @Override protected void onStart() {
        super.onStart();
        resumed = true;
        if (desiredScanning && radios.hasPermissions()) radios.start();
    }

    @Override protected void onStop() {
        resumed = false;
        radios.stop();
        repository.stopSession();
        super.onStop();
    }

    @Override protected void onDestroy() {
        radios.stop();
        main.removeCallbacks(ticker);
        repository.close();
        super.onDestroy();
    }

    private void requestRadioPermissions() {
        new AlertDialog.Builder(this)
            .setTitle("Radio access")
            .setMessage("Signal Hunter needs Bluetooth and precise "
                + "location access to discover nearby advertisers and "
                + "Wi-Fi access points. Android also requires Location "
                + "services to be enabled for scanning. Measurements "
                + "stay on this device until you export them. "
                + "No internet permission is requested.")
            .setPositiveButton("Continue", (d, w) ->
                requestPermissions(radios.permissions(), PERMISSIONS))
            .setNegativeButton("Not now", (d, w) -> {
                desiredScanning = false;
                changed();
            }).show();
    }

    @Override public void onRequestPermissionsResult(int code,
            String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != PERMISSIONS) return;
        if (radios.hasPermissions()) {
            desiredScanning = true;
            if (resumed) radios.start();
        } else {
            desiredScanning = false;
            error("Radio access was not granted. Use the permission "
                + "button to try again, or change access in Android Settings.");
        }
        changed();
    }

    private void buildShell() {
        wide = getResources().getConfiguration().screenWidthDp >= 600;
        root = Ui.column(this);
        root.setBackgroundColor(Palette.BG);
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() |
                    WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } else root.setFitsSystemWindows(true);
        setContentView(root);

        LinearLayout header = Ui.column(this);
        Ui.pad(this, header, 8, 4, 8, 4);
        LinearLayout titleRow = Ui.row(this);
        LinearLayout title = Ui.column(this);
        title.addView(Ui.text(this, "Signal Hunter", 15,
            Palette.TEXT, true));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = Ui.mono(this, "v0.1", 10, Palette.MUTED);
        titleRow.addView(version);
        header.addView(titleRow);
        header.addView(Ui.space(this, 2));
        LinearLayout controls = Ui.row(this);
        scanButton = Ui.button(this, "STOP", false, v -> toggleScan());
        controls.addView(scanButton);
        controls.addView(Ui.spaceWidth(this, 8));
        recordButton = Ui.button(this, "RECORD", false, v -> toggleRecord());
        controls.addView(recordButton);
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView wifi = Ui.button(this, "Wi-Fi scan", false,
            v -> radios.wifiNow());
        controls.addView(wifi);
        header.addView(controls);
        header.addView(Ui.space(this, 2));
        counts = Ui.mono(this, "0 devices", 11, Palette.MUTED);
        header.addView(counts);
        status = Ui.text(this, "Initializing radios", 11,
            Palette.MUTED, false);
        status.setMaxLines(2);
        header.addView(Ui.space(this, 3));
        header.addView(status);
        root.addView(header);
        root.addView(Ui.divider(this));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
            -1, 0, 1));
        root.addView(Ui.divider(this));
        LinearLayout nav = Ui.row(this);
        Ui.pad(this, nav, wide ? 24 : 8, 8, wide ? 24 : 8, 8);
        for (String name : new String[]{"Analyst", "Discover", "Hunt",
                "Sessions", "Library"}) {
            TextView item = Ui.text(this, name, wide ? 14 : 12,
                tab.equals(name) ? Palette.ACCENT : Palette.MUTED,
                tab.equals(name));
            item.setGravity(Gravity.CENTER);
            item.setMinHeight(Ui.dp(this, 48));
            item.setOnClickListener(v -> {
                selectedSession = 0;
                tab = name;
                render();
            });
            nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        root.addView(nav);
        render();
        changed();
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 1000);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            refreshLive();
            main.postDelayed(this, 1000);
        }
    };

    private void toggleScan() {
        desiredScanning = !desiredScanning;
        if (desiredScanning) {
            if (!radios.hasPermissions()) requestRadioPermissions();
            else radios.start();
        } else {
            radios.stop();
            repository.stopSession();
        }
        changed();
    }

    private void toggleRecord() {
        if (repository.recording() != 0 || repository.starting()) {
            repository.stopSession();
            return;
        }
        if (!radios.hasPermissions()) {
            requestRadioPermissions();
            return;
        }
        if (!radios.active()) {
            desiredScanning = true;
            radios.start();
        }
        EditText title = Ui.input(this, "Session name",
            "Field session " + date.format(new Date()));
        LinearLayout box = Ui.column(this);
        Ui.pad(this, box, 20, 12, 20, 4);
        box.addView(title);
        new AlertDialog.Builder(this).setTitle("Record measurements")
            .setView(box).setPositiveButton("Start", (d, w) ->
                repository.startSession(title.getText().toString().trim()
                    .isEmpty() ? "Untitled session" :
                    title.getText().toString().trim()))
            .setNegativeButton("Cancel", null).show();
    }

    @Override public void changed() {
        if (root == null || isDestroyed()) return;
        refreshLive();
    }

    @Override public void error(String message) {
        if (root == null || isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle("Signal Hunter")
            .setMessage(message).setPositiveButton("OK", null).show();
    }

    private void refreshLive() {
        if (root == null) return;
        if (analyst != null) analyst.refresh();
        int ble = 0, wifi = 0;
        for (DeviceState d : repository.devices().values()) {
            if ("BLE".equals(d.latest.radio)) ble++;
            else wifi++;
        }
        counts.setText(ble + " BLE  /  " + wifi + " Wi-Fi  /  "
            + repository.devices().size() + " observed identities");
        String b = repository.statuses().getOrDefault("BLE", "Not started");
        String w = repository.statuses().getOrDefault("Wi-Fi", "Not started");
        status.setText("BLE: " + b + "\nWi-Fi: " + w);
        scanButton.setText(radios.active() ? "STOP" : "SCAN");
        recordButton.setText(repository.starting() ? "STARTING" :
            repository.recording() != 0 ? "STOP REC" : "RECORD");
        if (adapter != null) adapter.setItems(filteredDevices());
        if (trace != null && selectedSession == 0 &&
                selectedKey != null) refreshTrace();
    }

    private void render() {
        if (content == null) return;
        content.removeAllViews();
        adapter = null;
        trace = null;
        reading = null;
        statistics = null;
        detailText = null;
        detailHost = null;
        analyst = null;
        switch (tab) {
            case "Analyst":
                analyst = new com.cory.signalhunter.ui.AnalystConsole(
                    this, repository,
                    new com.cory.signalhunter.ui.AnalystConsole.Files() {
                        public void exportJson(String value) {
                            analystExport = value;
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.setType("application/json");
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.putExtra(Intent.EXTRA_TITLE, "signal-capture.json");
                            startActivityForResult(i, ANALYST_EXPORT);
                        }
                        public void importJson() {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.setType("application/json");
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            startActivityForResult(i, ANALYST_IMPORT);
                        }
                    });
                showPage(analyst); break;
            case "Hunt": renderHunt(); break;
            case "Sessions": renderSessions(); break;
            case "Library": renderLibrary(); break;
            default: renderDiscover(); break;
        }
        refreshLive();
    }

    private LinearLayout page(String eyebrow, String title,
            String subtitle) {
        LinearLayout box = Ui.column(this);
        Ui.pad(this, box, wide ? 24 : 16, 20,
            wide ? 24 : 16, 16);
        box.addView(Ui.label(this, eyebrow));
        box.addView(Ui.space(this, 5));
        box.addView(Ui.text(this, title, wide ? 23 : 21,
            Palette.TEXT, true));
        if (subtitle != null) {
            box.addView(Ui.space(this, 6));
            box.addView(Ui.text(this, subtitle, 12,
                Palette.MUTED, false));
        }
        box.addView(Ui.space(this, 17));
        return box;
    }

    private void showPage(View view) {
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    private void renderDiscover() {
        LinearLayout page = Ui.column(this);
        LinearLayout head = page("LIVE ENVIRONMENT", "Discover",
            "Nearby advertisements and access points. "
            + "Signal strength is not a distance measurement.");
        EditText query = Ui.input(this, "Search name, address or radio", search);
        head.addView(query);
        query.setSingleLine(true);
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            public void onTextChanged(CharSequence s, int st, int before, int count) {
                search = s.toString();
                if (adapter != null) adapter.setItems(filteredDevices());
            }
            public void afterTextChanged(Editable s) { }
        });
        head.addView(Ui.space(this, 12));
        LinearLayout filters = Ui.row(this);
        for (String f : new String[]{"All", "BLE", "Wi-Fi"}) {
            TextView chip = Ui.button(this, f, filter.equals(f), v -> {
                filter = f;
                render();
            });
            filters.addView(chip);
            filters.addView(Ui.spaceWidth(this, 6));
        }
        TextView sorter = Ui.button(this, sort, false, v -> {
            String[] options = {"Recent", "Strongest", "Name"};
            new AlertDialog.Builder(this).setTitle("Sort observations")
                .setItems(options, (d, i) -> {
                    sort = options[i]; render();
                }).show();
        });
        filters.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        filters.addView(sorter);
        head.addView(filters);
        head.addView(Ui.space(this, 10));
        TextView note = Ui.text(this,
            "Wi-Fi scans may be cached or throttled by Android. "
            + "The original sample time is retained.",
            11, Palette.MUTED, false);
        head.addView(note);
        head.addView(Ui.space(this, 12));
        adapter = new DeviceAdapter(this, repository.saved());
        adapter.setItems(filteredDevices());
        ListView list = list(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            selectDevice(adapter.getItem(pos).key);
            if (wide) render();
            else { tab = "Hunt"; render(); }
        });
        if (wide) {
            LinearLayout columns = Ui.row(this);
            columns.setGravity(Gravity.TOP);
            LinearLayout left = Ui.column(this);
            left.addView(Ui.scroll(this, head));
            left.addView(Ui.divider(this));
            left.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            columns.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
            View separator = new View(this);
            separator.setBackgroundColor(Palette.LINE);
            columns.addView(separator, new LinearLayout.LayoutParams(
                Ui.dp(this, 1), -1));
            detailHost = Ui.column(this);
            columns.addView(detailHost, new LinearLayout.LayoutParams(0, -1, 1));
            showPage(columns);
            renderWideDetail();
        } else {
            page.addView(head);
            page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            showPage(page);
        }
    }

    private ListView list(DeviceAdapter a) {
        ListView l = new ListView(this);
        l.setAdapter(a);
        l.setDivider(new android.graphics.drawable.ColorDrawable(Palette.LINE));
        l.setDividerHeight(Ui.dp(this, 1));
        l.setBackgroundColor(Palette.BG);
        l.setCacheColorHint(Color.TRANSPARENT);
        l.setSelector(Ui.bg(this, Palette.RAISED, 0, 0));
        l.setFastScrollEnabled(true);
        l.setVerticalScrollBarEnabled(false);
        l.setEmptyView(null);
        return l;
    }

    private List<DeviceState> filteredDevices() {
        ArrayList<DeviceState> out = new ArrayList<>();
        String term = search.toLowerCase(Locale.ROOT);
        for (DeviceState d : repository.devices().values()) {
            Observation o = d.latest;
            if (!"All".equals(filter) && !filter.equals(o.radio)) continue;
            String name = DeviceAdapter.name(o, repository.saved());
            if (!term.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(term)
                    && !o.address.toLowerCase(Locale.ROOT).contains(term)
                    && !o.radio.toLowerCase(Locale.ROOT).contains(term)) continue;
            out.add(d);
        }
        Comparator<DeviceState> comparator;
        if ("Strongest".equals(sort)) comparator =
            Comparator.comparingInt((DeviceState d) -> d.latest.rssi).reversed();
        else if ("Name".equals(sort)) comparator =
            Comparator.comparing(d -> DeviceAdapter.name(d.latest,
                repository.saved()).toLowerCase(Locale.ROOT));
        else comparator = Comparator.comparingLong(
            (DeviceState d) -> d.latest.receivedMs).reversed();
        out.sort(comparator.thenComparing(d -> d.key));
        return out;
    }

    private void selectDevice(String key) {
        if (!key.equals(selectedKey)) {
            selectedKey = key;
            history = new ArrayList<>();
            historyLoaded = false;
            long request = ++historyRequest;
            repository.history(key, 2000, values -> {
                if (request != historyRequest || !key.equals(selectedKey)) return;
                history = values;
                historyLoaded = true;
                if (!repository.devices().containsKey(key) && !values.isEmpty()) {
                    DeviceState restored = new DeviceState(values.get(0));
                    for (int i = 1; i < values.size(); i++) {
                        restored.accept(values.get(i));
                    }
                    repository.devices().put(key, restored);
                    render();
                    return;
                }
                if (trace != null) refreshTrace();
            });
        }
    }

    private void renderWideDetail() {
        if (detailHost == null) return;
        detailHost.removeAllViews();
        if (selectedKey == null) {
            LinearLayout empty = page("INVESTIGATION", "Select a signal",
                "Choose an observed identity to inspect its measurements, "
                + "advertisement data, and saved history.");
            detailHost.addView(empty);
            return;
        }
        detailHost.addView(Ui.scroll(this, devicePage()),
            new LinearLayout.LayoutParams(-1, -1));
    }

    private void renderHunt() {
        if (selectedKey == null && !repository.devices().isEmpty()) {
            selectDevice(filteredOrAllFirst());
        }
        if (selectedKey == null) {
            showPage(Ui.scroll(this, page("INVESTIGATION", "No signal selected",
                "Start scanning and select a device in Discover.")));
            return;
        }
        showPage(Ui.scroll(this, devicePage()));
    }

    private String filteredOrAllFirst() {
        List<DeviceState> list = filteredDevices();
        if (!list.isEmpty()) return list.get(0).key;
        return repository.devices().values().iterator().next().key;
    }

    private LinearLayout devicePage() {
        DeviceState state = repository.devices().get(selectedKey);
        LinearLayout page = page("SIGNAL INVESTIGATION", "Hunt",
            "Raw received power. No distance or direction is inferred.");
        if (state == null) {
            page.addView(Ui.text(this, "No saved measurement for this identity.",
                13, Palette.MUTED, false));
            page.addView(Ui.space(this, 12));
            page.addView(Ui.button(this, "Choose device", false,
                v -> chooseDevice()));
            return page;
        }
        Observation o = state.latest;
        LinearLayout identity = Ui.card(this);
        identity.addView(Ui.label(this, o.radio + " / " + o.address));
        identity.addView(Ui.space(this, 7));
        identity.addView(Ui.text(this,
            DeviceAdapter.name(o, repository.saved()), 20,
            Palette.TEXT, true));
        identity.addView(Ui.space(this, 4));
        identity.addView(Ui.mono(this, o.address, 11, Palette.MUTED));
        identity.addView(Ui.space(this, 12));
        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.button(this, "Choose", false,
            v -> chooseDevice()));
        actions.addView(Ui.spaceWidth(this, 8));
        actions.addView(Ui.button(this, "Save / edit", false,
            v -> editDevice(selectedKey)));
        identity.addView(actions);
        page.addView(identity);
        page.addView(Ui.space(this, 12));
        LinearLayout instrument = Ui.card(this);
        instrument.addView(Ui.label(this, "RECEIVED SIGNAL STRENGTH"));
        instrument.addView(Ui.space(this, 8));
        reading = Ui.mono(this, "- dBm", 34, Palette.ACCENT);
        instrument.addView(reading);
        statistics = Ui.mono(this, "", 11, Palette.MUTED);
        instrument.addView(Ui.space(this, 5));
        instrument.addView(statistics);
        instrument.addView(Ui.space(this, 14));
        trace = new TraceView(this);
        trace.setBackground(Ui.bg(this, Palette.PANEL, 4, 0));
        instrument.addView(trace, new LinearLayout.LayoutParams(-1,
            Ui.dp(this, wide ? 270 : 240)));
        trace.setSelection(sample -> {
            if (reading != null) reading.setText(sample.rssi + " dBm");
            if (statistics != null) statistics.setText(
                "Selected: " + date.format(new Date(sample.wallMs))
                + " · " + sample.radio + " · source "
                + sample.sourceNs + " ns");
        });
        instrument.addView(Ui.space(this, 8));
        instrument.addView(Ui.text(this,
            "Tap the trace to inspect an actual sample. Lines connect "
            + "nearby observations only; gaps are not measurements.",
            11, Palette.MUTED, false));
        page.addView(instrument);
        page.addView(Ui.space(this, 12));
        LinearLayout facts = Ui.card(this);
        facts.addView(Ui.label(this, "LATEST OBSERVATION"));
        facts.addView(Ui.space(this, 10));
        detailText = Ui.mono(this, "", 11, Palette.TEXT);
        detailText.setTextIsSelectable(true);
        facts.addView(detailText);
        page.addView(facts);
        page.addView(Ui.space(this, 12));
        if (repository.saved().containsKey(selectedKey)) {
            Store.SavedDevice saved = repository.saved().get(selectedKey);
            LinearLayout notes = Ui.card(this);
            notes.addView(Ui.label(this, "FIELD NOTES"));
            notes.addView(Ui.space(this, 8));
            notes.addView(Ui.text(this, saved.notes.isEmpty() ?
                "No notes saved." : saved.notes, 13, Palette.TEXT, false));
            page.addView(notes);
            page.addView(Ui.space(this, 12));
        }
        refreshTrace();
        return page;
    }

    private void refreshTrace() {
        if (selectedKey == null) return;
        DeviceState state = repository.devices().get(selectedKey);
        if (state == null) return;
        Observation o = state.latest;
        if (reading != null) reading.setText(o.rssi + " dBm");
        ArrayList<Observation> values = new ArrayList<>();
        if (historyLoaded) values.addAll(history);
        else values.addAll(state.recent());
        Map<String, Observation> unique = new HashMap<>();
        for (Observation sample : values) {
            unique.put(sample.bootId + ":" + sample.sourceNs, sample);
        }
        for (Observation sample : state.recent()) {
            unique.put(sample.bootId + ":" + sample.sourceNs, sample);
        }
        values = new ArrayList<>(unique.values());
        values.sort(Comparator.comparingLong(a -> a.wallMs));
        if (trace != null) trace.setSamples(values);
        SignalMath.Stats stats = SignalMath.stats(values);
        if (statistics != null) statistics.setText(stats.count == 0 ?
            "No measurements" : String.format(Locale.US,
            "%d samples · min %d · max %d · mean %.1f dBm",
            stats.count, stats.min, stats.max, stats.mean));
        if (detailText != null) {
            String channel = o.frequency == 0 ? "Not reported" :
                o.frequency + " MHz / " + SignalMath.band(o.frequency)
                + " / channel " + (SignalMath.channel(o.frequency) < 0
                ? "unknown" : SignalMath.channel(o.frequency));
            detailText.setText("Radio: " + o.radio + "\nAddress: " + o.address
                + "\nRSSI: " + o.rssi + " dBm"
                + "\nFrequency: " + channel
                + "\nObserved: " + date.format(new Date(o.wallMs))
                + "\nReceived: " + date.format(new Date(o.receivedMs))
                + "\nAge: " + DeviceAdapter.age(this, o)
                + "\nBoot count: " + (o.bootId < 0 ?
                    "Unavailable" : Long.toString(o.bootId))
                + "\nSource elapsed: " + o.sourceNs + " ns"
                + "\n\n" + o.details + "\n\nRaw BLE advertisement:\n"
                + (o.rawHex.isEmpty() ? "Not available from platform" :
                    o.rawHex));
        }
    }

    private void chooseDevice() {
        List<DeviceState> options = new ArrayList<>(repository.devices().values());
        options.sort(Comparator.comparing(d -> DeviceAdapter.name(
            d.latest, repository.saved()).toLowerCase(Locale.ROOT)));
        if (options.isEmpty()) {
            error("No devices have been observed yet.");
            return;
        }
        String[] names = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            Observation o = options.get(i).latest;
            names[i] = DeviceAdapter.name(o, repository.saved())
                + "\n" + o.radio + " · " + o.address;
        }
        new AlertDialog.Builder(this).setTitle("Choose an identity")
            .setItems(names, (d, index) -> {
                selectDevice(options.get(index).key);
                tab = "Hunt";
                render();
            }).show();
    }

    private void editDevice(String key) {
        DeviceState state = repository.devices().get(key);
        Store.SavedDevice old = repository.saved().get(key);
        String alias = old == null ? (state == null ? "" :
            state.latest.name) : old.alias;
        LinearLayout form = Ui.column(this);
        Ui.pad(this, form, 20, 10, 20, 4);
        form.addView(Ui.label(this, "YOUR LABEL"));
        form.addView(Ui.space(this, 6));
        EditText name = Ui.input(this, "Device label", alias);
        form.addView(name);
        form.addView(Ui.space(this, 15));
        form.addView(Ui.label(this, "NOTES"));
        form.addView(Ui.space(this, 6));
        EditText notes = Ui.input(this, "Observations and context",
            old == null ? "" : old.notes);
        notes.setSingleLine(false);
        notes.setMinLines(4);
        notes.setGravity(Gravity.TOP);
        form.addView(notes);
        new AlertDialog.Builder(this).setTitle("Save observed identity")
            .setMessage(key + "\n\nLabels are yours. They do not "
                + "establish a physical device identity across address changes.")
            .setView(form).setPositiveButton("Save", (d, w) -> {
                repository.save(key, name.getText().toString().trim(),
                    notes.getText().toString());
                render();
            }).setNegativeButton("Cancel", null)
            .setNeutralButton(old == null ? "" : "Remove", (d, w) -> {
                if (old != null) { repository.remove(key); render(); }
            }).show();
    }

    private void renderLibrary() {
        LinearLayout page = page("LOCAL DEVICE LIBRARY", "Your identities",
            "Names and notes stay on your phone. Address rotation "
            + "is not automatically merged into a physical identity.");
        List<Store.SavedDevice> saved = new ArrayList<>(repository.saved().values());
        saved.sort(Comparator.comparing(d -> d.alias.toLowerCase(Locale.ROOT)));
        if (saved.isEmpty()) page.addView(emptyCard(
            "Nothing saved yet", "Open Hunt and save an observed identity "
                + "to give it a label and field notes."));
        for (Store.SavedDevice d : saved) {
            DeviceState state = repository.devices().get(d.key);
            LinearLayout card = Ui.card(this);
            card.addView(Ui.label(this, d.key.startsWith("BLE:") ?
                "BLUETOOTH" : "WI-FI"));
            card.addView(Ui.space(this, 5));
            card.addView(Ui.text(this, d.alias.isEmpty() ? d.key : d.alias,
                17, Palette.TEXT, true));
            card.addView(Ui.space(this, 4));
            card.addView(Ui.mono(this, d.key, 10, Palette.MUTED));
            if (state != null) {
                card.addView(Ui.space(this, 8));
                card.addView(Ui.text(this, state.latest.rssi + " dBm · "
                    + DeviceAdapter.age(this, state.latest), 12,
                    Palette.ACCENT, false));
            }
            if (!d.notes.isEmpty()) {
                card.addView(Ui.space(this, 9));
                card.addView(Ui.text(this, d.notes, 12, Palette.MUTED, false));
            }
            card.addView(Ui.space(this, 12));
            LinearLayout actions = Ui.row(this);
            actions.addView(Ui.button(this, "Inspect", false, v -> {
                selectDevice(d.key); tab = "Hunt"; render();
            }));
            actions.addView(Ui.spaceWidth(this, 8));
            actions.addView(Ui.button(this, "Edit", false,
                v -> editDevice(d.key)));
            card.addView(actions);
            page.addView(card);
            page.addView(Ui.space(this, 10));
        }
        showPage(Ui.scroll(this, page));
    }

    private LinearLayout emptyCard(String title, String message) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, title, 17, Palette.TEXT, true));
        card.addView(Ui.space(this, 8));
        card.addView(Ui.text(this, message, 13, Palette.MUTED, false));
        return card;
    }

    private void renderSessions() {
        if (selectedSession != 0) {
            renderSessionDetail();
            return;
        }
        LinearLayout page = page("EVIDENCE ARCHIVE", "Sessions",
            "Record actual radio observations, then inspect or export "
            + "the complete local record.");
        LinearLayout controls = Ui.row(this);
        controls.addView(Ui.button(this, repository.recording() != 0 ?
            "Stop recording" : "New session", repository.recording() != 0,
            v -> toggleRecord()));
        page.addView(controls);
        page.addView(Ui.space(this, 16));
        if (repository.sessions().isEmpty()) page.addView(emptyCard(
            "No sessions recorded", "Start a recording while scanning. "
                + "Leaving the app closes the active session."));
        for (Store.Session s : repository.sessions()) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.label(this, "SESSION " + s.id + " / " + s.status));
            card.addView(Ui.space(this, 6));
            card.addView(Ui.text(this, s.title, 17, Palette.TEXT, true));
            card.addView(Ui.space(this, 5));
            card.addView(Ui.mono(this, date.format(new Date(s.startedMs)),
                11, Palette.MUTED));
            card.addView(Ui.space(this, 12));
            card.addView(Ui.button(this, "Open record", false, v -> {
                selectedSession = s.id;
                sessionKey = null;
                render();
            }));
            page.addView(card);
            page.addView(Ui.space(this, 10));
        }
        showPage(Ui.scroll(this, page));
    }

    private void renderSessionDetail() {
        Store.Session session = null;
        for (Store.Session s : repository.sessions()) {
            if (s.id == selectedSession) { session = s; break; }
        }
        if (session == null) {
            selectedSession = 0; renderSessions(); return;
        }
        final Store.Session current = session;
        LinearLayout page = page("RECORDED EVIDENCE / " + session.id,
            session.title, date.format(new Date(session.startedMs))
            + " · " + session.status);
        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.button(this, "Back", false, v -> {
            selectedSession = 0; render();
        }));
        actions.addView(Ui.spaceWidth(this, 8));
        actions.addView(Ui.button(this, "Export JSON", true,
            v -> export(current)));
        page.addView(actions);
        page.addView(Ui.space(this, 15));
        LinearLayout summary = Ui.card(this);
        summary.addView(Ui.label(this, "RECORDING"));
        summary.addView(Ui.space(this, 8));
        TextView summaryText = Ui.mono(this, "Loading stored observations...",
            12, Palette.TEXT);
        summary.addView(summaryText);
        page.addView(summary);
        page.addView(Ui.space(this, 12));
        LinearLayout graphCard = Ui.card(this);
        graphCard.addView(Ui.label(this, "RECORDED TRACE"));
        graphCard.addView(Ui.space(this, 8));
        TextView selected = Ui.mono(this, "Select an identity below",
            12, Palette.MUTED);
        graphCard.addView(selected);
        graphCard.addView(Ui.space(this, 8));
        TraceView graph = new TraceView(this);
        graphCard.addView(graph, new LinearLayout.LayoutParams(-1,
            Ui.dp(this, 230)));
        TextView sampleText = Ui.mono(this, "", 11, Palette.MUTED);
        graphCard.addView(sampleText);
        graph.setSelection(o -> sampleText.setText(o.rssi + " dBm · "
            + date.format(new Date(o.wallMs)) + " · source "
            + o.sourceNs + " ns"));
        page.addView(graphCard);
        page.addView(Ui.space(this, 12));
        LinearLayout devices = Ui.column(this);
        page.addView(devices);
        long request = selectedSession;
        repository.sessionData(request, null, 0, values -> {
            if (selectedSession != request || !"Sessions".equals(tab)) return;
            sessionSamples = values;
            Set<String> ids = new HashSet<>();
            int ble = 0, wifi = 0;
            for (Observation o : values) {
                ids.add(o.key);
                if ("BLE".equals(o.radio)) ble++; else wifi++;
            }
            summaryText.setText(values.size() + " observations\n"
                + ids.size() + " observed identities\n"
                + ble + " BLE / " + wifi + " Wi-Fi\n"
                + "Started: " + date.format(new Date(current.startedMs))
                + "\nEnded: " + (current.endedMs == null ?
                    "Recording" : date.format(new Date(current.endedMs))));
            devices.removeAllViews();
            devices.addView(Ui.label(this, "RECORDED IDENTITIES"));
            devices.addView(Ui.space(this, 10));
            if (ids.isEmpty()) devices.addView(emptyCard(
                "No observations", "This session contains no recorded samples."));
            for (String key : ids) {
                Observation last = null;
                int count = 0;
                for (Observation o : values) {
                    if (o.key.equals(key)) { last = o; count++; }
                }
                if (last == null) continue;
                Observation sample = last;
                LinearLayout row = Ui.card(this);
                row.addView(Ui.text(this, DeviceAdapter.name(sample,
                    repository.saved()), 14, Palette.TEXT, true));
                row.addView(Ui.space(this, 3));
                row.addView(Ui.mono(this, key, 10, Palette.MUTED));
                row.addView(Ui.space(this, 5));
                row.addView(Ui.text(this, count + " samples · latest "
                    + sample.rssi + " dBm", 11, Palette.ACCENT, false));
                row.setOnClickListener(v -> {
                    sessionKey = key;
                    ArrayList<Observation> subset = new ArrayList<>();
                    for (Observation o : sessionSamples) {
                        if (o.key.equals(key)) subset.add(o);
                    }
                    graph.setSamples(subset);
                    SignalMath.Stats s = SignalMath.stats(subset);
                    selected.setText(key + "\n" + s.count + " samples · min "
                        + s.min + " · max " + s.max + " · mean "
                        + String.format(Locale.US, "%.1f", s.mean) + " dBm");
                    sampleText.setText("");
                });
                devices.addView(row);
                devices.addView(Ui.space(this, 8));
            }
            if (sessionKey != null) {
                ArrayList<Observation> subset = new ArrayList<>();
                for (Observation o : values) {
                    if (o.key.equals(sessionKey)) subset.add(o);
                }
                if (!subset.isEmpty()) graph.setSamples(subset);
            }
        });
        showPage(Ui.scroll(this, page));
    }

    private void export(Store.Session session) {
        exportSession = session.id;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,
            "signal-hunter-" + session.id + ".json");
        startActivityForResult(intent, EXPORT);
    }

    @Override protected void onActivityResult(int request, int result,
            Intent data) {
        super.onActivityResult(request, result, data);
        if ((request == ANALYST_EXPORT || request == ANALYST_IMPORT)
                && result == RESULT_OK && data != null
                && data.getData() != null) {
            final Uri uri = data.getData();
            final String snapshot = analystExport;
            new Thread(() -> {
                try {
                    if (request == ANALYST_EXPORT) {
                        if (snapshot == null) throw new java.io.IOException(
                            "Capture expired during Activity recreation; export again");
                        try (java.io.OutputStream out =
                                getContentResolver().openOutputStream(uri, "wt")) {
                            if (out == null) throw new java.io.IOException("No output");
                            out.write(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    } else {
                        java.io.ByteArrayOutputStream bytes =
                            new java.io.ByteArrayOutputStream();
                        try (java.io.InputStream in =
                                getContentResolver().openInputStream(uri)) {
                            if (in == null) throw new java.io.IOException("No input");
                            byte[] buf = new byte[8192]; int n;
                            while ((n = in.read(buf)) != -1) {
                                if (bytes.size()+n > 16000000)
                                    throw new java.io.IOException("Capture exceeds 16 MB");
                                bytes.write(buf, 0, n);
                            }
                        }
                        String json = bytes.toString("UTF-8");
                        runOnUiThread(() -> {
                            if (isDestroyed() || analyst == null) return;
                            try { analyst.load(json); }
                            catch (Exception e) { error("Import: " + e.getMessage()); }
                        });
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> error("Capture I/O: " + e.getMessage()));
                }
            }, "capture-io").start();
            return;
        }
        if (request == EXPORT && result == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) { error("Export destination missing"); return; }
            repository.exportSession(exportSession, uri,
                getContentResolver(), () ->
                    new AlertDialog.Builder(this).setTitle("Export complete")
                    .setMessage("The session was written as JSON to the "
                        + "location you selected.")
                    .setPositiveButton("OK", null).show());
        }
    }
}
