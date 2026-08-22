package com.evsuite.abrp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Charge detection. The reported regression was a charging car being read as
 * "parked and not charging", which hands it to {@link UploadCadence#PARKED_INTERVAL_MS}
 * and costs ABRP the SOC curve it needs to size a charging stop.
 */
public class ChargingSignalTest {

    private static final long T0 = 1_700_000_000_000L;

    private final ChargingSignal signal = new ChargingSignal();

    // ---- Charge port property, when the VHAL answers it ----

    @Test
    public void aConnectedPortIsCharging() {
        assertTrue(signal.evaluate(T0, true, null, true, 50));
    }

    @Test
    public void anUnpluggedParkedCarIsNotCharging() {
        assertFalse(signal.evaluate(T0, false, null, true, 50));
    }

    @Test
    public void anUnreadablePortStaysUnknown() {
        // Unknown must not collapse to "not charging": UploadCadence treats null as active.
        assertNull(signal.evaluate(T0, null, null, true, 50));
    }

    // ---- Fallback 1: charge rate ----

    @Test
    public void aPositiveChargeRateOverridesAPortThatReadsUnplugged() {
        assertTrue(signal.evaluate(T0, false, 7.4f, true, 50));
    }

    @Test
    public void aNegativeChargeRateIsDriving() {
        assertFalse(signal.evaluate(T0, false, -14.5f, false, 50));
    }

    @Test
    public void noiseAroundZeroIsNotCharging() {
        assertFalse(signal.evaluate(T0, false, 0.1f, true, 50));
    }

    // ---- Fallback 2: rising SOC on a parked car ----

    @Test
    public void aRisingSocOnAParkedCarIsCharging() {
        // Neither charge property readable — the only evidence left is the battery filling.
        assertNull(signal.evaluate(T0, null, null, true, 50));
        assertTrue(signal.evaluate(T0 + 300_000, null, null, true, 51));
    }

    @Test
    public void theSocRiseIsLatchedAcrossFlatTicks() {
        // SOC moves a whole percent every few minutes; without the latch is_charging
        // would flap between every pair of ticks.
        signal.evaluate(T0, null, null, true, 50);
        signal.evaluate(T0 + 300_000, null, null, true, 51);
        assertTrue(signal.evaluate(T0 + 315_000, null, null, true, 51));
        assertTrue(signal.evaluate(T0 + 1_500_000, null, null, true, 51));
    }

    @Test
    public void theLatchExpiresSoAParkedCarGoesBackToIdle() {
        signal.evaluate(T0, null, null, true, 50);
        signal.evaluate(T0 + 300_000, null, null, true, 51);
        assertNull(signal.evaluate(T0 + 300_000 + ChargingSignal.SOC_RISE_LATCH_MS,
                null, null, true, 51));
    }

    @Test
    public void aFallingSocDropsTheLatchImmediately() {
        signal.evaluate(T0, null, null, true, 50);
        assertTrue(signal.evaluate(T0 + 300_000, null, null, true, 51));
        assertNull(signal.evaluate(T0 + 600_000, null, null, true, 50));
    }

    @Test
    public void regenWhileDrivingDoesNotLatch() {
        // SOC climbing on a downhill run, then the car parks: not a charging session.
        signal.evaluate(T0, false, null, false, 50);
        signal.evaluate(T0 + 60_000, false, null, false, 52);
        assertFalse(signal.evaluate(T0 + 120_000, false, null, true, 52));
    }

    @Test
    public void anUnreadableSocKeepsThePreviousReadingToCompareAgainst() {
        signal.evaluate(T0, null, null, true, 50);
        signal.evaluate(T0 + 15_000, null, null, true, null);   // one failed read
        assertTrue(signal.evaluate(T0 + 30_000, null, null, true, 51));
    }

    // ---- The regression itself ----

    @Test
    public void aChargingCarIsNeverHandedToTheParkedCadence() {
        // Charging at home with a VHAL that does not implement the charge port property:
        // before the fix this was parked + FALSE, i.e. one upload every 15 minutes.
        ChargingSignal s = new ChargingSignal();
        UploadSettings settings = UploadSettings.defaults();

        Boolean charging = s.evaluate(T0, false, null, true, 50);
        assertEquals(UploadCadence.PARKED_INTERVAL_MS,
                UploadCadence.intervalFor(true, charging, 50, settings));

        charging = s.evaluate(T0 + 300_000, false, null, true, 51);
        assertEquals(settings.intervalMs(),
                UploadCadence.intervalFor(true, charging, 51, settings));
    }
}
