package com.pstv.tv

/** User ordering / hiding of playlist categories (groups) per tab. Stored in prefs; applied wherever groups become rows. */
/** Sort choices for the items inside a category. */
object Sorts {
    val all = listOf("new" to "Newest added first", "playlist" to "Playlist order", "az" to "A to Z", "year" to "Year, newest first")
    fun label(k: String) = all.firstOrNull { it.first == k }?.second ?: all[0].second
    fun next(k: String) = all[(all.indexOfFirst { it.first == k } + 1).mod(all.size)].first
}

object Categories {
    private val yearRe = Regex("(19|20)\\d{2}")
    private fun year(n: String) = yearRe.find(n)?.value?.toIntOrNull()

    /** Default order: groups containing the priority words first (earlier word = higher), then groups with a year newest first, then the rest as in the playlist. */
    fun smartSort(groups: List<Group>): List<Group> {
        if (!Prefs.catSmart) return groups
        val words = Prefs.catPriority.lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        fun prio(n: String): Int { val l = n.lowercase(); val i = words.indexOfFirst { l.contains(it) }; return if (i < 0) words.size else i }
        return groups.withIndex().sortedWith(compareBy({ prio(it.value.name) }, { if (year(it.value.name) == null) 1 else 0 }, { -(year(it.value.name) ?: 0) }, { it.index })).map { it.value }
    }

    private fun key(kind: Kind, what: String) = "cat_${what}_${kind.ordinal}"
    private fun split(s: String) = s.split('\n').filter { it.isNotEmpty() }

    fun order(kind: Kind): List<String> = split(Prefs.rawGet(key(kind, "order")))
    fun hidden(kind: Kind): Set<String> = split(Prefs.rawGet(key(kind, "hidden"))).toSet()

    fun save(kind: Kind, order: List<String>, hidden: Set<String>) {
        Prefs.rawPut(key(kind, "order"), order.joinToString("\n"))
        Prefs.rawPut(key(kind, "hidden"), hidden.joinToString("\n"))
    }

    fun reset(kind: Kind) = save(kind, emptyList(), emptySet())

    /** Forget every manual order (hidden categories are kept) so the priority words / smart order apply again. */
    fun clearOrders() { for (k in Kind.values()) save(k, emptyList(), hidden(k)) }

    /** Custom order first, then everything else in playlist order; hidden ones dropped unless [includeHidden]. */
    fun apply(kind: Kind, groups: List<Group>, includeHidden: Boolean = false): List<Group> {
        val ord = order(kind); val hid = hidden(kind)
        val byName = groups.associateBy { it.name }
        val first = ord.mapNotNull { byName[it] }
        val rest = smartSort(groups.filter { it.name !in ord })
        return (first + rest).filter { includeHidden || it.name !in hid }
    }
}
