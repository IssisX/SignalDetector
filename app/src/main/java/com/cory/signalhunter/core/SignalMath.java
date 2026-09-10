package com.cory.signalhunter.core;

import java.util.ArrayList;
import java.util.List;

public final class SignalMath {
    private SignalMath() { }

    public static String hex(byte[] bytes) {
        if (bytes == null) return "";
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int n = bytes[i] & 255;
            out[i * 2] = digits[n >>> 4];
            out[i * 2 + 1] = digits[n & 15];
        }
        return new String(out);
    }

    public static long wallTime(long sourceNs, long nowNs,
            long nowWallMs) {
        if (sourceNs <= 0 || sourceNs > nowNs) {
            throw new IllegalArgumentException("Invalid source clock");
        }
        return nowWallMs - (nowNs - sourceNs) / 1_000_000L;
    }

    public static int channel(int mhz) {
        if (mhz == 2484) return 14;
        if (mhz >= 2412 && mhz <= 2472 &&
                (mhz - 2412) % 5 == 0) {
            return 1 + (mhz - 2412) / 5;
        }
        if (mhz >= 5000 && mhz <= 5895 && mhz % 5 == 0) {
            return (mhz - 5000) / 5;
        }
        if (mhz == 5935) return 2;
        if (mhz >= 5955 && mhz <= 7115 &&
                (mhz - 5955) % 5 == 0) {
            return 1 + (mhz - 5955) / 5;
        }
        return -1;
    }

    public static String band(int mhz) {
        if (mhz >= 2400 && mhz < 2500) return "2.4 GHz";
        if (mhz >= 4900 && mhz < 5925) return "5 GHz";
        if (mhz >= 5925 && mhz <= 7125) return "6 GHz";
        if (mhz >= 57000 && mhz <= 71000) return "60 GHz";
        return "Unknown band";
    }

    public static Stats stats(List<Observation> input) {
        if (input.isEmpty()) return new Stats(0, 0, 0, 0, 0);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        for (Observation o : input) {
            min = Math.min(min, o.rssi);
            max = Math.max(max, o.rssi);
            sum += o.rssi;
        }
        return new Stats(input.size(), min, max,
                (double) sum / input.size(),
                input.get(input.size() - 1).rssi);
    }

    public static final class Stats {
        public final int count, min, max, latest;
        public final double mean;
        public Stats(int count, int min, int max,
                double mean, int latest) {
            this.count = count;
            this.min = min;
            this.max = max;
            this.mean = mean;
            this.latest = latest;
        }
    }

    public static List<Observation> ordered(
            List<Observation> input) {
        ArrayList<Observation> out = new ArrayList<>(input);
        out.sort((a, b) -> Long.compare(a.sourceNs, b.sourceNs));
        return out;
    }
}
