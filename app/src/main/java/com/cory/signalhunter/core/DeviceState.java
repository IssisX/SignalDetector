package com.cory.signalhunter.core;

import java.util.ArrayList;
import java.util.List;

public final class DeviceState {
    public final String key;
    public Observation latest;
    public int count;
    private final Observation[] recent = new Observation[360];
    private int next;
    private int size;

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
        recent[next] = o;
        next = (next + 1) % recent.length;
        size = Math.min(size + 1, recent.length);
        return true;
    }

    public List<Observation> recent() {
        ArrayList<Observation> out = new ArrayList<>(size);
        int start = (next - size + recent.length) % recent.length;
        for (int i = 0; i < size; i++) {
            out.add(recent[(start + i) % recent.length]);
        }
        return out;
    }
}
