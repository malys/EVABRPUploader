package com.mg4.abrptelemetry;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unstable channel: checks, downloads, verifies and installs a newer unstable APK.
 */
final class UpdateHook {

    private static final String TAG = "UpdateHook";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private UpdateHook() { }

    static boolean isSupported() { return true; }

    /** Fire-and-forget check. Network work runs off the main thread. */
    static void checkInBackground(Context context) {
        if (!RUNNING.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                OtaUpdater.purgeCachedApks(app);
                String current = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), 0).versionName;
                if (current == null) return;

                OtaUpdater.Update update = OtaUpdater.check(current);
                if (update == null) {
                    Log.i(TAG, "No newer unstable than " + current);
                    return;
                }

                File apk = OtaUpdater.download(app, update);
                if (apk == null) return;
                boolean installed;
                try {
                    installed = OtaUpdater.install(app, apk);
                } finally {
                    apk.delete();
                }
                if (!installed) Log.w(TAG, "Automatic update installation failed");
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Cannot read our own version", e);
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
            } finally {
                RUNNING.set(false);
            }
        }, "ota-check").start();
    }
}
