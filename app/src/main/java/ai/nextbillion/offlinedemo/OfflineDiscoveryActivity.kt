package ai.nextbillion.offlinedemo

import android.os.Bundle
import android.os.Looper
import android.os.StatFs
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import ai.nextbillion.kits.directions.models.DirectionsRoute
import ai.nextbillion.kits.geojson.Point
import ai.nextbillion.navigation.core.offline.NBNavigationTaskCallback
import ai.nextbillion.navigation.core.offline.OfflineCountyQueryTask
import ai.nextbillion.navigation.core.offline.OfflineDownloadNetworkPolicy
import ai.nextbillion.navigation.core.offline.OfflineRegionCatalogSyncResult
import ai.nextbillion.navigation.core.offline.OfflineRegionListResult
import ai.nextbillion.navigation.core.offline.OfflineRegionListRow
import ai.nextbillion.navigation.core.offline.RouteMode
import ai.nextbillion.navigation.core.offline.RouteSource
import ai.nextbillion.navigation.core.offline.region.RegionListSyncMode
import ai.nextbillion.navigation.ui.NBNavigation
import ai.nextbillion.offlinedemo.databinding.ActivityOfflineDiscoveryBinding
import java.util.Locale

/**
 * Radius and along-route catalog discovery pushed from the Regions root page.
 *
 * Discovery returns region identities, then the SDK joins Route and Map catalog metadata into
 * [OfflineRegionListRow] values. Only rows containing both packages are selectable for combined
 * download.
 */
class OfflineDiscoveryActivity : AppCompatActivity() {
    private var binding: ActivityOfflineDiscoveryBinding? = null
    private lateinit var overlayController: MapOverlayController
    private lateinit var adapter: DiscoveryRegionAdapter
    private lateinit var session: DiscoverySessionViewModel
    private var queryTask: OfflineCountyQueryTask? = null
    private var progressSubscription: NBNavigation.OfflineProgressSubscription? = null
    private var batchSubscription: OfflineBatchDownloadCoordinator.Subscription? = null
    private var initializationSubscription: OfflineNavigationManager.Subscription? = null
    private var rows: List<OfflineRegionListRow> = emptyList()
    private var initialized = false
    private var batchRunning = false
    private var routeModeBeforeQuery: RouteMode? = null
    private var downloadPageSavedState: Bundle? = null
    private val mode: DiscoveryMode by lazy {
        if (intent.getStringExtra(EXTRA_MODE) == MODE_ROUTE) DiscoveryMode.ROUTE
        else DiscoveryMode.RADIUS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadPageSavedState = savedInstanceState
        val viewBinding = ActivityOfflineDiscoveryBinding.inflate(layoutInflater)
        binding = viewBinding
        setContentView(viewBinding.root)
        applySystemBarInsets(viewBinding.root)
        session = ViewModelProvider(this)[DiscoverySessionViewModel::class.java]
        rows = session.rows
        overlayController = MapOverlayController(this)
        adapter = DiscoveryRegionAdapter(::onSelectionChanged)
        viewBinding.discoveryRegionList.layoutManager = LinearLayoutManager(this)
        viewBinding.discoveryRegionList.adapter = adapter
        adapter.submitRows(rows)
        adapter.restoreSelection(session.selectedRegionIds)
        configureMode(viewBinding)
        viewBinding.discoveryBackButton.setOnClickListener { finish() }
        viewBinding.runDiscoveryButton.setOnClickListener { runDiscovery() }
        viewBinding.selectAllDiscoveryButton.setOnClickListener { adapter.selectAllOrClear() }
        viewBinding.downloadSelectedDiscoveryButton.setOnClickListener { confirmBatchDownload() }

        viewBinding.discoveryMapView.onCreate(savedInstanceState)
        viewBinding.discoveryMapView.getMapAsync { map ->
            if (!isAlive()) return@getMapAsync
            overlayController.attach(map)
            session.routeGeometry?.let { geometry ->
                runCatching { overlayController.drawRoute(geometry, session.routePrecision) }
            }
        }

        setControlsEnabled(false)
        setStatus(getString(R.string.offline_initializing))
        initializationSubscription = OfflineNavigationManager.initialize { result ->
            if (!isAlive()) return@initialize
            result.onSuccess {
                initialized = true
                setControlsEnabled(true)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    startVisibleSubscriptions()
                    OfflineBatchDownloadCoordinator.resumePending()
                }
                setStatus(
                    if (rows.isNotEmpty()) {
                        resources.getQuantityString(
                            R.plurals.discovery_results_restored,
                            rows.size,
                            rows.size,
                        )
                    } else if (mode == DiscoveryMode.RADIUS) {
                        "Enter a center and radius, then find matching regions."
                    } else {
                        "Enter route endpoints, then generate a route and find its regions."
                    },
                )
            }.onFailure { showError("Offline initialization failed", it) }
        }
    }

    private fun startVisibleSubscriptions() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        // Observers are visible-screen concerns. Recreate them in onStart and cancel in onStop so
        // a back-stack Activity cannot retain/update an inactive RecyclerView or MapView.
        if (progressSubscription == null) {
            progressSubscription = NBNavigation.observeOfflineProgress { snapshots ->
                deliver { adapter.submitProgress(snapshots) }
            }
        }
        if (batchSubscription == null) {
            batchSubscription = OfflineBatchDownloadCoordinator.observe(::renderBatchState)
        }
    }

    private fun renderBatchState(snapshot: OfflineBatchDownloadCoordinator.Snapshot) = deliver {
        batchRunning = snapshot.running
        setControlsEnabled(initialized && !snapshot.running)
        when {
            snapshot.running -> setStatus(
                getString(
                    R.string.batch_download_progress,
                    (snapshot.completed + 1).coerceAtMost(snapshot.total),
                    snapshot.total,
                    snapshot.currentRegionId?.toString() ?: getString(R.string.unknown_value),
                ),
            )
            snapshot.total > 0 -> setStatus(
                resources.getQuantityString(
                    R.plurals.batch_download_complete,
                    snapshot.total,
                    snapshot.total,
                    snapshot.failures,
                ),
            )
        }
    }

    private fun configureMode(viewBinding: ActivityOfflineDiscoveryBinding) {
        val isRadius = mode == DiscoveryMode.RADIUS
        viewBinding.discoveryTitleText.setText(
            if (isRadius) R.string.discovery_radius_title else R.string.discovery_route_title,
        )
        viewBinding.discoveryDestinationRow.visibility = if (isRadius) View.GONE else View.VISIBLE
        viewBinding.discoveryRadiusInput.visibility = if (isRadius) View.VISIBLE else View.GONE
        viewBinding.runDiscoveryButton.setText(
            if (isRadius) R.string.find_regions_radius else R.string.find_regions_route,
        )
    }

    private fun runDiscovery() {
        if (!initialized || batchRunning) return
        val viewBinding = binding ?: return
        val origin = parseCoordinates(
            viewBinding.discoveryOriginLatInput.text,
            viewBinding.discoveryOriginLonInput.text,
        )
        if (origin == null) {
            return setStatus(getString(R.string.invalid_coordinates))
        }
        queryTask?.cancel()
        setQueryRunning(true)
        setStatus("Synchronizing region catalogs with AUTO…")
        // Recommendations are joined against local catalogs, so refresh those caches before the
        // spatial query. A sync error is non-fatal when a previously successful cache exists.
        NBNavigation.syncRegions(
            RegionListSyncMode.AUTO,
            callback<OfflineRegionCatalogSyncResult>({
                if (mode == DiscoveryMode.RADIUS) {
                    val radiusKm = viewBinding.discoveryRadiusInput.text.toString().toDoubleOrNull()
                    if (radiusKm == null || !radiusKm.isFinite() || radiusKm < 0.0) {
                        setQueryRunning(false)
                        return@callback setStatus("Enter a finite, non-negative radius in kilometers.")
                    }
                    queryRadius(origin.latitude, origin.longitude, radiusKm * 1_000.0)
                } else {
                    val destination = parseCoordinates(
                        viewBinding.discoveryDestinationLatInput.text,
                        viewBinding.discoveryDestinationLonInput.text,
                    )
                    if (destination == null) {
                        setQueryRunning(false)
                        return@callback setStatus(getString(R.string.invalid_coordinates))
                    }
                    queryAlongRoute(origin, destination)
                }
            }, onError = {
                setStatus("AUTO sync failed; continuing with the retained catalog: ${it.message}")
                if (mode == DiscoveryMode.RADIUS) {
                    val radiusKm = viewBinding.discoveryRadiusInput.text.toString().toDoubleOrNull()
                    if (radiusKm == null || !radiusKm.isFinite() || radiusKm < 0.0) {
                        setQueryRunning(false)
                    } else {
                        queryRadius(origin.latitude, origin.longitude, radiusKm * 1_000.0)
                    }
                } else {
                    val destination = parseCoordinates(
                        viewBinding.discoveryDestinationLatInput.text,
                        viewBinding.discoveryDestinationLonInput.text,
                    )
                    if (destination == null) {
                        setQueryRunning(false)
                    } else {
                        queryAlongRoute(origin, destination)
                    }
                }
            }),
        )
    }

    private fun queryRadius(latitude: Double, longitude: Double, radiusMeters: Double) {
        setStatus("Finding regions within ${formatDistance(radiusMeters)}…")
        // Keep the cancellable task and release it on replacement/destruction. This prevents a
        // stale query from updating a recreated Activity.
        queryTask = NBNavigation.getCountiesInRadius(
            latitude,
            longitude,
            radiusMeters,
            callback<OfflineRegionListResult>(::applyResults, ::finishQueryWithError),
        )
        if (queryTask == null) finishQueryWithError(IllegalStateException("The radius query did not start."))
    }

    private fun queryAlongRoute(
        origin: Coordinates,
        destination: Coordinates,
    ) {
        // Region discovery needs an actual route geometry. Force only this request online, retain
        // the independent download policy, then restore the customer's previous route mode.
        val previousMode = NBNavigation.getRouteMode()
        val policy = NBNavigation.getDownloadNetworkPolicy()
        routeModeBeforeQuery = previousMode
        NBNavigation.setRuntimeConfig(RouteMode.ONLINE_ONLY, policy)
        setStatus("Generating the route used for region discovery…")
        NBNavigation.fetchRoute(
            Point.fromLngLat(origin.longitude, origin.latitude),
            Point.fromLngLat(destination.longitude, destination.latitude),
            "en",
            object : NBNavigation.SourceAwareRoutingCallback {
                override fun onRoutingReady(
                    routes: MutableList<DirectionsRoute>,
                    source: RouteSource,
                ) {
                    restoreRouteMode(policy)
                    deliver {
                        val route = routes.firstOrNull()
                        if (route == null) {
                            return@deliver finishQueryWithError(
                                IllegalStateException("No route was returned."),
                            )
                        }
                        route.geometry()?.takeIf { it.isNotBlank() }?.let {
                            session.routeGeometry = it
                            session.routePrecision = route.precision()
                            runCatching { overlayController.drawRoute(it, route.precision()) }
                        }
                        setStatus("Route source ${source.name}. Finding regions along the route…")
                        // Passing the SDK route object preserves its decoded shape and precision;
                        // callers should not rebuild an approximate line from UI coordinates.
                        queryTask = NBNavigation.getCountiesAlongRoute(
                            route,
                            callback<OfflineRegionListResult>(::applyResults, ::finishQueryWithError),
                        )
                        if (queryTask == null) {
                            finishQueryWithError(
                                IllegalStateException("The along-route query did not start."),
                            )
                        }
                    }
                }

                override fun onRoutingError(throwable: Throwable) {
                    restoreRouteMode(policy)
                    deliver { finishQueryWithError(throwable) }
                }
            },
        )
    }

    private fun restoreRouteMode(policy: OfflineDownloadNetworkPolicy = NBNavigation.getDownloadNetworkPolicy()) {
        val previous = routeModeBeforeQuery ?: return
        routeModeBeforeQuery = null
        NBNavigation.setRuntimeConfig(previous, policy)
    }

    private fun applyResults(result: OfflineRegionListResult?) {
        queryTask = null
        setQueryRunning(false)
        rows = result?.regionRows.orEmpty().sortedWith(
            compareBy(
                { it.country.orEmpty() },
                { it.adminL1.orEmpty() },
                { it.adminL2.orEmpty() },
                { it.adminL3.orEmpty() },
                { it.displayName },
            ),
        )
        session.rows = rows
        session.selectedRegionIds = emptySet()
        adapter.submitRows(rows)
        val partial = listOfNotNull(
            result?.routeError?.let { "route: $it" },
            result?.mapError?.let { "map: $it" },
        ).joinToString("; ")
        setStatus(
            buildString {
                append("Found ${rows.size} matching regions")
                if (rows.isNotEmpty()) append(". Select complete Route + Map packages to download.")
                else append(".")
                if (partial.isNotBlank()) append(" Partial errors: $partial")
            },
        )
    }

    private fun finishQueryWithError(throwable: Throwable) {
        queryTask = null
        setQueryRunning(false)
        showError("Region discovery failed", throwable)
    }

    private fun confirmBatchDownload() {
        if (batchRunning) return
        val selected = adapter.selectedRegionIds()
        val selectedRows = rows.filter { it.regionId in selected && it.hasRoute && it.hasMap }
        if (selectedRows.isEmpty()) return setStatus("Select at least one complete Route + Map package.")
        // The estimate remains nullable if any Route or Map size is unknown. See the explicit
        // acknowledgement below; silently treating an unknown component as zero is unsafe.
        val estimatedBytes = selectedRows.fold(0L as Long?) { total, row ->
            DownloadSizeMath.sumKnown(
                total,
                DownloadSizeMath.sumKnown(row.routeSizeBytes, row.mapSizeBytes),
            )
        }
        val policy = selectedNetworkPolicy()
        if (estimatedBytes == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.unknown_download_size_title)
                .setMessage(R.string.unknown_download_size_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.continue_action) { _, _ ->
                    confirmBatchNetworkPolicy(selectedRows, policy, null)
                }
                .show()
            return
        }
        if (!hasEnoughStorage(estimatedBytes)) return
        confirmBatchNetworkPolicy(selectedRows, policy, estimatedBytes)
    }

    private fun confirmBatchNetworkPolicy(
        selectedRows: List<OfflineRegionListRow>,
        policy: OfflineDownloadNetworkPolicy,
        estimatedBytes: Long?,
    ) {
        if (policy == OfflineDownloadNetworkPolicy.ANY) {
            AlertDialog.Builder(this)
                .setTitle(R.string.mobile_data_title)
                .setMessage(
                    getString(
                        R.string.mobile_data_message,
                        estimatedBytes?.let(::formatBytes) ?: getString(R.string.unknown_value),
                    ),
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.download) { _, _ -> startBatchDownload(selectedRows, policy) }
                .show()
        } else {
            startBatchDownload(selectedRows, policy)
        }
    }

    private fun startBatchDownload(
        selectedRows: List<OfflineRegionListRow>,
        policy: OfflineDownloadNetworkPolicy,
    ) {
        // The process coordinator owns the callbacks and persisted queue. The Activity only renders
        // snapshots, so rotation or tab navigation cannot interrupt the sequence.
        if (!OfflineBatchDownloadCoordinator.start(selectedRows.map { it.regionId }, policy)) {
            setStatus(getString(R.string.batch_download_already_running))
        }
    }

    private fun onSelectionChanged(selected: Set<Long>) {
        session.selectedRegionIds = selected
        val viewBinding = binding ?: return
        viewBinding.downloadSelectedDiscoveryButton.isEnabled = selected.isNotEmpty() && !batchRunning
        viewBinding.downloadSelectedDiscoveryButton.text =
            if (selected.isEmpty()) getString(R.string.download_selected)
            else "Download selected (${selected.size})"
        viewBinding.selectAllDiscoveryButton.setText(
            if (adapter.allEligibleSelected()) R.string.clear_selection else R.string.select_all,
        )
    }

    private fun selectedNetworkPolicy(): OfflineDownloadNetworkPolicy {
        val name = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(PREFERENCE_POLICY, OfflineDownloadNetworkPolicy.WIFI_ONLY.name)
        return runCatching { OfflineDownloadNetworkPolicy.valueOf(name.orEmpty()) }
            .getOrDefault(OfflineDownloadNetworkPolicy.WIFI_ONLY)
    }

    private fun hasEnoughStorage(downloadBytes: Long): Boolean {
        val required = DownloadSizeMath.requiredFreeBytes(downloadBytes)
        val available = StatFs(filesDir.absolutePath).availableBytes
        if (available >= required) return true
        AlertDialog.Builder(this)
            .setTitle(R.string.insufficient_storage_title)
            .setMessage(
                getString(
                    R.string.insufficient_storage_message,
                    formatBytes(required),
                    formatBytes(available),
                ),
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return false
    }

    private fun setQueryRunning(running: Boolean) {
        binding?.runDiscoveryButton?.isEnabled = !running && initialized && !batchRunning
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding?.runDiscoveryButton?.isEnabled = enabled
        binding?.selectAllDiscoveryButton?.isEnabled = enabled
        binding?.downloadSelectedDiscoveryButton?.isEnabled =
            enabled && adapter.selectedRegionIds().isNotEmpty()
    }

    private fun setStatus(message: String) = deliver { binding?.discoveryStatusText?.text = message }

    private fun showError(action: String, throwable: Throwable) {
        val error = NBNavigation.toNavigationError(throwable)
        setStatus(
            "$action: code=${error.code}, cause=${error.causeCode ?: "—"}, " +
                "recoverable=${error.recoverable}, message=${error.message}",
        )
    }

    private fun <T> callback(
        onSuccess: (T?) -> Unit,
        onError: (Throwable) -> Unit = { showError("SDK operation failed", it) },
    ) = object : NBNavigationTaskCallback<T> {
        override fun onSuccess(result: T?) = deliver { onSuccess(result) }
        override fun onError(throwable: Throwable) = deliver { onError(throwable) }
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1_000.0) String.format(Locale.US, "%.1f km", meters / 1_000.0)
        else String.format(Locale.US, "%.0f m", meters)

    private fun formatBytes(bytes: Long): String = Formatter.formatFileSize(this, bytes)

    private fun deliver(block: () -> Unit) {
        if (!isAlive()) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else runOnUiThread { if (isAlive()) block() }
    }

    private fun isAlive(): Boolean = !isFinishing && !isDestroyed && binding != null

    override fun onStart() {
        super.onStart()
        binding?.discoveryMapView?.onStart()
        // begin/end belongs to visible lifecycle because the SDK exposes one active page owner.
        NBNavigation.beginDownloadPage(this, downloadPageSavedState)
        downloadPageSavedState = null
        if (initialized) {
            startVisibleSubscriptions()
            OfflineBatchDownloadCoordinator.resumePending()
        }
    }

    override fun onResume() {
        super.onResume()
        binding?.discoveryMapView?.onResume()
    }

    override fun onPause() {
        binding?.discoveryMapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        progressSubscription?.cancel()
        progressSubscription = null
        batchSubscription?.cancel()
        batchSubscription = null
        NBNavigation.endDownloadPage(this)
        binding?.discoveryMapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding?.discoveryMapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding?.discoveryMapView?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        initializationSubscription?.cancel()
        initializationSubscription = null
        restoreRouteMode()
        queryTask?.cancel()
        queryTask = null
        progressSubscription?.cancel()
        progressSubscription = null
        if (binding != null) binding?.discoveryMapView?.onDestroy()
        overlayController.detach()
        binding = null
        super.onDestroy()
    }

    private enum class DiscoveryMode { RADIUS, ROUTE }

    companion object {
        const val EXTRA_MODE = "offline_discovery_mode"
        const val MODE_RADIUS = "radius"
        const val MODE_ROUTE = "route"
        private const val PREFERENCES_NAME = "offline_region_downloads"
        private const val PREFERENCE_POLICY = "network_policy"
    }
}
