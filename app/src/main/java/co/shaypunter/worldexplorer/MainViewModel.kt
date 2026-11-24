package co.shaypunter.worldexplorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.shaypunter.worldexplorer.data.AppDatabase
import co.shaypunter.worldexplorer.data.ExploredArea
import co.shaypunter.worldexplorer.data.ExploredPoint
import co.shaypunter.worldexplorer.data.ExploredRepository
import co.shaypunter.worldexplorer.data.TrackingPreferences
import co.shaypunter.worldexplorer.ui.FogIndexBuilder
import co.shaypunter.worldexplorer.ui.FogScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ExploredRepository(AppDatabase.get(application))

    /**
     * One-shot cleanup that flattens GPS-noise bumps in points recorded since
     * the last pass. Each point is smoothed at most once. It's the only thing
     * that changes existing rows, so it runs before the point list is first
     * read: after that the list only gets new rows appended (#5676).
     */
    private val smoothing = viewModelScope.launch(Dispatchers.IO) {
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

    private val feed = repository.pointsFeed()

    val points: StateFlow<List<ExploredPoint>> = flow {
        smoothing.join()
        emitAll(feed.flow())
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val fogIndex = FogIndexBuilder()

    /** The points laid out for the fog overlay, built off the main thread (#5674). */
    val fogScene: StateFlow<FogScene> = points
        .map { pts -> fogIndex.update(pts) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FogScene.EMPTY
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
}
