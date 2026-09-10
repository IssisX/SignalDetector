package com.cory.signalhunter.core;

import java.util.List;

public final class CoreTest {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    private static Observation sample(long ns, int dbm) {
        return new Observation("BLE", "aa:bb:cc:dd:ee:ff", "",
            dbm, 0, ns, 1000, 1000, 1, "", "");
    }
    public static void main(String[] args) {
        check(SignalMath.hex(new byte[]{0, 15, -1}).equals("000FFF"),
            "hex unsigned bytes");
        check(SignalMath.wallTime(2_000_000_000L,
            5_000_000_000L, 10000) == 7000, "clock conversion");
        check(SignalMath.channel(2412) == 1, "2.4 channel");
        check(SignalMath.channel(2484) == 14, "channel 14");
        check(SignalMath.channel(5180) == 36, "5 GHz channel");
        check(SignalMath.channel(5955) == 1, "6 GHz channel");
        check(SignalMath.channel(5935) == 2, "6 GHz channel 2");
        check(SignalMath.channel(1234) == -1, "unknown channel");
        check(SignalMath.band(2412).equals("2.4 GHz"), "band");
        Observation a = sample(100, -70);
        DeviceState state = new DeviceState(a);
        check(!state.accept(sample(100, -20)), "duplicate rejected");
        check(!state.accept(sample(99, -20)), "older rejected");
        check(state.accept(sample(101, -60)), "newer accepted");
        check(state.accept(new Observation("BLE",
            "aa:bb:cc:dd:ee:ff", "", -55, 0,
            1, 2000, 2000, 2, "", "")), "new boot accepted");
        check(!state.accept(sample(999, -10)), "older boot rejected");
        check(state.count == 3, "count correct");
        check(state.latest.rssi == -55, "latest correct");
        check(SignalMath.stats(state.recent()).mean == -185.0 / 3,
            "mean is arithmetic dBm mean");
        check(SignalMath.stats(List.of()).count == 0, "empty stats");
        check(a.key.equals("BLE:AA:BB:CC:DD:EE:FF"),
            "radio-qualified identity");
        try {
            new Observation("Wi-Fi", "", "", -50, 2412,
                1, 1, 1, 1, "", "");
            throw new AssertionError("missing address accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        try {
            SignalMath.wallTime(6, 5, 1000);
            throw new AssertionError("future timestamp accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        check(ProtocolFields.wifi(5, 0, "0003").contains("period=3"),
            "DTIM decode");
        check(ProtocolFields.wifi(11, 0, "0200800100")
            .contains("stations=2"), "QBSS little endian");
        check(ProtocolFields.wifi(48, 0, "0100000fac040100000fac04"
            + "0100000fac08c000").contains("required=true"),
            "RSN PMF bits");
        check(ProtocolFields.wifi(48, 0, "0100")
            .contains("TRUNCATED"), "short RSN rejected");
        check(ProtocolFields.bluetooth("020af6")
            .contains("-10 dBm"), "signed BLE Tx power");
        check(ProtocolFields.bluetooth("0501")
            .contains("TRUNCATED"), "AD bounds check");
        check(Analysis.series(List.of(sample(100, -70),
            sample(200, -70), sample(300, -70)))
            .contains("100.0 / 100"), "stable series");
        DeviceState ring = new DeviceState(sample(1, -70));
        for (int i=2;i<=500;i++) ring.accept(sample(i, -60));
        check(ring.recent().size()==360, "ring bounded");
        check(ring.recent().get(0).sourceNs==141, "ring ordered");
        check(ring.count==500, "ring total count");
        System.out.println("PASS " + checks + " core assertions");
    }
}
