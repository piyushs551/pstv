package com.pstv.tv

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Prog(val start: Long, val stop: Long, val title: String)

/**
 * SQLite store. Items are keyed by stream URL so favorites / recents survive a playlist refresh.
 * Search uses an FTS4 index over the normalised title; the XMLTV guide lives in its own table.
 */
object Db {
    private lateinit var helper: H
    @Volatile var version = 0   // bumped when items change; UI listens via Repo.listeners

    fun init(c: Context) { helper = H(c.applicationContext) }
    private val rd get() = helper.readableDatabase
    private val wr get() = helper.writableDatabase

    private class H(c: Context) : SQLiteOpenHelper(c, "pstv.db", null, 3) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(false); db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE items(id INTEGER PRIMARY KEY, kind INTEGER, grp TEXT, name TEXT, norm TEXT, logo TEXT, url TEXT, year INTEGER, overview TEXT, backdrop TEXT, poster TEXT, tvg TEXT, rating REAL, genres TEXT, sort INTEGER)""")
            db.execSQL("CREATE INDEX i_kind_grp ON items(kind, grp, sort)")
            db.execSQL("CREATE INDEX i_norm ON items(norm)")
            db.execSQL("CREATE INDEX i_tvg ON items(tvg)")
            db.execSQL("CREATE UNIQUE INDEX i_url ON items(url)")
            db.execSQL("CREATE VIRTUAL TABLE items_fts USING fts4(content='items', norm)")
            db.execSQL("CREATE TABLE favs(url TEXT PRIMARY KEY, ts INTEGER)")
            db.execSQL("CREATE TABLE recents(url TEXT PRIMARY KEY, pos INTEGER, dur INTEGER, ts INTEGER, parent TEXT, label TEXT)")
            db.execSQL("CREATE TABLE livehist(url TEXT PRIMARY KEY, ts INTEGER)")
            db.execSQL("CREATE TABLE seen(url TEXT PRIMARY KEY, ts INTEGER)")
            // TMDB metadata survives playlist refreshes, keyed by normalised title + kind.
            db.execSQL("CREATE TABLE meta(norm TEXT, kind INTEGER, year INTEGER, overview TEXT, backdrop TEXT, poster TEXT, rating REAL, genres TEXT, PRIMARY KEY(norm, kind))")
            db.execSQL("CREATE TABLE epg(ch TEXT, start INTEGER, stop INTEGER, title TEXT)")
            db.execSQL("CREATE INDEX i_epg ON epg(ch, start)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            if (o >= 2) {   // 2 -> 3: remember the series (parent) and episode label for Continue Watching
                db.execSQL("ALTER TABLE recents ADD COLUMN parent TEXT"); db.execSQL("ALTER TABLE recents ADD COLUMN label TEXT"); return
            }
            for (t in listOf("items", "items_fts", "favs", "recents", "livehist", "seen", "meta", "epg")) db.execSQL("DROP TABLE IF EXISTS $t")
            onCreate(db)
        }
    }

    private const val COLS = "id,kind,grp,name,logo,url,year,overview,backdrop,poster,tvg,rating,genres"
    private val I_COLS = COLS.split(',').joinToString(",") { "i.$it" }
    private val kinds = Kind.values()

    private fun Cursor.item(extra: Boolean = false) = Item(
        id = getLong(0), kind = kinds[getInt(1)], group = getString(2) ?: "", name = getString(3) ?: "",
        logo = getString(4), url = getString(5) ?: "", year = if (isNull(6)) null else getInt(6),
        overview = getString(7), backdrop = getString(8), poster = getString(9),
        tvg = getString(10), rating = if (isNull(11)) null else getFloat(11), genres = getString(12),
        posMs = if (extra) getLong(13) else 0, durMs = if (extra) getLong(14) else 0, note = if (extra && columnCount > 15) getString(15) else null,
    )

    private fun query(sql: String, vararg args: String): List<Item> =
        rd.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.item()) } }

    // ---- import ---------------------------------------------------------------------------------

    fun count(): Int = rd.rawQuery("SELECT COUNT(*) FROM items", null).use { it.moveToFirst(); it.getInt(0) }

    /** Replace the whole catalogue in one transaction; readers keep seeing the old data until commit. */
    fun replaceAll(feed: ((Item) -> Unit) -> Unit) {
        val db = wr
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM items")
            val st = db.compileStatement("INSERT OR IGNORE INTO items(kind,grp,name,norm,logo,url,year,tvg,sort) VALUES(?,?,?,?,?,?,?,?,?)")
            var n = 0L
            feed { it ->
                st.clearBindings()
                st.bindLong(1, it.kind.ordinal.toLong())
                st.bindString(2, it.group)
                st.bindString(3, it.name)
                st.bindString(4, normTitle(it.name))
                if (it.logo != null) st.bindString(5, it.logo)
                st.bindString(6, it.url)
                (it.year ?: yearOf(it.name))?.let { y -> st.bindLong(7, y.toLong()) }
                if (it.tvg != null) st.bindString(8, it.tvg)
                st.bindLong(9, n++)
                st.executeInsert()
            }
            db.execSQL("""UPDATE items SET
                overview=(SELECT overview FROM meta m WHERE m.norm=items.norm AND m.kind=items.kind),
                backdrop=(SELECT backdrop FROM meta m WHERE m.norm=items.norm AND m.kind=items.kind),
                poster=(SELECT poster FROM meta m WHERE m.norm=items.norm AND m.kind=items.kind),
                rating=(SELECT rating FROM meta m WHERE m.norm=items.norm AND m.kind=items.kind),
                genres=(SELECT genres FROM meta m WHERE m.norm=items.norm AND m.kind=items.kind)
                WHERE kind IN (1,2) AND EXISTS (SELECT 1 FROM meta m WHERE m.norm=items.norm AND m.kind=items.kind)""")
            // "Recently added": remember when each stream was first seen.
            db.execSQL("INSERT OR IGNORE INTO seen(url, ts) SELECT url, ? FROM items", arrayOf<Any>(System.currentTimeMillis()))
            db.execSQL("DELETE FROM seen WHERE url NOT IN (SELECT url FROM items)")
            db.execSQL("INSERT INTO items_fts(items_fts) VALUES('rebuild')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        version++
    }

    // ---- browsing -------------------------------------------------------------------------------

    fun groups(kind: Kind? = null): List<Group> = rd.rawQuery(
        "SELECT kind, grp, COUNT(*) FROM items ${if (kind != null) "WHERE kind=${kind.ordinal}" else ""} GROUP BY kind, grp ORDER BY kind, MIN(sort)", null
    ).use { c -> buildList { while (c.moveToNext()) add(Group(kinds[c.getInt(0)], c.getString(1) ?: "", c.getInt(2))) } }

    fun page(kind: Kind, group: String, offset: Int, limit: Int): List<Item> =
        query("SELECT $COLS FROM items WHERE kind=? AND grp=? ORDER BY sort LIMIT $limit OFFSET $offset", kind.ordinal.toString(), group)

    fun latest(kind: Kind, offset: Int, limit: Int): List<Item> =
        query("SELECT $I_COLS FROM items i LEFT JOIN seen s ON s.url=i.url WHERE i.kind=? ORDER BY s.ts DESC, i.sort DESC LIMIT $limit OFFSET $offset", kind.ordinal.toString())

    fun byUrl(url: String): Item? = query("SELECT $COLS FROM items WHERE url=? LIMIT 1", url).firstOrNull()

    fun byId(id: Long): Item? = query("SELECT $COLS FROM items WHERE id=?", id.toString()).firstOrNull()

    /** Exact normalised-title match, used to keep only TMDB titles that the playlist contains. */
    fun matchTitle(norm: String, kind: Kind, year: Int?): Item? {
        if (norm.isEmpty()) return null
        val list = query("SELECT $COLS FROM items WHERE norm=? AND kind=? ORDER BY sort LIMIT 8", norm, kind.ordinal.toString())
        return list.firstOrNull { year == null || it.year == null || it.year == year } ?: list.firstOrNull()
    }

    fun search(q: String, limit: Int = 120): List<Item> {
        val words = normTitle(q).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val match = words.joinToString(" ") { "$it*" }
        return query(
            "SELECT $COLS FROM items WHERE id IN (SELECT docid FROM items_fts WHERE items_fts MATCH ?) ORDER BY kind DESC, sort LIMIT $limit", match
        )
    }

    // ---- favorites / recents / live history ----------------------------------------------------

    fun isFav(url: String) = rd.rawQuery("SELECT 1 FROM favs WHERE url=?", arrayOf(url)).use { it.moveToFirst() }

    fun toggleFav(url: String): Boolean {
        return if (isFav(url)) { wr.delete("favs", "url=?", arrayOf(url)); false }
        else { wr.insertWithOnConflict("favs", null, ContentValues().apply { put("url", url); put("ts", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE); true }
    }

    fun favorites(limit: Int = 200): List<Item> =
        query("SELECT $I_COLS FROM favs f JOIN items i ON i.url=f.url ORDER BY f.ts DESC LIMIT $limit")

    fun saveProgress(url: String, pos: Long, dur: Long, parent: String? = null, label: String? = null) {
        val finished = dur > 0 && pos > dur * 0.95
        if (finished) { wr.delete("recents", "url=?", arrayOf(url)); return }
        wr.insertWithOnConflict("recents", null, ContentValues().apply {
            put("url", url); put("pos", pos); put("dur", dur); put("ts", System.currentTimeMillis()); put("parent", parent); put("label", label)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun progressOf(url: String): Long =
        rd.rawQuery("SELECT pos FROM recents WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Continue Watching: movies and series (an episode shows as its series, with an "S1 E3" note), newest first, one per title. */
    fun recents(limit: Int = 30): List<Item> {
        val seen = HashSet<String>()
        return rd.rawQuery(
            "SELECT $I_COLS, r.pos, r.dur, r.label FROM recents r JOIN items i ON i.url=COALESCE(r.parent, r.url) WHERE r.pos > 10000 ORDER BY r.ts DESC LIMIT ${limit * 3}", null
        ).use { c -> buildList { while (c.moveToNext()) { val it = c.item(extra = true); if (seen.add(it.url)) add(it) } } }.take(limit)
    }

    /** Last watched episode of a series: (episode url, label, position). */
    fun lastEpisode(parent: String): Triple<String, String?, Long>? =
        rd.rawQuery("SELECT url, label, pos FROM recents WHERE parent=? ORDER BY ts DESC LIMIT 1", arrayOf(parent)).use { if (it.moveToFirst()) Triple(it.getString(0), it.getString(1), it.getLong(2)) else null }

    fun touchLive(url: String) {
        wr.insertWithOnConflict("livehist", null, ContentValues().apply { put("url", url); put("ts", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recentLive(limit: Int = 40): List<Item> =
        query("SELECT $I_COLS FROM livehist h JOIN items i ON i.url=h.url ORDER BY h.ts DESC LIMIT $limit")

    // ---- metadata -------------------------------------------------------------------------------

    fun saveMeta(item: Item, year: Int?, overview: String?, backdrop: String?, poster: String?, rating: Float?, genres: String?) {
        val db = wr
        val norm = normTitle(item.name)
        db.insertWithOnConflict("meta", null, ContentValues().apply {
            put("norm", norm); put("kind", item.kind.ordinal); put("year", year); put("overview", overview)
            put("backdrop", backdrop); put("poster", poster); put("rating", rating); put("genres", genres)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "UPDATE items SET overview=?, backdrop=?, poster=?, rating=?, genres=?, year=COALESCE(year, ?) WHERE norm=? AND kind=?",
            arrayOf<Any?>(overview, backdrop, poster, rating, genres, year, norm, item.kind.ordinal)
        )
    }

    // ---- guide (XMLTV) --------------------------------------------------------------------------

    fun liveTvgIds(): HashSet<String> = rd.rawQuery("SELECT DISTINCT tvg FROM items WHERE kind=0 AND tvg IS NOT NULL", null)
        .use { c -> HashSet<String>().also { while (c.moveToNext()) it.add(c.getString(0)) } }

    fun replaceEpg(feed: ((String, Long, Long, String) -> Unit) -> Unit) {
        val db = wr
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM epg")
            val st = db.compileStatement("INSERT INTO epg(ch,start,stop,title) VALUES(?,?,?,?)")
            feed { ch, s, e, t -> st.clearBindings(); st.bindString(1, ch); st.bindLong(2, s); st.bindLong(3, e); st.bindString(4, t); st.executeInsert() }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun epgCount(): Int = rd.rawQuery("SELECT COUNT(*) FROM epg", null).use { it.moveToFirst(); it.getInt(0) }

    /** Live channels for the guide grid. */
    fun epgChannels(onlyWithData: Boolean, offset: Int, limit: Int): List<Item> = query(
        if (onlyWithData) "SELECT $COLS FROM items WHERE kind=0 AND tvg IN (SELECT DISTINCT ch FROM epg) ORDER BY sort LIMIT $limit OFFSET $offset"
        else "SELECT $COLS FROM items WHERE kind=0 ORDER BY sort LIMIT $limit OFFSET $offset"
    )

    fun epgRange(channels: List<String>, from: Long, to: Long): Map<String, List<Prog>> {
        if (channels.isEmpty()) return emptyMap()
        val ph = channels.joinToString(",") { "?" }
        val out = HashMap<String, MutableList<Prog>>()
        rd.rawQuery("SELECT ch,start,stop,title FROM epg WHERE ch IN ($ph) AND stop>? AND start<? ORDER BY ch,start", (channels + from.toString() + to.toString()).toTypedArray())
            .use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList() }.add(Prog(c.getLong(1), c.getLong(2), c.getString(3) ?: "")) }
        return out
    }
}
