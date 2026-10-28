package app.diurna

import android.app.Activity
import android.os.Bundle
import app.diurna.data.Backups
import kotlinx.coroutines.launch

/**
 * scripts/backup-db asks for a fresh backup by starting this activity over adb.
 * It is not exported, so other apps on the phone cannot trigger a backup; the adb
 * shell is allowed to start non-exported components.
 */
class BackupTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(Backups.EXTRA_BACKUP, false) == true) {
            container.appScope.launch { container.backups.backupNow() }
        }
        finish()
    }
}
