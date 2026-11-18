// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.spotify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SpotifyAlarmRingerTest {

    @Test
    public void spotifyRingsOnlyWhenEverythingAllowsIt() {
        assertNull(SpotifyAlarmRinger.getUnusableReason(true, true, false, false, false));
    }

    @Test
    public void eachObstacleMakesTheAlarmUseItsRegularRingtone() {
        assertNotNull(SpotifyAlarmRinger.getUnusableReason(false, true, false, false, false));
        assertNotNull(SpotifyAlarmRinger.getUnusableReason(true, false, false, false, false));
        assertNotNull(SpotifyAlarmRinger.getUnusableReason(true, true, true, false, false));
        assertNotNull(SpotifyAlarmRinger.getUnusableReason(true, true, false, true, false));
        assertNotNull(SpotifyAlarmRinger.getUnusableReason(true, true, false, false, true));
    }

    @Test
    public void theFirstObstacleIsReported() {
        assertEquals("invalid Spotify source", SpotifyAlarmRinger.getUnusableReason(false, false, true, true, true));
        assertEquals("device locked since boot (Direct Boot)",
            SpotifyAlarmRinger.getUnusableReason(true, false, true, true, true));
    }
}
