package ai.nextbillion.offlinedemo

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import ai.nextbillion.navigation.core.offline.NBNavigationTaskCallback
import ai.nextbillion.navigation.core.offline.OfflineDownloadNetworkPolicy
import ai.nextbillion.navigation.core.offline.OfflineRegionDownloadData
import ai.nextbillion.navigation.ui.NBNavigation

/**
 * Process-wide, persisted sequential downloader for discovery results.
 *
 * SDK callbacks never retain an Activity. A pending queue survives configuration changes and is
 * retried when a download page becomes visible after process recreation.
 *
 * `downloadRegion()` only acknowledges that Route + Map work was submitted; it does not mean that
 * either package has finished downloading. Consequently this coordinator serializes submissions,
 * while UI completion continues to come from `observeOfflineProgress()`.
 */
object OfflineBatchDownloadCoordinator {
    data class Snapshot(
        val running: Boolean = false,
        val total: Int = 0,
        val completed: Int = 0,
        val failures: Int = 0,
        val currentRegionId: Long? = null,
    )

    fun interface Subscription {
        fun cancel()
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var loaded = false
    private var remainingIds = mutableListOf<Long>()
    private var policy = OfflineDownloadNetworkPolicy.WIFI_ONLY
    private var total = 0
    private var completed = 0
    private var failures = 0
    private var submitting = false
    private var attemptGeneration = 0L
    private var nextObserverId = 0L
    private val observers = linkedMapOf<Long, (Snapshot) -> Unit>()

    fun install(application: Application) {
        synchronized(lock) {
            this.application = application
            loadLocked()
        }
    }

    fun observe(observer: (Snapshot) -> Unit): Subscription {
        val id: Long
        val value: Snapshot
        synchronized(lock) {
            loadLocked()
            id = ++nextObserverId
            observers[id] = observer
            value = snapshotLocked()
        }
        // Always provide an initial snapshot so a recreated Activity can render immediately.
        dispatchObserver(id, value)
        return Subscription { synchronized(lock) { observers.remove(id) } }
    }

    fun start(regionIds: Collection<Long>, networkPolicy: OfflineDownloadNetworkPolicy): Boolean {
        val uniqueIds = regionIds.filter { it > 0L }.distinct()
        if (uniqueIds.isEmpty()) return false
        synchronized(lock) {
            loadLocked()
            if (remainingIds.isNotEmpty() || submitting) return false
            remainingIds = uniqueIds.toMutableList()
            policy = networkPolicy
            total = uniqueIds.size
            completed = 0
            failures = 0
            // Persist before the first SDK call. If the process dies between submission and its
            // callback, the same region is safely resubmitted when a download page is reopened.
            persistLocked()
        }
        notifyObservers()
        pump()
        return true
    }

    /** Called by a visible download page after offline initialization succeeds. */
    fun resumePending() {
        synchronized(lock) { loadLocked() }
        pump()
    }

    private fun pump() {
        val regionId: Long
        val selectedPolicy: OfflineDownloadNetworkPolicy
        val generation: Long
        synchronized(lock) {
            // Exactly one request may be awaiting its orchestration callback at a time. This keeps
            // a large discovery result from flooding both Route and Map download queues.
            if (submitting || remainingIds.isEmpty()) return
            submitting = true
            regionId = remainingIds.first()
            selectedPolicy = policy
            generation = ++attemptGeneration
        }
        notifyObservers()
        runCatching {
            NBNavigation.setDownloadNetworkPolicy(selectedPolicy)
            NBNavigation.downloadRegion(
                regionId,
                object : NBNavigationTaskCallback<OfflineRegionDownloadData> {
                    override fun onSuccess(result: OfflineRegionDownloadData?) {
                        finishAttempt(generation, regionId, failed = false)
                    }

                    override fun onError(throwable: Throwable) {
                        finishAttempt(generation, regionId, failed = true)
                    }
                },
            )
        }.onFailure { finishAttempt(generation, regionId, failed = true) }
    }

    private fun finishAttempt(generation: Long, regionId: Long, failed: Boolean) {
        synchronized(lock) {
            // Some SDK/network layers can race a terminal callback with retry/recreation. The
            // generation and head check make completion idempotent.
            if (!submitting || generation != attemptGeneration || remainingIds.firstOrNull() != regionId) {
                return
            }
            remainingIds.removeAt(0)
            completed++
            if (failed) failures++
            submitting = false
            if (remainingIds.isEmpty()) clearPersistedLocked() else persistLocked()
        }
        notifyObservers()
        pump()
    }

    private fun notifyObservers() {
        val observerIds: List<Long>
        val value: Snapshot
        synchronized(lock) {
            value = snapshotLocked()
            observerIds = observers.keys.toList()
        }
        observerIds.forEach { dispatchObserver(it, value) }
    }

    private fun dispatchObserver(observerId: Long, value: Snapshot) {
        val deliver: () -> Unit = {
            val observer = synchronized(lock) { observers[observerId] }
            if (observer != null) observer(value)
        }
        // Look up the observer at delivery time, rather than copying the callback itself. A screen
        // cancelled in onStop() is therefore not retained by a queued main-thread message.
        if (Looper.myLooper() == Looper.getMainLooper()) deliver() else mainHandler.post(deliver)
    }

    private fun snapshotLocked() = Snapshot(
        running = submitting || remainingIds.isNotEmpty(),
        total = total,
        completed = completed,
        failures = failures,
        currentRegionId = remainingIds.firstOrNull(),
    )

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val preferences = application?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?: return
        remainingIds = preferences.getString(KEY_REMAINING, null)
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            ?.filter { it > 0L }
            ?.toMutableList()
            ?: mutableListOf()
        policy = runCatching {
            OfflineDownloadNetworkPolicy.valueOf(
                preferences.getString(KEY_POLICY, OfflineDownloadNetworkPolicy.WIFI_ONLY.name).orEmpty(),
            )
        }.getOrDefault(OfflineDownloadNetworkPolicy.WIFI_ONLY)
        completed = preferences.getInt(KEY_COMPLETED, 0).coerceAtLeast(0)
        failures = preferences.getInt(KEY_FAILURES, 0).coerceIn(0, completed)
        total = preferences.getInt(KEY_TOTAL, remainingIds.size + completed)
            .coerceAtLeast(remainingIds.size + completed)
    }

    private fun persistLocked() {
        // commit=true is intentional: losing this very small queue during an immediate process
        // kill is worse than the negligible synchronous preferences write.
        application?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)?.edit(commit = true) {
            putString(KEY_REMAINING, remainingIds.joinToString(","))
            putString(KEY_POLICY, policy.name)
            putInt(KEY_TOTAL, total)
            putInt(KEY_COMPLETED, completed)
            putInt(KEY_FAILURES, failures)
        }
    }

    private fun clearPersistedLocked() {
        application?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)?.edit(commit = true) {
            remove(KEY_REMAINING)
            remove(KEY_POLICY)
            remove(KEY_TOTAL)
            remove(KEY_COMPLETED)
            remove(KEY_FAILURES)
        }
    }

    private const val PREFERENCES_NAME = "offline_batch_downloads"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_POLICY = "policy"
    private const val KEY_TOTAL = "total"
    private const val KEY_COMPLETED = "completed"
    private const val KEY_FAILURES = "failures"
}
