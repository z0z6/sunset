package pl.sunsetcheck

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class HourlySeries(
    val times: LongArray,          // epoch seconds (UTC)
    val low: DoubleArray,
    val mid: DoubleArray,
    val high: DoubleArray,
    val vis: DoubleArray,          // metry
    val precip: DoubleArray        // mm/h
) {
    /** Liniowa interpolacja wartości godzinowej w chwili t (epoch sekundy). */
    fun at(arr: DoubleArray, t: Long): Double {
        val idx = times.indexOfLast { it <= t }
        if (idx < 0 || idx >= times.size - 1) return Double.NaN
        val a = arr[idx]
        val b = arr[idx + 1]
        if (a.isNaN() && b.isNaN()) return Double.NaN
        if (a.isNaN()) return b
        if (b.isNaN()) return a
        val f = (t - times[idx]).toDouble() / (times[idx + 1] - times[idx]).toDouble()
        return a + (b - a) * f
    }
}

object OpenMeteo {
    /** id modelu w API -> nazwa wyświetlana */
    val MODELS = listOf(
        "ecmwf_ifs025" to "ECMWF IFS",
        "icon_seamless" to "DWD ICON",
        "gfs_seamless" to "NOAA GFS",
        "meteofrance_seamless" to "Météo-France"
    )

    private fun fmt(x: Double) = String.format(Locale.US, "%.5f", x)

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "SunsetCheck/0.1")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun elevations(lats: List<Double>, lons: List<Double>): List<Double> =
        withContext(Dispatchers.IO) {
            val url = "https://api.open-meteo.com/v1/elevation?latitude=" +
                lats.joinToString(",") { fmt(it) } + "&longitude=" + lons.joinToString(",") { fmt(it) }
            val arr = JSONObject(httpGet(url)).getJSONArray("elevation")
            List(arr.length()) { arr.getDouble(it) }
        }

    /** Zwraca serie godzinowe dla każdego punktu albo null, gdy model niedostępny. */
    suspend fun forecast(model: String, lats: List<Double>, lons: List<Double>): List<HourlySeries>? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=" +
                    lats.joinToString(",") { fmt(it) } + "&longitude=" + lons.joinToString(",") { fmt(it) } +
                    "&hourly=cloud_cover_low,cloud_cover_mid,cloud_cover_high,visibility,precipitation" +
                    "&models=$model&timeformat=unixtime&forecast_days=4"
                val text = httpGet(url).trim()
                val arr = if (text.startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
                val out = ArrayList<HourlySeries>()
                for (i in 0 until arr.length()) {
                    val hourly = arr.getJSONObject(i).getJSONObject("hourly")
                    val ta = hourly.getJSONArray("time")
                    val n = ta.length()
                    val times = LongArray(n) { ta.getLong(it) }
                    fun series(name: String): DoubleArray {
                        val a = hourly.optJSONArray(name) ?: hourly.optJSONArray("${name}_$model")
                        return DoubleArray(n) { k -> if (a == null || a.isNull(k)) Double.NaN else a.getDouble(k) }
                    }
                    out.add(
                        HourlySeries(
                            times, series("cloud_cover_low"), series("cloud_cover_mid"),
                            series("cloud_cover_high"), series("visibility"), series("precipitation")
                        )
                    )
                }
                if (out.size == lats.size) out else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
}
