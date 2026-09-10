package com.cory.signalhunter.core;

import java.util.*;

/** Coupled projections of authoritative observations. Never an ingest owner. */
public final class AdvancedAnalysis {
    private AdvancedAnalysis() { }

    public static final class Profile {
        public final DeviceState state;
        public final double mean, variance, stability;
        public final int widthMhz;
        public final boolean stale;
        public int overlaps;
        Profile(DeviceState d, double m, double v, double s, int w, boolean x) {
            state=d; mean=m; variance=v; stability=s; widthMhz=w; stale=x;
        }
    }

    public static final class Snapshot {
        public final Map<String,Profile> profiles = new LinkedHashMap<>();
        public final List<String> events = new ArrayList<>();
        public final List<String> overlaps = new ArrayList<>();
        public final TreeMap<Integer,Integer> channelCounts = new TreeMap<>();
    }

    public static Snapshot build(Map<String,DeviceState> source,
            Set<String> watches, long nowMs, boolean live) {
        Snapshot out = new Snapshot();

        // System 1: temporal profile from each identity's retained ring.
        for (DeviceState d : source.values()) {
            List<Observation> samples=d.recent();
            double mean=0, variance=0, gapMean=0, gapVariance=0;
            for (Observation o : samples) mean += o.rssi;
            if (!samples.isEmpty()) mean /= samples.size();
            for (Observation o : samples) variance += (o.rssi-mean)*(o.rssi-mean);
            if (!samples.isEmpty()) variance /= samples.size();
            int gaps=0;
            for (int i=1;i<samples.size();i++) {
                Observation a=samples.get(i-1), b=samples.get(i);
                if(a.bootId==b.bootId && b.sourceNs>a.sourceNs) {
                    gapMean+=(b.sourceNs-a.sourceNs)/1e6; gaps++;
                }
            }
            if(gaps>0) gapMean/=gaps;
            for (int i=1;i<samples.size();i++) {
                Observation a=samples.get(i-1), b=samples.get(i);
                if(a.bootId==b.bootId && b.sourceNs>a.sourceNs) {
                    double delta=(b.sourceNs-a.sourceNs)/1e6-gapMean;
                    gapVariance+=delta*delta;
                }
            }
            double cv=gaps==0 || gapMean==0 ? 1 : Math.sqrt(gapVariance/gaps)/gapMean;
            double stability=100/(1+Math.sqrt(variance)/10+cv);
            long staleAfter="BLE".equals(d.latest.radio)?30000:180000;
            boolean stale=live && nowMs-d.latest.wallMs>staleAfter;
            Profile p=new Profile(d,mean,variance,stability,
                width(d.latest),stale);
            out.profiles.put(d.key,p);
            if(d.latest.frequency>0)
                out.channelCounts.merge(d.latest.frequency,1,Integer::sum);
        }

        // System 2: channel footprints consume widths and temporal profiles.
        ArrayList<Profile> wifi=new ArrayList<>();
        for(Profile p:out.profiles.values())
            if(p.state.latest.frequency>0) wifi.add(p);
        for(int i=0;i<wifi.size();i++) for(int j=i+1;j<wifi.size();j++) {
            Profile a=wifi.get(i), b=wifi.get(j);
            Observation ao=a.state.latest, bo=b.state.latest;
            double reach=(a.widthMhz+b.widthMhz)/2.0;
            int delta=Math.abs(ao.frequency-bo.frequency);
            if(delta<reach) {
                a.overlaps++; b.overlaps++;
                String kind=delta==0?"CO-CHANNEL":"FOOTPRINT OVERLAP";
                out.overlaps.add(String.format(Locale.US,
                    "%s  %s  <->  %s  delta=%d MHz  widths=%d/%d MHz  stability=%.0f/%.0f",
                    kind,ao.address,bo.address,delta,a.widthMhz,b.widthMhz,
                    a.stability,b.stability));
            }
        }

        // System 3: mutation/presence alerts consume profile and overlap state.
        for(Profile p:out.profiles.values()) {
            DeviceState d=p.state; List<Observation> s=d.recent();
            String flag=watches.contains(d.key)?"WATCH ALERT  ":"";
            if(!s.isEmpty()) out.events.add(s.get(0).wallMs+"  "+flag+d.key+
                "  APPEARED / FIRST RETAINED");
            for(int i=1;i<s.size();i++) {
                String change=Analysis.changes(s.get(i-1),s.get(i));
                if(!change.isEmpty()) out.events.add(s.get(i).wallMs+"  "+flag+
                    d.key+"  "+change);
            }
            if(p.stale) out.events.add(d.latest.wallMs+"  "+flag+d.key+
                "  DISAPPEARANCE CANDIDATE / stale, not proof of departure");
            if(watches.contains(d.key) && p.overlaps>0)
                out.events.add(d.latest.wallMs+"  WATCH ALERT  "+d.key+
                    "  DERIVED channel-footprint relationships="+p.overlaps);
        }
        out.events.sort(Collections.reverseOrder());
        out.overlaps.sort(String::compareTo);
        return out;
    }

    /** Android ScanResult channelWidth constants mapped to nominal MHz. */
    public static int width(Observation o) {
        String raw=Analysis.field(o.details,"Channel width code");
        try {
            switch(Integer.parseInt(raw.trim())) {
                case 1: return 40;
                case 2: return 80;
                case 3: return 160;
                case 4: return 160; // 80+80: aggregate span, centers retained separately.
                case 5: return 320;
                default: return 20;
            }
        } catch(RuntimeException e) { return 20; }
    }
}
