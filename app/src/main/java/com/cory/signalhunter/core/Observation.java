package com.cory.signalhunter.core;

public final class Observation {
    public final String radio;
    public final String address;
    public final String name;
    public final int rssi;
    public final int frequency;
    public final long sourceNs;
    public final long wallMs;
    public final long receivedMs;
    public final long bootId;
    public final String details;
    public final String rawHex;
    public final String key;

    public Observation(String radio, String address, String name,
            int rssi, int frequency, long sourceNs, long wallMs,
            long receivedMs, long bootId, String details, String rawHex) {
        if (!"BLE".equals(radio) && !"Wi-Fi".equals(radio)) {
            throw new IllegalArgumentException("Unknown radio");
        }
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Missing address");
        }
        if (sourceNs <= 0 || wallMs <= 0 || receivedMs <= 0) {
            throw new IllegalArgumentException("Invalid timestamp");
        }
        this.radio = radio;
        this.address = address.toUpperCase(java.util.Locale.ROOT);
        this.key = radio + ":" + this.address;
        this.name = name == null ? "" : name;
        this.rssi = rssi;
        this.frequency = frequency;
        this.sourceNs = sourceNs;
        this.wallMs = wallMs;
        this.receivedMs = receivedMs;
        this.bootId = bootId;
        this.details = details == null ? "" : details;
        this.rawHex = rawHex == null ? "" : rawHex;
    }
}
