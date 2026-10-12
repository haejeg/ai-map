package com.opticalcenter.spatialnav.spatial

import com.opticalcenter.spatialnav.navigation.BoundingBox
import kotlin.math.cos
import kotlin.math.sqrt

/** Where a position falls on a polyline. */
data class RouteProjection(
    /** Closest point on the polyline. */
    val point: GeoPoint,
    /** Index of the segment start (segment is points[i]..points[i+1]). */
    val segmentIndex: Int,
    /** Distance travelled along the polyline from its start to [point], metres. */
    val alongM: Double,
    /** Perpendicular distance from the position to the polyline, metres. */
    val offRouteM: Double,
)

/**
 * Polyline helpers for navigation. Distances along the line use haversine;
 * snapping uses a local equirectangular projection, which is accurate to well
 * under a metre at the scale of a single road segment.
 */
class RoutePolyline(val points: List<GeoPoint>) {
    init {
        require(points.size >= 2) { "A route needs at least two points" }
    }

    /** cumulative[i] = distance from points[0] to points[i]. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            c[i] = c[i - 1] + SpatialEngine.haversineDistance(a.lat, a.lon, b.lat, b.lon)
        }
    }

    val lengthM: Double get() = cumulative.last()

    /**
     * Snap [p] to the polyline. [fromSegment] limits the search to segments at
     * or after a known position so a route that doubles back doesn't snap
     * backwards; pass 0 to search everything.
     */
    fun project(p: GeoPoint, fromSegment: Int = 0): RouteProjection {
        var best: RouteProjection? = null
        val start = fromSegment.coerceIn(0, points.size - 2)
        for (i in start until points.size - 1) {
            val candidate = projectOntoSegment(p, i)
            if (best == null || candidate.offRouteM < best.offRouteM) best = candidate
        }
        return best!!
    }

    private fun projectOntoSegment(p: GeoPoint, i: Int): RouteProjection {
        val a = points[i]
        val b = points[i + 1]
        // Local metric frame centred on a.
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(a.lat))
        val bx = (b.lon - a.lon) * mPerDegLon
        val by = (b.lat - a.lat) * mPerDegLat
        val px = (p.lon - a.lon) * mPerDegLon
        val py = (p.lat - a.lat) * mPerDegLat
        val segLen2 = bx * bx + by * by
        val t = if (segLen2 == 0.0) 0.0 else ((px * bx + py * by) / segLen2).coerceIn(0.0, 1.0)
        val cx = bx * t
        val cy = by * t
        val snapped = GeoPoint(a.lat + cy / mPerDegLat, a.lon + cx / mPerDegLon)
        val segLen = cumulative[i + 1] - cumulative[i]
        return RouteProjection(
            point = snapped,
            segmentIndex = i,
            alongM = cumulative[i] + segLen * t,
            offRouteM = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy)),
        )
    }

    /** Point at [alongM] metres from the start (clamped to the ends). */
    fun pointAt(alongM: Double): GeoPoint {
        if (alongM <= 0) return points.first()
        if (alongM >= lengthM) return points.last()
        val i = cumulative.indexOfFirst { it >= alongM }.coerceAtLeast(1)
        val segLen = cumulative[i] - cumulative[i - 1]
        val t = if (segLen == 0.0) 0.0 else (alongM - cumulative[i - 1]) / segLen
        val a = points[i - 1]
        val b = points[i]
        return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** Distance from [p] to the nearest point of the polyline, metres. */
    fun distanceTo(p: GeoPoint): Double = project(p).offRouteM

    /** Points from [fromAlongM] to the end, starting with the interpolated point. */
    fun remainingPoints(fromAlongM: Double): List<GeoPoint> {
        val firstAfter = cumulative.indexOfFirst { it > fromAlongM }
        if (firstAfter == -1) return listOf(points.last())
        return listOf(pointAt(fromAlongM)) + points.subList(firstAfter, points.size)
    }
}

object GeoBounds {
    fun of(points: List<GeoPoint>, paddingM: Double = 0.0): BoundingBox {
        val padLat = paddingM / 111_320.0
        val midLat = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
        val padLon = paddingM / (111_320.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.01))
        return BoundingBox(
            minLat = points.minOf { it.lat } - padLat,
            minLon = points.minOf { it.lon } - padLon,
            maxLat = points.maxOf { it.lat } + padLat,
            maxLon = points.maxOf { it.lon } + padLon,
        )
    }

    fun around(center: GeoPoint, radiusM: Double) = of(listOf(center), radiusM)

    fun areaKm2(box: BoundingBox): Double {
        val heightKm = (box.maxLat - box.minLat) * 111.32
        val widthKm = (box.maxLon - box.minLon) * 111.32 * cos(Math.toRadians((box.minLat + box.maxLat) / 2))
        return heightKm * widthKm
    }

    /** Shrink [box] around its centre until it is at most [maxKm2]. */
    fun clampArea(box: BoundingBox, maxKm2: Double): BoundingBox {
        val area = areaKm2(box)
        if (area <= maxKm2) return box
        val scale = sqrt(maxKm2 / area) * 0.99
        val cLat = (box.minLat + box.maxLat) / 2
        val cLon = (box.minLon + box.maxLon) / 2
        val hLat = (box.maxLat - box.minLat) / 2 * scale
        val hLon = (box.maxLon - box.minLon) / 2 * scale
        return BoundingBox(cLat - hLat, cLon - hLon, cLat + hLat, cLon + hLon)
    }
}
