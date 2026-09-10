package com.cory.signalhunter.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.cory.signalhunter.core.Observation;
import java.util.ArrayList;
import java.util.List;

public final class Store extends SQLiteOpenHelper {
    public Store(Context context) {
        super(context, "signal-hunter.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE observations ("
            + "id INTEGER PRIMARY KEY, session_id INTEGER,"
            + "radio TEXT NOT NULL, address TEXT NOT NULL,"
            + "name TEXT NOT NULL, rssi INTEGER NOT NULL,"
            + "frequency INTEGER NOT NULL, source_ns INTEGER NOT NULL,"
            + "wall_ms INTEGER NOT NULL, received_ms INTEGER NOT NULL, boot_id INTEGER NOT NULL,"
            + "details TEXT NOT NULL, raw_hex TEXT NOT NULL,"
            + "UNIQUE(session_id, radio, address, boot_id, source_ns))");
        db.execSQL("CREATE INDEX observation_lookup ON observations"
            + "(radio, address, boot_id, source_ns)");
        db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY,"
            + "title TEXT NOT NULL, started_ms INTEGER NOT NULL,"
            + "ended_ms INTEGER, status TEXT NOT NULL)");
        db.execSQL("CREATE TABLE devices (key TEXT PRIMARY KEY,"
            + "alias TEXT NOT NULL, notes TEXT NOT NULL,"
            + "saved_ms INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db,
            int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported database upgrade");
    }

    public void insert(long session, Observation o) {
        ContentValues v = new ContentValues();
        v.put("session_id", session);
        v.put("radio", o.radio);
        v.put("address", o.address);
        v.put("name", o.name);
        v.put("rssi", o.rssi);
        v.put("frequency", o.frequency);
        v.put("source_ns", o.sourceNs);
        v.put("wall_ms", o.wallMs);
        v.put("received_ms", o.receivedMs);
        v.put("boot_id", o.bootId);
        v.put("details", o.details);
        v.put("raw_hex", o.rawHex);
        getWritableDatabase().insertWithOnConflict("observations",
            null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public long startSession(String title, long now) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("started_ms", now);
        v.put("status", "recording");
        return getWritableDatabase().insertOrThrow("sessions", null, v);
    }

    public void endSession(long id, long now) {
        ContentValues v = new ContentValues();
        v.put("ended_ms", now);
        v.put("status", "complete");
        getWritableDatabase().update("sessions", v, "id=?",
            new String[]{Long.toString(id)});
    }

    public void recoverInterrupted(long now) {
        ContentValues v = new ContentValues();
        v.put("ended_ms", now);
        v.put("status", "interrupted");
        getWritableDatabase().update("sessions", v,
            "status='recording'", null);
    }

    public void save(String key, String alias, String notes,
            long now) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("alias", alias);
        v.put("notes", notes);
        v.put("saved_ms", now);
        getWritableDatabase().insertWithOnConflict("devices", null,
            v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void remove(String key) {
        getWritableDatabase().delete("devices", "key=?",
            new String[]{key});
    }

    public List<SavedDevice> saved() {
        ArrayList<SavedDevice> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT key,alias,notes,saved_ms FROM devices "
                + "ORDER BY alias COLLATE NOCASE", null)) {
            while (c.moveToNext()) out.add(new SavedDevice(
                c.getString(0), c.getString(1), c.getString(2),
                c.getLong(3)));
        }
        return out;
    }

    public List<Session> sessions() {
        ArrayList<Session> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,started_ms,ended_ms,status "
                + "FROM sessions ORDER BY id DESC", null)) {
            while (c.moveToNext()) out.add(new Session(
                c.getLong(0), c.getString(1), c.getLong(2),
                c.isNull(3) ? null : c.getLong(3), c.getString(4)));
        }
        return out;
    }

    public List<Observation> observations(long session,
            String key, int limit) {
        String where = "session_id=?";
        ArrayList<String> args = new ArrayList<>();
        args.add(Long.toString(session));
        if (key != null) {
            int colon = key.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("Invalid key");
            where += " AND radio=? AND address=?";
            args.add(key.substring(0, colon));
            args.add(key.substring(colon + 1));
        }
        return query(where, args.toArray(new String[0]), limit);
    }

    public List<Observation> latestDevices() {
        return query("1=1", new String[0], 5000);
    }

    public List<Observation> history(String key, int limit) {
        int colon = key.indexOf(':');
        if (colon < 1) throw new IllegalArgumentException("Invalid key");
        return query("radio=? AND address=?", new String[]{
            key.substring(0, colon), key.substring(colon + 1)}, limit);
    }

    private List<Observation> query(String where, String[] args,
            int limit) {
        ArrayList<Observation> out = new ArrayList<>();
        String sql = "SELECT radio,address,name,rssi,frequency,"
            + "source_ns,wall_ms,received_ms,boot_id,details,raw_hex "
            + "FROM observations WHERE " + where
            + " ORDER BY id DESC";
        if (limit > 0) sql += " LIMIT " + limit;
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(new Observation(
                c.getString(0), c.getString(1), c.getString(2),
                c.getInt(3), c.getInt(4), c.getLong(5),
                c.getLong(6), c.getLong(7), c.getLong(8),
                c.getString(9), c.getString(10)));
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public static final class SavedDevice {
        public final String key, alias, notes;
        public final long savedMs;
        public SavedDevice(String key, String alias, String notes,
                long savedMs) {
            this.key = key;
            this.alias = alias;
            this.notes = notes;
            this.savedMs = savedMs;
        }
    }

    public static final class Session {
        public final long id, startedMs;
        public final Long endedMs;
        public final String title, status;
        public Session(long id, String title, long startedMs,
                Long endedMs, String status) {
            this.id = id;
            this.title = title;
            this.startedMs = startedMs;
            this.endedMs = endedMs;
            this.status = status;
        }
    }
}
