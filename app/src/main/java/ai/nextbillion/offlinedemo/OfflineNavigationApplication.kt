package ai.nextbillion.offlinedemo

import android.app.Application
import ai.nextbillion.maps.Nextbillion
import ai.nextbillion.maps.style.NGLWellKnownTileServer
import timber.log.Timber

/**
 * Process entry point for the Maps SDK and offline-navigation facade.
 *
 * Integration rule: initialize [Nextbillion] before the first call to [OfflineNavigationManager].
 * The navigation facade obtains its application context and tile-server configuration from the
 * Maps singleton, so reversing this order can make initialization fail before any Activity opens.
 */
class OfflineNavigationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // Install only process-owned state here. The coordinator does not submit work until a
        // visible, initialized download page calls resumePending().
        OfflineBatchDownloadCoordinator.install(this)
        if (BuildConfig.NB_ACCESS_KEY.isNotBlank()) {
            // NGLTomTom must match the dataset used by the Navigation SDK account.
            Nextbillion.getInstance(
                applicationContext,
                BuildConfig.NB_ACCESS_KEY,
                NGLWellKnownTileServer.NGLTomTom,
            )
            // Warm up once per process. Screens can safely call initialize() again and subscribe
            // to the shared result without starting competing native-engine initializations.
            OfflineNavigationManager.initialize()
        }
    }
}
