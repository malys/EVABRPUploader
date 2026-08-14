package com.evsuite.abrp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ABRP config file parsing — pure JVM, no Android needed.
 *
 * The file exists so a user does not have to type a long api_key and token on the car's
 * on-screen keyboard, so the parser must be forgiving about whitespace, case and comments
 * while never inventing a value the file did not set.
 */
public class ConfigImportTest {

    @Test
    public void parsesCredentials() {
        ConfigImport c = ConfigImport.parse("api_key = abc123\ntoken = def456\n");
        assertEquals("abc123", c.apiKey);
        assertEquals("def456", c.token);
        assertFalse(c.isEmpty());
    }

    @Test
    public void ignoresCommentsBlankLinesAndCase() {
        ConfigImport c = ConfigImport.parse(
                "# my config\n\n  API_KEY =  key  \n# token below\nTOKEN=tok\n");
        assertEquals("key", c.apiKey);
        assertEquals("tok", c.token);
    }

    @Test
    public void absentKeysStayNullSoNothingIsWiped() {
        ConfigImport c = ConfigImport.parse("token = only-token\n");
        assertNull(c.apiKey);
        assertEquals("only-token", c.token);
        assertNull(c.intervalSec);
        assertNull(c.boostLowSoc);
        assertNull(c.lowSocPercent);
    }

    @Test
    public void emptyOrJunkFileYieldsEmptyConfig() {
        assertTrue(ConfigImport.parse("").isEmpty());
        assertTrue(ConfigImport.parse("just some prose\nno equals here\n").isEmpty());
        assertTrue(ConfigImport.parse("# only a comment\n").isEmpty());
    }

    @Test
    public void intervalSnapsToNearestOfferedChoice() {
        // 45 is not an offered value; the spinner only has 15/30/60/120/300.
        assertEquals(Integer.valueOf(30), ConfigImport.parse("interval_sec = 45").intervalSec);
        assertEquals(Integer.valueOf(60), ConfigImport.parse("interval_sec = 59").intervalSec);
        assertEquals(Integer.valueOf(300), ConfigImport.parse("interval_sec = 9999").intervalSec);
    }

    @Test
    public void lowSocPercentIsClampedTo1To99() {
        assertEquals(Integer.valueOf(99), ConfigImport.parse("low_soc_percent = 250").lowSocPercent);
        assertEquals(Integer.valueOf(1), ConfigImport.parse("low_soc_percent = 0").lowSocPercent);
        assertEquals(Integer.valueOf(20), ConfigImport.parse("low_soc_percent = 20").lowSocPercent);
    }

    @Test
    public void boostAcceptsCommonBooleanSpellings() {
        assertEquals(Boolean.TRUE, ConfigImport.parse("boost_low_soc = on").boostLowSoc);
        assertEquals(Boolean.TRUE, ConfigImport.parse("boost_low_soc = TRUE").boostLowSoc);
        assertEquals(Boolean.FALSE, ConfigImport.parse("boost_low_soc = 0").boostLowSoc);
        assertNull(ConfigImport.parse("boost_low_soc = maybe").boostLowSoc);
    }

    @Test
    public void onlyFirstEqualsSplitsSoValueMayContainEquals() {
        ConfigImport c = ConfigImport.parse("token = tok=with=equals\n");
        assertEquals("tok=with=equals", c.token);
    }

    @Test
    public void aliasKeysAreAccepted() {
        ConfigImport c = ConfigImport.parse("apikey = k\nuser_token = t\n");
        assertEquals("k", c.apiKey);
        assertEquals("t", c.token);
    }
}
