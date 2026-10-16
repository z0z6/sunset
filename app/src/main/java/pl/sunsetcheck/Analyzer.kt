package pl.sunsetcheck

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.*

data class CloudSample(val distKm: Double, val low: Double, val mid: Double, val high: Double)

data class ModelResult(
    val name: String,
    val probability: Double,
    val low: Double, val mid: Double, val high: Double,
    val precipMm: Double,
    val visibilityKm: Double?,
    val color: Double,
    val cross: List<CloudSample>
)

enum class Confidence(val label: String) { HIGH("wysoka"), MEDIUM("średnia"), LOW("niska") }

data class Analysis(
    val lat: Double,
    val lon: Double,
    val observerElevationM: Double?,
    val eventEpochMs: Long,
    val eventAzimuth: Double,
    val horizonDeg: Double,
    val terrainKnown: Boolean,
    val evalElevationDeg: Double,
    val models: List<ModelResult>,
    val failedModels: List<String>,
    val score: Int,
    val scoreMin: Int,
    val scoreMax: Int,
    val confidence: Confidence,
    val colorPotential: Int,
    val verdict: String,
    val reasons: List<String>,
    val avgLow: Double,
    val avgMid: Double,
    val avgHigh: Double,
    val cross: List<CloudSample>,
    val sunPath: List<SunSample>,
    val leadHours: Double,
    val event: SunEvent,
    val confidencePct: Int
)

class AnalysisException(msg: String) : Exception(msg)

/**
 * Model heurystyczny: Słońce tuż nad horyzontem jest widoczne, jeśli promień
 * (biegnący pod kątem e nad horyzontem) nie przecina chmur. Na wysokości 0–2 km promień
 * jest w odległości 0–~80 km od obserwatora, 2–6 km: ~80–190 km, 6–12 km: ~190–300 km.
 * Prognozy pobieramy więc wzdłuż azymutu słońca, a nie tylko w punkcie obserwatora.
 */
object Analyzer {
    private val CLOUD_D = listOf(0.0, 30.0, 60.0, 100.0, 150.0, 200.0, 250.0, 300.0)
    private val TERRAIN_D = listOf(0.0, 0.3, 0.6, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 15.0, 20.0, 30.0, 40.0, 50.0)

    /** Odległość (km), na której promień pod kątem e osiąga wysokość h nad gruntem (z krzywizną Ziemi). */
    fun layerDist(hKm: Double, eDeg: Double): Double {
        val r = Geo.R_KM
        val t = tan(Math.toRadians(eDeg))
        return r * (-t + sqrt(t * t + 2 * hKm / r))
    }

    private class Pt(val low: Double, val mid: Double, val high: Double, val vis: Double, val precip: Double)

    private fun nz(x: Double) = if (x.isNaN()) 0.0 else x

    private fun interp(vals: List<Double>, x: Double): Double {
        val xs = CLOUD_D
        if (x <= xs.first()) return vals.first()
        if (x >= xs.last()) return vals.last()
        val i = xs.indexOfLast { it <= x }
        val a = vals[i]
        val b = vals[i + 1]
        if (a.isNaN()) return b
        if (b.isNaN()) return a
        val f = (x - xs[i]) / (xs[i + 1] - xs[i])
        return a + (b - a) * f
    }

    private fun avgAlong(vals: List<Double>, a: Double, b: Double): Double {
        var s = 0.0
        var n = 0
        for (k in 0..4) {
            val v = interp(vals, a + (b - a) * k / 4.0)
            if (!v.isNaN()) { s += v; n++ }
        }
        return if (n == 0) Double.NaN else s / n
    }

    /** Prawdopodobieństwo zablokowania promienia przez warstwę (heurystyka – do kalibracji). */
    private fun blocked(cover: Double, pathKm: Double, opacity: Double): Double =
        opacity * (1.0 - (1.0 - cover).pow(1.0 + pathKm / 100.0))

    private fun computeModel(name: String, s: List<HourlySeries>, tSec: Long, e: Double, terrainF: Double): ModelResult? {
        val pts = s.map {
            Pt(it.at(it.low, tSec), it.at(it.mid, tSec), it.at(it.high, tSec), it.at(it.vis, tSec), it.at(it.precip, tSec))
        }
        if (pts.all { it.low.isNaN() && it.mid.isNaN() && it.high.isNaN() }) return null

        val d2 = layerDist(2.0, e)
        val d6 = layerDist(6.0, e)
        val d12 = layerDist(12.0, e)

        val low = nz(avgAlong(pts.map { it.low }, 0.0, d2)) / 100.0
        val mid = nz(avgAlong(pts.map { it.mid }, d2, d6)) / 100.0
        val high = nz(avgAlong(pts.map { it.high }, d6, d12)) / 100.0

        val pLow = 1.0 - blocked(low, d2, 1.0)
        val pMid = 1.0 - blocked(mid, d6 - d2, 0.9)
        val pHigh = 1.0 - blocked(high, d12 - d6, 0.4)

        val precip = pts.take(3).maxOf { nz(it.precip) }
        val precipF = 1.0 - 0.9 * (precip / 0.3).coerceIn(0.0, 1.0)
        val visM = pts[0].vis
        val visF = if (visM.isNaN()) 1.0 else ((visM - 800.0) / (8000.0 - 800.0)).coerceIn(0.0, 1.0)

        val clearSun = pLow * pMid * pHigh * precipF * visF * terrainF
        val x = (mid + high) / 2.0
        val color = exp(-((x - 0.45).pow(2)) / (2 * 0.2 * 0.2)) * pLow * precipF * visF * terrainF

        return ModelResult(
            name = name,
            probability = clearSun.coerceIn(0.0, 1.0),
            low = low, mid = mid, high = high,
            precipMm = precip,
            visibilityKm = if (visM.isNaN()) null else visM / 1000.0,
            color = color.coerceIn(0.0, 1.0),
            cross = pts.mapIndexed { i, p -> CloudSample(CLOUD_D[i], nz(p.low) / 100.0, nz(p.mid) / 100.0, nz(p.high) / 100.0) }
        )
    }

    suspend fun analyze(lat: Double, lon: Double, date: LocalDate, event: SunEvent = SunEvent.SUNSET): Analysis = coroutineScope {
        val sunset = Solar.event(event, date, lat, lon) ?: throw AnalysisException("Brak ${event.nounGen} słońca w tej dacie.")
        val now = System.currentTimeMillis()
        if (sunset < now) throw AnalysisException("${event.label.replaceFirstChar { it.uppercase() }} słońca w tym dniu już minął – wybierz późniejszy dzień.")
        val az = Solar.position(sunset, lat, lon).azimuthDeg

        // --- horyzont terenowy w kierunku zachodu ---
        var horizon = 0.0
        var obsElev: Double? = null
        var terrainKnown = false
        try {
            val tp = TERRAIN_D.map { Geo.destination(lat, lon, az, it) }
            val el = OpenMeteo.elevations(tp.map { it.first }, tp.map { it.second })
            obsElev = el[0]
            for (i in 1 until el.size) {
                val d = TERRAIN_D[i] * 1000.0
                val drop = 0.87 * d * d / (2 * 6371000.0) // krzywizna + refrakcja (k≈0.13)
                val dh = el[i] - (el[0] + 1.7) - drop
                horizon = max(horizon, Math.toDegrees(atan2(dh, d)))
            }
            terrainKnown = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // brak danych terenu – liczymy dla płaskiego horyzontu
        }
        val terrainF = (1.0 - horizon / 3.0).coerceIn(0.1, 1.0)
        val e = (horizon + 0.5).coerceIn(1.0, 6.0)

        // --- prognozy z kilku modeli wzdłuż azymutu słońca ---
        val cp = CLOUD_D.map { Geo.destination(lat, lon, az, it) }
        val fetched = OpenMeteo.MODELS.map { (id, name) ->
            async { name to OpenMeteo.forecast(id, cp.map { it.first }, cp.map { it.second }) }
        }.awaitAll()

        val tSec = if (event == SunEvent.SUNSET) sunset / 1000L - 600L else sunset / 1000L + 600L // Słońce ~1–2° nad horyzontem
        val results = ArrayList<ModelResult>()
        val failed = ArrayList<String>()
        for ((name, series) in fetched) {
            val r = if (series == null) null else computeModel(name, series, tSec, e, terrainF)
            if (r == null) failed.add(name) else results.add(r)
        }
        if (results.isEmpty()) throw AnalysisException("Nie udało się pobrać prognoz (sprawdź internet).")

        val probs = results.map { it.probability }
        val mean = probs.average()
        val spread = probs.max() - probs.min()
        val leadH = (sunset - now) / 3_600_000.0
        var conf = 1.0 - spread * 1.2 - ((leadH - 12.0) / 96.0).coerceIn(0.0, 0.5)
        if (results.size < 3) conf -= 0.2
        val confidence = when {
            conf > 0.75 -> Confidence.HIGH
            conf > 0.5 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val score = (mean * 100).roundToInt().coerceIn(1, 99)
        val sMin = (probs.min() * 100).roundToInt().coerceIn(1, 99)
        val sMax = (probs.max() * 100).roundToInt().coerceIn(1, 99)

        val avgLow = results.map { it.low }.average()
        val avgMid = results.map { it.mid }.average()
        val avgHigh = results.map { it.high }.average()
        val precip = results.maxOf { it.precipMm }
        val vis = results.mapNotNull { it.visibilityKm }.minOrNull()

        val verdict = when {
            score >= 80 && confidence != Confidence.LOW -> "Bardzo dobre szanse na widoczny ${event.label}"
            score >= 60 -> "Dobre szanse, ale bez gwarancji"
            score >= 35 -> "Niepewnie – chmury mogą zasłonić słońce"
            else -> "Raczej nie zobaczysz ${event.nounGen}"
        }

        fun pct(x: Double) = "${(x * 100).roundToInt()}%"
        val reasons = ArrayList<String>()
        reasons.add("Chmury niskie na drodze promieni (0–${layerDist(2.0, e).roundToInt()} km): ${pct(avgLow)}")
        reasons.add("Chmury średnie: ${pct(avgMid)}, wysokie (cienkie, słabiej blokują): ${pct(avgHigh)}")
        if (precip >= 0.1) reasons.add("Możliwe opady w kierunku zachodu (do ${"%.1f".format(precip)} mm/h)")
        if (vis != null && vis < 8.0) reasons.add("Ograniczona widzialność przy gruncie: ${"%.1f".format(vis)} km (mgła/zamglenie)")
        if (horizon >= 0.3) reasons.add("Teren/zabudowa zasłania horyzont do ok. ${"%.1f".format(horizon)}° – tarcza ${if (event == SunEvent.SUNSET) "zniknie przed" else "pojawi się dopiero po"} „prawdziwym” ${event.nounGen}")
        if (!terrainKnown) reasons.add("Brak danych o terenie – założono płaski horyzont")
        if (spread > 0.25) reasons.add("Modele się rozchodzą (${sMin}–${sMax}%) – prognoza niepewna")
        if (leadH > 48) reasons.add("Prognoza na ponad 2 doby – niska wiarygodność szczegółów")

        val cross = CLOUD_D.indices.map { i ->
            CloudSample(
                CLOUD_D[i],
                results.map { it.cross[i].low }.average(),
                results.map { it.cross[i].mid }.average(),
                results.map { it.cross[i].high }.average()
            )
        }

        Analysis(
            lat = lat, lon = lon, observerElevationM = obsElev,
            eventEpochMs = sunset, eventAzimuth = az, horizonDeg = horizon, terrainKnown = terrainKnown,
            evalElevationDeg = e, models = results, failedModels = failed,
            score = score, scoreMin = sMin, scoreMax = sMax, confidence = confidence,
            colorPotential = (results.map { it.color }.average() * 100).roundToInt(),
            verdict = verdict, reasons = reasons,
            avgLow = avgLow, avgMid = avgMid, avgHigh = avgHigh,
            cross = cross, sunPath = Solar.sunPath(date, lat, lon), leadHours = leadH,
            event = event, confidencePct = (conf * 100).roundToInt().coerceIn(5, 95)
        )
    }
}
