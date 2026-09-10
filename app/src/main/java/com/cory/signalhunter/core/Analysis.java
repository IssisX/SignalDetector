package com.cory.signalhunter.core;

import java.util.List;
import java.util.Locale;

/** Pure projections of retained observations, never radio truth. */
public final class Analysis {
    private Analysis() { }

    public static String field(String details, String key) {
        for (String line : details.split("\n")) {
            if (line.startsWith(key + ": ")) {
                return line.substring(key.length() + 2);
            }
        }
        return "Not exposed";
    }

    public static String series(List<Observation> samples) {
        if (samples.size() < 3) return
            "DERIVED: insufficient samples (minimum 3)";
        double mean = 0, variance = 0, gap = 0, gapVar = 0;
        for (Observation o : samples) mean += o.rssi;
        mean /= samples.size();
        for (Observation o : samples) {
            variance += (o.rssi - mean) * (o.rssi - mean);
        }
        variance /= samples.size();
        int n = 0;
        for (int i = 1; i < samples.size(); i++) {
            Observation a = samples.get(i - 1), b = samples.get(i);
            if (a.bootId != b.bootId || b.sourceNs <= a.sourceNs)
                continue;
            gap += (b.sourceNs - a.sourceNs) / 1e6;
            n++;
        }
        if (n == 0) return "DERIVED: no same-boot intervals";
        gap /= n;
        for (int i = 1; i < samples.size(); i++) {
            Observation a = samples.get(i - 1), b = samples.get(i);
            if (a.bootId != b.bootId || b.sourceNs <= a.sourceNs)
                continue;
            double d = (b.sourceNs - a.sourceNs) / 1e6 - gap;
            gapVar += d * d;
        }
        double cv = Math.sqrt(gapVar / n) / gap;
        double score = 100 / (1 + Math.sqrt(variance) / 10 + cv);
        return String.format(Locale.US,
            "DERIVED / retained %d samples\n"
            + "RSSI mean %.1f dBm; variance %.2f dB²\n"
            + "Observed interval %.0f ms; CV %.2f\n"
            + "Stability %.1f / 100\n"
            + "100/(1 + RSSI SD/10 + interval CV)\n"
            + "Observation interval is NOT beacon/advertising interval",
            samples.size(), mean, variance, gap, cv, score);
    }

    public static String changes(Observation a, Observation b) {
        StringBuilder out = new StringBuilder();
        if (!a.name.equals(b.name)) out.append("NAME ");
        if (a.frequency != b.frequency) out.append("HOP ");
        if (!field(a.details, "Capabilities").equals(
                field(b.details, "Capabilities"))) out.append("CRYPTO ");
        if (!field(a.details, "Channel width code").equals(
                field(b.details, "Channel width code")))
            out.append("WIDTH ");
        if (!elements(a.details).equals(elements(b.details)))
            out.append("IE ");
        if (!a.rawHex.equals(b.rawHex)) out.append("AD ");
        return out.toString().trim();
    }

    private static String elements(String details) {
        StringBuilder out = new StringBuilder();
        for (String line : details.split("\n")) {
            if (line.startsWith("IE ")) out.append(line);
        }
        return out.toString();
    }

    public static String identity(Observation o) {
        if ("BLE".equals(o.radio)) return
            "Address type: not exposed by this collector\n"
            + "Address rotation possible; no physical identity claim";
        try {
            int first = Integer.parseInt(o.address.substring(0, 2), 16);
            return "DERIVED address bits: "
                + ((first & 2) != 0 ? "locally administered" :
                    "universally administered")
                + ((first & 1) != 0 ? "; multicast" : "; unicast")
                + "\nOUI ownership is not device identity";
        } catch (RuntimeException e) {
            return "Address format not parseable";
        }
    }
}
