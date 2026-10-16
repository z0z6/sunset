package pl.sunsetcheck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = packageName
        val base = File(cacheDir, "osmdroid").apply { mkdirs() }
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
        setContent { SunsetTheme { App() } }
    }
}

val Night = Color(0xFF0B1026)
val Night2 = Color(0xFF1A1F4A)
val SunYellow = Color(0xFFFFB300)
val Coral = Color(0xFFFF6B4A)

@Composable
fun SunsetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SunYellow, onPrimary = Color(0xFF1B1200), secondary = Coral,
            background = Night, surface = Color(0xFF151A3D), onSurface = Color.White,
            surfaceVariant = Color(0xFF1F2552)
        ),
        content = content
    )
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    var gpsMsg by remember { mutableStateOf<String?>(null) }

    val doGps = {
        gpsMsg = "Szukam pozycji GPS…"
        LocationHelper.request(
            ctx,
            onResult = { la, lo -> gpsMsg = null; vm.pick(la, lo, recenterMap = true) },
            onFail = { gpsMsg = "Nie udało się ustalić pozycji (włącz lokalizację)." }
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) doGps() else gpsMsg = "Brak zgody na lokalizację – wskaż punkt na mapie."
    }

    val analysis = (vm.state as? UiState.Done)?.analysis

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night, Night2)))
            .systemBarsPadding()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("SunsetCheck", fontSize = 26.sp, fontWeight = FontWeight.Black, color = SunYellow)
            Text("Czy zobaczę zachód lub wschód słońca? Dotknij mapy, aby wybrać punkt.", fontSize = 13.sp, color = Color(0xFFB8C0E0))
        }

        MapPicker(
            lat = vm.lat, lon = vm.lon, azimuth = analysis?.eventAzimuth, recenter = vm.recenter,
            onPick = { la, lo -> vm.pick(la, lo) },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(230.dp)
                .clip(RoundedCornerShape(20.dp))
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = vm.event == SunEvent.SUNSET,
                onClick = { vm.event = SunEvent.SUNSET; vm.pick(vm.lat, vm.lon) },
                label = { Text("🌇 Zachód") }
            )
            FilterChip(
                selected = vm.event == SunEvent.SUNRISE,
                onClick = { vm.event = SunEvent.SUNRISE; vm.pick(vm.lat, vm.lon) },
                label = { Text("🌅 Wschód") }
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Dziś", "Jutro", "Pojutrze").forEachIndexed { i, label ->
                FilterChip(
                    selected = vm.dayOffset == i,
                    onClick = { vm.dayOffset = i; vm.pick(vm.lat, vm.lon) },
                    label = { Text(label) }
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = {
                val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (ok) doGps() else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("📍 GPS") }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            gpsMsg?.let { Text(it, color = Color(0xFFB8C0E0), fontSize = 13.sp) }
            Text(
                "Punkt: %.4f, %.4f".format(vm.lat, vm.lon),
                color = Color(0xFFB8C0E0), fontSize = 12.sp
            )
            Button(
                onClick = { vm.analyze() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = vm.state !is UiState.Loading
            ) { Text("Analizuj ${vm.event.label} słońca", fontWeight = FontWeight.Bold) }

            when (val s = vm.state) {
                UiState.Idle -> {}
                UiState.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UiState.Error -> Text(s.msg, color = Coral)
                is UiState.Done -> Results(s.analysis)
            }
        }
    }
}
