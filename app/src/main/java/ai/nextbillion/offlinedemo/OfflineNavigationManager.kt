package ai.nextbillion.offlinedemo

import android.os.Handler
import android.os.Looper
import ai.nextbillion.navigation.core.offline.NBNavigationTaskCallback
import ai.nextbillion.navigation.ui.NBNavigation

/**
 * Process-wide owner for offline-engine initialization.
 *
 * NBNavigation is process-scoped and must not be initialized independently by every Activity.
 * This owner serializes the first initialization attempt, shares its result with all interested
 * screens, and lets a destroyed screen unregister its callback.
 *
 * A failed attempt returns the state to [State.IDLE], so a later user retry starts a fresh SDK
 * initialization. A successful attempt remains [State.READY] for the lifetime of the process;
 * Activities deliberately do not call the SDK shutdown API during ordinary screen destruction.
 */
object OfflineNavigationManager {
    private enum class State { IDLE, INITIALIZING, READY }

    fun interface Subscription {
        /** Removes the callback without changing process-wide SDK state. */
        fun cancel()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var state = State.IDLE
    private var initializationGeneration = 0L
    private var nextListenerId = 0L
    private val listeners = linkedMapOf<Long, (Result<Unit>) -> Unit>()

    /**
     * Ensures that the SDK is initialized and optionally reports readiness on the main thread.
     *
     * The returned subscription must be cancelled from the Activity's `onDestroy()`. Keeping the
     * callback in this process singleton after an Activity is destroyed would otherwise retain its
     * view hierarchy until initialization finishes.
     */
    fun initialize(listener: ((Result<Unit>) -> Unit)? = null): Subscription {
        var startInitialization = false
        var generation = 0L
        var listenerId: Long? = null
        synchronized(lock) {
            if (listener != null) {
                val id = ++nextListenerId
                listenerId = id
                listeners[id] = listener
            }
            if (state == State.READY) {
                Unit
            } else if (state == State.IDLE) {
                // Only the thread that performs IDLE -> INITIALIZING calls the SDK.
                state = State.INITIALIZING
                generation = ++initializationGeneration
                startInitialization = true
            }
        }

        val subscription = Subscription {
            listenerId?.let { id -> synchronized(lock) { listeners.remove(id) } }
        }
        if (!startInitialization && isReady()) {
            // Preserve one callback contract even when the process was already initialized.
            listenerId?.let { deliver(it, Result.success(Unit)) }
        }
        if (!startInitialization) return subscription

        runCatching {
            NBNavigation.initialize(object : NBNavigationTaskCallback<Void> {
                override fun onSuccess(result: Void?) {
                    complete(generation, Result.success(Unit))
                }

                override fun onError(throwable: Throwable) {
                    complete(generation, Result.failure(throwable))
                }
            })
        }.onFailure { complete(generation, Result.failure(it)) }
        return subscription
    }

    private fun isReady(): Boolean = synchronized(lock) { state == State.READY }

    private fun complete(generation: Long, result: Result<Unit>) {
        val listenerIds = synchronized(lock) {
            // Ignore duplicate or stale native callbacks from an earlier failed attempt.
            if (generation != initializationGeneration || state != State.INITIALIZING) {
                return
            }
            state = if (result.isSuccess) State.READY else State.IDLE
            listeners.keys.toList()
        }
        listenerIds.forEach { deliver(it, result) }
    }

    private fun deliver(listenerId: Long, result: Result<Unit>) {
        dispatch {
            val callback = synchronized(lock) { listeners.remove(listenerId) }
            callback?.invoke(result)
        }
    }

    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
