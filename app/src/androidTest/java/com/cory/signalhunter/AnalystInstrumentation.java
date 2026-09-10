package com.cory.signalhunter;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.cory.signalhunter.ui.AnalystConsole;
import org.json.*;

/** Real Activity-path smoke test. Fixtures are imported as synthetic. */
public final class AnalystInstrumentation extends Instrumentation {
    private int checks;
    private String failure;
    @Override public void onCreate(Bundle b) { super.onCreate(b); start(); }
    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    private View find(View v, Class<?> type) {
        if (type.isInstance(v)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) {
                View out=find(g.getChildAt(i),type);
                if(out!=null) return out;
            }
        }
        return null;
    }
    private TextView text(View v, String label) {
        if(v instanceof TextView &&
                ((TextView)v).getText().toString().equals(label))
            return (TextView)v;
        if(v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) {
                TextView out=text(g.getChildAt(i),label);
                if(out!=null) return out;
            }
        }
        return null;
    }
    private String fixture() throws Exception {
        JSONObject root=new JSONObject();
        root.put("schema","signalhunter.analyst.1");
        root.put("path","SYNTHETIC / DEMO SCENE");
        JSONArray rows=new JSONArray();
        for(String radio:new String[]{"Wi-Fi","BLE"})
            for(int i=0;i<40;i++) for(int sample=1;sample<=3;sample++) {
                JSONObject o=new JSONObject();
                o.put("radio",radio).put("address",
                    String.format("02:00:00:00:00:%02x",i))
                    .put("name","fixture-"+i).put("rssi",-40-i)
                    .put("frequency",radio.equals("Wi-Fi")?2412:0)
                    .put("sourceNs",sample*1000000000L)
                    .put("wallMs",100000+sample*1000L)
                    .put("receivedMs",100000+sample*1000L)
                    .put("bootId",1).put("details","fixture")
                    .put("rawHex","");
                rows.put(o);
            }
        root.put("observations",rows);
        root.put("operator",new JSONObject().put(
            "note:Wi-Fi:02:00:00:00:00:00","fixture note"));
        return root.toString();
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Intent intent=new Intent(getTargetContext(),MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity a=startActivitySync(intent);
            waitForIdleSync();
            AnalystConsole console=(AnalystConsole)find(
                a.getWindow().getDecorView(),AnalystConsole.class);
            check(console!=null,"analyst is default screen");
            String input=fixture();
            runOnMainSync(() -> {
                try { console.load(input); }
                catch(Throwable t) { failure=t.toString(); }
            });
            waitForIdleSync();
            runOnMainSync(() -> {
                try {
                    check(failure==null,"fixture import");
                    ListView grid=(ListView)find(console,ListView.class);
                    check(grid.getCount()==40,"Wi-Fi rows");
                    check(grid.getChildCount()>=8,
                        "visible row count="+grid.getChildCount());
                    String export=console.export();
                    JSONObject j=new JSONObject(export);
                    check(j.getJSONArray("observations").length()==240,
                        "both radios exported");
                    console.load(export);
                    check(new JSONObject(console.export()).toString()
                        .equals(j.toString()),"JSON round trip");
                    text(console,"BLE").performClick();
                    check(grid.getCount()==40,"BLE rows");
                    EditText q=(EditText)find(console,EditText.class);
                    q.setText("fixture-39");
                    check(grid.getCount()==1,"BLE search");
                    text(console,"Wi-Fi").performClick();
                    check(grid.getCount()==1,"Wi-Fi search");
                    q.setText("");
                    grid.performItemClick(null,0,0);
                    check(new JSONObject(console.export())
                        .getJSONArray("observations").length()==3,
                        "selection export");
                    console.refresh();
                    check(new JSONObject(console.export())
                        .getJSONArray("observations").length()==3,
                        "selection survives refresh");
                    java.lang.reflect.Field replayField=
                        AnalystConsole.class.getDeclaredField("replay");
                    replayField.setAccessible(true);
                    java.util.Map<String,com.cory.signalhunter.core.DeviceState>
                        scene=(java.util.Map<String,
                        com.cory.signalhunter.core.DeviceState>)
                        replayField.get(console);
                    com.cory.signalhunter.core.DeviceState target=
                        scene.get("Wi-Fi:02:00:00:00:00:00");
                    target.accept(new com.cory.signalhunter.core.Observation(
                        "Wi-Fi","02:00:00:00:00:00","mutated fixture",
                        -90,2437,4000000000L,104000,104000,1,
                        "fixture", ""));
                    console.refresh();
                    JSONArray changed=new JSONObject(console.export())
                        .getJSONArray("observations");
                    check(changed.length()==4,"selection survives mutation");
                    check(changed.getJSONObject(3).getInt("rssi")==-90,
                        "new RSSI reaches selected series/export");
                } catch(Throwable t) { failure=t.toString(); }
            });
            if(failure!=null) throw new AssertionError(failure);
            waitForIdleSync();
            android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(
                    new java.io.File(getTargetContext().getFilesDir(),
                    "analyst.png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,
                    100,out);
            }
            result.putString("stream","ANALYST_PASS "+checks+" checks\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable t) {
            result.putString("stream","ANALYST_FAIL "+t+"\n");
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
