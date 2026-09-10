package com.cory.signalhunter.core;

import java.util.ArrayList;
import java.util.List;

public final class DeviceState {
    public final String key;
    public Observation latest;
    public int count;
    private final ArrayList<Observation> recent = new ArrayList<>();

    public DeviceState(Observation first) {
        key = first.key;
        accept(first);
    }

    public boolean accept(Observation o) {
        if (!key.equals(o.key)) {
            throw new IllegalArgumentException("Identity mismatch");
        }
        if (latest != null) {
            if (o.bootId >= 0 && latest.bootId >= 0) {
                if (o.bootId < latest.bootId) return false;
                if (o.bootId == latest.bootId &&
                        o.sourceNs <= latest.sourceNs) return false;
            } else if (o.receivedMs < latest.receivedMs ||
                    (o.receivedMs == latest.receivedMs &&
                    o.sourceNs <= latest.sourceNs)) return false;
        }
        latest = o;
        count++;
        recent.add(o);
        if (recent.size() > 360) recent.remove(0);
        return true;
    }

    public List<Observation> recent() {
        return new ArrayList<>(recent);
    }
}
