package com.opticalcenter.spatialnav.spatial

data class Poi(
    val id: String,
    val name: String,
    val category: String,
    val location: GeoPoint,
)

/** A POI annotated with its bearing/direction/distance from the user. */
data class LocatedPoi(val poi: Poi, val analysis: SpatialAnalysis)

/**
 * Mock POIs defined by a fixed absolute bearing/distance from an anchor point.
 * Anchoring them to the user's first fix keeps the demo meaningful anywhere
 * while staying fully deterministic.
 */
object MockPois {
    private data class Template(
        val id: String,
        val name: String,
        val category: String,
        val bearing: Double,
        val distance: Double,
    )

    private val TEMPLATES = listOf(
        Template("cafe", "Blue Door Coffee", "cafe", 10.0, 120.0),
        Template("pharmacy", "Corner Pharmacy", "pharmacy", 40.0, 260.0),
        Template("park", "Linden Pocket Park", "park", 75.0, 180.0),
        Template("bus", "Market Street Bus Stop", "transit stop", 100.0, 90.0),
        Template("library", "Public Library", "library", 150.0, 340.0),
        Template("grocery", "Fresh Grocer", "grocery", 200.0, 210.0),
        Template("museum", "City History Museum", "museum", 250.0, 420.0),
        Template("atm", "ATM Kiosk", "ATM", 290.0, 60.0),
        Template("hospital", "General Hospital", "hospital", 330.0, 480.0),
    )

    fun around(anchor: GeoPoint): List<Poi> = TEMPLATES.map {
        Poi(it.id, it.name, it.category, SpatialEngine.destinationPoint(anchor.lat, anchor.lon, it.bearing, it.distance))
    }
}
