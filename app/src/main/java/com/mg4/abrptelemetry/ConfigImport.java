package com.mg4.abrptelemetry;

/**
 * Parses an ABRP configuration text file so a user can set the app up without typing on the
 * car's on-screen keyboard — the api_key and token in particular are long and error-prone to
 * enter by hand. The file is a plain {@code key = value} list, one entry per line:
 *
 * <pre>
 *   # ABRP Uploader config
 *   api_key = 1234abcd-...
 *   token   = 5678efgh-...
 *   interval_sec = 60
 *   boost_low_soc = true
 *   low_soc_percent = 20
 * </pre>
 *
 * Only api_key and token are commonly needed; the cadence keys are optional. Blank lines and
 * lines starting with {@code #} are ignored, keys are case-insensitive, and surrounding
 * whitespace is trimmed. Every field is nullable: the caller applies only what the file set,
 * leaving anything absent untouched.
 */
final class ConfigImport {

    final String apiKey;
    final String token;
    final Integer intervalSec;
    final Boolean boostLowSoc;
    final Integer lowSocPercent;

    private ConfigImport(String apiKey, String token, Integer intervalSec,
                         Boolean boostLowSoc, Integer lowSocPercent) {
        this.apiKey = apiKey;
        this.token = token;
        this.intervalSec = intervalSec;
        this.boostLowSoc = boostLowSoc;
        this.lowSocPercent = lowSocPercent;
    }

    /** True when the file yielded nothing usable — an empty or malformed file, not a config. */
    boolean isEmpty() {
        return apiKey == null && token == null && intervalSec == null
                && boostLowSoc == null && lowSocPercent == null;
    }

    static ConfigImport parse(String text) {
        String apiKey = null, token = null;
        Integer intervalSec = null, lowSocPercent = null;
        Boolean boostLowSoc = null;

        for (String line : text.split("\n", -1)) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;

            int sep = line.indexOf('=');
            if (sep <= 0) continue;

            String key = line.substring(0, sep).trim().toLowerCase(java.util.Locale.US);
            String value = line.substring(sep + 1).trim();
            if (value.isEmpty()) continue;

            switch (key) {
                case "api_key":
                case "apikey":
                    apiKey = value;
                    break;
                case "token":
                case "user_token":
                    token = value;
                    break;
                case "interval_sec":
                case "upload_interval_sec":
                    intervalSec = nearestInterval(value);
                    break;
                case "boost_low_soc":
                    boostLowSoc = parseBool(value);
                    break;
                case "low_soc_percent":
                    lowSocPercent = clampPercent(value);
                    break;
                default:
                    // Unknown keys are ignored rather than rejected, so a file can carry
                    // comments or future keys without breaking an older build.
                    break;
            }
        }
        return new ConfigImport(apiKey, token, intervalSec, boostLowSoc, lowSocPercent);
    }

    /** Snaps to the nearest offered choice: the UI spinner has no free-form interval. */
    private static Integer nearestInterval(String value) {
        int requested;
        try {
            requested = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
        int best = UploadSettings.INTERVAL_CHOICES_SEC[0];
        for (int choice : UploadSettings.INTERVAL_CHOICES_SEC) {
            if (Math.abs(choice - requested) < Math.abs(best - requested)) best = choice;
        }
        return best;
    }

    /** Same 1–99 clamp the manual field enforces, so an import cannot set an unusable value. */
    private static Integer clampPercent(String value) {
        try {
            return Math.max(1, Math.min(99, Integer.parseInt(value)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseBool(String value) {
        value = value.toLowerCase(java.util.Locale.US);
        if (value.equals("true") || value.equals("on") || value.equals("1")) return Boolean.TRUE;
        if (value.equals("false") || value.equals("off") || value.equals("0")) return Boolean.FALSE;
        return null;
    }
}
