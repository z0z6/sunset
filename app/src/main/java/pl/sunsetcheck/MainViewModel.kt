package pl.sunsetcheck

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Error(val msg: String) : UiState
    data class Done(val analysis: Analysis) : UiState
}

class MainViewModel : ViewModel() {
    var lat by mutableDoubleStateOf(52.0)
        private set
    var lon by mutableDoubleStateOf(19.4)
        private set
    var dayOffset by mutableIntStateOf(0)
    var recenter by mutableIntStateOf(0)
        private set
    var state by mutableStateOf<UiState>(UiState.Idle)
        private set

    private var job: Job? = null

    fun pick(la: Double, lo: Double, recenterMap: Boolean = false) {
        lat = la
        lon = lo
        if (recenterMap) recenter++
        job?.cancel()
        state = UiState.Idle
    }

    fun analyze() {
        job?.cancel()
        state = UiState.Loading
        job = viewModelScope.launch {
            state = try {
                val date = LocalDate.now(Solar.ZONE).plusDays(dayOffset.toLong())
                UiState.Done(Analyzer.analyze(lat, lon, date))
            } catch (e: AnalysisException) {
                UiState.Error(e.message ?: "Błąd analizy")
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error("Błąd: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
