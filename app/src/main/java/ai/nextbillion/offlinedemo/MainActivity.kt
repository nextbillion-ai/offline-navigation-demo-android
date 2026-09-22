package ai.nextbillion.offlinedemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import ai.nextbillion.kits.directions.models.DirectionsRoute
import ai.nextbillion.kits.geojson.Point
import ai.nextbillion.navigation.core.offline.NBNavigationTaskCallback
import ai.nextbillion.navigation.core.offline.OfflineTaskState
import ai.nextbillion.navigation.core.offline.RouteMode
import ai.nextbillion.navigation.core.offline.RouteSource
import ai.nextbillion.navigation.ui.NavLauncherConfig
import ai.nextbillion.navigation.ui.NBNavigation
import ai.nextbillion.navigation.ui.NavigationLauncher
import ai.nextbillion.offlinedemo.databinding.ActivityMainBinding
import java.util.Locale
import timber.log.Timber

/**
 * Route reference screen: installed coverage, source-aware route calculation, and SDK UI launch.
 *
 * Typed coordinates do not require device location. Fine-location permission is intentionally
 * deferred until the user starts turn-by-turn navigation.
 */
class MainActivity : AppCompatActivity() {
    private var binding: ActivityMainBinding? = null
    private lateinit var overlayController: MapOverlayController
    private var offlineInitialized = false
    private var initializationRequested = false
    private lateinit var routeSession: RouteSessionViewModel
    private var progressSubscription: NBNavigation.OfflineProgressSubscription? = null
    private var initializationSubscription: OfflineNavigationManager.Subscription? = null
    private var completedRegionIds: Set<Long> = emptySet()
    private var installedBoundaryRefreshGeneration = 0L

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            requestNotificationPermissionThenStart()
        } else {
            setStatus(getString(R.string.precise_location_required))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Notification permission is recommended but not required to open navigation.
        launchNavigation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.NB_ACCESS_KEY.isBlank()) {
            showMissingConfiguration()
            return
        }

        val viewBinding = ActivityMainBinding.inflate(layoutInflater)
        binding = viewBinding
        setContentView(viewBinding.root)
        applySystemBarInsets(viewBinding.root)
        routeSession = ViewModelProvider(this)[RouteSessionViewModel::class.java]
        overlayController = MapOverlayController(this)
        DemoTabs.bind(this, viewBinding.bottomNavigation, R.id.tab_route)

        viewBinding.downloadRegionsButton.setOnClickListener {
            DemoTabs.open(this, R.id.tab_regions)
        }
        viewBinding.routeButton.setOnClickListener { calculateRoute() }
        viewBinding.startNavigationButton.setOnClickListener { startNavigation() }
        restoreRouteUi(viewBinding)

        viewBinding.mapView.onCreate(savedInstanceState)
        viewBinding.mapView.getMapAsync { map ->
            if (isAlive()) {
                overlayController.attach(map)
                routeSession.geometry?.let { geometry ->
                    runCatching { overlayController.drawRoute(geometry, routeSession.precision) }
                }
                refreshInstalledRegionBoundaries()
            }
        }
        initializeOfflineFeature()
    }

    private fun showMissingConfiguration() {
        val messageView = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            text = getString(R.string.setup_full_message)
            textSize = 17f
            setTextIsSelectable(true)
        }
        setContentView(messageView)
        applySystemBarInsets(messageView)
    }

    private fun initializeOfflineFeature() {
        if (initializationRequested || offlineInitialized) return
        initializationRequested = true
        val startedAt = SystemClock.elapsedRealtime()
        setStatus(getString(R.string.offline_initializing))
        initializationSubscription?.cancel()
        initializationSubscription = OfflineNavigationManager.initialize { result ->
            if (!isAlive()) return@initialize
            initializationRequested = false
            result.onSuccess {
                offlineInitialized = true
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    startProgressObservation()
                }
                refreshInstalledRegionBoundaries()
                setStatus(
                    getString(
                        R.string.offline_ready,
                        SystemClock.elapsedRealtime() - startedAt,
                    ),
                )
            }.onFailure {
                offlineInitialized = false
                showError(getString(R.string.offline_initialization_failed), it)
            }
        }
    }

    private fun startProgressObservation() {
        if (progressSubscription != null) return
        progressSubscription = NBNavigation.observeOfflineProgress { snapshots ->
            // Installed boundary coverage is based on the routing package. Overall completion also
            // requires Map tiles, but the SDK boundary API specifically describes installed routes.
            val completed = snapshots
                .filter { it.routeNetwork?.state == OfflineTaskState.COMPLETED }
                .mapTo(linkedSetOf()) { it.regionId }
            deliver {
                if (completed != completedRegionIds) {
                    completedRegionIds = completed
                    refreshInstalledRegionBoundaries()
                }
            }
        }
    }

    private fun refreshInstalledRegionBoundaries() {
        if (!offlineInitialized || !isAlive()) return
        val generation = ++installedBoundaryRefreshGeneration
        // One FeatureCollection is cheaper and simpler than requesting every installed region
        // separately. Generation guards prevent a slower old request replacing a newer overlay.
        NBNavigation.getInstalledRoutesRegionBoundaryFeatureCollection(
            object : NBNavigationTaskCallback<String> {
                override fun onSuccess(result: String?) = deliver {
                    if (generation != installedBoundaryRefreshGeneration) return@deliver
                    Timber.d("Installed-region boundary GeoJSON chars=%d", result?.length ?: 0)
                    overlayController.drawInstalledBoundaries(result)
                }

                override fun onError(throwable: Throwable) {
                    Timber.w(throwable, "Failed to load installed region boundaries")
                }
            },
        )
    }

    private fun calculateRoute() {
        if (!offlineInitialized) {
            initializeOfflineFeature()
            return setStatus(getString(R.string.offline_not_ready))
        }
        val viewBinding = binding ?: return
        val origin = parseCoordinates(
            viewBinding.originLatInput.text,
            viewBinding.originLonInput.text,
        )
        val destination = parseCoordinates(
            viewBinding.destinationLatInput.text,
            viewBinding.destinationLonInput.text,
        )
        if (origin == null || destination == null) {
            return setStatus(getString(R.string.invalid_coordinates))
        }

        val mode = when (viewBinding.routeModeGroup.checkedButtonId) {
            R.id.routeModeOnlineButton -> RouteMode.ONLINE_ONLY
            R.id.routeModeOfflineButton -> RouteMode.OFFLINE_ONLY
            else -> RouteMode.SMART
        }
        // Runtime config contains two independent global choices. Preserve the download policy
        // selected on the Regions page when changing only the route source strategy.
        NBNavigation.setRuntimeConfig(mode, NBNavigation.getDownloadNetworkPolicy())
        clearSelectedRoute()
        viewBinding.routeButton.isEnabled = false
        viewBinding.routeResultText.setText(R.string.route_calculating)
        // SMART can fall back between online and offline engines. The callback's RouteSource is
        // displayed so integrators can verify which engine actually served the route.
        NBNavigation.fetchRoute(
            Point.fromLngLat(origin.longitude, origin.latitude),
            Point.fromLngLat(destination.longitude, destination.latitude),
            "en",
            object : NBNavigation.SourceAwareRoutingCallback {
                override fun onRoutingReady(
                    routes: MutableList<DirectionsRoute>,
                    source: RouteSource,
                ) = deliver {
                    viewBinding.routeButton.isEnabled = true
                    val route = routes.firstOrNull()
                    val geometry = route?.geometry()
                    if (route == null || geometry.isNullOrBlank()) {
                        return@deliver setStatus(getString(R.string.route_missing_geometry))
                    }
                    routeSession.selectedRoute = route
                    routeSession.availableRoutes = routes.toList()
                    routeSession.geometry = geometry
                    routeSession.precision = route.precision()
                    viewBinding.startNavigationButton.visibility = View.VISIBLE
                    viewBinding.startNavigationButton.isEnabled = true
                    runCatching { overlayController.drawRoute(geometry, route.precision()) }
                        .onFailure { showError(getString(R.string.route_render_failed), it) }
                    routeSession.resultText = String.format(
                        Locale.US,
                        "Source: %s\nDistance: %.2f km · ETA: %.1f min · Alternatives: %d",
                        source.name,
                        (route.distance() ?: 0.0) / 1000.0,
                        (route.duration() ?: 0.0) / 60.0,
                        routes.size,
                    )
                    viewBinding.routeResultText.text = routeSession.resultText
                    setStatus(getString(R.string.route_ready, source.name))
                }

                override fun onRoutingError(throwable: Throwable) = deliver {
                    viewBinding.routeButton.isEnabled = true
                    clearSelectedRoute()
                    showError(getString(R.string.route_failed), throwable)
                }
            },
        )
    }

    private fun startNavigation() {
        if (routeSession.selectedRoute == null) {
            return setStatus(getString(R.string.calculate_route_before_navigation))
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }
        requestNotificationPermissionThenStart()
    }

    private fun requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchNavigation()
        }
    }

    private fun launchNavigation() {
        val route = routeSession.selectedRoute
            ?: return setStatus(getString(R.string.calculate_route_before_navigation))
        val routes = routeSession.availableRoutes.ifEmpty { listOf(route) }
        runCatching {
            // Pass the chosen route plus alternatives returned by the same request. The SDK owns
            // the navigation Activity lifecycle after startNavigation().
            val config = NavLauncherConfig.builder(route)
                .routes(routes)
                .isRoutePreviewModel(false)
                .shouldSimulateRoute(binding?.simulateNavigationSwitch?.isChecked == true)
                .showSpeedometer(true)
                .build()
            NavigationLauncher.startNavigation(this, config)
        }.onFailure {
            showError(getString(R.string.navigation_launch_failed), it)
        }
    }

    private fun clearSelectedRoute() {
        routeSession.clear()
        binding?.startNavigationButton?.apply {
            isEnabled = false
            visibility = View.GONE
        }
    }

    private fun restoreRouteUi(viewBinding: ActivityMainBinding) {
        viewBinding.routeResultText.text = routeSession.resultText
            ?: getString(R.string.route_result_hint)
        val hasRoute = routeSession.selectedRoute != null
        viewBinding.startNavigationButton.visibility = if (hasRoute) View.VISIBLE else View.GONE
        viewBinding.startNavigationButton.isEnabled = hasRoute
    }

    private fun showError(action: String, throwable: Throwable) {
        val error = NBNavigation.toNavigationError(throwable)
        setStatus(
            "$action: code=${error.code}, cause=${error.causeCode ?: "—"}, " +
                "recoverable=${error.recoverable}, message=${error.message}",
        )
    }

    private fun setStatus(message: String) = deliver { binding?.statusText?.text = message }

    private fun deliver(block: () -> Unit) {
        if (!isAlive()) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else runOnUiThread { if (isAlive()) block() }
    }

    private fun isAlive(): Boolean = !isFinishing && !isDestroyed && binding != null

    override fun onStart() {
        super.onStart()
        binding?.mapView?.onStart()
        if (offlineInitialized) startProgressObservation()
    }

    override fun onResume() {
        super.onResume()
        binding?.bottomNavigation?.selectedItemId = R.id.tab_route
        binding?.mapView?.onResume()
        refreshInstalledRegionBoundaries()
    }

    override fun onPause() {
        binding?.mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        progressSubscription?.cancel()
        progressSubscription = null
        binding?.mapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding?.mapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding?.mapView?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        installedBoundaryRefreshGeneration++
        initializationSubscription?.cancel()
        initializationSubscription = null
        progressSubscription?.cancel()
        progressSubscription = null
        binding?.mapView?.onDestroy()
        if (::overlayController.isInitialized) overlayController.detach()
        binding = null
        super.onDestroy()
    }

}
