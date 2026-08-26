package com.evsuite.abrp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import android.Manifest;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.evsuite.hardware.EVHardware;
import com.evsuite.hardware.saic.SaicWeather;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that polls vehicle telemetry every 15 s and pushes it to ABRP.
 *
 * Reliability design:
 *  - Uses ScheduledExecutorService (not Handler chains) so exceptions inside one
 *    upload cycle cannot break the schedule. Even on a thrown RuntimeException
 *    the next tick still fires.
 *  - Uploads unconditionally: if the car adapter never connects we still emit
 *    GPS + timestamp so ABRP at least sees the vehicle online.
 *  - Car adapter (re-)connection is retried every 30 s by the scheduler itself
 *    if {@link CarPropertyAdapter#isConnected()} is false. This handles the
 *    case where the underlying android.car.Car createCar() / connect() call
 *    fails silently with no listener callback.
 *  - All IPC + HTTP happens on the scheduler thread, never the main thread.
 */
public class AbrpUploadService extends Service {

    public static final String ACTION_STOP = "com.evsuite.abrp.STOP";

    /**
     * Live in-process signal. The "service_running" preference cannot be trusted on its
     * own: a force-kill (adb install -r, low-memory kill) skips onDestroy and leaves it
     * stale-true forever. This field dies with the process, so it is only ever true while
     * the service really is up.
     */
    private static volatile boolean running = false;

    public static boolean isRunning() { return running; }

    /**
     * Upload history, shared with the UI. Static because the activity reads it while the
     * service owns it; it lives and dies with the process, like {@link #running}.
     */
    private static final UploadLog uploadLog = new UploadLog();

    public static UploadLog log() { return uploadLog; }

    public static UploadLog.State state() { return uploadLog.state(running); }

    /** Re-read after the user changes the cadence, without restarting the service. */
    public static void reloadSettings() { settingsDirty = true; }

    private static volatile boolean settingsDirty = false;

    private static final String TAG             = "AbrpUploadService";
    private static final String CHANNEL_ID      = "abrp_uploader";
    private static final int    NOTIF_ID        = 1;
    /** Scheduler tick. Whether a tick uploads is decided by {@link UploadCadence}. */
    private static final long   UPLOAD_INTERVAL_SEC = 15;
    /**
     * Warm-up before the first upload. Was 45 s with no stated reason; the car adapter
     * connects asynchronously and telemetry now omits whatever it cannot read (T-913), so
     * an early first sample is useful rather than misleading.
     */
    private static final long   FIRST_UPLOAD_DELAY_SEC = 20;
    private static final long   CAR_RECONNECT_INTERVAL_SEC = 30;
    private static final long   LOCATION_RETRY_INTERVAL_SEC = 30;
    /**
     * Floor for how old a GPS fix may be and still describe where the car is now.
     *
     * A fix has no expiry of its own, so the previous behaviour was to keep reporting the
     * last one forever: the position saved when the car was parked at home was uploaded,
     * stamped with the current time, for the whole first part of the next drive. ABRP then
     * plans from a place the car left minutes ago. The subscription asks for a fix every
     * upload interval, so anything older than several intervals means the receiver has
     * stopped producing (garage, tunnel) and position is genuinely unknown.
     */
    private static final long   MIN_LOCATION_MAX_AGE_MS = 5 * 60 * 1000L;
    /**
     * How long a weather reading stands in for the car's own thermometer.
     *
     * The query can reach the network from inside the head unit and blocks the upload thread
     * while it does, so it is not something to do every tick. Outside air does not move fast
     * enough for ten minutes to matter to a consumption model.
     */
    private static final long   WEATHER_TTL_MS = 10 * 60 * 1000L;

    // Set from the main thread (LocationListener uses Main looper), read from
    // the scheduler thread inside doUpload. volatile is sufficient since Location
    // is effectively immutable for our purposes.
    private volatile Location lastLocation;

    // Replaced on the main thread by connectCarAdapter, read from the scheduler thread.
    private volatile CarPropertyAdapter carAdapter;
    private LocationManager      locationManager;
    private ScheduledExecutorService scheduler;
    private SharedPreferences    prefs;
    /** Credentials only — encrypted at rest, see {@link SecurePrefs}. */
    private SharedPreferences    securePrefs;
    private Handler              mainHandler;

    private volatile long lastSuccessfulUploadMs = 0L;
    private volatile long lastCarConnectAttemptMs = 0L;
    /** Previous tick's state, to detect a transition worth reporting immediately. */
    private volatile Boolean lastParked = null;
    private volatile Boolean lastCharging = null;
    private volatile long lastUploadAttemptMs = 0L;
    /** Charge detection state. Touched only from the scheduler thread, inside doUpload. */
    private final ChargingSignal chargingSignal = new ChargingSignal();
    private final ChargeMeter    chargeMeter    = new ChargeMeter();
    private volatile UploadSettings settings = UploadSettings.defaults();
    /** False while no GPS subscription is delivering — drives the watchdog re-arm. */
    /** What the foreground notification currently says, so re-asserting it does not blank it. */
    private volatile String lastStatus = "Starting…";
    private volatile boolean locationUpdatesActive = false;
    private volatile long lastLocationRequestMs = 0L;
    /** Last head-unit weather reading and when it was taken — see {@link #WEATHER_TTL_MS}. */
    private volatile SaicWeather.Reading lastWeather = null;
    private volatile long lastWeatherMs = 0L;

    // ---------- Lifecycle ----------

    @Override
    public void onCreate() {
        super.onCreate();
        prefs       = getSharedPreferences("abrp_prefs", MODE_PRIVATE);
        securePrefs = SecurePrefs.get(this);
        settings    = UploadSettings.from(prefs);
        uploadLog.clear();
        mainHandler = new Handler(Looper.getMainLooper());
        scheduler   = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "abrp-upload");
            t.setDaemon(false);
            return t;
        });

        createNotificationChannel();
        startInForeground();
        running = true;
        prefs.edit().putBoolean("service_running", true).apply();

        // Firmware-agnostic vehicle reads (speed, outside temp, park) go through
        // EVHardware, which branches per generation internally and so works on all
        // supported firmwares (SWI68/69/131/132/133/165). Idempotent. The vendor SOC/range
        // reads stay on CarPropertyAdapter — EVHardware has no EV-battery abstraction and
        // those IDs are confirmed for SWI68 only (see FIRMWARE.md).
        EVHardware.INSTANCE.init(getApplicationContext());
        // The head unit's own weather service, bound for one reason: ENV_OUTSIDE_TEMPERATURE
        // is not implemented on this vehicle, so the car cannot say how warm it is outside
        // and something else has to. Idempotent, asynchronous, and absent on a car that does
        // not have the map stack — in which case ext_temp simply stays unreported.
        SaicWeather.INSTANCE.connect(getApplicationContext());

        connectCarAdapter();
        requestLocationUpdates();

        // Fire the first upload after a short warm-up, then every UPLOAD_INTERVAL_SEC.
        // scheduleWithFixedDelay ensures we always get at least UPLOAD_INTERVAL_SEC
        // between cycles even if a previous upload was slow.
        scheduler.scheduleWithFixedDelay(
                this::safeUploadCycle,
                FIRST_UPLOAD_DELAY_SEC, UPLOAD_INTERVAL_SEC, TimeUnit.SECONDS);

        Log.i(TAG, "Service started, upload scheduler armed");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean("service_enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        // Re-asserted on every start: a position grant that arrives after the service is up
        // changes which foreground types it may hold, and the activity starts it again as
        // soon as the user answers. Without this the grant only took effect at the next boot.
        startInForeground();
        return START_STICKY;
    }

    /**
     * Enters the foreground holding only the service types this app currently has the
     * permissions for.
     *
     * The service declares {@code foregroundServiceType="location"}, and since API 34 the
     * platform validates every declared type against what is held at the moment
     * startForeground runs — the plain two-argument call claims them all. On a car where
     * position had not been granted yet (a fresh install, or the boot receiver starting the
     * service before the app was ever opened) that threw a SecurityException and the uploader
     * died before its first tick. Without the permission the service still runs and still
     * uploads; it simply has no position to send, exactly as before.
     */
    private void startInForeground() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            // Service types do not exist before API 29: nothing is declared and nothing is
            // checked, so the plain call is the only correct one there.
            startForeground(NOTIF_ID, buildNotification(lastStatus));
            return;
        }
        int types = hasLocationPermission()
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(lastStatus), types);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service stopping");
        if (scheduler != null) scheduler.shutdownNow();
        if (carAdapter != null) carAdapter.disconnect();
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        running = false;
        prefs.edit().putBoolean("service_running", false).apply();
        super.onDestroy();
    }

    // ---------- Car adapter ----------

    private void connectCarAdapter() {
        lastCarConnectAttemptMs = System.currentTimeMillis();
        // Must run on main thread because ServiceConnection callbacks need a Looper.
        mainHandler.post(() -> {
            if (carAdapter != null) {
                try { carAdapter.disconnect(); } catch (Exception ignored) {}
            }
            carAdapter = new CarPropertyAdapter(new CarPropertyAdapter.Listener() {
                @Override
                public void onConnected() {
                    Log.i(TAG, "CarPropertyAdapter connected");
                }
                @Override
                public void onDisconnected() {
                    Log.w(TAG, "CarPropertyAdapter disconnected — scheduler will retry");
                    // Reconnect is handled by the scheduler watchdog.
                }
            });
            try {
                carAdapter.connect(AbrpUploadService.this);
            } catch (Throwable t) {
                Log.e(TAG, "carAdapter.connect threw", t);
            }
        });
    }

    // ---------- Upload cycle (runs on scheduler thread) ----------

    private void safeUploadCycle() {
        try {
            if (settingsDirty) {
                settingsDirty = false;
                settings = UploadSettings.from(prefs);
                // The GPS subscription is a power cost of its own; keep it in step with
                // how often we actually send.
                mainHandler.post(this::requestLocationUpdates);
                Log.i(TAG, "Settings reloaded: interval=" + settings.intervalSec + "s"
                        + " boostLowSoc=" + settings.boostLowSoc
                        + " lowSoc=" + settings.lowSocPercent + "%");
            }
            // Self-watchdog: if the car adapter looks dead and we haven't tried
            // reconnecting recently, kick a reconnect.
            if ((carAdapter == null || !carAdapter.isConnected())
                    && System.currentTimeMillis() - lastCarConnectAttemptMs
                       > CAR_RECONNECT_INTERVAL_SEC * 1000) {
                Log.w(TAG, "Car adapter not connected, attempting reconnect");
                connectCarAdapter();
            }

            // Same watchdog for GPS: requestLocationUpdates used to run once in onCreate,
            // so a provider that was off at start-up — or dropped later — meant
            // position-less telemetry for the rest of the session.
            if (!locationUpdatesActive
                    && System.currentTimeMillis() - lastLocationRequestMs
                       > LOCATION_RETRY_INTERVAL_SEC * 1000) {
                Log.w(TAG, "Location updates inactive, re-arming");
                mainHandler.post(this::requestLocationUpdates);
            }
            doUpload();
        } catch (Throwable t) {
            // Absolutely never let an exception escape — would not break the
            // schedule with scheduleWithFixedDelay's executor, but we want a
            // clean log line anyway.
            Log.e(TAG, "upload cycle threw", t);
        }
    }

    private void doUpload() {
        String token  = securePrefs.getString(SecurePrefs.KEY_TOKEN,   "").trim();
        String apiKey = securePrefs.getString(SecurePrefs.KEY_API_KEY, "").trim();

        if (token.isEmpty()) {
            updateNotification("No ABRP token — open app to configure");
            return;
        }
        if (apiKey.isEmpty()) {
            updateNotification("No API key — open app to configure");
            return;
        }

        // Read whatever the car will give us. Every getter returns null when the read
        // fails, and a null field is omitted from the payload — never sent as 0.
        boolean carUp = carAdapter != null && carAdapter.isConnected();

        // Speed via EVHardware: it reads the standard AAOS property on every generation
        // and returns km/h (PERF_VEHICLE_SPEED is m/s, and negative in reverse) — the raw
        // vendor read here would have shipped m/s mislabelled as km/h.
        Float speedKmh = EVHardware.INSTANCE.getVehicleSpeedKmh();

        Float socRaw = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_EV_BATTERY_PCT,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;
        Integer soc = socRaw == null ? null : Math.round(socRaw);

        Integer rangeKm = carUp ? carAdapter.getIntProperty(
                CarPropertyAdapter.PROP_EV_RANGE_KM,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;
        if (carUp && (rangeKm == null || rangeKm <= 0)) {
            // The vendor cluster above is confirmed on SWI68 only; everywhere else it
            // reads null or 0. RANGE_REMAINING is standard AAOS and is in METRES.
            Float rangeM = carAdapter.getFloatProperty(
                    CarPropertyAdapter.PROP_RANGE_REMAINING,
                    CarPropertyAdapter.PROP_AREA_GLOBAL);
            rangeKm = (rangeM == null || rangeM <= 0f) ? null : Math.round(rangeM / 1000f);
        }

        // Outside temp via EVHardware: standard AAOS ENV_OUTSIDE_TEMPERATURE, valid on
        // every generation. The old local vendor ID (0x15602511) was SWI68-only.
        Float extTemp = EVHardware.INSTANCE.getOutsideTempCelsius();

        // Charge rate comes in mW, signed the VHAL's way: +ve charging, -ve driving.
        Float chargeRateRaw = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_EV_INSTANTANEOUS_CHARGE_RATE,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;
        Float chargeRateKw = chargeRateRaw == null ? null : chargeRateRaw / 1_000_000f;

        Boolean portConnected = carUp ? carAdapter.getBooleanProperty(
                CarPropertyAdapter.PROP_EV_CHARGE_PORT_CONNECTED,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;

        // Park state via EVHardware: it resolves gear per generation (VPM / CarStateClient
        // / VehicleConditionManager depending on firmware). Null means unknown, not "moving".
        Boolean parked = EVHardware.INSTANCE.isVehicleInPark();

        // The charge port property is standard AAOS and this VHAL need not implement it.
        // Trusting it alone meant a plugged-in car that reads "unplugged" was treated as
        // parked-and-idle and throttled to one sample every 15 minutes for the whole
        // charge — see ChargingSignal for the fallbacks.
        Boolean charging = chargingSignal.evaluate(
                System.currentTimeMillis(), portConnected, chargeRateKw, parked, soc);

        // ABRP signs power the other way round from the VHAL: positive is power leaving
        // the battery (driving), negative is power going into it (charging).
        Float powerKw = chargeRateKw == null ? null : -chargeRateKw;

        // DCFC heuristic: charging at AC speeds (3-22 kW) is type-2; above ~25 kW
        // it can only be DC fast. Undecidable if either input is missing.
        Boolean dcfc = (charging == null || chargeRateKw == null)
                ? null : (charging && chargeRateKw > 25f);

        // Cabin temperature — try the standard HVAC property, may fail on this VHAL.
        Float cabinTemp = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_CABIN_TEMP,
                CarPropertyAdapter.PROP_AREA_HVAC) : null;

        // Pack temperature. Standard AAOS, but only since Android 14 — on AAOS 9 this is
        // very likely absent, which costs one null read and omits the field.
        Float battTemp = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_EV_BATTERY_AVG_TEMP,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;

        // Nominal usable pack capacity, in Wh — ABRP wants kWh.
        Float capacityWh = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_INFO_EV_BATTERY_CAPACITY,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;

        // Energy currently in the pack, in Wh — ABRP wants kWh.
        Float soeWh = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_EV_BATTERY_LEVEL,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;

        Float odometerKm = carUp ? carAdapter.getFloatProperty(
                CarPropertyAdapter.PROP_ODOMETER,
                CarPropertyAdapter.PROP_AREA_GLOBAL) : null;

        // Climate setpoint via EVHardware: it already resolves HVAC per generation.
        Float hvacSetpoint = EVHardware.INSTANCE.getTemperatureSetCelsius();

        // Sampled before the cadence check below: the meter integrates the charge rate
        // over time, so it has to see every tick, not only the ones that get uploaded.
        Double kwhCharged = chargeMeter.sample(
                System.currentTimeMillis(), charging, chargeRateKw);

        // Thin out uploads while the car is parked and not charging. Evaluated here, once
        // parked/charging are known, so a transition is never delayed.
        boolean stateChanged = !java.util.Objects.equals(parked, lastParked)
                            || !java.util.Objects.equals(charging, lastCharging);
        lastParked = parked;
        lastCharging = charging;

        // Cadence is measured on ATTEMPTS, not successes: keying it on the last success
        // would retry every tick while offline, which is exactly when saving power matters.
        if (!UploadCadence.shouldUpload(System.currentTimeMillis(), lastUploadAttemptMs,
                parked, charging, soc, stateChanged, settings)) {
            return;
        }
        lastUploadAttemptMs = System.currentTimeMillis();

        Location loc = freshLocation();

        // ext_temp was reaching ABRP as 0 degC, and omitting the unreadable value only turned
        // that into ABRP's own default 0. The car is the problem: this VHAL does not implement
        // ENV_OUTSIDE_TEMPERATURE, so there is no vehicle reading to correct. The head unit's
        // weather service answers for a position, and ambient air at the car is what ext_temp
        // means — a better figure than a bumper sensor in the sun would give anyway.
        SaicWeather.Reading sky = null;
        if (!TelemetryPayload.isPlausibleTemp(extTemp)) {
            sky = weatherAt(loc);
            if (sky != null && sky.getTemperatureCelsius() != null) {
                extTemp = sky.getTemperatureCelsius().floatValue();
            }
        }

        TelemetryPayload tlm = new TelemetryPayload(System.currentTimeMillis() / 1000);
        tlm.soc           = soc;
        tlm.speedKmh      = speedKmh;
        tlm.rangeKm       = rangeKm;
        tlm.extTemp       = extTemp;
        tlm.powerKw       = powerKw;
        tlm.charging      = charging;
        tlm.dcfc          = dcfc;
        tlm.parked        = parked;
        tlm.cabinTemp     = cabinTemp;
        tlm.battTempC     = battTemp;
        tlm.capacityKwh   = capacityWh == null ? null : capacityWh / 1000f;
        tlm.soeKwh        = soeWh == null ? null : soeWh / 1000f;
        tlm.kwhCharged    = kwhCharged;
        tlm.odometerKm    = odometerKm;
        tlm.hvacSetpointC = hvacSetpoint;
        if (carUp) {
            tlm.tirePressureFlKpa = tirePressure(CarPropertyAdapter.WHEEL_LEFT_FRONT);
            tlm.tirePressureFrKpa = tirePressure(CarPropertyAdapter.WHEEL_RIGHT_FRONT);
            tlm.tirePressureRlKpa = tirePressure(CarPropertyAdapter.WHEEL_LEFT_REAR);
            tlm.tirePressureRrKpa = tirePressure(CarPropertyAdapter.WHEEL_RIGHT_REAR);
        }
        if (loc != null) {
            tlm.lat       = loc.getLatitude();
            tlm.lon       = loc.getLongitude();
            tlm.elevation = loc.hasAltitude() ? loc.getAltitude() : null;
            tlm.heading   = loc.hasBearing()  ? loc.getBearing()  : null;
        }

        String tlmJson = tlm.build();
        String summary = TelemetryPayload.summarize(tlmJson);
        if (loc != null) {
            summary += " (fix " + locationAgeMs(loc) / 1000 + "s old"
                    + " +/-" + (loc.hasAccuracy() ? Math.round(loc.getAccuracy()) + "m" : "?")
                    + " via " + loc.getProvider();
            // The weather service names the place it answered for. ABRP shows an address it
            // reverse-geocodes from the same coordinates, so the two disagreeing says the
            // coordinates are wrong, and the two agreeing says ABRP is showing something old.
            if (sky != null && !sky.getCity().isEmpty()) summary += ", near " + sky.getCity();
            summary += ")";
        }
        // What actually went on the wire. Kept on the log entry as well as in logcat: the
        // head unit this runs on has no adb attached, so the in-app log is the only place
        // the driver can see it.
        Log.i(TAG, "TLM " + summary);

        sendToAbrp(apiKey, token, tlmJson, summary, soc, speedKmh, carUp);
    }

    /**
     * Current conditions where the car is, or null when nothing can answer.
     *
     * Cached for {@link #WEATHER_TTL_MS}: the query blocks the upload thread on what may be a
     * network round-trip inside the head unit, and it is asked on every upload the car's own
     * thermometer cannot answer — which, on this vehicle, is all of them. A stale reading is
     * kept when a refresh fails, since ten-minute-old air is a better answer than none.
     */
    private SaicWeather.Reading weatherAt(Location loc) {
        if (loc == null) return null;
        long now = System.currentTimeMillis();
        SaicWeather.Reading cached = lastWeather;
        if (cached != null && now - lastWeatherMs < WEATHER_TTL_MS) return cached;
        if (!SaicWeather.INSTANCE.isAvailable()) return cached;
        SaicWeather.Reading reading = SaicWeather.INSTANCE.currentAt(
                loc.getLatitude(), loc.getLongitude(), weatherLanguage());
        if (reading == null) return cached;
        lastWeather = reading;
        lastWeatherMs = now;
        return reading;
    }

    /** The provider answers in the language it is asked in; the driver's is the right one. */
    private static String weatherLanguage() {
        java.util.Locale locale = java.util.Locale.getDefault();
        String language = locale.getLanguage().toLowerCase(java.util.Locale.US);
        String country = locale.getCountry().toLowerCase(java.util.Locale.US);
        return country.isEmpty() ? language : language + "-" + country;
    }

    /** One wheel's pressure in kPa, or null when that wheel has no readable sensor. */
    private Float tirePressure(int wheelArea) {
        CarPropertyAdapter adapter = carAdapter;
        if (adapter == null) return null;
        return adapter.getFloatProperty(CarPropertyAdapter.PROP_TIRE_PRESSURE, wheelArea);
    }

    private void sendToAbrp(String apiKey, String token, String tlmJson, String summary,
                            Integer soc, Float speedKmh, boolean carUp) {
        try {
            AbrpApi.Response response = AbrpApi.send(apiKey, token, tlmJson);
            int code = response.code;

            // The response body can echo request details — debug builds only.
            if (BuildConfig.DEBUG) Log.d(TAG, "ABRP [" + code + "]: " + response.body);

            uploadLog.record(new UploadLog.Entry(System.currentTimeMillis(), code,
                    code == 200, code == 200 ? "OK" : ("HTTP " + code), summary));

            if (code == 200) {
                lastSuccessfulUploadMs = System.currentTimeMillis();
                String time = android.text.format.DateFormat
                        .format("HH:mm:ss", lastSuccessfulUploadMs).toString();
                prefs.edit()
                        .putString("last_upload_time",   time)
                        .putString("last_upload_status", "OK")
                        .apply();
                // Only claim a value in the notification if we actually read one.
                String detail = (soc != null && speedKmh != null)
                        ? ("SOC " + soc + "% · " + Math.round(speedKmh) + " km/h · " + time)
                        : (carUp ? ("Car data partial · " + time) : ("GPS only · " + time));
                updateNotification(detail);
            } else {
                prefs.edit().putString("last_upload_status", "HTTP " + code).apply();
                int failures = uploadLog.consecutiveFailures();
                updateNotification(failures >= UploadLog.FAILURES_FOR_ERROR
                        ? ("Upload failing (" + failures + "x) — HTTP " + code)
                        : ("Upload error: HTTP " + code));
            }
        } catch (java.net.UnknownHostException e) {
            // httpStatus 0: the request never reached a server.
            uploadLog.record(new UploadLog.Entry(
                    System.currentTimeMillis(), 0, false, "No internet", summary));
            prefs.edit().putString("last_upload_status", "No internet").apply();
            updateNotification("Offline — will retry");
        } catch (Exception e) {
            Log.e(TAG, "Upload failed", e);
            uploadLog.record(new UploadLog.Entry(System.currentTimeMillis(), 0, false,
                    e.getClass().getSimpleName(), summary));
            prefs.edit().putString("last_upload_status",
                    e.getClass().getSimpleName() + ": " + e.getMessage()).apply();
        }
    }

    // ---------- Location ----------

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            lastLocation = location;
            locationUpdatesActive = true;
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {
            Log.i(TAG, "GPS provider enabled — re-arming location updates");
            // The subscription made while the provider was off delivers nothing; ask again.
            requestLocationUpdates();
        }
        @Override public void onProviderDisabled(String p) {
            Log.w(TAG, "GPS provider disabled — telemetry will omit position");
            locationUpdatesActive = false;
            // A stale fix is worse than none: ABRP would think the car is parked there.
            lastLocation = null;
        }
    };

    /**
     * The last GPS fix if it still describes where the car is, otherwise null so the
     * payload omits position entirely. Omitting it leaves ABRP on its own last known
     * point; sending a stale one actively moves the car back to where it used to be.
     */
    private Location freshLocation() {
        Location loc = lastLocation;
        if (loc == null) return null;
        long ageMs = locationAgeMs(loc);
        long maxAgeMs = maxLocationAgeMs();
        if (ageMs > maxAgeMs) {
            Log.w(TAG, "Dropping stale GPS fix: " + ageMs / 1000 + "s old (max "
                    + maxAgeMs / 1000 + "s) — position omitted");
            return null;
        }
        return loc;
    }

    /**
     * Age of a fix, measured on the monotonic clock. Location.getTime() is wall-clock and
     * the head unit adjusts its own clock from GPS, which can make a fresh fix look like
     * it came from the future — or from hours ago — the moment that correction lands.
     */
    private static long locationAgeMs(Location loc) {
        long fixNanos = loc.getElapsedRealtimeNanos();
        // A provider that does not stamp the monotonic clock leaves this at zero, which would
        // date every fix to the last boot and drop all of them — turning a staleness guard
        // into a position blackout. An unstamped fix is one whose age cannot be known, and
        // guessing "ancient" there is the worse of the two guesses.
        if (fixNanos <= 0L) return 0L;
        long ageMs = (SystemClock.elapsedRealtimeNanos() - fixNanos) / 1_000_000L;
        // A fix from the future is a clock that moved, not a fix worth dropping.
        return Math.max(ageMs, 0L);
    }

    /** Several subscription intervals, so a single missed fix never blanks the position. */
    private long maxLocationAgeMs() {
        return Math.max(MIN_LOCATION_MAX_AGE_MS, 3 * settings.intervalMs());
    }

    /**
     * Subscribes to GPS updates. Safe to call repeatedly: the previous subscription is
     * removed first, so the watchdog can re-arm without stacking listeners.
     */
    private void requestLocationUpdates() {
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
            locationUpdatesActive = false;

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                // Match the upload cadence: a 10 s GPS subscription burned power for
                // fixes that were thrown away between uploads.
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, settings.intervalMs(), 0f,
                        locationListener, Looper.getMainLooper());
                locationUpdatesActive = true;
                lastLocationRequestMs = System.currentTimeMillis();
                // Seed from the last known fix so the first upload is not position-less —
                // but only if it is recent. This is where the car's overnight parking spot
                // used to come from: cold GPS at start-up delivers nothing for the first
                // minutes, and the fix from the previous drive filled the gap.
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null && locationAgeMs(last) <= maxLocationAgeMs()) {
                    lastLocation = last;
                } else if (last != null) {
                    Log.i(TAG, "Ignoring last known fix: " + locationAgeMs(last) / 1000
                            + "s old, waiting for a live one");
                }
            } else {
                Log.w(TAG, "GPS provider disabled — will retry");
                lastLocationRequestMs = System.currentTimeMillis();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission not granted: " + e.getMessage());
            lastLocationRequestMs = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "Location unavailable: " + e.getMessage());
            lastLocationRequestMs = System.currentTimeMillis();
        }
    }

    // ---------- Notification ----------

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_desc));
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String status) {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, AbrpUploadService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(status)
                .setContentIntent(openApp)
                .addAction(android.R.drawable.ic_delete, getString(R.string.notif_action_stop), stopPi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String status) {
        lastStatus = status;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(status));
    }
}
