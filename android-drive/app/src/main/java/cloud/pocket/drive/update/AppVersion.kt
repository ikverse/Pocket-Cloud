package cloud.pocket.drive.update

/**
 * The three numbers a build names itself with.
 *
 * Compared as numbers and never as text: text puts 1.0.9 above 1.0.10, the version released
 * immediately after it, and an app that gets this backwards either offers an update forever or
 * never offers one at all.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Reads a version out of whatever names one, or null when nothing here names one.
         *
         * Every spelling this project uses has to arrive as the same number: a release tag ends in
         * `v0.2.0`, the build calls itself `0.2.0`, and a sideloaded debug build calls itself
         * `0.2.0-debug`. Whatever follows the digits says which build, never which build is newer,
         * so it is read and dropped rather than compared.
         */
        fun parse(text: String): AppVersion? {
            val digits = text.trim().trimStart('v', 'V').takeWhile { it.isDigit() || it == '.' }
            val parts = digits.split('.')
                .filter(String::isNotBlank)
                .map { it.toIntOrNull() ?: return null }
            if (parts.isEmpty()) return null
            return AppVersion(parts[0], parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
        }
    }
}
