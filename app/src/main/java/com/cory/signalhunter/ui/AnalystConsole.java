package com.cory.signalhunter.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.text.*;
import com.cory.signalhunter.core.*;
import com.cory.signalhunter.store.Repository;
import org.json.*;
import java.util.*;

/** UI projection. Live observations remain owned by Repository. */
public final class AnalystConsole extends LinearLayout {
    public interface Files {
        void exportJson(String value);
        void importJson();
    }
    private final Activity host;
    private final Repository repo;
    private final Files files;
    private final SharedPreferences prefs;
    private final Set<String> selected = new HashSet<>();
    private final Set<String> watches = new HashSet<>();
    private final Map<String, DeviceState> replay = new LinkedHashMap<>();
    private final ArrayList<DeviceState> rows = new ArrayList<>();
    private final ListView grid;
    private final Rows adapter = new Rows();
    private final EditText query;
    private final TextView inspector, summary;
    private final TraceView trace;
    private final LinearLayout body, listPane, details;
    private LinearLayout drawer;
    private final TextView output;
    private final Spectrum spectrum;
    private String layer, order, focus = "", drawerMode = "Events";
    private boolean replayMode, watchOnly;
    private JSONObject replayOperator = new JSONObject();
    private String importedPath = "UNKNOWN";
    private String band = "All bands";
    private String comparison = "Import a second capture to compare.";
    private final boolean wide;

    public AnalystConsole(Activity activity, Repository repository,
            Files fileActions) {
        super(activity);
        host = activity; repo = repository; files = fileActions;
        prefs = host.getSharedPreferences("analyst", 0);
        watches.addAll(prefs.getStringSet("watches", new HashSet<>()));
        layer = prefs.getString("layer", "Wi-Fi");
        order = prefs.getString("order", "RSSI");
        wide = getResources().getConfiguration().screenWidthDp >= 600;
        setOrientation(VERTICAL);
        setBackgroundColor(Palette.BG);
        query = Ui.input(host, "Search IDs, names, protocol fields",
            prefs.getString("query", ""));
        query.setTextSize(12);
        addView(query, new LayoutParams(-1, Ui.dp(host, 40)));
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int c,int f){}
            public void onTextChanged(CharSequence s,int a,int b,int c){
                refresh();
            }
            public void afterTextChanged(Editable e){}
        });
        LinearLayout tools = Ui.row(host);
        for (String name : new String[]{"Wi-Fi", "BLE", "All",
                "STA", "BT Conn"}) {
            button(tools, name, () -> { layer = name; refresh(); });
        }
        button(tools, "Sort", () -> new AlertDialog.Builder(host)
            .setItems(new String[]{"RSSI", "Name", "Recent", "Channel"},
                (d, i) -> {
                    order = new String[]{"RSSI", "Name", "Recent",
                        "Channel"}[i]; refresh();
                }).show());
        button(tools, "Watch", () -> {
            for (String key : selected) {
                if (!watches.remove(key)) watches.add(key);
            }
            prefs.edit().putStringSet("watches", watches).apply();
            refresh();
        });
        button(tools, "Watch only", () -> {
            watchOnly = !watchOnly; refresh();
        });
        button(tools, "Copy", () -> copy(export()));
        button(tools, "Export", () -> files.exportJson(export()));
        button(tools, "Import", files::importJson);
        button(tools, "Live", () -> { replayMode = false; refresh(); });
        button(tools, "Save view", () -> {
            prefs.edit().putString("layer", layer)
                .putString("order", order)
                .putString("query", query.getText().toString()).apply();
            Toast.makeText(host, "View saved", Toast.LENGTH_SHORT).show();
        });
        button(tools, "Restore view", () -> {
            layer = prefs.getString("layer", "Wi-Fi");
            order = prefs.getString("order", "RSSI");
            query.setText(prefs.getString("query", ""));
            refresh();
        });
        button(tools, "Notes", this::notes);
        button(tools, "Session note", () -> {
            EditText note=Ui.input(host,"Capture note",
                operator("note:session"));
            note.setSingleLine(false);
            new AlertDialog.Builder(host).setTitle("Operator capture note")
                .setView(note).setPositiveButton("Save", (d,w) ->
                    operatorPut("note:session",note.getText().toString()))
                .setNegativeButton("Cancel",null).show();
        });
        button(tools, "Band", () -> {
            String[] bands={"All bands","2.4 GHz","5 GHz","6 GHz"};
            new AlertDialog.Builder(host).setItems(bands,(d,i) -> {
                band=bands[i]; refresh();
            }).show();
        });
        button(tools, "Link", this::link);
        HorizontalScrollView scroller = new HorizontalScrollView(host);
        scroller.addView(tools); addView(scroller);
        summary = Ui.mono(host, "", 10, Palette.ACCENT);
        addView(summary);
        body = Ui.column(host);
        body.setOrientation(wide ? HORIZONTAL : VERTICAL);
        listPane = Ui.column(host);
        listPane.addView(Ui.mono(host,
            "  NAME / IDENTIFIER              dBm / CHANNEL / AGE",
            10, Palette.MUTED));
        grid = new ListView(host);
        grid.setAdapter(adapter);
        grid.setDividerHeight(1);
        // Selection is keyed by observation identity, never adapter position.
        grid.setChoiceMode(ListView.CHOICE_MODE_NONE);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            focus = rows.get(pos).key;
            if (!selected.remove(focus)) selected.add(focus);
            refresh();
        });
        listPane.addView(grid, new LayoutParams(-1, 0, 1));
        float ratio = prefs.getFloat("split", .68f);
        body.addView(listPane, wide ? new LayoutParams(0, -1, ratio)
            : new LayoutParams(-1, 0, ratio));
        View splitter = new View(host);
        splitter.setBackgroundColor(Palette.LINE);
        body.addView(splitter, wide ?
            new LayoutParams(Ui.dp(host, 12), -1) :
            new LayoutParams(-1, Ui.dp(host, 12)));
        details = Ui.column(host);
        trace = new TraceView(host);
        details.addView(trace, new LayoutParams(-1, Ui.dp(host, 90)));
        inspector = Ui.mono(host, "Select an observation", 11, Palette.TEXT);
        inspector.setTextIsSelectable(true);
        details.addView(Ui.scroll(host, inspector),
            new LayoutParams(-1, 0, 1));
        body.addView(details, wide ? new LayoutParams(0, -1, 1-ratio)
            : new LayoutParams(-1, 0, 1-ratio));
        splitter.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                int[] loc = new int[2]; body.getLocationOnScreen(loc);
                float r = wide ? (e.getRawX()-loc[0])/body.getWidth()
                    : (e.getRawY()-loc[1])/body.getHeight();
                r = Math.max(.5f, Math.min(.85f, r));
                ((LayoutParams) listPane.getLayoutParams()).weight = r;
                ((LayoutParams) details.getLayoutParams()).weight = 1-r;
                body.requestLayout();
                prefs.edit().putFloat("split", r).apply();
            }
            return true;
        });
        addView(body, new LayoutParams(-1, 0, 1));
        LinearLayout tabs = Ui.row(host);
        for (String name : new String[]{"Events", "Spectrum",
                "Occupancy", "Overlap", "Links", "Diff"}) {
            button(tabs, name, () -> {
                drawerMode = name; drawer.setVisibility(VISIBLE);
                prefs.edit().putBoolean("drawer", true).apply();
                refresh();
            });
        }
        button(tabs, "Drawer", () -> {
            drawer.setVisibility(drawer.getVisibility() == VISIBLE ?
                GONE : VISIBLE);
            prefs.edit().putBoolean("drawer",
                drawer.getVisibility() == VISIBLE).apply();
        });
        HorizontalScrollView tabScroll = new HorizontalScrollView(host);
        tabScroll.addView(tabs); addView(tabScroll);
        drawer = Ui.column(host);
        output = Ui.mono(host, "", 10, Palette.MUTED);
        output.setTextIsSelectable(true);
        spectrum = new Spectrum(host);
        drawer.addView(spectrum, new LayoutParams(-1, Ui.dp(host, 100)));
        drawer.addView(Ui.scroll(host, output), new LayoutParams(-1, 0, 1));
        addView(drawer, new LayoutParams(-1, Ui.dp(host, 90)));
        drawer.setVisibility(prefs.getBoolean("drawer", false) ?
            VISIBLE : GONE);
        refresh();
    }

    private void button(LinearLayout row, String name, Runnable action) {
        TextView b = Ui.button(host, name, false, v -> action.run());
        b.setTextSize(11);
        b.setPadding(Ui.dp(host, 9), 0, Ui.dp(host, 9), 0);
        b.setMinHeight(Ui.dp(host, 36)); row.addView(b);
    }

    private Map<String, DeviceState> data() {
        return replayMode ? replay : repo.devices();
    }

    public void refresh() {
        if (summary == null) return;
        rows.clear();
        String q = query.getText().toString().toLowerCase(Locale.ROOT);
        for (DeviceState state : data().values()) {
            Observation o = state.latest;
            if (!"All".equals(layer) && !layer.equals(o.radio)) continue;
            if (!band.equals("All bands") && (o.frequency==0 ||
                    !SignalMath.band(o.frequency).equals(band))) continue;
            if (watchOnly && !watches.contains(o.key)) continue;
            if (!(o.name + o.address + o.details).toLowerCase(Locale.ROOT)
                    .contains(q)) continue;
            rows.add(state);
        }
        Comparator<DeviceState> cmp = Comparator.comparingInt(
            (DeviceState d) -> d.latest.rssi).reversed();
        if (order.equals("Name")) cmp = Comparator.comparing(
            d -> d.latest.name);
        if (order.equals("Recent")) cmp = Comparator.comparingLong(
            (DeviceState d) -> d.latest.wallMs).reversed();
        if (order.equals("Channel")) cmp = Comparator.comparingInt(
            d -> d.latest.frequency);
        rows.sort(cmp.thenComparing(d -> d.key));
        adapter.notifyDataSetChanged();
        summary.setText((replayMode ? "REPLAY / " + importedPath :
            "RADIO PATH LIVE / Android; Wi-Fi snapshots")
            + " | " + layer + " | " + rows.size() + " rows | "
            + selected.size() + " selected | " + order + " | " + band
            + (watchOnly ? " | WATCH FILTER" : "")
            + "\n" + new java.text.SimpleDateFormat("HH:mm:ss",
                Locale.US).format(new Date()));
        if (layer.equals("STA") || layer.equals("BT Conn")) {
            inspector.setText("NOT IN THIS DATA PATH\n"
                + "Passive Android discovery does not expose this table.");
            trace.setSamples(Collections.emptyList());
        } else inspect();
        drawAnalysis();
    }

    private void inspect() {
        DeviceState d = data().get(focus);
        if (d == null) {
            inspector.setText("Select a row. Tap more rows to multi-select."
                + "\nNo measurements? Check permissions and adapter status."
                + "\nNo distance, direction or physical identity inferred.");
            return;
        }
        Observation o = d.latest;
        List<Observation> samples = d.recent();
        trace.setSamples(samples);
        inspector.setText(o.key + "\n" + o.name
            + "\nPROVENANCE: " + (replayMode ? "imported capture" :
                "Android " + o.radio + " scan API")
            + "\nSource: " + o.sourceNs + " ns / boot " + o.bootId
            + "\nFirst retained: " + samples.get(0).wallMs
            + "\nLast source wall: " + o.wallMs
            + "\nReceived: " + o.receivedMs
            + "\nRetained: " + samples.size() + " / accepted " + d.count
            + "\nRSSI: " + o.rssi + " dBm\n"
            + Analysis.identity(o) + "\n\n" + Analysis.series(samples)
            + "\n\nPLATFORM FIELDS\n" + o.details
            + "\n\nRAW AD BYTES\n" + (o.rawHex.isEmpty() ?
                "Not exposed / not applicable" : o.rawHex)
            + "\n" + ProtocolFields.bluetooth(o.rawHex)
            + "\n\nOPERATOR NOTES\n"
            + operator("note:" + focus)
            + "\n\nRELATIONSHIPS\n" + relations(focus));
    }

    private String relations(String key) {
        DeviceState state = data().get(key);
        if (state == null) return "";
        Observation o = state.latest;
        StringBuilder s = new StringBuilder();
        for (DeviceState d : data().values()) {
            if (d.key.equals(key)) continue;
            Observation b = d.latest;
            if (!o.name.isEmpty() && o.name.equals(b.name)
                    && o.radio.equals("Wi-Fi") && b.radio.equals("Wi-Fi"))
                s.append("DERIVED same SSID: ").append(b.key).append('\n');
            if (!o.radio.equals(b.radio) &&
                    Math.abs(o.wallMs-b.wallMs) < 1000)
                s.append("HYPOTHESIS temporal coincidence <1s: ")
                    .append(b.key).append("; not identity\n");
        }
        s.append("OPERATOR ASSERTIONS: ")
            .append(operator("link:" + key));
        return s.toString();
    }

    private String operator(String key) {
        return replayMode ? replayOperator.optString(key, "") :
            prefs.getString(key, "");
    }

    private void operatorPut(String key, String value) {
        if (replayMode) {
            try { replayOperator.put(key, value); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        } else prefs.edit().putString(key, value).apply();
    }

    private void notes() {
        if (focus.isEmpty()) return;
        final String key = focus;
        EditText input = Ui.input(host, "Operator note",
            operator("note:"+key));
        input.setSingleLine(false);
        new AlertDialog.Builder(host).setTitle(key).setView(input)
            .setPositiveButton("Save", (d,w) -> {
                operatorPut("note:"+key, input.getText().toString());
                refresh();
            }).setNegativeButton("Cancel", null).show();
    }

    private void link() {
        if (selected.size() < 2) {
            Toast.makeText(host, "Select at least two rows",
                Toast.LENGTH_SHORT).show(); return;
        }
        ArrayList<String> keys = new ArrayList<>(selected);
        Collections.sort(keys);
        new AlertDialog.Builder(host).setTitle("Assert operator link?")
            .setMessage(String.join("\n", keys))
            .setPositiveButton("Assert", (d,w) -> {
                for (String key : keys) operatorPut(
                    "link:"+key, String.join("\n", keys));
                refresh();
            }).setNegativeButton("Cancel", null).show();
    }

    private void drawAnalysis() {
        spectrum.setVisibility((drawerMode.equals("Spectrum") ||
            drawerMode.equals("Occupancy")) ?
            VISIBLE : GONE);
        spectrum.invalidate();
        if (drawerMode.equals("Diff")) { output.setText(comparison); return; }
        StringBuilder s = new StringBuilder();
        if (drawerMode.equals("Links")) {
            s.append(relations(focus));
        } else if (drawerMode.equals("Overlap")) {
            s.append("DERIVED primary-channel proximity candidates\n"
                + "Co-channel: equal primary frequency; adjacent: <20MHz.\n"
                + "Not measured interference; widths/airtime not modeled.\n");
            ArrayList<DeviceState> all = new ArrayList<>(data().values());
            for (int i=0;i<all.size();i++) {
                Observation a=all.get(i).latest;
                if (a.frequency==0) continue;
                for (int j=i+1;j<all.size();j++) {
                    Observation b=all.get(j).latest;
                    if (b.frequency==0) continue;
                    int delta=Math.abs(a.frequency-b.frequency);
                    if (delta<20) s.append(a.address).append(" ↔ ")
                        .append(b.address).append(delta==0 ?
                            " CO-CHANNEL" : " ADJACENT")
                        .append(" Δ=").append(delta).append("MHz\n");
                }
            }
        } else if (drawerMode.equals("Occupancy")) {
            s.append("DERIVED observed BSS count / primary frequency\n"
                + "NOT measured airtime, noise or spectrum utilization\n");
            TreeMap<Integer,Integer> bins = new TreeMap<>();
            for (DeviceState d : data().values()) {
                if (d.latest.frequency > 0) bins.merge(
                    d.latest.frequency, 1, Integer::sum);
            }
            for (Map.Entry<Integer,Integer> b : bins.entrySet())
                s.append(b.getKey()).append(" MHz: ")
                    .append(b.getValue()).append(" observed BSS\n");
        } else if (drawerMode.equals("Spectrum")) {
            s.append("RSSI vs primary frequency / latest samples\n"
                + "Not a spectrum analyzer; no noise-floor measurement.");
        } else {
            s.append("DERIVED events / retained window; no packet claims\n");
            ArrayList<String> events = new ArrayList<>();
            for (DeviceState d : data().values()) {
                List<Observation> samples = d.recent();
                if (!samples.isEmpty()) events.add(samples.get(0).wallMs
                    + " " + d.key + " FIRST RETAINED OBSERVATION");
                for (int i = 1; i < samples.size(); i++) {
                    String change = Analysis.changes(samples.get(i-1),
                        samples.get(i));
                    if (!change.isEmpty()) events.add(
                        samples.get(i).wallMs + " " + d.key + " " + change
                        + (watches.contains(d.key) ? " WATCH ALERT" : ""));
                }
                long age = System.currentTimeMillis()-d.latest.wallMs;
                if (!replayMode && age >
                        (d.latest.radio.equals("BLE") ? 30000 : 180000))
                    events.add(d.latest.wallMs + " " + d.key
                        + " STALE (not proof of departure)"
                        + (watches.contains(d.key) ? " WATCH ALERT" : ""));
            }
            Collections.sort(events, Collections.reverseOrder());
            for (int i=0; i<Math.min(200, events.size()); i++)
                s.append(events.get(i)).append('\n');
        }
        output.setText(s.toString());
    }

    public String export() {
        try {
            JSONObject root = new JSONObject();
            root.put("schema", "signalhunter.analyst.1");
            root.put("path", replayMode ? importedPath : "ANDROID");
            JSONArray records = new JSONArray();
            for (DeviceState state : data().values()) {
                if (!selected.isEmpty() && !selected.contains(state.key))
                    continue;
                for (Observation o : state.recent()) {
                    JSONObject j = new JSONObject();
                    j.put("uid",o.key+":"+o.bootId+":"+o.sourceNs);
                    j.put("layer",o.radio.equals("BLE")?"bt":"wifi");
                    j.put("kind",o.radio.equals("BLE")?"advertisement":"BSS");
                    j.put("provenance",new JSONArray().put(
                        (replayMode && importedPath.startsWith("SYNTHETIC") ?
                        "SYNTHETIC / DEMO SCENE" : "Android "+o.radio
                        +" scan API / sourceNs / bootId")));
                    j.put("quality","OBSERVED; not physical identity; "
                        + "Wi-Fi may be cached; imported claims unverified");
                    j.put("radio", o.radio).put("address", o.address)
                        .put("name", o.name).put("rssi", o.rssi)
                        .put("frequency", o.frequency)
                        .put("sourceNs", o.sourceNs).put("wallMs", o.wallMs)
                        .put("receivedMs", o.receivedMs).put("bootId", o.bootId)
                        .put("details", o.details).put("rawHex", o.rawHex);
                    records.put(j);
                }
            }
            root.put("observations", records);
            JSONObject operator = replayMode ? replayOperator :
                new JSONObject();
            for (Map.Entry<String,?> e : prefs.getAll().entrySet()) {
                if (replayMode) break;
                if (e.getKey().startsWith("note:") ||
                        e.getKey().startsWith("link:"))
                    operator.put(e.getKey(), e.getValue());
            }
            root.put("operator", operator);
            return root.toString(2);
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public void load(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!root.getString("schema").equals("signalhunter.analyst.1"))
            throw new JSONException("Unsupported capture schema");
        JSONArray records = root.getJSONArray("observations");
        if (records.length() > 100000)
            throw new JSONException("Capture exceeds 100000 observations");
        String sourcePath = root.getString("path");
        if (!sourcePath.equals("ANDROID") &&
                !sourcePath.equals("SYNTHETIC / DEMO SCENE"))
            throw new JSONException("Unsupported capture provenance");
        JSONObject nextOperator = root.optJSONObject("operator");
        Map<String,DeviceState> next = new LinkedHashMap<>();
        for (int i=0; i<records.length(); i++) {
            JSONObject j = records.getJSONObject(i);
            Observation o = new Observation(j.getString("radio"),
                j.getString("address"), j.getString("name"),
                j.getInt("rssi"), j.getInt("frequency"),
                j.getLong("sourceNs"), j.getLong("wallMs"),
                j.getLong("receivedMs"), j.getLong("bootId"),
                j.getString("details"), j.getString("rawHex"));
            if (!next.containsKey(o.key)) next.put(o.key, new DeviceState(o));
            else {
                DeviceState d = next.get(o.key);
                if (d.count >= 360)
                    throw new JSONException("More than 360 samples per identity");
                if (!d.accept(o))
                    throw new JSONException("Duplicate/out-of-order observation");
            }
        }
        StringBuilder diff = new StringBuilder("Capture diff / latest fields\n");
        for (String key : next.keySet()) {
            DeviceState old = data().get(key);
            diff.append(key).append(' ').append(old == null ? "ADDED" :
                Analysis.changes(old.latest, next.get(key).latest))
                .append('\n');
        }
        for (String key : data().keySet()) {
            if (!next.containsKey(key)) diff.append(key).append(" ABSENT\n");
        }
        comparison = diff.toString();
        replay.clear(); replay.putAll(next); replayMode = true;
        importedPath = sourcePath;
        replayOperator = nextOperator == null ? new JSONObject() : nextOperator;
        selected.clear(); focus = "";
        // Imported assertions stay in replay, isolated from live operator facts.
        refresh();
    }

    private void copy(String value) {
        ((ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("Signal Hunter", value));
        Toast.makeText(host, "Copied", Toast.LENGTH_SHORT).show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || query.hasFocus())
            return super.dispatchKeyEvent(e);
        if (e.getKeyCode() == KeyEvent.KEYCODE_SLASH) {
            query.requestFocus(); return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            selected.clear(); refresh(); return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_E) {
            files.exportJson(export()); return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_J ||
                e.getKeyCode() == KeyEvent.KEYCODE_K) {
            int index = -1;
            for (int i=0; i<rows.size(); i++)
                if (rows.get(i).key.equals(focus)) index = i;
            index += e.getKeyCode() == KeyEvent.KEYCODE_J ? 1 : -1;
            if (!rows.isEmpty()) {
                index = Math.max(0, Math.min(rows.size()-1, index));
                focus = rows.get(index).key;
                grid.setSelection(index); inspect();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private final class Rows extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int i) { return rows.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View old, ViewGroup parent) {
            TextView t = old instanceof TextView ? (TextView) old :
                Ui.mono(host, "", 11, Palette.TEXT);
            Observation o = rows.get(i).latest;
            t.setMaxLines(2); t.setSingleLine(false);
            t.setPadding(8, 0, 8, 0);
            t.setHeight(Ui.dp(host, 40));
            t.setText((selected.contains(o.key) ? "✓ " : "  ")
                + (watches.contains(o.key) ? "★ " : "")
                + (o.name.isEmpty() ? "[unnamed]" : o.name)
                + "  " + o.rssi + " dBm\n  " + o.address
                + " | " + o.radio + " | ch "
                + (o.frequency == 0 ? "n/a" : SignalMath.channel(o.frequency))
                + " | " + DeviceAdapter.age(host, o));
            t.setBackgroundColor(o.key.equals(focus) ?
                Palette.RAISED : Palette.BG);
            return t;
        }
    }

    private final class Spectrum extends View {
        private final Paint paint = new Paint(3);
        Spectrum(Context c) { super(c); }
        protected void onDraw(Canvas c) {
            if (drawerMode.equals("Occupancy")) {
                TreeSet<Integer> frequencies = new TreeSet<>();
                long end = 0;
                for (DeviceState d : data().values()) {
                    if (d.latest.frequency>0) frequencies.add(d.latest.frequency);
                    end=Math.max(end,d.latest.wallMs);
                }
                if (frequencies.isEmpty()) return;
                int row=0;
                float height=getHeight()/(float)frequencies.size();
                paint.setTextSize(Ui.dp(host,9));
                for (int frequency : frequencies) {
                    int[] bins=new int[30];
                    for (DeviceState d : data().values())
                        for (Observation o : d.recent()) {
                            long age=end-o.wallMs;
                            if(o.frequency==frequency && age>=0 && age<300000)
                                bins[29-(int)(age/10000)]++;
                        }
                    paint.setColor(Palette.MUTED);
                    c.drawText(""+frequency,0,(row+1)*height,paint);
                    float width=(getWidth()-40f)/30;
                    for(int i=0;i<30;i++) {
                        paint.setColor(Palette.ACCENT);
                        paint.setAlpha(bins[i]==0 ? 0 :
                            Math.min(255,50+bins[i]*25));
                        c.drawRect(40+i*width,row*height,
                            40+(i+1)*width-1,(row+1)*height-1,paint);
                    }
                    paint.setAlpha(255); row++;
                }
                return;
            }
            int min = Integer.MAX_VALUE, max = 0;
            for (DeviceState d : data().values()) {
                if (d.latest.frequency == 0) continue;
                min = Math.min(min, d.latest.frequency);
                max = Math.max(max, d.latest.frequency);
            }
            paint.setColor(Palette.ACCENT);
            paint.setStrokeWidth(Ui.dp(host, 3));
            for (DeviceState d : data().values()) {
                Observation o = d.latest;
                if (o.frequency == 0) continue;
                float x = 8 + (getWidth()-16f)*(o.frequency-min)
                    / Math.max(1, max-min);
                float y = getHeight() * Math.max(0, Math.min(1,
                    (-o.rssi-20)/100f));
                c.drawLine(x, getHeight(), x, y, paint);
            }
        }
    }
}
