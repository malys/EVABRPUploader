package com.evsuite.abrp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The broadcast parser, on the JVM.
 *
 * What is under test is not the vendor's payload — that shape is unverified and may well be
 * something else on the car. It is that the parser finds a temperature wherever the payload
 * puts it, refuses to invent one, and says what it was given when it fails, because the
 * diagnostic is the only thing that reaches a driver with no adb.
 */
public class HeadUnitWeatherTest {

    @Test
    public void readsATemperatureAndThePlaceItIsFor() {
        HeadUnitWeather weather = new HeadUnitWeather();
        weather.acceptPayload((
                "{\"code\":0,\"weather\":{\"temperature\":27,\"unit\":\"C\","
                        + "\"address\":\"Toulouse\",\"conditionType\":1}}"));
        assertEquals(27f, weather.temperatureC(), 0.01f);
        assertEquals("Toulouse", weather.place());
        assertEquals("Toulouse", weather.diagnostic());
    }

    @Test
    public void findsTheValueWhereverThePayloadNests_it() {
        HeadUnitWeather weather = new HeadUnitWeather();
        weather.acceptPayload((
                "{\"data\":{\"current\":[{\"tempValue\":\"18.5\",\"tempUnit\":\"C\"}]}}"));
        assertEquals(18.5f, weather.temperatureC(), 0.01f);
    }

    @Test
    public void convertsAFahrenheitPayload() {
        HeadUnitWeather weather = new HeadUnitWeather();
        weather.acceptPayload(("{\"temperature\":81,\"temperatureUnit\":\"F\"}"));
        assertEquals(27.2f, weather.temperatureC(), 0.1f);
    }

    @Test
    public void anUnnamedUnitIsTakenAsCelsius() {
        HeadUnitWeather weather = new HeadUnitWeather();
        weather.acceptPayload(("{\"temp\":21}"));
        assertEquals(21f, weather.temperatureC(), 0.01f);
    }

    @Test
    public void aPayloadWithoutATemperatureReportsWhatItWasGiven() {
        HeadUnitWeather weather = new HeadUnitWeather();
        weather.acceptPayload(("{\"conditionType\":3,\"address\":\"Toulouse\"}"));
        assertNull(weather.temperatureC());
        assertTrue(weather.diagnostic(), weather.diagnostic().startsWith("no temperature in {"));
        assertTrue(weather.diagnostic(), weather.diagnostic().contains("conditionType"));
    }

    @Test
    public void readsThePlaceFromACityKeyToo() {
        HeadUnitWeather weather = new HeadUnitWeather();
        weather.acceptPayload("{\"temp\":14,\"city\":\"Auzeville\"}");
        assertEquals(14f, weather.temperatureC(), 0.01f);
        assertEquals("Auzeville", weather.place());
    }

    @Test
    public void aBroadcastWithNothingReadableIsReportedNotGuessed() {
        HeadUnitWeather weather = new HeadUnitWeather();
        weather.acceptPayload(null);
        assertNull(weather.temperatureC());
        assertEquals("broadcast carried no JSON", weather.diagnostic());
    }

    @Test
    public void beforeAnyBroadcastTheDiagnosticSaysSo() {
        HeadUnitWeather weather = new HeadUnitWeather();
        assertNull(weather.temperatureC());
        assertEquals("no broadcast yet", weather.diagnostic());
    }

    @Test
    public void malformedJsonDoesNotEscapeTheReceiver() {
        HeadUnitWeather weather = new HeadUnitWeather();
        weather.acceptPayload(("{not json at all"));
        assertNull(weather.temperatureC());
        assertTrue(weather.diagnostic(), weather.diagnostic().contains("reading broadcast"));
    }
}
