package com.evsuite.abrp;

/**
 * Accumulates the energy taken by the pack during one charging session, for ABRP's
 * {@code kwh_charged}.
 *
 * This VHAL exposes an instantaneous charge rate but no energy counter, so the figure is
 * integrated here: each sample adds the trapezoid between the previous rate and the
 * current one. ABRP accepts either a lifetime total or a per-session figure; per-session
 * is what this vehicle can actually measure, so the counter resets whenever a new session
 * starts and the field is omitted entirely while nothing is charging.
 *
 * Stateful and NOT thread-safe: only the {@code abrp-upload} scheduler thread calls it,
 * on every tick — including the ticks whose upload the cadence policy throttles away, or
 * the integral would lose every interval that was not uploaded.
 */
final class ChargeMeter {

    /**
     * Longest gap that can still be integrated. It has to clear
     * {@link UploadCadence#PARKED_INTERVAL_MS} with room to spare, because a
     * parked-and-charging car whose port property reads false is sampled at exactly that
     * rate and its whole session would otherwise integrate to zero. Beyond this, the two
     * rates say nothing about what happened in between, so the gap is skipped rather than
     * guessed at.
     */
    static final long MAX_SAMPLE_GAP_MS = 1_200_000L;

    private static final double MS_PER_HOUR = 3_600_000.0;

    private boolean sessionOpen;
    private long    lastSampleMs;
    private Float   lastRateKw;
    private double  kwhCharged;

    /**
     * @param nowMs         current time
     * @param charging      as {@link ChargingSignal} resolved it; null means unknown
     * @param chargeRateKw  charge rate in kW as the VHAL signs it (+ve while charging),
     *                      null when unreadable
     * @return kWh into the pack since this session started, or null when no session is
     *         open — null must leave {@code kwh_charged} out of the payload rather than
     *         report a stale total against the next charge
     */
    Double sample(long nowMs, Boolean charging, Float chargeRateKw) {
        if (Boolean.FALSE.equals(charging)) {
            sessionOpen = false;
            lastRateKw  = null;
            return null;
        }
        // Unknown keeps the session alive but contributes nothing: the car is still on
        // the plug as far as anyone knows, yet there is no rate to integrate.
        if (charging == null) {
            if (!sessionOpen) return null;
            lastRateKw = null;
            lastSampleMs = nowMs;
            return round2(kwhCharged);
        }

        if (!sessionOpen) {
            sessionOpen  = true;
            kwhCharged   = 0.0;
            lastSampleMs = nowMs;
            lastRateKw   = positive(chargeRateKw);
            return 0.0;
        }

        Float rateKw = positive(chargeRateKw);
        long gapMs = nowMs - lastSampleMs;
        if (rateKw != null && lastRateKw != null && gapMs > 0L && gapMs <= MAX_SAMPLE_GAP_MS) {
            kwhCharged += (lastRateKw + rateKw) / 2.0 * (gapMs / MS_PER_HOUR);
        }
        lastSampleMs = nowMs;
        lastRateKw   = rateKw;
        return round2(kwhCharged);
    }

    /**
     * The rate clamped at zero, or null when unreadable. A charging car that reports a
     * negative rate is reporting noise or a sign the VHAL flipped; either way the pack
     * cannot have given energy back to the charger, and subtracting it would make the
     * session total drift downwards.
     */
    private static Float positive(Float rateKw) {
        if (rateKw == null) return null;
        return rateKw < 0f ? 0f : rateKw;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
