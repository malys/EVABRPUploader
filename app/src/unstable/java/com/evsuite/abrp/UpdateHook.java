package com.evsuite.abrp;

import android.content.Context;
/**
 * OTA is suspended while the suite safety and legal audit is open.
 * Keep this flavour seam inert until a reviewed change explicitly re-enables it.
 */
final class UpdateHook {
    private UpdateHook() { }

    static boolean isSupported() { return false; }
    static void checkInBackground(Context context) { }
}
