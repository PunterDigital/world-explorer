package co.shaypunter.worldexplorer.update

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import co.shaypunter.worldexplorer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Where an install session (AppUpdater.installWithSession) reports back. Not
 * exported, so only Android's installer (through our own PendingIntent) can
 * reach it: an app that sends `ACTION_INSTALL_STATUS` to the exported
 * MainActivity gets nothing (#5616). A confirmation is only passed on when it
 * opens the system package installer, with every URI grant stripped, and
 * MainActivity shows it the next time it's on screen.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdater.ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (installStatusAction(status)) {
            InstallStatusAction.CONFIRM -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                val safe = confirm?.let { installerConfirmation(context, it) }
                if (safe == null) {
                    Log.w(TAG, "Dropped a confirmation that doesn't open the package installer: $confirm")
                    Toast.makeText(context.applicationContext, R.string.update_install_failed, Toast.LENGTH_LONG).show()
                    return
                }
                InstallConfirmations.post(safe)
            }
            InstallStatusAction.FAILED -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Install failed: status $status, $detail")
                Toast.makeText(context.applicationContext, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            }
            InstallStatusAction.INSTALLED, InstallStatusAction.CANCELLED -> Unit
        }
    }

    companion object {
        private const val TAG = "AppUpdate"

        private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        /**
         * [confirm] pinned to the system package installer it resolves to, with
         * no URI grants, selector or clip data; null when it doesn't open the
         * installer's confirmation.
         */
        fun installerConfirmation(context: Context, confirm: Intent): Intent? {
            val copy = Intent(confirm)
            copy.selector = null
            copy.clipData = null
            copy.flags = copy.flags and GRANT_FLAGS.inv()
            val resolved = context.packageManager.resolveActivity(copy, 0)?.activityInfo
            val app = resolved?.applicationInfo
            val system = app != null &&
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (!isInstallerConfirmation(
                    action = copy.action,
                    targetPackage = copy.`package` ?: copy.component?.packageName,
                    resolvedPackage = resolved?.packageName,
                    resolvedIsSystemApp = system,
                    ourPackage = context.packageName
                )
            ) return null
            copy.component = ComponentName(resolved!!.packageName, resolved.name)
            return copy
        }
    }
}

/**
 * Hands installer confirmations from [InstallStatusReceiver] to MainActivity.
 * Holds the newest one until the activity is started and takes it, so an
 * answer that arrives while the app is in the background waits for the user.
 */
object InstallConfirmations {
    private val _pending = MutableStateFlow<Intent?>(null)
    val pending: StateFlow<Intent?> = _pending.asStateFlow()

    fun post(confirm: Intent) {
        _pending.value = confirm
    }

    /** The waiting confirmation, if any; it's handed out once. */
    fun take(): Intent? = _pending.getAndUpdate { null }
}
