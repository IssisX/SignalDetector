package com.cory.signalhunter.store;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.cory.signalhunter.core.DeviceState;
import com.cory.signalhunter.core.Observation;
import com.cory.signalhunter.radio.RadioSink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Repository implements RadioSink {
    public interface Listener {
        void changed();
        void error(String message);
    }
    public interface Result<T> {
        void accept(T value);
    }

    private final Store store;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final Map<String, DeviceState> devices = new LinkedHashMap<>();
    private final Map<String, Store.SavedDevice> saved = new LinkedHashMap<>();
    private final Map<String, String> statuses = new LinkedHashMap<>();
    private List<Store.Session> sessions = new ArrayList<>();
    private long recording;
    private boolean starting;
    private volatile boolean stopAfterStart;
    private boolean closed;
    private boolean notificationPending;

    public Repository(Context context, Listener listener) {
        store = new Store(context);
        this.listener = listener;
    }

    private void task(Runnable runnable) {
        if (closed) return;
        disk.execute(() -> {
            try { runnable.run(); }
            catch (Exception e) {
                main.post(() -> listener.error("Storage: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        });
    }

    private void notifyChange() {
        if (notificationPending) return;
        notificationPending = true;
        main.postDelayed(() -> {
            notificationPending = false;
            listener.changed();
        }, 150);
    }

    public void load() {
        task(() -> {
            store.recoverInterrupted(System.currentTimeMillis());
            List<Observation> history = store.latestDevices();
            List<Store.SavedDevice> known = store.saved();
            List<Store.Session> list = store.sessions();
            main.post(() -> {
                for (Observation o : history) {
                    if (!devices.containsKey(o.key)) {
                        devices.put(o.key, new DeviceState(o));
                    } else devices.get(o.key).accept(o);
                }
                saved.clear();
                for (Store.SavedDevice d : known) saved.put(d.key, d);
                sessions = list;
                notifyChange();
            });
        });
    }

    @Override public void observation(Observation o) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> observation(o));
            return;
        }
        DeviceState state = devices.get(o.key);
        if (state == null) {
            state = new DeviceState(o);
            devices.put(o.key, state);
        } else if (!state.accept(o)) return;
        long session = recording;
        task(() -> store.insert(session, o));
        notifyChange();
    }

    @Override public void status(String radio, String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> status(radio, message));
            return;
        }
        statuses.put(radio, message);
        notifyChange();
    }

    public Map<String, DeviceState> devices() { return devices; }
    public Map<String, Store.SavedDevice> saved() { return saved; }
    public Map<String, String> statuses() { return statuses; }
    public List<Store.Session> sessions() { return sessions; }
    public long recording() { return recording; }
    public boolean starting() { return starting; }

    public void startSession(String title) {
        if (recording != 0 || starting) return;
        starting = true;
        stopAfterStart = false;
        notifyChange();
        task(() -> {
            long id = store.startSession(title, System.currentTimeMillis());
            boolean stopped = stopAfterStart || closed;
            if (stopped) store.endSession(id, System.currentTimeMillis());
            List<Store.Session> list = store.sessions();
            main.post(() -> {
                recording = stopped ? 0 : id;
                starting = false;
                sessions = list;
                notifyChange();
            });
        });
    }

    public void stopSession() {
        if (starting) {
            stopAfterStart = true;
            return;
        }
        if (recording == 0) return;
        long id = recording;
        recording = 0;
        task(() -> {
            store.endSession(id, System.currentTimeMillis());
            List<Store.Session> list = store.sessions();
            main.post(() -> { sessions = list; notifyChange(); });
        });
        notifyChange();
    }

    public void save(String key, String alias, String notes) {
        long now = System.currentTimeMillis();
        Store.SavedDevice d = new Store.SavedDevice(key, alias, notes, now);
        saved.put(key, d);
        task(() -> store.save(key, alias, notes, now));
        notifyChange();
    }

    public void remove(String key) {
        saved.remove(key);
        task(() -> store.remove(key));
        notifyChange();
    }

    public void history(String key, int limit,
            Result<List<Observation>> result) {
        task(() -> {
            List<Observation> out = store.history(key, limit);
            main.post(() -> result.accept(out));
        });
    }

    public void sessionData(long id, String key, int limit,
            Result<List<Observation>> result) {
        task(() -> {
            List<Observation> out = store.observations(id, key, limit);
            main.post(() -> result.accept(out));
        });
    }

    public void exportSession(long id, android.net.Uri uri,
            android.content.ContentResolver resolver, Runnable done) {
        task(() -> {
            try (java.io.OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) throw new java.io.IOException("No output stream");
                SessionExport.write(store, id, output);
            } catch (java.io.IOException e) {
                main.post(() -> listener.error("Export failed: " + e.getMessage()));
                return;
            }
            main.post(done);
        });
    }

    public void close() {
        stopSession();
        stopAfterStart = true;
        closed = true;
        disk.execute(() -> {
            try { store.close(); }
            catch (Exception e) {
                android.util.Log.e("SignalHunter", "Database close", e);
            }
        });
        disk.shutdown();
    }
}
