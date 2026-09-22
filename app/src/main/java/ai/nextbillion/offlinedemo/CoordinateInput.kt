package ai.nextbillion.offlinedemo

data class Coordinates(val latitude: Double, val longitude: Double)

/**
 * Parses a user-entered WGS84 coordinate pair.
 *
 * `toDoubleOrNull()` alone accepts non-finite values, so NaN/infinity and out-of-range geographic
 * values are rejected before a request reaches either the online or native offline router.
 */
fun parseCoordinates(latitudeText: CharSequence, longitudeText: CharSequence): Coordinates? {
    val latitude = latitudeText.toString().toDoubleOrNull()
    val longitude = longitudeText.toString().toDoubleOrNull()
    if (latitude == null || longitude == null) return null
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return Coordinates(latitude, longitude)
}
