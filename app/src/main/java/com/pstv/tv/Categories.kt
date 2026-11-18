package com.pstv.tv

/** User ordering / hiding of playlist categories (groups) per tab. Stored in prefs; applied wherever groups become rows. */
object Categories {
    private fun key(kind: Kind, what: String) = "cat_${what}_${kind.ordinal}"
    private fun split(s: String) = s.split('\n').filter { it.isNotEmpty() }

    fun order(kind: Kind): List<String> = split(Prefs.rawGet(key(kind, "order")))
    fun hidden(kind: Kind): Set<String> = split(Prefs.rawGet(key(kind, "hidden"))).toSet()

    fun save(kind: Kind, order: List<String>, hidden: Set<String>) {
        Prefs.rawPut(key(kind, "order"), order.joinToString("\n"))
        Prefs.rawPut(key(kind, "hidden"), hidden.joinToString("\n"))
    }

    fun reset(kind: Kind) = save(kind, emptyList(), emptySet())

    /** Custom order first, then everything else in playlist order; hidden ones dropped unless [includeHidden]. */
    fun apply(kind: Kind, groups: List<Group>, includeHidden: Boolean = false): List<Group> {
        val ord = order(kind); val hid = hidden(kind)
        val byName = groups.associateBy { it.name }
        val first = ord.mapNotNull { byName[it] }
        val rest = groups.filter { it.name !in ord }
        return (first + rest).filter { includeHidden || it.name !in hid }
    }
}
