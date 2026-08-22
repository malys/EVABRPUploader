package com.evsuite.abrp;

/**
 * Works out whether the car is charging from whichever signals the VHAL actually answers.
 *
 * {@code EV_CHARGE_PORT_CONNECTED} is a standard AAOS property and this vehicle's VHAL is
 * not obliged to implement it. When it answers {@code false} on a plugged-in car, the
 * cadence policy sees "parked and not charging" and backs off to
 * {@link UploadCadence#PARKED_INTERVAL_MS} for the whole session — which is exactly when
 * ABRP needs a tight SOC curve to size a charging stop. Two fallbacks close that hole:
 *
 *  1. the instantaneous charge rate, which is positive only while the pack takes energy;
 *  2. a rising SOC on a parked car, which nothing but charging can produce.
 *
 * The SOC fallback is latched, because SOC only moves a whole percent every few minutes
 * and a signal that flapped between ticks would flap {@code is_charging} in the payload
 * too. The latch expires so a car left parked after a charge falls back to the idle
 * cadence instead of polling all night.
 *
 * Stateful and NOT thread-safe: only the {@code abrp-upload} scheduler thread calls it.
 */
final class ChargingSignal {

    /** Above this the pack is taking energy; below it the reading is noise around zero. */
    static final float CHARGING_POWER_KW = 0.3f;

    /**
     * How long a SOC rise keeps counting as "charging". Long enough to cover the slowest
     * realistic case — a 2 kW granny lead needs ~19 min per percent on a 64 kWh pack —
     * and short enough that a finished charge returns to the idle cadence promptly.
     */
    static final long SOC_RISE_LATCH_MS = 1_800_000L;

    private Integer lastSocPercent;
    /** When SOC last rose while parked; 0 when there is no live rise to trust. */
    private long socRoseAtMs;

    /**
     * @param nowMs          current time
     * @param portConnected  charge port property, null when unreadable
     * @param chargeRateKw   charge rate in kW as the VHAL signs it (+ve while charging),
     *                       null when unreadable
     * @param parked         null when the gear could not be read
     * @param socPercent     null when the battery level could not be read
     * @return TRUE / FALSE when the state can be established, null when nothing could be
     *         read — null must keep counting as "unknown", never as "not charging"
     */
    Boolean evaluate(long nowMs, Boolean portConnected, Float chargeRateKw,
                     Boolean parked, Integer socPercent) {
        trackSoc(nowMs, parked, socPercent);

        if (Boolean.TRUE.equals(portConnected)) return Boolean.TRUE;
        if (chargeRateKw != null && chargeRateKw > CHARGING_POWER_KW) return Boolean.TRUE;
        if (Boolean.TRUE.equals(parked)
                && socRoseAtMs > 0L && nowMs - socRoseAtMs < SOC_RISE_LATCH_MS) {
            return Boolean.TRUE;
        }
        // Nothing overrode the port: pass its reading through, including its null.
        return portConnected;
    }

    private void trackSoc(long nowMs, Boolean parked, Integer socPercent) {
        if (!Boolean.TRUE.equals(parked)) {
            // Regen while driving also raises SOC. Keep the latch clear so parking right
            // after a downhill run does not read as charging.
            socRoseAtMs = 0L;
            lastSocPercent = socPercent;
            return;
        }
        if (socPercent == null) return;   // keep the previous reading to compare against
        if (lastSocPercent != null) {
            if (socPercent > lastSocPercent) socRoseAtMs = nowMs;
            else if (socPercent < lastSocPercent) socRoseAtMs = 0L;   // draining, not charging
        }
        lastSocPercent = socPercent;
    }
}
