package co.shaypunter.worldexplorer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The explored points for the UI, kept up to date without re-reading the
 * table (#5676): the first collection reads every row once; after that each
 * change to the table only fetches the rows after the last id seen and
 * appends them. A later collection (the screen coming back) carries on from
 * the list it already has.
 *
 * Points are only ever appended while the app is open: trail smoothing, the
 * one in-place update, runs before the first read (see MainViewModel). If
 * rows go away (the data was cleared), the list is read again in full.
 *
 * Collect from one coroutine at a time.
 */
class PointsFeed(private val dao: ExploredPointDao) {

    private var points: List<ExploredPoint>? = null

    fun flow(): Flow<List<ExploredPoint>> = flow {
        var current = points ?: dao.getAll().also { points = it }
        emit(current)
        dao.observeMaxId().collect { max ->
            val lastId = current.lastOrNull()?.id ?: 0L
            val newest = max ?: 0L
            val next = when {
                newest == lastId -> return@collect
                newest > lastId -> {
                    val added = dao.getAfter(lastId)
                    if (added.isEmpty()) return@collect
                    ArrayList<ExploredPoint>(current.size + added.size).apply {
                        addAll(current)
                        addAll(added)
                    }
                }
                else -> dao.getAll()
            }
            current = next
            points = next
            emit(next)
        }
    }
}
