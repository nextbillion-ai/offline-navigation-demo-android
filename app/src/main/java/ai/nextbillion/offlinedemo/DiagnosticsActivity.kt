package ai.nextbillion.offlinedemo

import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import ai.nextbillion.navigation.core.offline.HealthStatus
import ai.nextbillion.navigation.core.offline.NBNavigationTaskCallback
import ai.nextbillion.navigation.ui.NBNavigation
import ai.nextbillion.offlinedemo.databinding.ActivityDiagnosticsBinding

/**
 * Operational page for separating SDK/configuration failures from missing offline data.
 *
 * Version access is safe before initialization; the deeper health check runs only after the
 * shared process initializer succeeds. A generation token prevents an older refresh response from
 * replacing a newer report or touching a destroyed Activity.
 */
class DiagnosticsActivity : AppCompatActivity() {
    private var binding: ActivityDiagnosticsBinding? = null
    private var refreshGeneration = 0L
    private var initializationSubscription: OfflineNavigationManager.Subscription? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewBinding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        binding = viewBinding
        setContentView(viewBinding.root)
        applySystemBarInsets(viewBinding.root)
        DemoTabs.bind(this, viewBinding.bottomNavigation, R.id.tab_diagnostics)
        viewBinding.refreshDiagnosticsButton.setOnClickListener { refreshDiagnostics() }
        if (BuildConfig.NB_ACCESS_KEY.isBlank()) {
            viewBinding.diagnosticsStatusText.setText(R.string.setup_full_message)
            viewBinding.refreshDiagnosticsButton.isEnabled = false
        } else {
            refreshDiagnostics()
        }
    }

    override fun onResume() {
        super.onResume()
        binding?.bottomNavigation?.selectedItemId = R.id.tab_diagnostics
    }

    private fun refreshDiagnostics() {
        val viewBinding = binding ?: return
        val generation = ++refreshGeneration
        val startedAt = SystemClock.elapsedRealtime()
        viewBinding.refreshDiagnosticsButton.isEnabled = false
        viewBinding.diagnosticsStatusText.setText(R.string.offline_initializing)
        viewBinding.diagnosticsText.text = getString(
            R.string.diagnostics_versions,
            NBNavigation.sdkVersion(),
            NBNavigation.engineVersion(),
        )

        // Refresh replaces only this screen's readiness callback. It does not reset or shut down
        // the process-wide SDK instance owned by OfflineNavigationManager.
        initializationSubscription?.cancel()
        initializationSubscription = OfflineNavigationManager.initialize { initialization ->
            if (!isCurrent(generation)) return@initialize
            initialization.onFailure {
                viewBinding.refreshDiagnosticsButton.isEnabled = true
                showError(R.string.diagnostics_initialization_failed, it)
                return@initialize
            }
            viewBinding.diagnosticsStatusText.setText(R.string.diagnostics_checking_health)
            NBNavigation.healthCheck(object : NBNavigationTaskCallback<HealthStatus> {
                override fun onSuccess(result: HealthStatus?) = deliver {
                    if (!isCurrent(generation)) return@deliver
                    viewBinding.refreshDiagnosticsButton.isEnabled = true
                    if (result == null) {
                        viewBinding.diagnosticsStatusText.setText(R.string.diagnostics_empty_health)
                        return@deliver
                    }
                    viewBinding.diagnosticsStatusText.text = getString(
                        R.string.diagnostics_health_complete,
                        SystemClock.elapsedRealtime() - startedAt,
                    )
                    viewBinding.diagnosticsText.text = renderHealth(result)
                }

                override fun onError(throwable: Throwable) = deliver {
                    if (!isCurrent(generation)) return@deliver
                    viewBinding.refreshDiagnosticsButton.isEnabled = true
                    showError(R.string.diagnostics_health_failed, throwable)
                }
            })
        }
    }

    private fun renderHealth(health: HealthStatus): String = getString(
        R.string.diagnostics_health_report,
        health.state,
        health.sdkVersion,
        health.engineVersion,
        health.nativeBuildId,
        health.schemaVersion,
        health.supportedSchemaVersion,
        health.datasetVersion,
        health.supportedDatasetVersionRange,
        health.dataMode,
        health.tileSourceMode,
        health.activeReaderSource,
        health.loadedTileCount,
        health.sqliteRowsLoaded,
        health.requestCount,
        health.uptimeSeconds,
        NBNavigation.getRouteMode(),
        NBNavigation.getDownloadNetworkPolicy(),
        health.dataRoot,
    )

    private fun showError(action: Int, throwable: Throwable) {
        // The structured SDK error is suitable for user/support decisions; the raw stack trace is
        // intentionally confined to this reference diagnostics page and must be sanitized before
        // a production app uploads or exposes it.
        val error = NBNavigation.toNavigationError(throwable)
        binding?.diagnosticsStatusText?.text = getString(
            R.string.diagnostics_error,
            getString(action),
            error.code,
            error.causeCode ?: "—",
            error.recoverable.toString(),
        )
        binding?.diagnosticsText?.text = throwable.stackTraceToString()
    }

    private fun isCurrent(generation: Long): Boolean =
        generation == refreshGeneration && !isFinishing && !isDestroyed && binding != null

    private fun deliver(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else runOnUiThread(block)
    }

    override fun onDestroy() {
        refreshGeneration++
        initializationSubscription?.cancel()
        initializationSubscription = null
        binding = null
        super.onDestroy()
    }
}
