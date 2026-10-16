package pl.sunsetcheck

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val HM = DateTimeFormatter.ofPattern("HH:mm").withZone(Solar.ZONE)
fun fmtTime(ms: Long): String = HM.format(Instant.ofEpochMilli(ms))
private val Dim = Color(0xFFB8C0E0)

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SunYellow)
            content()
        }
    }
}

@Composable
fun Results(a: Analysis) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hero(a)

        SectionCard("Dlaczego nie ma „100%”?") {
            Text(
                "Prognoza chmur to zawsze prawdopodobieństwo. Wynik to średnia z ${a.models.size} modeli " +
                    "(rozrzut: ${a.scoreMin}–${a.scoreMax}), policzona dla linii widzenia w stronę słońca. " +
                    "Nie widzi on pojedynczych chmur, drzew ani budynków. Przed wyjściem sprawdź zdjęcia satelitarne/radar.",
                color = Dim, fontSize = 13.sp
            )
            a.reasons.forEach { Text("• $it", fontSize = 13.sp) }
        }

        SectionCard("Prognozy modeli") {
            a.models.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m.name, modifier = Modifier.width(105.dp), fontSize = 13.sp)
                    LinearProgressIndicator(
                        progress = { m.probability.toFloat() },
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = barColor(m.probability)
                    )
                    Text("${(m.probability * 100).roundToInt()}%", modifier = Modifier.width(40.dp), fontSize = 13.sp)
                }
            }
            if (a.failedModels.isNotEmpty()) {
                Text("Niedostępne: ${a.failedModels.joinToString()}", color = Dim, fontSize = 12.sp)
            }
        }

        SectionCard("Zachmurzenie na linii widzenia") {
            LayerBar("Niskie (0–2 km)", a.avgLow)
            LayerBar("Średnie (2–6 km)", a.avgMid)
            LayerBar("Wysokie (6–12 km)", a.avgHigh)
            Text("Potencjał barw nieba: ${a.colorPotential}/100", color = Dim, fontSize = 13.sp)
        }

        SectionCard("Kopuła nieba 3D") {
            Text("Przeciągnij, aby obracać. Suwak przesuwa Słońce w ciągu dnia.", color = Dim, fontSize = 12.sp)
            SkyDome(a)
        }

        SectionCard("Przekrój wzdłuż promienia słońca") {
            Text("Krzywizna Ziemi i warstwy chmur (skala przesadzona w pionie).", color = Dim, fontSize = 12.sp)
            CrossSection(a)
        }

        SectionCard("Szczegóły") {
            Text("Zachód: ${fmtTime(a.sunsetEpochMs)}, azymut ${a.sunsetAzimuth.roundToInt()}°", fontSize = 13.sp)
            Text(
                "Horyzont terenowy: ${"%.1f".format(a.horizonDeg)}°" +
                    (a.observerElevationM?.let { ", wysokość punktu: ${it.roundToInt()} m n.p.m." } ?: ""),
                fontSize = 13.sp
            )
            Text("Czas do zachodu: ${a.leadHours.roundToInt()} h", fontSize = 13.sp)
            Text(
                "Dane: Open-Meteo (ECMWF, ICON, GFS, Météo-France), mapa © OpenStreetMap. " +
                    "Teren: model 90 m – bez budynków i drzew.",
                color = Dim, fontSize = 11.sp
            )
        }
    }
}

private fun barColor(p: Double): Color = when {
    p >= 0.7 -> Color(0xFF7BD88F)
    p >= 0.4 -> Color(0xFFFFB300)
    else -> Color(0xFFFF6B4A)
}

@Composable
private fun LayerBar(label: String, v: Double) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(125.dp), fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { v.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Color(0xFF9FB4FF)
        )
        Text("${(v * 100).roundToInt()}%", modifier = Modifier.width(40.dp), fontSize = 13.sp)
    }
}

@Composable
private fun Hero(a: Analysis) {
    Box(Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(24.dp))) {
        SunsetScene(a, Modifier.fillMaxSize())
        val shadow = Shadow(Color.Black, blurRadius = 10f)
        Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text("${a.score}", style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Black, color = Color.White, shadow = shadow))
            Text("/100 szans na widoczny zachód", style = TextStyle(fontSize = 13.sp, color = Color.White, shadow = shadow))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(a.verdict, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, shadow = shadow))
            Text(
                "Zachód ${fmtTime(a.sunsetEpochMs)} • azymut ${a.sunsetAzimuth.roundToInt()}° • pewność prognozy: ${a.confidence.label}",
                style = TextStyle(fontSize = 12.sp, color = Color.White, shadow = shadow)
            )
        }
    }
}
