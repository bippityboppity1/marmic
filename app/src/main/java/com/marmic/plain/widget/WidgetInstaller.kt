package com.marmic.plain.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

private const val TAG = "WidgetInstaller"

/** Request code for the configure step, which must go through the legacy path. */
const val REQUEST_CONFIGURE_WIDGET = 0x9001

/**
 * Drives the three-step dance required to host a third-party widget:
 *
 *  1. allocate an id from our [PlainAppWidgetHost];
 *  2. bind the id to the provider — a normal app cannot hold BIND_APPWIDGET, so
 *     if [AppWidgetManager.bindAppWidgetIdIfAllowed] says no we ask the system
 *     to get the user's consent;
 *  3. run the provider's configure activity, if it declares one.
 *
 * Any step can be cancelled, in which case the allocated id is released again.
 */
class WidgetInstaller(
    private val activity: ComponentActivity,
    private val host: PlainAppWidgetHost,
    private val appWidgetManager: AppWidgetManager,
    /** Called once the widget is bound, configured and ready to be persisted. */
    private val onWidgetReady: (appWidgetId: Int) -> Unit,
) {

    private var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    private val bindLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val id = pendingWidgetId
            if (result.resultCode == Activity.RESULT_OK && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                configureOrFinish(id)
            } else {
                abandon()
            }
        }

    /** Entry point: called with a provider the user picked from our own list. */
    fun install(provider: AppWidgetProviderInfo, user: UserHandle = Process.myUserHandle()) {
        val id = host.allocateAppWidgetId()
        pendingWidgetId = id

        val bound = runCatching {
            appWidgetManager.bindAppWidgetIdIfAllowed(id, user, provider.provider, null)
        }.getOrDefault(false)

        if (bound) {
            configureOrFinish(id)
            return
        }

        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user)
        }
        runCatching { bindLauncher.launch(intent) }
            .onFailure {
                Log.w(TAG, "No system UI to grant widget binding", it)
                abandon()
            }
    }

    private fun configureOrFinish(id: Int) {
        val info = appWidgetManager.getAppWidgetInfo(id)
        val configure = info?.configure
        if (configure == null) {
            finish(id)
            return
        }

        // startAppWidgetConfigureActivityForResult goes through an IntentSender
        // owned by the system, which is the only way to reach a configure
        // activity that the provider did not export.
        val started = runCatching {
            host.startAppWidgetConfigureActivityForResult(
                activity,
                id,
                /* intentFlags = */ 0,
                REQUEST_CONFIGURE_WIDGET,
                /* options = */ null,
            )
            true
        }.getOrElse {
            Log.w(TAG, "Configure activity for $configure could not be started", it)
            false
        }

        // Some providers declare a configure activity they cannot actually open.
        // Keeping the widget is friendlier than dropping it on the floor.
        if (!started) finish(id)
    }

    /** Must be forwarded from the activity's onActivityResult. */
    fun onConfigureResult(requestCode: Int, resultCode: Int) {
        if (requestCode != REQUEST_CONFIGURE_WIDGET) return
        val id = pendingWidgetId
        if (resultCode == Activity.RESULT_OK && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish(id)
        } else {
            abandon()
        }
    }

    private fun finish(id: Int) {
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        onWidgetReady(id)
    }

    private fun abandon() {
        val id = pendingWidgetId
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            runCatching { host.deleteAppWidgetId(id) }
        }
    }
}
