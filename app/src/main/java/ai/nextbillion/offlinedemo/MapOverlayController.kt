package ai.nextbillion.offlinedemo

import android.content.Context
import ai.nextbillion.kits.geojson.Feature
import ai.nextbillion.kits.geojson.FeatureCollection
import ai.nextbillion.kits.geojson.LineString
import ai.nextbillion.maps.camera.CameraUpdateFactory
import ai.nextbillion.maps.core.NextbillionMap
import ai.nextbillion.maps.geometry.LatLngBounds
import ai.nextbillion.maps.style.layers.FillLayer
import ai.nextbillion.maps.style.layers.LineLayer
import ai.nextbillion.maps.style.layers.PropertyFactory.fillColor
import ai.nextbillion.maps.style.layers.PropertyFactory.fillOpacity
import ai.nextbillion.maps.style.layers.PropertyFactory.lineColor
import ai.nextbillion.maps.style.layers.PropertyFactory.lineWidth
import ai.nextbillion.maps.style.sources.GeoJsonOptions
import ai.nextbillion.maps.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber

/**
 * Owns only map overlay state; it deliberately stores density rather than the Activity context.
 * [detach] must be called from Activity.onDestroy() so a retained controller cannot keep the map
 * and its rendering surface alive.
 */
class MapOverlayController(context: Context) {
    private val density = context.resources.displayMetrics.density
    private var map: NextbillionMap? = null
    fun attach(nextbillionMap: NextbillionMap) { map = nextbillionMap }
    fun detach() { map = null }

    fun drawBoundary(geoJson: String) {
        val targetMap = map ?: return
        targetMap.getStyle { style ->
            runCatching {
                if (style.getLayer(BOUNDARY_FILL) != null) style.removeLayer(BOUNDARY_FILL)
                if (style.getLayer(BOUNDARY_LINE) != null) style.removeLayer(BOUNDARY_LINE)
                if (style.getSource(BOUNDARY_SOURCE) != null) style.removeSource(BOUNDARY_SOURCE)
                if (geoJson.isBlank()) return@runCatching
                style.addSource(GeoJsonSource(BOUNDARY_SOURCE, geoJson))
                style.addLayer(FillLayer(BOUNDARY_FILL, BOUNDARY_SOURCE).withProperties(
                    fillColor("#155EEF"), fillOpacity(0.20f)))
                style.addLayer(LineLayer(BOUNDARY_LINE, BOUNDARY_SOURCE).withProperties(
                    lineColor("#155EEF"), lineWidth(2.5f)))
            }.onFailure { Timber.w(it, "Failed to draw region boundary") }
        }
        // Camera fitting is derived from GeoJSON because a boundary may be a Polygon,
        // MultiPolygon, FeatureCollection, or GeometryCollection.
        boundaryBounds(geoJson)?.let { bounds ->
            targetMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(40)), 700)
        }
    }

    /** Draws every installed routing-region boundary without changing the customer's route camera. */
    fun drawInstalledBoundaries(geoJson: String?) {
        val targetMap = map ?: return
        targetMap.getStyle { style ->
            if (style.getLayer(INSTALLED_BOUNDARY_FILL) != null) {
                style.removeLayer(INSTALLED_BOUNDARY_FILL)
            }
            if (style.getLayer(INSTALLED_BOUNDARY_LINE) != null) {
                style.removeLayer(INSTALLED_BOUNDARY_LINE)
            }
            if (style.getSource(INSTALLED_BOUNDARY_SOURCE) != null) {
                style.removeSource(INSTALLED_BOUNDARY_SOURCE)
            }
            if (geoJson.isNullOrBlank()) return@getStyle
            runCatching {
                style.addSource(GeoJsonSource(INSTALLED_BOUNDARY_SOURCE, geoJson))
                style.addLayer(
                    FillLayer(INSTALLED_BOUNDARY_FILL, INSTALLED_BOUNDARY_SOURCE).withProperties(
                        fillColor("#007AFF"),
                        fillOpacity(0.25f),
                    ),
                )
                style.addLayer(
                    LineLayer(INSTALLED_BOUNDARY_LINE, INSTALLED_BOUNDARY_SOURCE).withProperties(
                        lineColor("#007AFF"),
                        lineWidth(2f),
                    ),
                )
            }.onFailure { Timber.w(it, "Failed to draw installed region boundaries") }
        }
    }

    fun drawRoute(encodedGeometry: String, precision: Int) {
        // Use the precision returned by DirectionsRoute. Assuming polyline5/polyline6 here can move
        // the rendered line far away from the route that the navigation engine will follow.
        val targetMap = map ?: return
        val line = LineString.fromPolyline(encodedGeometry, precision)
        val feature = FeatureCollection.fromFeature(Feature.fromGeometry(line))
        targetMap.getStyle { style ->
            runCatching {
                if (style.getLayer(ROUTE_LINE) != null) style.removeLayer(ROUTE_LINE)
                if (style.getSource(ROUTE_SOURCE) != null) style.removeSource(ROUTE_SOURCE)
                style.addSource(GeoJsonSource(ROUTE_SOURCE, feature, GeoJsonOptions().withMaxZoom(16)))
                style.addLayer(LineLayer(ROUTE_LINE, ROUTE_SOURCE).withProperties(
                    lineColor("#E31B54"), lineWidth(5f)))
            }.onFailure { Timber.w(it, "Failed to draw route overlay") }
        }
        val coordinates = line.coordinates()
        if (coordinates.size > 1) {
            val bounds = LatLngBounds.from(
                coordinates.maxOf { it.latitude() }, coordinates.maxOf { it.longitude() },
                coordinates.minOf { it.latitude() }, coordinates.minOf { it.longitude() },
            )
            targetMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(40)), 700)
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun boundaryBounds(geoJson: String): LatLngBounds? = runCatching {
        val root = JSONTokener(geoJson).nextValue() as? JSONObject ?: return@runCatching null
        val bounds = MutableBounds()
        when (root.optString("type")) {
            "FeatureCollection" -> {
                val features = root.optJSONArray("features") ?: JSONArray()
                for (index in 0 until features.length()) {
                    scanGeometry(features.optJSONObject(index)?.optJSONObject("geometry"), bounds)
                }
            }
            "Feature" -> scanGeometry(root.optJSONObject("geometry"), bounds)
            else -> scanGeometry(root, bounds)
        }
        bounds.toLatLngBounds()
    }.getOrNull()

    private fun scanGeometry(geometry: JSONObject?, bounds: MutableBounds) {
        if (geometry == null) return
        geometry.optJSONArray("coordinates")?.let { scanCoordinates(it, bounds) }
        val geometries = geometry.optJSONArray("geometries") ?: return
        for (index in 0 until geometries.length()) {
            scanGeometry(geometries.optJSONObject(index), bounds)
        }
    }

    private fun scanCoordinates(value: JSONArray, bounds: MutableBounds) {
        if (value.length() >= 2 && value.opt(0) is Number && value.opt(1) is Number) {
            bounds.include(value.optDouble(0), value.optDouble(1))
            return
        }
        for (index in 0 until value.length()) {
            (value.opt(index) as? JSONArray)?.let { scanCoordinates(it, bounds) }
        }
    }

    private class MutableBounds {
        private var minLatitude = Double.POSITIVE_INFINITY
        private var minLongitude = Double.POSITIVE_INFINITY
        private var maxLatitude = Double.NEGATIVE_INFINITY
        private var maxLongitude = Double.NEGATIVE_INFINITY

        fun include(longitude: Double, latitude: Double) {
            if (!longitude.isFinite() || !latitude.isFinite()) return
            minLatitude = minOf(minLatitude, latitude)
            maxLatitude = maxOf(maxLatitude, latitude)
            minLongitude = minOf(minLongitude, longitude)
            maxLongitude = maxOf(maxLongitude, longitude)
        }

        fun toLatLngBounds(): LatLngBounds? {
            if (!minLatitude.isFinite() || !minLongitude.isFinite() ||
                !maxLatitude.isFinite() || !maxLongitude.isFinite()
            ) return null
            return LatLngBounds.from(
                maxLatitude,
                maxLongitude,
                minLatitude,
                minLongitude,
            )
        }
    }

    private companion object {
        const val BOUNDARY_SOURCE = "offline-demo-boundary-source"
        const val BOUNDARY_FILL = "offline-demo-boundary-fill"
        const val BOUNDARY_LINE = "offline-demo-boundary-line"
        const val ROUTE_SOURCE = "offline-demo-route-source"
        const val ROUTE_LINE = "offline-demo-route-line"
        const val INSTALLED_BOUNDARY_SOURCE = "offline-demo-installed-boundary-source"
        const val INSTALLED_BOUNDARY_FILL = "offline-demo-installed-boundary-fill"
        const val INSTALLED_BOUNDARY_LINE = "offline-demo-installed-boundary-line"
    }
}
