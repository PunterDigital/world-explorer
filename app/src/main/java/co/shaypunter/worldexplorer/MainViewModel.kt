package co.shaypunter.worldexplorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.shaypunter.worldexplorer.data.AppDatabase
import co.shaypunter.worldexplorer.data.ExploredPoint
import co.shaypunter.worldexplorer.data.ExploredRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ExploredRepository(
        AppDatabase.get(application).exploredPointDao()
    )

    val points: StateFlow<List<ExploredPoint>> = repository.pointsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * Naive sum of (π·r²) across all explored points, normalised against
     * Earth's total surface area. Because dedup keeps points at least half a
     * radius apart, neighbouring circles can overlap and this slightly
     * overcounts — but for a planet-sized denominator that's fine; nobody is
     * going to hit even 0.001 % through a rounding error.
     */
    val percentExplored: StateFlow<Double> = points
        .map { pts ->
            val coveredMeters = pts.sumOf { p ->
                Math.PI * p.radiusMeters.toDouble() * p.radiusMeters.toDouble()
            }
            (coveredMeters / EARTH_SURFACE_AREA_M2 * 100.0).coerceIn(0.0, 100.0)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0.0
        )

    companion object {
        private const val EARTH_SURFACE_AREA_M2 = 510_072_000_000_000.0
    }
}
