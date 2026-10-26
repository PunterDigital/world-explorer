package co.shaypunter.worldexplorer.update

/**
 * A release version such as `0.4.0`, `v1.2` or `1.0.0-beta.2`, compared the
 * semver way: numeric parts first (missing parts count as 0, so `1.2` equals
 * `1.2.0`), and a pre-release sorts before the release it leads up to.
 * Build metadata (`+...`) is ignored.
 */
class Version private constructor(
    private val parts: List<Int>,
    private val preRelease: List<String>
) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (i in 0 until size) {
            val diff = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        // 1.0.0-beta < 1.0.0
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return other.preRelease.size.coerceAtMost(1) - preRelease.size.coerceAtMost(1)
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val diff = comparePreReleasePart(preRelease[i], other.preRelease[i])
            if (diff != 0) return diff
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun equals(other: Any?) = other is Version && compareTo(other) == 0

    override fun hashCode() = parts.dropLastWhile { it == 0 }.hashCode() * 31 + preRelease.hashCode()

    override fun toString() = parts.joinToString(".") +
        if (preRelease.isEmpty()) "" else preRelease.joinToString(".", prefix = "-")

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d+(?:\.\d+){0,3})(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Parses a tag or version name; null when it isn't a version. */
        fun parse(text: String): Version? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val parts = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            val pre = match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.isEmpty() }) return null
            return Version(parts, pre)
        }

        private fun comparePreReleasePart(a: String, b: String): Int {
            val na = a.toIntOrNull()
            val nb = b.toIntOrNull()
            return when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1 // numeric identifiers sort before alphanumeric ones
                nb != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

/** True when [candidate] is a valid version newer than [current]. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val new = Version.parse(candidate) ?: return false
    val installed = Version.parse(current) ?: return false
    return new > installed
}
