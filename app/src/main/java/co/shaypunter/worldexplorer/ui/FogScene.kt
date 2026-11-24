package co.shaypunter.worldexplorer.ui

import co.shaypunter.worldexplorer.data.ExploredPoint

/**
 * What [FogOverlay] draws: a [FogIndex] of most points plus a small one of
 * the points recorded since it was built, so a new fix doesn't re-sort the
 * whole history (#5674).
 */
class FogScene(val base: FogIndex, val recent: FogIndex) {
    val size: Int get() = base.size + recent.size
    val maxRadiusMeters: Float get() = maxOf(base.maxRadiusMeters, recent.maxRadiusMeters)

    companion object {
        val EMPTY = FogScene(FogIndex.EMPTY, FogIndex.EMPTY)

        /** Everything in one index: for tests and one-off use. */
        fun of(points: List<ExploredPoint>) = FogScene(FogIndex.build(points), FogIndex.EMPTY)
    }
}

/**
 * Turns the point list (ordered by id, only ever appended to while the app
 * is open, see PointsFeed) into [FogScene]s. New points go into the small
 * recent index; once it holds more than [recentMax], everything is rebuilt
 * into the base index. A list that isn't an append of the last one (data
 * cleared) is rebuilt from scratch.
 *
 * Not thread-safe: feed it from one coroutine, off the main thread.
 */
class FogIndexBuilder(private val recentMax: Int = 256) {

    private var base = FogIndex.EMPTY
    private var baseCount = 0
    private val recent = ArrayList<ExploredPoint>()
    private var lastId = 0L
    private var scene = FogScene.EMPTY

    fun update(points: List<ExploredPoint>): FogScene {
        if (points.isEmpty()) return rebuild(points)
        if (points.last().id < lastId) return rebuild(points)
        var start = points.size
        while (start > 0 && points[start - 1].id > lastId) start--
        if (start == points.size) return scene
        if (start != baseCount + recent.size) return rebuild(points)
        for (i in start until points.size) recent += points[i]
        lastId = points.last().id
        if (recent.size > recentMax) return rebuild(points)
        scene = FogScene(base, FogIndex.build(recent))
        return scene
    }

    private fun rebuild(points: List<ExploredPoint>): FogScene {
        base = FogIndex.build(points)
        baseCount = points.size
        recent.clear()
        lastId = points.lastOrNull()?.id ?: 0L
        scene = FogScene(base, FogIndex.EMPTY)
        return scene
    }
}
