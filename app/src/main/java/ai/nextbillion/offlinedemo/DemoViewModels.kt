package ai.nextbillion.offlinedemo

import androidx.lifecycle.ViewModel
import ai.nextbillion.kits.directions.models.DirectionsRoute
import ai.nextbillion.navigation.core.offline.OfflineRegionListRow

/**
 * Holds the last route response across Activity recreation.
 *
 * The SDK route objects contain no Activity/View references, so retaining them in a ViewModel is
 * safe and avoids an unnecessary network/offline-engine request after rotation. Map objects and
 * view binding must remain Activity-owned and are intentionally not stored here.
 */
class RouteSessionViewModel : ViewModel() {
    var selectedRoute: DirectionsRoute? = null
    var availableRoutes: List<DirectionsRoute> = emptyList()
    var geometry: String? = null
    var precision: Int = 6
    var resultText: String? = null

    fun clear() {
        selectedRoute = null
        availableRoutes = emptyList()
        geometry = null
        resultText = null
    }
}

/**
 * Configuration-change state for radius/route discovery.
 *
 * Region rows and route geometry are response data only. Running SDK tasks and subscriptions stay
 * with the Activity so they can be cancelled deterministically when the screen is destroyed.
 */
class DiscoverySessionViewModel : ViewModel() {
    var rows: List<OfflineRegionListRow> = emptyList()
    var selectedRegionIds: Set<Long> = emptySet()
    var routeGeometry: String? = null
    var routePrecision: Int = 6
}
