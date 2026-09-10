package com.cory.signalhunter.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class SessionExport {
    private SessionExport() { }

    public static void write(Store store, long sessionId,
            OutputStream output) throws IOException {
        SQLiteDatabase db = store.getReadableDatabase();
        try (JsonWriter json = new JsonWriter(new OutputStreamWriter(
                output, StandardCharsets.UTF_8))) {
            json.setIndent("  ");
            json.beginObject();
            json.name("schema").value("signal-hunter/session/1");
            json.name("session");
            try (Cursor c = db.rawQuery(
                    "SELECT id,title,started_ms,ended_ms,status "
                    + "FROM sessions WHERE id=?",
                    new String[]{Long.toString(sessionId)})) {
                if (!c.moveToFirst()) throw new IOException("Session not found");
                json.beginObject();
                json.name("id").value(c.getLong(0));
                json.name("title").value(c.getString(1));
                json.name("started_ms").value(c.getLong(2));
                json.name("ended_ms");
                if (c.isNull(3)) json.nullValue();
                else json.value(c.getLong(3));
                json.name("status").value(c.getString(4));
                json.endObject();
            }
            json.name("observations").beginArray();
            try (Cursor c = db.rawQuery(
                    "SELECT radio,address,name,rssi,frequency,"
                    + "source_ns,wall_ms,received_ms,boot_id,details,raw_hex "
                    + "FROM observations WHERE session_id=? "
                    + "ORDER BY id",
                    new String[]{Long.toString(sessionId)})) {
                while (c.moveToNext()) {
                    json.beginObject();
                    json.name("radio").value(c.getString(0));
                    json.name("address").value(c.getString(1));
                    json.name("name").value(c.getString(2));
                    json.name("rssi_dbm").value(c.getInt(3));
                    json.name("frequency_mhz").value(c.getInt(4));
                    json.name("source_elapsed_ns").value(c.getLong(5));
                    json.name("wall_ms").value(c.getLong(6));
                    json.name("received_ms").value(c.getLong(7));
                    json.name("boot_id").value(c.getLong(8));
                    json.name("details").value(c.getString(9));
                    json.name("raw_hex").value(c.getString(10));
                    json.endObject();
                }
            }
            json.endArray();
            json.endObject();
        }
    }
}
