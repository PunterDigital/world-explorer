package co.shaypunter.worldexplorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.shaypunter.worldexplorer.data.AppDatabase
import co.shaypunter.worldexplorer.data.ExploredArea
import co.shaypunter.worldexplorer.data.ExploredPoint
import co.shaypunter.worldexplorer.data.ExploredRepository
import co.shaypunter.worldexplorer.data.TrackingPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val exploredArea = ExploredArea()

    /**
     * Area of the union of all explored circles (see [ExploredArea]), as a
     * share of Earth's land surface area (oceans excluded). New points are
     * added incrementally, off the main thread.
     *
     * Note: points recorded over water (ferries, beaches, etc.) still count
     * toward the numerator; correctly masking the numerator would need an
     * offline land/sea grid, which isn't worth the storage today.
     */
    val percentExplored: StateFlow<Double> = points
        .map { pts ->
            ExploredArea.percentOfLand(exploredArea.update(pts))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0.0
        )

    /**
     * Fire-and-forget cleanup pass that flattens GPS-noise bumps in points
     * recorded since the last pass. Each point is smoothed at most once.
     */
    fun smoothTrailOnce() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = TrackingPreferences(getApplication())
            val watermark = prefs.smoothedUpToId
            prefs.smoothedUpToId = if (watermark == null) {
                // First run of the watermarked smoother. Older builds re-smoothed
                // the whole history on every launch, so treat what's there as
                // done rather than pulling its curves straighter again.
                repository.maxId()
            } else {
                repository.smoothTrail(watermark)
            }
        }
    }
}
