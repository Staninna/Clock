// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.homeassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class HomeAssistantClientTest {

    @Test
    public void normalizesAddresses() {
        assertEquals("http://homeassistant.local:8123", HomeAssistantClient.normalizeUrl("homeassistant.local"));
        assertEquals("http://192.168.1.20:8123", HomeAssistantClient.normalizeUrl(" 192.168.1.20 "));
        assertEquals("http://192.168.1.20:8124", HomeAssistantClient.normalizeUrl("192.168.1.20:8124"));
        assertEquals("http://homeassistant.local:8123", HomeAssistantClient.normalizeUrl("http://homeassistant.local:8123/"));
        assertEquals("http://homeassistant.local:8123", HomeAssistantClient.normalizeUrl("http://homeassistant.local:8123/lovelace/0"));
        assertEquals("https://ha.example.com", HomeAssistantClient.normalizeUrl("https://ha.example.com"));
        assertEquals("https://ha.example.com", HomeAssistantClient.normalizeUrl("HTTPS://ha.example.com/"));
        assertEquals("https://abc.ui.nabu.casa", HomeAssistantClient.normalizeUrl("https://abc.ui.nabu.casa/profile"));
        assertEquals("", HomeAssistantClient.normalizeUrl(""));
        assertEquals("", HomeAssistantClient.normalizeUrl(null));
        assertEquals("", HomeAssistantClient.normalizeUrl("http://"));
    }

    @Test
    public void detectsInsecurePublicAddresses() {
        assertFalse(HomeAssistantClient.isInsecurePublicUrl("http://homeassistant.local:8123"));
        assertFalse(HomeAssistantClient.isInsecurePublicUrl("http://192.168.1.20:8123"));
        assertFalse(HomeAssistantClient.isInsecurePublicUrl("http://10.0.2.2:8123"));
        assertFalse(HomeAssistantClient.isInsecurePublicUrl("http://172.20.0.5:8123"));
        assertFalse(HomeAssistantClient.isInsecurePublicUrl("http://100.101.102.103:8123"));
        assertFalse(HomeAssistantClient.isInsecurePublicUrl("http://homeassistant:8123"));
        assertFalse(HomeAssistantClient.isInsecurePublicUrl("http://[fd00::1]:8123"));
        assertFalse(HomeAssistantClient.isInsecurePublicUrl("https://ha.example.com"));
        assertTrue(HomeAssistantClient.isInsecurePublicUrl("http://ha.example.com:8123"));
        assertTrue(HomeAssistantClient.isInsecurePublicUrl("http://8.8.8.8:8123"));
    }

    @Test
    public void slugifiesDeviceNames() {
        assertEquals("pixel_8_clock", HomeAssistantSettings.slugify("Pixel 8 Clock"));
        assertEquals("stan_s_phone", HomeAssistantSettings.slugify("Stan's phone!"));
        assertEquals("clock", HomeAssistantSettings.slugify("***"));
    }

    @Test
    public void formatsTimestampsForHomeAssistant() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Amsterdam"));
        calendar.clear();
        calendar.set(2026, Calendar.NOVEMBER, 14, 7, 0, 0);
        assertEquals("2026-11-14T07:00:00+01:00", HomeAssistant.formatTimestamp(calendar));

        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(2026, Calendar.JUNE, 1, 6, 30, 0);
        assertEquals("2026-06-01T06:30:00Z", HomeAssistant.formatTimestamp(utc));
    }
}
