// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SpotifyUtilsTest {

    private static void assertSource(String input, SpotifyUtils.Type type, String id) {
        SpotifyUtils.Source source = SpotifyUtils.parse(input);
        assertNotNull("Not parsed: " + input, source);
        assertEquals(type, source.type());
        assertEquals(id, source.id());
    }

    @Test
    public void parsesShareLinks() {
        assertSource("https://open.spotify.com/playlist/37i9dQZF1DX0UrRvztWcAU?si=a1b2c3d4e5",
            SpotifyUtils.Type.PLAYLIST, "37i9dQZF1DX0UrRvztWcAU");
        assertSource("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
            SpotifyUtils.Type.TRACK, "4uLU6hMCjMI75M1A2tKUQC");
        assertSource("open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3",
            SpotifyUtils.Type.ALBUM, "1DFixLWuPkv3KT3TnV35m3");
        assertSource("https://open.spotify.com/intl-nl/artist/0OdUWJ0sBjDrqHygGUXeCF",
            SpotifyUtils.Type.ARTIST, "0OdUWJ0sBjDrqHygGUXeCF");
        assertSource("https://open.spotify.com/show/2MAi0BvDc6GTFvKFPXnkCL?si=x",
            SpotifyUtils.Type.SHOW, "2MAi0BvDc6GTFvKFPXnkCL");
        assertSource("https://open.spotify.com/episode/512ojhOuo1ktJprKbVcKyQ",
            SpotifyUtils.Type.EPISODE, "512ojhOuo1ktJprKbVcKyQ");
        assertSource("HTTPS://OPEN.SPOTIFY.COM/playlist/37i9dQZF1DX0UrRvztWcAU",
            SpotifyUtils.Type.PLAYLIST, "37i9dQZF1DX0UrRvztWcAU");
    }

    @Test
    public void parsesSpotifyUris() {
        assertSource("spotify:playlist:37i9dQZF1DX0UrRvztWcAU", SpotifyUtils.Type.PLAYLIST, "37i9dQZF1DX0UrRvztWcAU");
        assertSource("spotify:user:someone:playlist:37i9dQZF1DX0UrRvztWcAU", SpotifyUtils.Type.PLAYLIST, "37i9dQZF1DX0UrRvztWcAU");
        assertSource("  spotify:track:4uLU6hMCjMI75M1A2tKUQC:play ", SpotifyUtils.Type.TRACK, "4uLU6hMCjMI75M1A2tKUQC");
    }

    @Test
    public void findsLinksInSharedText() {
        assertSource("Wake up! Listen on Spotify: https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3?si=abc",
            SpotifyUtils.Type.ALBUM, "1DFixLWuPkv3KT3TnV35m3");
    }

    @Test
    public void rejectsOtherInput() {
        assertNull(SpotifyUtils.parse(null));
        assertNull(SpotifyUtils.parse(""));
        assertNull(SpotifyUtils.parse("   "));
        assertNull(SpotifyUtils.parse("https://example.com/playlist/37i9dQZF1DX0UrRvztWcAU"));
        assertNull(SpotifyUtils.parse("https://open.spotify.com/user/someone"));
        assertNull(SpotifyUtils.parse("https://open.spotify.com/playlist/short"));
        assertNull(SpotifyUtils.parse("spotify:playlist:"));
        assertNull(SpotifyUtils.parse("spotify:playlist:37i9dQZF1DX0Ur%20vztWcAU"));
    }

    @Test
    public void buildsSpotifyUrisAndLinks() {
        SpotifyUtils.Source source = SpotifyUtils.parse("https://open.spotify.com/playlist/37i9dQZF1DX0UrRvztWcAU");
        assertNotNull(source);
        assertEquals("spotify:playlist:37i9dQZF1DX0UrRvztWcAU", source.spotifyUri());
        assertEquals("https://open.spotify.com/playlist/37i9dQZF1DX0UrRvztWcAU", source.webUrl());
        assertEquals("Morning", source.withTitle("  Morning ").title());
        assertNull(source.withTitle("").title());
    }

    @Test
    public void recognizesLinksBeingTyped() {
        org.junit.Assert.assertTrue(SpotifyUtils.looksLikeLinkInProgress("https:/"));
        org.junit.Assert.assertTrue(SpotifyUtils.looksLikeLinkInProgress("https://open.spot"));
        org.junit.Assert.assertTrue(SpotifyUtils.looksLikeLinkInProgress("https://open.spotify.com/album/1DF"));
        org.junit.Assert.assertTrue(SpotifyUtils.looksLikeLinkInProgress("spoti"));
        org.junit.Assert.assertFalse(SpotifyUtils.looksLikeLinkInProgress("https://youtube.com"));
        org.junit.Assert.assertFalse(SpotifyUtils.looksLikeLinkInProgress("hello world"));
    }

    @Test
    public void picksThisPhoneAmongSpotifyConnectDevices() {
        SpotifyWebApi.Device phone = new SpotifyWebApi.Device("p1", "Pixel 8", "Smartphone", false);
        SpotifyWebApi.Device speaker = new SpotifyWebApi.Device("s1", "Living room", "Speaker", true);
        SpotifyWebApi.Device computer = new SpotifyWebApi.Device("c1", "Laptop", "Computer", false);

        java.util.List<String> names = java.util.Arrays.asList("Pixel 8", null);

        assertEquals(phone, SpotifyAlarmPlayer.findThisPhone(java.util.List.of(speaker, phone, computer), names));
        // Never another kind of device, even if it is the active one.
        assertNull(SpotifyAlarmPlayer.findThisPhone(java.util.List.of(speaker, computer), names));
        assertNull(SpotifyAlarmPlayer.findThisPhone(java.util.List.of(), names));

        // Never another phone of the same account, even the active one or the only one.
        SpotifyWebApi.Device otherPhone = new SpotifyWebApi.Device("p2", "Old phone", "Smartphone", true);
        assertEquals(phone, SpotifyAlarmPlayer.findThisPhone(java.util.List.of(otherPhone, phone), names));
        assertNull(SpotifyAlarmPlayer.findThisPhone(java.util.List.of(otherPhone), names));
    }

    @Test
    public void validatesClientIds() {
        org.junit.Assert.assertTrue(SpotifyAccount.isValidClientId("0123456789abcdef0123456789ABCDEF"));
        org.junit.Assert.assertTrue(SpotifyAccount.isValidClientId(" 0123456789abcdef0123456789abcdef "));
        org.junit.Assert.assertFalse(SpotifyAccount.isValidClientId("0123456789abcdef"));
        org.junit.Assert.assertFalse(SpotifyAccount.isValidClientId("0123456789abcdef0123456789abcdeg"));
        org.junit.Assert.assertFalse(SpotifyAccount.isValidClientId(null));
        assertEquals(64, SpotifyAccount.randomString(64).length());
    }

    @Test
    public void scalesAlarmVolumeToMediaVolume() {
        assertEquals(1, SpotifyAlarmPlayer.scaleVolume(0, 7, 25));
        assertEquals(25, SpotifyAlarmPlayer.scaleVolume(7, 7, 25));
        assertEquals(25, SpotifyAlarmPlayer.scaleVolume(12, 7, 25));
        assertEquals(14, SpotifyAlarmPlayer.scaleVolume(4, 7, 25));
        assertEquals(15, SpotifyAlarmPlayer.scaleVolume(15, 15, 15));
    }
}
