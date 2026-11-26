// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.best.deskclock.alarms.AlarmInitReceiver;
import com.best.deskclock.utils.LogUtils;

/**
 * Full-data backup (Auto Backup, Seedvault) with the default file handling. Its only job is to
 * register the restored alarms with AlarmManager, which a restore does not do.
 */
public class DeskClockBackupAgent extends BackupAgent {

    private static final String ACTION_RESTORE_FINISHED = "com.best.deskclock.action.RESTORE_FINISHED";

    /** Time for the restore to kill this process before the alarms are registered. */
    private static final long RESTORE_SETTLE_MS = 10_000;

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data, ParcelFileDescriptor newState) {
        // Unused: fullBackupOnly in the manifest.
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState) {
        // Unused: fullBackupOnly in the manifest.
    }

    /**
     * The restore runs this process in a restricted mode: no DeskClockApplication and no content
     * providers, so the alarm database can't be read here. A broadcast to AlarmInitReceiver, which
     * registers all alarm instances as after a reboot, runs once the restore has killed this process.
     */
    @Override
    public void onRestoreFinished() {
        final Intent intent = new Intent(ACTION_RESTORE_FINISHED).setClass(this, AlarmInitReceiver.class);
        final PendingIntent operation = PendingIntent.getBroadcast(this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final AlarmManager alarmManager = getSystemService(AlarmManager.class);
        final long triggerAt = SystemClock.elapsedRealtime() + RESTORE_SETTLE_MS;

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation);
        } catch (SecurityException e) {
            // No exact alarm permission: a few minutes late is better than waiting for a reboot.
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation);
        }

        LogUtils.i("Restore finished: alarms are registered again in " + RESTORE_SETTLE_MS + " ms");
    }
}
