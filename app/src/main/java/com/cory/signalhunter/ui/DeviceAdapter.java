package com.cory.signalhunter.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.cory.signalhunter.core.DeviceState;
import com.cory.signalhunter.core.Observation;
import com.cory.signalhunter.core.SignalMath;
import com.cory.signalhunter.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public final class DeviceAdapter extends BaseAdapter {
    private final Context context;
    private final Map<String, Store.SavedDevice> saved;
    private List<DeviceState> items = new ArrayList<>();

    public DeviceAdapter(Context context,
            Map<String, Store.SavedDevice> saved) {
        this.context = context;
        this.saved = saved;
    }

    public void setItems(List<DeviceState> values) {
        items = new ArrayList<>(values);
        notifyDataSetChanged();
    }
    @Override public int getCount() { return items.size(); }
    @Override public DeviceState getItem(int index) { return items.get(index); }
    @Override public long getItemId(int index) { return index; }

    public static String name(Observation o,
            Map<String, Store.SavedDevice> saved) {
        Store.SavedDevice d = saved.get(o.key);
        if (d != null && !d.alias.isEmpty()) return d.alias;
        if (!o.name.isEmpty()) return o.name;
        return "BLE".equals(o.radio) ? "Unknown advertiser" :
            "Hidden / unnamed network";
    }

    public static String age(Context context, Observation o) {
        long ms;
        int boot = android.provider.Settings.Global.getInt(
            context.getContentResolver(),
            android.provider.Settings.Global.BOOT_COUNT, -1);
        if (o.bootId >= 0 && o.bootId == boot) {
            ms = (SystemClock.elapsedRealtimeNanos() - o.sourceNs)
                / 1_000_000L;
        } else ms = System.currentTimeMillis() - o.wallMs;
        if (ms < 0) return "Clock changed";
        if (ms < 1000) return "now";
        if (ms < 60000) return ms / 1000 + "s ago";
        if (ms < 3600000) return ms / 60000 + "m ago";
        if (ms < 86400000) return ms / 3600000 + "h ago";
        return ms / 86400000 + "d ago";
    }

    @Override public View getView(int position, View convert,
            ViewGroup parent) {
        Holder h;
        if (convert == null) {
            LinearLayout root = Ui.row(context);
            Ui.pad(context, root, 12, 13, 12, 13);
            h = new Holder();
            LinearLayout left = Ui.column(context);
            h.name = Ui.text(context, "", 14, Palette.TEXT, true);
            h.name.setSingleLine(true);
            h.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            h.address = Ui.mono(context, "", 10, Palette.MUTED);
            h.meta = Ui.text(context, "", 11, Palette.MUTED, false);
            left.addView(h.name);
            left.addView(Ui.space(context, 3));
            left.addView(h.address);
            left.addView(Ui.space(context, 5));
            left.addView(h.meta);
            root.addView(left, new LinearLayout.LayoutParams(
                0, -2, 1));
            LinearLayout right = Ui.column(context);
            right.setGravity(Gravity.RIGHT);
            h.dbm = Ui.mono(context, "", 16, Palette.ACCENT);
            h.dbm.setGravity(Gravity.RIGHT);
            right.addView(h.dbm);
            h.spark = new Spark(context);
            right.addView(h.spark, new LinearLayout.LayoutParams(
                Ui.dp(context, 80), Ui.dp(context, 28)));
            root.addView(right);
            root.setTag(h);
            convert = root;
        } else h = (Holder) convert.getTag();
        DeviceState state = getItem(position);
        Observation o = state.latest;
        h.name.setText(name(o, saved));
        h.address.setText(o.address);
        String channel = o.frequency == 0 ? "" : " · "
            + SignalMath.band(o.frequency) + " ch "
            + (SignalMath.channel(o.frequency) < 0 ? "?" :
                SignalMath.channel(o.frequency));
        h.meta.setText(o.radio + channel + " · " + age(context, o));
        h.dbm.setText(o.rssi + " dBm");
        h.spark.setSamples(state.recent());
        return convert;
    }

    private static final class Holder {
        TextView name, address, meta, dbm;
        Spark spark;
    }

    private static final class Spark extends View {
        private List<Observation> values = new ArrayList<>();
        private final Paint p = new Paint(3);
        private final Path path = new Path();
        Spark(Context c) { super(c); }
        void setSamples(List<Observation> input) {
            values = input.size() > 40 ? new ArrayList<>(
                input.subList(input.size() - 40, input.size())) : input;
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            if (values.isEmpty()) return;
            long first = values.get(0).wallMs;
            long last = values.get(values.size() - 1).wallMs;
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (Observation o : values) {
                min = Math.min(min, o.rssi);
                max = Math.max(max, o.rssi);
            }
            float span = Math.max(1, max - min);
            long duration = Math.max(1, last - first);
            path.reset();
            Observation previous = null;
            for (Observation o : values) {
                float x = getWidth() * (o.wallMs - first) / duration;
                float y = 3 + (getHeight() - 6) *
                    (1f - (o.rssi - min) / span);
                if (previous == null || o.wallMs <= previous.wallMs ||
                    o.wallMs - previous.wallMs >
                    ("BLE".equals(o.radio) ? 15000 : 90000)) {
                    path.moveTo(x, y);
                } else path.lineTo(x, y);
                previous = o;
            }
            p.setColor(Palette.ACCENT);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(getContext(), 1.5f));
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
