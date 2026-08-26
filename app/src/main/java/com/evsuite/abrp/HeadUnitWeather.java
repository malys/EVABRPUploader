package com.evsuite.abrp;

import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Outside temperature from the head unit's own weather app.
 *
 * The car does not implement ENV_OUTSIDE_TEMPERATURE, so there is no vehicle sensor to read,
 * and the vendor map service that answers a weather query by position never bound on this
 * firmware — {@code ext_temp} was simply absent from every upload. What demonstrably works is
 * the head unit's status bar, which shows a temperature all the time. It gets it from a
 * broadcast, and so does this.
 *
 * **Everything about the payload here is unverified.** The action and the extra name come from
 * reading the head unit, not from a published interface, and the shape inside is read by
 * searching rather than by field offsets: any key whose name mentions a temperature, at any
 * depth. That is deliberate. A parser written against a guessed layout fails silently and
 * looks exactly like the bug it was meant to fix, whereas a search either finds a number or
 * reports what it was given — see {@link #diagnostic()}, which reaches the app's own log.
 *
 * Stays in this app rather than in EVHardware for the same reason: the shared library carries
 * vendor knowledge that has been confirmed on a car. Once a driver's log shows this reading a
 * real broadcast, it belongs there instead, next to SaicWeather.
 */
final class HeadUnitWeather {

    /** The head unit's weather app announces each update with this. */
    static final String ACTION = "com.saicmotor.weather";

    /** Enough of an unreadable payload to recognise its shape, short enough for a log line. */
    private static final int RAW_SAMPLE_CHARS = 200;

    /** How deep the search descends. A weather payload is nested, not deeply nested. */
    private static final int MAX_DEPTH = 6;

    private volatile Float temperatureC;
    /** The place the head unit named — what ABRP's address is checked against. */
    private volatile String place = "";
    /** Why the last broadcast produced nothing, for the log. Null once one produced something. */
    private volatile String problem = "no broadcast yet";
    private volatile long receivedMs = 0L;

    /** Outside temperature in °C, or null while no broadcast has carried one. */
    Float temperatureC() {
        return temperatureC;
    }

    String place() {
        return place;
    }

    long receivedMs() {
        return receivedMs;
    }

    /**
     * One short token for the upload log: the place a reading came from, or the reason there
     * is none. "no broadcast yet" and "no temperature in {...}" call for different fixes and
     * are indistinguishable from the ABRP side.
     */
    String diagnostic() {
        if (temperatureC == null) return problem;
        String age = (System.currentTimeMillis() - receivedMs) / 60000 + "min";
        return (place.isEmpty() ? "ok" : place) + "/" + age;
    }

    /** Reads whatever the head unit just broadcast. Never throws: this runs in a receiver. */
    void accept(Intent intent) {
        if (intent == null) {
            // Only reached from the sticky probe, which answers null when the platform is
            // holding no such broadcast. Says nothing about whether one will ever be sent.
            if (temperatureC == null) problem = "none held, none sent yet";
            return;
        }
        acceptPayload(payloadOf(intent));
    }

    /** Restores the last reading across a restart — see the service's prefs. */
    void restore(float celsius, String named, long whenMs) {
        temperatureC = celsius;
        place = named == null ? "" : named;
        receivedMs = whenMs;
        problem = null;
    }

    /**
     * The half of {@link #accept} that has no Android in it, so the search below is testable
     * against the payloads it might be handed rather than only against the one it expects.
     */
    void acceptPayload(String payload) {
        try {
            if (payload == null) {
                problem = "broadcast carried no JSON";
                return;
            }
            JSONObject json = new JSONObject(payload);
            Float celsius = toCelsius(findNumber(json, "temp", 0), findUnit(json, 0));
            if (celsius == null) {
                problem = "no temperature in " + truncate(payload);
                return;
            }
            temperatureC = celsius;
            String named = findText(json, 0);
            place = named == null ? "" : named;
            receivedMs = System.currentTimeMillis();
            problem = null;
        } catch (Exception e) {
            // A payload that is not the JSON this expects is a finding, not a crash.
            problem = e.getClass().getSimpleName() + " reading broadcast";
        }
    }

    /**
     * The JSON the broadcast carries. Named extras first, then any string extra that looks
     * like an object — the extra's name is as unverified as the rest of the payload, and a
     * renamed key is not a reason to give up on a broadcast that is otherwise readable.
     */
    private static String payloadOf(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return null;
        String named = asJson(extras.get("weather"));
        if (named != null) return named;
        for (String key : extras.keySet()) {
            String candidate = asJson(extras.get(key));
            if (candidate != null) return candidate;
        }
        return null;
    }

    private static String asJson(Object value) {
        if (!(value instanceof String)) return null;
        String text = ((String) value).trim();
        return text.startsWith("{") ? text : null;
    }

    /**
     * The first number under a key whose name mentions [needle], at any depth.
     *
     * A search rather than a path: the payload's own field names are the only thing here that
     * is self-describing, and they survive a layout change that a field order would not.
     */
    private static Double findNumber(Object node, String needle, int depth) {
        if (depth > MAX_DEPTH) return null;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                Object value = object.opt(key);
                if (key.toLowerCase(java.util.Locale.US).contains(needle)) {
                    Double number = asNumber(value);
                    if (number != null) return number;
                }
                Double nested = findNumber(value, needle, depth + 1);
                if (nested != null) return nested;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Double nested = findNumber(array.opt(i), needle, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    /** Numbers arrive as numbers or as strings depending on the producer; both count. */
    private static Double asNumber(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.valueOf(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** The unit the payload names, if it names one at all. */
    private static String findUnit(Object node, int depth) {
        if (depth > MAX_DEPTH) return null;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                Object value = object.opt(key);
                if (key.toLowerCase(java.util.Locale.US).contains("unit")
                        && value instanceof String) {
                    return (String) value;
                }
                String nested = findUnit(value, depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    /** The place the reading is for — whatever the payload calls it. */
    private static String findText(Object node, int depth) {
        if (depth > MAX_DEPTH || !(node instanceof JSONObject)) return null;
        JSONObject object = (JSONObject) node;
        for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String key = keys.next();
            String lower = key.toLowerCase(java.util.Locale.US);
            Object value = object.opt(key);
            if ((lower.contains("address") || lower.contains("city") || lower.contains("poi"))
                    && value instanceof String && !((String) value).trim().isEmpty()) {
                return ((String) value).trim();
            }
            String nested = findText(value, depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }

    /**
     * Celsius, or null when the payload named a unit this does not know.
     *
     * An unnamed unit is taken as Celsius: the head unit displays °C on this car, and the
     * value is sanity-checked by the caller anyway. A named Fahrenheit is converted, because
     * passing 81 °F through as 81 °C is worse than reporting nothing.
     */
    private static Float toCelsius(Double value, String unit) {
        if (value == null) return null;
        String name = unit == null ? "" : unit.trim().toUpperCase(java.util.Locale.US);
        if (name.contains("F")) return (float) ((value - 32.0) * 5.0 / 9.0);
        return value.floatValue();
    }

    private static String truncate(String payload) {
        return payload.length() <= RAW_SAMPLE_CHARS
                ? payload
                : payload.substring(0, RAW_SAMPLE_CHARS) + "…";
    }
}
