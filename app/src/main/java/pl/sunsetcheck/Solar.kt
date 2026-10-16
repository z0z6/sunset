package pl.sunsetcheck

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*

data class SunPos(val azimuthDeg: Double, val elevationDeg: Double)
data class SunSample(val epochMs: Long, val azimuth: Double, val elevation: Double)

/** Pozycja Słońca (algorytm Astronomical Almanac, dokładność ~0.01–0.05°). */
object Solar {
    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm360(x: Double): Double {
        val m = x % 360.0
        return if (m < 0) m + 360.0 else m
    }

    fun position(epochMs: Long, latDeg: Double, lonDeg: Double): SunPos {
        val n = epochMs / 86400000.0 + 2440587.5 - 2451545.0
        val meanLon = norm360(280.460 + 0.9856474 * n)
        val g = rad(norm360(357.528 + 0.9856003 * n))
        val lambda = rad(meanLon + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = rad(23.439 - 0.0000004 * n)
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val dec = asin(sin(eps) * sin(lambda))
        val gmst = norm360(280.46061837 + 360.98564736629 * n)
        val ha = rad(gmst + lonDeg) - ra
        val lat = rad(latDeg)
        val el = asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha))
        val azFromSouth = atan2(sin(ha), cos(ha) * sin(lat) - tan(dec) * cos(lat))
        return SunPos(norm360(deg(azFromSouth) + 180.0), deg(el))
    }

    /** Moment zachodu (górny brzeg tarczy + refrakcja ≈ środek tarczy na -0.833°). */
    fun sunset(date: LocalDate, lat: Double, lon: Double): Long? {
        val start = date.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli()
        val end = start + 12 * 3600_000L
        var t = start
        var prev = position(t, lat, lon).elevationDeg
        while (t < end) {
            val nt = t + 60_000L
            val e = position(nt, lat, lon).elevationDeg
            if (prev > -0.833 && e <= -0.833) return nt
            prev = e
            t = nt
        }
        return null
    }

    fun sunPath(date: LocalDate, lat: Double, lon: Double): List<SunSample> {
        val start = date.atStartOfDay(ZONE).toInstant().toEpochMilli()
        val out = ArrayList<SunSample>()
        var t = start
        while (t <= start + 24 * 3600_000L) {
            val p = position(t, lat, lon)
            if (p.elevationDeg >= -3.0) out.add(SunSample(t, p.azimuthDeg, p.elevationDeg))
            t += 600_000L
        }
        return out
    }
}

object Geo {
    const val R_KM = 6371.0

    /** Punkt oddalony o distKm w kierunku bearingDeg (azymut od północy). */
    fun destination(latDeg: Double, lonDeg: Double, bearingDeg: Double, distKm: Double): Pair<Double, Double> {
        val d = distKm / R_KM
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(latDeg)
        val l1 = Math.toRadians(lonDeg)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540.0) % 360.0 - 180.0)
    }
}
