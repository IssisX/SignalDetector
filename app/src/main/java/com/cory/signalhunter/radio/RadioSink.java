package com.cory.signalhunter.radio;

import com.cory.signalhunter.core.Observation;

public interface RadioSink {
    void observation(Observation observation);
    void status(String radio, String message);
}
