package ai.nextbillion.offlinedemo

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.format.Formatter
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import ai.nextbillion.maps.core.NextbillionMap
import ai.nextbillion.navigation.core.offline.NBNavigationTaskCallback
import ai.nextbillion.navigation.core.offline.OfflineDownloadNetworkPolicy
import ai.nextbillion.navigation.core.offline.OfflineRegionCancelData
import ai.nextbillion.navigation.core.offline.OfflineRegionCatalogSyncResult
import ai.nextbillion.navigation.core.offline.OfflineRegionDeleteData
import ai.nextbillion.navigation.core.offline.OfflineRegionDownloadData
import ai.nextbillion.navigation.core.offline.OfflineRegionListResult
import ai.nextbillion.navigation.core.offline.OfflineRegionListRow
import ai.nextbillion.navigation.core.offline.OfflineRegionPauseData
import ai.nextbillion.navigation.core.offline.OfflineRegionProgress
import ai.nextbillion.navigation.core.offline.OfflineRegionResumeData
import ai.nextbillion.navigation.core.offline.OfflineSubtaskProgress
import ai.nextbillion.navigation.core.offline.OfflineTaskState
import ai.nextbillion.navigation.core.offline.region.OfflineRegionBoundary
import ai.nextbillion.navigation.core.offline.region.RegionListSyncMode
import ai.nextbillion.navigation.ui.NBNavigation
import ai.nextbillion.offlinedemo.databinding.ActivityRegionDownloadBinding

/**
 * Customer-facing reference for the full offline-region lifecycle.
 *
 * The SDK returns a flat catalog. This screen presents it as collapsible Admin-L1 groups while
 * retaining each SDK [OfflineRegionListRow] unchanged for download operations. Route-network and
 * map-tile packages are treated as independent subtasks throughout the UI.
 */
class RegionDownloadActivity : AppCompatActivity() {
    private var binding: ActivityRegionDownloadBinding? = null
    private var nextbillionMap: NextbillionMap? = null
    private var progressSubscription: NBNavigation.OfflineProgressSubscription? = null
    private var initializationSubscription: OfflineNavigationManager.Subscription? = null
    private lateinit var regionAdapter: RegionAdapter
    private lateinit var overlayController: MapOverlayController
    private var offlineInitialized = false
    private var selectedRegion: OfflineRegionListRow? = null
    private var latestProgress: Map<Long, OfflineRegionProgress> = emptyMap()
    private var catalogRows: List<OfflineRegionListRow> = emptyList()
    private var catalogReadToken = 0L
    private var showingDetail = false
    private var pendingDetailRegionId: Long? = null
    private var selectedCountry = DEFAULT_COUNTRY
    private var selectedPolicy = OfflineDownloadNetworkPolicy.WIFI_ONLY
    private var downloadPageSavedState: Bundle? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var restoredExpandedStateKeys: ArrayList<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadPageSavedState = savedInstanceState
        restoreUiState(savedInstanceState)
        val viewBinding = ActivityRegionDownloadBinding.inflate(layoutInflater)
        binding = viewBinding
        setContentView(viewBinding.root)
        applySystemBarInsets(viewBinding.root)
        DemoTabs.bind(this, viewBinding.bottomNavigation, R.id.tab_regions)

        overlayController = MapOverlayController(this)
        regionAdapter = RegionAdapter(::showRegionDetail, ::handlePrimaryAction)
        restoredExpandedStateKeys?.let(regionAdapter::restoreExpandedStateKeys)
        viewBinding.regionList.layoutManager = LinearLayoutManager(this)
        viewBinding.regionList.adapter = regionAdapter
        configureClicks(viewBinding)
        renderCountryButton()
        renderNetworkPolicyButton()
        showRegionList()
        setCatalogControlsEnabled(false)
        setRegionActionsEnabled(false)

        viewBinding.downloadMapView.onCreate(savedInstanceState)
        viewBinding.downloadMapView.getMapAsync { map ->
            if (!isAlive()) return@getMapAsync
            nextbillionMap = map
            overlayController.attach(map)
            if (showingDetail) selectedRegion?.regionId?.let(::showBoundary)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackNavigation()
        })

        initializeOfflineFeature()
    }

    private fun restoreUiState(savedInstanceState: Bundle?) {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        selectedCountry = savedInstanceState?.getString(STATE_COUNTRY)
            ?: preferences.getString(PREFERENCE_COUNTRY, DEFAULT_COUNTRY)
            ?: DEFAULT_COUNTRY
        val policyName = savedInstanceState?.getString(STATE_POLICY)
            ?: preferences.getString(
                PREFERENCE_POLICY,
                OfflineDownloadNetworkPolicy.WIFI_ONLY.name,
            )
        selectedPolicy = runCatching {
            OfflineDownloadNetworkPolicy.valueOf(policyName.orEmpty())
        }.getOrDefault(OfflineDownloadNetworkPolicy.WIFI_ONLY)
        if (selectedPolicy == OfflineDownloadNetworkPolicy.UNMETERED) {
            selectedPolicy = OfflineDownloadNetworkPolicy.WIFI_ONLY
        }
        if (savedInstanceState?.getBoolean(STATE_SHOWING_DETAIL) == true) {
            pendingDetailRegionId = savedInstanceState.getLong(STATE_REGION_ID)
                .takeIf { it > 0L }
        }
        restoredExpandedStateKeys = savedInstanceState?.getStringArrayList(STATE_EXPANDED_GROUPS)
    }

    private fun configureClicks(viewBinding: ActivityRegionDownloadBinding) {
        viewBinding.downloadBackButton.setOnClickListener { handleBackNavigation() }
        viewBinding.discoveryModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || checkedId == R.id.discoveryModeRegionsButton) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.discoveryModeRadiusButton) {
                OfflineDiscoveryActivity.MODE_RADIUS
            } else {
                OfflineDiscoveryActivity.MODE_ROUTE
            }
            startActivity(
                Intent(this, OfflineDiscoveryActivity::class.java)
                    .putExtra(OfflineDiscoveryActivity.EXTRA_MODE, mode),
            )
        }
        viewBinding.countryButton.setOnClickListener(::showCountryMenu)
        viewBinding.networkPolicyButton.setOnClickListener(::showNetworkPolicyMenu)
        viewBinding.searchEdit.doAfterTextChanged {
            pendingSearch?.let(searchHandler::removeCallbacks)
            pendingSearch = Runnable(::renderFilteredRows).also {
                searchHandler.postDelayed(it, SEARCH_DEBOUNCE_MS)
            }
        }
        viewBinding.readCacheButton.setOnClickListener { readCachedRegions() }
        viewBinding.autoSyncButton.setOnClickListener { syncCatalog(RegionListSyncMode.AUTO) }
        viewBinding.forceSyncButton.setOnClickListener { syncCatalog(RegionListSyncMode.FORCE) }
        viewBinding.downloadButton.setOnClickListener {
            selectedRegion?.let(::requestDownload) ?: setStatus("Select a region first.")
        }
        viewBinding.pauseButton.setOnClickListener { selectedRegionId()?.let(::pauseRegion) }
        viewBinding.resumeButton.setOnClickListener {
            selectedRegion?.let(::requestResume) ?: setStatus("Select a region first.")
        }
        viewBinding.cancelButton.setOnClickListener { selectedRegionId()?.let(::cancelRegion) }
        viewBinding.deleteButton.setOnClickListener { confirmDeleteSelectedRegion() }
        viewBinding.boundaryButton.setOnClickListener { selectedRegionId()?.let(::showBoundary) }
    }

    private fun initializeOfflineFeature() {
        setStatus(getString(R.string.offline_initializing))
        initializationSubscription?.cancel()
        initializationSubscription = OfflineNavigationManager.initialize { result ->
            if (!isAlive()) return@initialize
            result.onSuccess {
                offlineInitialized = true
                setCatalogControlsEnabled(true)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    startProgressObservation()
                }
                // Paint persisted data first. AUTO sync then refreshes the atomic SDK caches only
                // when its TTL/policy requires network work, avoiding an empty loading screen.
                setStatus(getString(R.string.catalog_reading_cache))
                readCachedRegions { syncCatalog(RegionListSyncMode.AUTO) }
            }.onFailure {
                offlineInitialized = false
                showError(getString(R.string.offline_initialization_failed), it)
            }
        }
    }

    private fun startProgressObservation() {
        if (progressSubscription != null) return
        progressSubscription = NBNavigation.observeOfflineProgress { snapshots ->
            deliver {
                // Progress snapshots, rather than the download submission callback, are the source
                // of truth for pause/resume buttons and final offline readiness.
                latestProgress = snapshots.associateBy { it.regionId }
                regionAdapter.submitProgress(snapshots)
                selectedRegion?.let(::renderSelectedRegion)
            }
        }
    }

    private fun readCachedRegions(afterRead: (() -> Unit)? = null) {
        if (!requireInitialized()) return
        val token = ++catalogReadToken
        setStatus("Reading cached region catalog for $selectedCountry…")
        // fetchRegionLists() is cache-only. It is safe for first paint and for airplane-mode use;
        // syncRegions() is the API that may contact the catalog service.
        NBNavigation.fetchRegionLists(
            selectedCountry,
            taskCallback<OfflineRegionListResult>({ result ->
                if (token != catalogReadToken) return@taskCallback
                catalogRows = result?.regionRows.orEmpty()
                    .sortedWith(
                        compareBy<OfflineRegionListRow>(
                            { it.adminL1.orEmpty() },
                            { it.adminL2.orEmpty() },
                            { it.adminL3.orEmpty() },
                            { it.displayName },
                        ),
                    )
                renderFilteredRows()
                selectedRegion = selectedRegion?.regionId?.let { selectedId ->
                    catalogRows.firstOrNull { it.regionId == selectedId }
                }
                val requestedRegionId = pendingDetailRegionId
                if (requestedRegionId != null) {
                    pendingDetailRegionId = null
                    catalogRows.firstOrNull { it.regionId == requestedRegionId }
                        ?.let(::showRegionDetail)
                } else {
                    selectedRegion?.let(::renderSelectedRegion) ?: clearSelection()
                }
                val partial = listOfNotNull(
                    result?.routeError?.let { "route: $it" },
                    result?.mapError?.let { "map: $it" },
                ).joinToString("; ")
                setStatus(
                    if (partial.isBlank()) {
                        "Loaded ${catalogRows.size} cached regions for $selectedCountry."
                    } else {
                        "Loaded ${catalogRows.size} regions with partial catalog errors: $partial"
                    },
                )
                afterRead?.invoke()
            }, onError = {
                if (token != catalogReadToken) return@taskCallback
                showError("Cached catalog read failed", it)
                afterRead?.invoke()
            }),
        )
    }

    private fun renderFilteredRows() {
        val query = binding?.searchEdit?.text?.toString()?.trim().orEmpty()
        val filtered = if (query.isBlank()) {
            catalogRows
        } else {
            catalogRows.filter { row ->
                listOfNotNull(
                    row.displayName,
                    row.country,
                    row.adminL1,
                    row.adminL2,
                    row.adminL3,
                    row.regionId.toString(),
                ).any { it.contains(query, ignoreCase = true) }
            }
        }
        regionAdapter.submitRows(filtered, expandAll = query.isNotBlank())
        selectedRegion?.let { selected ->
            if (filtered.any { it.regionId == selected.regionId }) {
                regionAdapter.select(selected.regionId)
            }
        }
        binding?.emptyRegionsText?.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding?.regionList?.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun syncCatalog(mode: RegionListSyncMode) {
        if (!requireInitialized()) return
        setCatalogControlsEnabled(false)
        setStatus("${mode.name} catalog sync in progress…")
        // AUTO honors the SDK TTL. FORCE is reserved for the user's explicit Refresh action.
        // Route and Map results can be partial, so both statuses are surfaced independently.
        NBNavigation.syncRegions(
            mode,
            taskCallback<OfflineRegionCatalogSyncResult>({ result ->
                setCatalogControlsEnabled(true)
                setStatus(
                    "${mode.name} sync complete. " +
                        "Route=${result?.route?.listSyncStatus}, " +
                        "map=${result?.map?.listSyncStatus}. Reading cache…",
                )
                readCachedRegions()
            }, onError = {
                setCatalogControlsEnabled(true)
                showError("${mode.name} sync failed; last successful cache is retained", it)
                readCachedRegions()
            }),
        )
    }

    private fun showCountryMenu(anchor: View) {
        PopupMenu(this, anchor, Gravity.END).apply {
            menu.add(Menu.NONE, MENU_COUNTRY_US, 0, R.string.country_united_states)
                .isCheckable = true
            menu.add(Menu.NONE, MENU_COUNTRY_INDIA, 1, R.string.country_india)
                .isCheckable = true
            menu.setGroupCheckable(Menu.NONE, true, true)
            menu.findItem(
                if (selectedCountry == COUNTRY_INDIA) MENU_COUNTRY_INDIA else MENU_COUNTRY_US,
            )?.isChecked = true
            setOnMenuItemClickListener { item ->
                val newCountry = when (item.itemId) {
                    MENU_COUNTRY_US -> DEFAULT_COUNTRY
                    MENU_COUNTRY_INDIA -> COUNTRY_INDIA
                    else -> return@setOnMenuItemClickListener false
                }
                if (newCountry != selectedCountry) {
                    selectedCountry = newCountry
                    persistUiPreferences()
                    renderCountryButton()
                    binding?.searchEdit?.text?.clear()
                    catalogRows = emptyList()
                    regionAdapter.clearExpandedStateKeys()
                    clearSelection()
                    showRegionList()
                    readCachedRegions { syncCatalog(RegionListSyncMode.AUTO) }
                }
                true
            }
            show()
        }
    }

    private fun showNetworkPolicyMenu(anchor: View) {
        PopupMenu(this, anchor, Gravity.END).apply {
            menu.add(Menu.NONE, MENU_WIFI_ONLY, 0, R.string.network_wifi_only).isCheckable = true
            menu.add(Menu.NONE, MENU_ANY_NETWORK, 1, R.string.network_any).isCheckable = true
            menu.setGroupCheckable(Menu.NONE, true, true)
            menu.findItem(
                if (selectedPolicy == OfflineDownloadNetworkPolicy.ANY) {
                    MENU_ANY_NETWORK
                } else {
                    MENU_WIFI_ONLY
                },
            )?.isChecked = true
            setOnMenuItemClickListener { item ->
                selectedPolicy = when (item.itemId) {
                    MENU_WIFI_ONLY -> OfflineDownloadNetworkPolicy.WIFI_ONLY
                    MENU_ANY_NETWORK -> OfflineDownloadNetworkPolicy.ANY
                    else -> return@setOnMenuItemClickListener false
                }
                persistUiPreferences()
                renderNetworkPolicyButton()
                true
            }
            show()
        }
    }

    private fun renderCountryButton() {
        binding?.countryButton?.setText(
            if (selectedCountry == COUNTRY_INDIA) R.string.country_india_short
            else R.string.country_us_short,
        )
    }

    private fun renderNetworkPolicyButton() {
        binding?.networkPolicyButton?.setText(
            if (selectedPolicy == OfflineDownloadNetworkPolicy.ANY) R.string.network_any
            else R.string.network_wifi_only,
        )
    }

    private fun persistUiPreferences() {
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit {
                putString(PREFERENCE_COUNTRY, selectedCountry)
                putString(PREFERENCE_POLICY, selectedPolicy.name)
            }
    }

    private fun handlePrimaryAction(row: OfflineRegionListRow) {
        selectedRegion = row
        regionAdapter.select(row.regionId)
        // One row-level action maps the SDK state machine to the least surprising operation.
        when (latestProgress[row.regionId]?.overallState) {
            OfflineTaskState.QUEUED,
            OfflineTaskState.RUNNING,
            -> pauseRegion(row.regionId)
            OfflineTaskState.PAUSED,
            OfflineTaskState.PARTIAL,
            OfflineTaskState.FAILED,
            -> requestResume(row)
            OfflineTaskState.COMPLETED -> showRegionDetail(row)
            else -> requestDownload(row)
        }
    }

    private fun showRegionDetail(row: OfflineRegionListRow) {
        selectedRegion = row
        regionAdapter.select(row.regionId)
        showingDetail = true
        binding?.regionListPanel?.visibility = View.GONE
        binding?.regionDetailPanel?.visibility = View.VISIBLE
        binding?.downloadTitleText?.text = row.displayName
        binding?.countryButton?.visibility = View.GONE
        binding?.bottomNavigation?.visibility = View.GONE
        binding?.downloadMapView?.visibility = View.VISIBLE
        renderSelectedRegion(row)
        if (nextbillionMap != null) showBoundary(row.regionId)
        else setStatus("Opening region details…")
    }

    private fun showRegionList() {
        showingDetail = false
        binding?.regionDetailPanel?.visibility = View.GONE
        binding?.regionListPanel?.visibility = View.VISIBLE
        binding?.downloadTitleText?.setText(R.string.region_downloads_title)
        binding?.countryButton?.visibility = View.VISIBLE
        binding?.bottomNavigation?.visibility = View.VISIBLE
        binding?.downloadMapView?.visibility = View.GONE
    }

    private fun handleBackNavigation() {
        if (showingDetail) showRegionList() else finish()
    }

    private fun clearSelection() {
        selectedRegion = null
        binding?.selectedRegionText?.setText(R.string.selected_region)
        setRegionActionsEnabled(false)
        if (showingDetail) showRegionList()
    }

    private fun renderSelectedRegion(row: OfflineRegionListRow) {
        val route = row.routeRegion
        val map = row.mapRegion
        val progress = latestProgress[row.regionId]
        val total = DownloadSizeMath.sumKnown(row.routeSizeBytes, row.mapSizeBytes)
        binding?.selectedRegionText?.text = buildString {
            append("${row.displayName} (#${row.regionId})\n")
            append("Route: version=${route?.version ?: "—"}, size=${formatBytes(row.routeSizeBytes)}, ")
            append("state=${progress?.routeNetwork?.state ?: route?.downloadStatus ?: "NOT_STARTED"}")
            if (route?.updateAvailable == true) append(", update available")
            append("\nMap: version=${map?.version ?: "—"}, size=${formatBytes(row.mapSizeBytes)}, ")
            append("state=${progress?.mapTile?.state ?: "NOT_STARTED"}\n")
            append("Estimated package: ${formatBytes(total)}")
            progress?.let {
                append(" · Overall ${it.overallState} ${it.overallPercent.coerceIn(0, 100)}%")
            }
        }
        updateRegionActionButtons(row, progress)
    }

    private fun updateRegionActionButtons(
        row: OfflineRegionListRow,
        progress: OfflineRegionProgress?,
    ) {
        val hasCompletePackage = row.hasRoute && row.hasMap
        val state = progress?.overallState
        binding?.downloadButton?.isEnabled = hasCompletePackage &&
            state !in setOf(
                OfflineTaskState.QUEUED,
                OfflineTaskState.RUNNING,
                OfflineTaskState.COMPLETED,
            )
        binding?.pauseButton?.isEnabled =
            state == OfflineTaskState.QUEUED || state == OfflineTaskState.RUNNING
        binding?.resumeButton?.isEnabled = state in setOf(
            OfflineTaskState.PAUSED,
            OfflineTaskState.PARTIAL,
            OfflineTaskState.FAILED,
        )
        binding?.cancelButton?.isEnabled = state in setOf(
            OfflineTaskState.QUEUED,
            OfflineTaskState.RUNNING,
            OfflineTaskState.PAUSED,
            OfflineTaskState.PARTIAL,
            OfflineTaskState.FAILED,
        )
        binding?.deleteButton?.isEnabled =
            state != null && state != OfflineTaskState.NOT_STARTED
        binding?.boundaryButton?.isEnabled = true
    }

    private fun requestDownload(row: OfflineRegionListRow) {
        if (!row.hasRoute || !row.hasMap) {
            return setStatus("This region does not contain both route and map metadata.")
        }
        when (latestProgress[row.regionId]?.overallState) {
            OfflineTaskState.PAUSED,
            OfflineTaskState.PARTIAL,
            OfflineTaskState.FAILED,
            -> return requestResume(row)
            OfflineTaskState.COMPLETED -> return showRegionDetail(row)
            else -> Unit
        }

        val remaining = remainingDownloadBytes(row, latestProgress[row.regionId])
        runDownloadPreflight(remaining, R.string.download) { startRegionDownload(row.regionId) }
    }

    private fun requestResume(row: OfflineRegionListRow) {
        val remaining = remainingDownloadBytes(row, latestProgress[row.regionId])
        runDownloadPreflight(remaining, R.string.resume) { resumeRegion(row.regionId) }
    }

    private fun runDownloadPreflight(
        remainingBytes: Long?,
        positiveButtonText: Int,
        action: () -> Unit,
    ) {
        // Unknown metadata must never be converted to zero: that would bypass the storage check and
        // tell the user a potentially large package is free. Require explicit acknowledgement.
        if (remainingBytes == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.unknown_download_size_title)
                .setMessage(R.string.unknown_download_size_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.continue_action) { _, _ ->
                    confirmNetworkPolicy(null, positiveButtonText, action)
                }
                .show()
            return
        }
        if (!hasEnoughStorage(remainingBytes)) return
        confirmNetworkPolicy(remainingBytes, positiveButtonText, action)
    }

    private fun confirmNetworkPolicy(
        remainingBytes: Long?,
        positiveButtonText: Int,
        action: () -> Unit,
    ) {
        // ANY may use a metered cellular network. The explicit confirmation is intentionally done
        // immediately before submission/resume, not only when the preference is selected.
        if (selectedNetworkPolicy() == OfflineDownloadNetworkPolicy.ANY) {
            AlertDialog.Builder(this)
                .setTitle(R.string.mobile_data_title)
                .setMessage(
                    getString(
                        R.string.mobile_data_message,
                        remainingBytes?.let(::formatBytes) ?: getString(R.string.unknown_value),
                    ),
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(positiveButtonText) { _, _ -> action() }
                .show()
        } else {
            action()
        }
    }

    private fun hasEnoughStorage(remainingBytes: Long): Boolean {
        val required = DownloadSizeMath.requiredFreeBytes(remainingBytes)
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

    private fun startRegionDownload(regionId: Long) {
        if (!requireInitialized()) return
        NBNavigation.setDownloadNetworkPolicy(selectedNetworkPolicy())
        setStatus("Submitting route-network and map-tile download for region $regionId…")
        // A single SDK call orchestrates both packages. Its success callback means "accepted";
        // observeOfflineProgress() must reach COMPLETED for Route and Map before offline use.
        NBNavigation.downloadRegion(
            regionId,
            taskCallback<OfflineRegionDownloadData>({ result ->
                setStatus(
                    "Download accepted for $regionId. " +
                        "Route=${result?.routeRegion?.downloadStatus}, " +
                        "map=${result?.mapTileRegion?.action}. Progress is authoritative.",
                )
                readCachedRegions()
            }, onError = {
                showError("Region download failed", it)
                readCachedRegions()
            }),
        )
    }

    private fun pauseRegion(regionId: Long) {
        NBNavigation.pauseRegion(
            regionId,
            taskCallback<OfflineRegionPauseData>({ result ->
                setStatus(
                    "Pause $regionId: route errorCode=${result?.routeRegion?.errorCode}, " +
                        "map=${result?.mapTileRegion?.action}",
                )
                readCachedRegions()
            }, onError = {
                showError("Pause failed", it)
                readCachedRegions()
            }),
        )
    }

    private fun resumeRegion(regionId: Long) {
        NBNavigation.setDownloadNetworkPolicy(selectedNetworkPolicy())
        NBNavigation.resumeRegion(
            regionId,
            taskCallback<OfflineRegionResumeData>({ result ->
                setStatus(
                    "Resume $regionId: route=${result?.routeRegion?.downloadStatus}, " +
                        "map=${result?.mapTileRegion?.action}",
                )
                readCachedRegions()
            }, onError = {
                showError("Resume failed", it)
                readCachedRegions()
            }),
        )
    }

    private fun cancelRegion(regionId: Long) {
        NBNavigation.cancelRegion(
            regionId,
            taskCallback<OfflineRegionCancelData>({ result ->
                setStatus(
                    "Cancel $regionId: route errorCode=${result?.routeRegion?.errorCode}, " +
                        "map=${result?.mapTileRegion?.action}; persisted data is retained.",
                )
                readCachedRegions()
            }, onError = {
                showError("Cancel failed", it)
                readCachedRegions()
            }),
        )
    }

    private fun confirmDeleteSelectedRegion() {
        val regionId = selectedRegionId() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_region_title)
            .setMessage(getString(R.string.delete_region_message, regionId.toString()))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteRegion(regionId) }
            .show()
    }

    private fun deleteRegion(regionId: Long) {
        NBNavigation.deleteRegionAllData(
            regionId,
            taskCallback<OfflineRegionDeleteData>({ result ->
                setStatus(
                    "Delete $regionId: route errorCode=${result?.routeRegion?.errorCode}, " +
                        "map=${result?.mapTileRegion?.action}",
                )
                clearSelection()
                readCachedRegions()
            }, onError = {
                showError("Delete failed", it)
                readCachedRegions()
            }),
        )
    }

    private fun showBoundary(regionId: Long) {
        if (nextbillionMap == null) {
            setStatus("The map is still loading…")
            return
        }
        setStatus("Loading boundary for region $regionId…")
        // getRegionBoundary() provides drawable GeoJSON. focusMapTilePreviewRegion() is a separate
        // convenience and can return false when local map data is not installed yet.
        NBNavigation.getRegionBoundary(
            regionId,
            taskCallback<OfflineRegionBoundary>({ boundary ->
                if (boundary == null || boundary.isEmpty) {
                    return@taskCallback setStatus("No boundary is available for region $regionId.")
                }
                overlayController.drawBoundary(boundary.geoJson)
                val focused = nextbillionMap?.let {
                    NBNavigation.focusMapTilePreviewRegion(it, regionId)
                } ?: false
                setStatus(
                    if (focused) "Boundary rendered and camera focused."
                    else "Boundary rendered; install map data to enable local preview focus.",
                )
            }),
        )
    }

    private fun remainingDownloadBytes(
        row: OfflineRegionListRow,
        progress: OfflineRegionProgress?,
    ): Long? = DownloadSizeMath.sumKnown(
        remainingSubtaskBytes(progress?.routeNetwork, row.routeSizeBytes),
        remainingSubtaskBytes(progress?.mapTile, row.mapSizeBytes),
    )

    private fun remainingSubtaskBytes(progress: OfflineSubtaskProgress?, fallback: Long?): Long? =
        if (progress?.state == OfflineTaskState.COMPLETED) 0L
        else DownloadSizeMath.remaining(progress?.downloaded, progress?.total, fallback)

    private fun selectedNetworkPolicy(): OfflineDownloadNetworkPolicy = selectedPolicy

    private fun setCatalogControlsEnabled(enabled: Boolean) {
        binding?.readCacheButton?.isEnabled = enabled
        binding?.autoSyncButton?.isEnabled = enabled
        binding?.forceSyncButton?.isEnabled = enabled
        binding?.countryButton?.isEnabled = enabled
    }

    private fun setRegionActionsEnabled(enabled: Boolean) {
        listOf(
            binding?.downloadButton,
            binding?.pauseButton,
            binding?.resumeButton,
            binding?.cancelButton,
            binding?.deleteButton,
            binding?.boundaryButton,
        ).forEach { it?.isEnabled = enabled }
    }

    private fun selectedRegionId(): Long? = selectedRegion?.regionId.also {
        if (it == null) setStatus("Select a region first.")
    }

    private fun requireInitialized(): Boolean = offlineInitialized.also {
        if (!it) setStatus(getString(R.string.offline_not_ready))
    }

    private fun formatBytes(bytes: Long?): String =
        bytes?.takeIf { it >= 0L }?.let { Formatter.formatFileSize(this, it) } ?: "—"

    private fun showError(action: String, throwable: Throwable) {
        val error = NBNavigation.toNavigationError(throwable)
        setStatus(
            "$action: code=${error.code}, cause=${error.causeCode ?: "—"}, " +
                "recoverable=${error.recoverable}, message=${error.message}",
        )
    }

    private fun setStatus(message: String) = deliver { binding?.downloadStatusText?.text = message }

    private fun <T> taskCallback(
        onSuccess: (T?) -> Unit,
        onError: ((Throwable) -> Unit)? = null,
    ) = object : NBNavigationTaskCallback<T> {
        override fun onSuccess(result: T?) = deliver { onSuccess(result) }
        override fun onError(throwable: Throwable) = deliver {
            onError?.invoke(throwable) ?: showError("SDK operation failed", throwable)
        }
    }

    private fun deliver(block: () -> Unit) {
        if (!isAlive()) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else runOnUiThread { if (isAlive()) block() }
    }

    private fun isAlive(): Boolean = !isFinishing && !isDestroyed && binding != null

    override fun onStart() {
        super.onStart()
        binding?.downloadMapView?.onStart()
        // The SDK has one download-page owner. Binding in onCreate/onDestroy is incorrect when a
        // discovery Activity is placed above this one: the child replaces the owner, then clears it
        // when it closes. Rebinding in onStart restores the visible page as owner.
        NBNavigation.beginDownloadPage(this, downloadPageSavedState)
        downloadPageSavedState = null
        if (offlineInitialized) startProgressObservation()
    }

    override fun onResume() {
        super.onResume()
        binding?.bottomNavigation?.selectedItemId = R.id.tab_regions
        binding?.discoveryModeGroup?.check(R.id.discoveryModeRegionsButton)
        binding?.downloadMapView?.onResume()
    }

    override fun onPause() {
        binding?.downloadMapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Stop UI-only observation while this root Activity is kept in the task back stack. The
        // process-scoped SDK and any active downloads continue running.
        progressSubscription?.cancel()
        progressSubscription = null
        NBNavigation.endDownloadPage(this)
        binding?.downloadMapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding?.downloadMapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding?.downloadMapView?.onSaveInstanceState(outState)
        outState.putString(STATE_COUNTRY, selectedCountry)
        outState.putString(STATE_POLICY, selectedPolicy.name)
        outState.putBoolean(STATE_SHOWING_DETAIL, showingDetail || pendingDetailRegionId != null)
        (selectedRegion?.regionId ?: pendingDetailRegionId)?.let {
            outState.putLong(STATE_REGION_ID, it)
        }
        outState.putStringArrayList(STATE_EXPANDED_GROUPS, regionAdapter.expandedStateKeys())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        pendingSearch?.let(searchHandler::removeCallbacks)
        pendingSearch = null
        initializationSubscription?.cancel()
        initializationSubscription = null
        progressSubscription?.cancel()
        progressSubscription = null
        if (binding != null) {
            binding?.downloadMapView?.onDestroy()
        }
        overlayController.detach()
        nextbillionMap = null
        binding = null
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_COUNTRY = "United States"
        const val COUNTRY_INDIA = "India"
        const val PREFERENCES_NAME = "offline_region_downloads"
        const val PREFERENCE_COUNTRY = "country"
        const val PREFERENCE_POLICY = "network_policy"
        const val STATE_COUNTRY = "state_country"
        const val STATE_POLICY = "state_policy"
        const val STATE_SHOWING_DETAIL = "state_showing_detail"
        const val STATE_REGION_ID = "state_region_id"
        const val STATE_EXPANDED_GROUPS = "state_expanded_groups"
        const val SEARCH_DEBOUNCE_MS = 250L
        const val MENU_COUNTRY_US = 1
        const val MENU_COUNTRY_INDIA = 2
        const val MENU_WIFI_ONLY = 3
        const val MENU_ANY_NETWORK = 4

    }
}
