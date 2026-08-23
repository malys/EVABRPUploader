package com.evsuite.abrp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Charge session energy integration — pure JVM, no car needed.
 *
 * The rule under test: kwh_charged is a per-session figure that only ever grows while a
 * session is open, and is absent (null) the rest of the time. A stale total carried into
 * the next charge would tell ABRP the session started part-way through.
 */
public class ChargeMeterTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;

    @Test
    public void nothingIsReportedWhileNotCharging() {
        ChargeMeter meter = new ChargeMeter();
        assertNull(meter.sample(T0, Boolean.FALSE, 0f));
        assertNull(meter.sample(T0 + MINUTE, Boolean.FALSE, 0f));
    }

    @Test
    public void aSessionStartsAtZero() {
        ChargeMeter meter = new ChargeMeter();
        assertEquals(0.0, meter.sample(T0, Boolean.TRUE, 11f), 0.001);
    }

    @Test
    public void energyIsIntegratedOverTheSession() {
        ChargeMeter meter = new ChargeMeter();
        meter.sample(T0, Boolean.TRUE, 11f);
        // A flat 11 kW for one hour is 11 kWh, sampled every 15 minutes.
        double kwh = 0;
        for (int i = 1; i <= 4; i++) {
            kwh = meter.sample(T0 + i * 15 * MINUTE, Boolean.TRUE, 11f);
        }
        assertEquals(11.0, kwh, 0.01);
    }

    @Test
    public void aRampingRateIsIntegratedAsATrapezoid() {
        ChargeMeter meter = new ChargeMeter();
        meter.sample(T0, Boolean.TRUE, 100f);
        // 100 kW tapering to 50 kW over 20 min averages 75 kW → 25 kWh.
        assertEquals(25.0, meter.sample(T0 + 20 * MINUTE, Boolean.TRUE, 50f), 0.01);
    }

    @Test
    public void aNewSessionRestartsFromZero() {
        ChargeMeter meter = new ChargeMeter();
        meter.sample(T0, Boolean.TRUE, 50f);
        meter.sample(T0 + 15 * MINUTE, Boolean.TRUE, 50f);
        assertNull(meter.sample(T0 + 16 * MINUTE, Boolean.FALSE, 0f));
        assertEquals(0.0, meter.sample(T0 + 120 * MINUTE, Boolean.TRUE, 50f), 0.001);
    }

    @Test
    public void anUnknownChargeStateHoldsTheTotalWithoutGuessing() {
        ChargeMeter meter = new ChargeMeter();
        meter.sample(T0, Boolean.TRUE, 50f);
        double afterAQuarter = meter.sample(T0 + 15 * MINUTE, Boolean.TRUE, 50f);
        assertEquals(12.5, afterAQuarter, 0.01);
        // The car link drops: no rate to integrate, but the session is still open.
        assertEquals(afterAQuarter, meter.sample(T0 + 25 * MINUTE, null, null), 0.001);
        // and the gap it covered is not back-filled when readings return.
        assertEquals(afterAQuarter, meter.sample(T0 + 25 * MINUTE, Boolean.TRUE, 50f), 0.001);
    }

    @Test
    public void anUnknownChargeStateOutsideASessionReportsNothing() {
        assertNull(new ChargeMeter().sample(T0, null, null));
    }

    @Test
    public void aGapTooLongToTrustIsSkipped() {
        ChargeMeter meter = new ChargeMeter();
        meter.sample(T0, Boolean.TRUE, 50f);
        long gap = ChargeMeter.MAX_SAMPLE_GAP_MS + MINUTE;
        assertEquals(0.0, meter.sample(T0 + gap, Boolean.TRUE, 50f), 0.001);
        // Sampling resumes normally from there.
        assertEquals(12.5, meter.sample(T0 + gap + 15 * MINUTE, Boolean.TRUE, 50f), 0.01);
    }

    @Test
    public void aNegativeRateNeverDrainsTheTotal() {
        ChargeMeter meter = new ChargeMeter();
        meter.sample(T0, Boolean.TRUE, 50f);
        double reached = meter.sample(T0 + 15 * MINUTE, Boolean.TRUE, 50f);
        assertEquals(12.5, reached, 0.01);
        // A sign flip in the VHAL must not subtract energy already counted: the negative
        // rate is read as zero, so the interval ramps 50 kW → 0 kW and adds 6.25 kWh.
        assertEquals(18.75, meter.sample(T0 + 30 * MINUTE, Boolean.TRUE, -50f), 0.01);
    }

    @Test
    public void anUnreadableRateDoesNotEndTheSession() {
        ChargeMeter meter = new ChargeMeter();
        meter.sample(T0, Boolean.TRUE, 50f);
        assertEquals(0.0, meter.sample(T0 + 10 * MINUTE, Boolean.TRUE, null), 0.001);
        // The interval that ended in an unreadable rate is lost — one endpoint of the
        // trapezoid is missing — but the session survives and counting resumes after it.
        assertEquals(0.0, meter.sample(T0 + 20 * MINUTE, Boolean.TRUE, 50f), 0.001);
        assertEquals(12.5, meter.sample(T0 + 35 * MINUTE, Boolean.TRUE, 50f), 0.01);
    }
}
