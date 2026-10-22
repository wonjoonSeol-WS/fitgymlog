package io.fitgymlog.app.transfer

/**
 * Matches exercise names from other apps to our exercise ids. Spacing, case and punctuation are
 * ignored, so "Pull-up (weighted)" matches "Pull Up (Weighted)" and "풀업 (중량)" matches "풀 업 (중량)".
 */
class ExerciseMatcher(namesById: Map<String, Collection<String>>) {
    private val idByName: Map<String, String> = buildMap {
        namesById.forEach { (id, names) ->
            names.map(::normalize).filter { it.isNotEmpty() }.forEach { putIfAbsent(it, id) }
        }
    }

    fun match(name: String): String? = idByName[normalize(name)]

    companion object {
        private val separators = Regex("""[^\p{L}\p{N}]+""")

        fun normalize(name: String): String = name.lowercase().replace(separators, "")
    }
}
