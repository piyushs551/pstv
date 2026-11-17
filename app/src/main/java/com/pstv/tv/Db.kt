package com.pstv.tv

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite store. Items are keyed by stream URL so favorites / recents survive a playlist refresh.
 * Search uses an FTS4 index over the normalised title.
 */
object Db {
    private lateinit var helper: H
    @Volatile var version = 0   // bumped when items change; UI listens via Repo.listeners

    fun init(c: Context) { helper = H(c.applicationContext) }
    private val rd get() = helper.readableDatabase
    private val wr get() = helper.writableDatabase

    private class H(c: Context) : SQLiteOpenHelper(c, "pstv.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(false); db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE items(id INTEGER PRIMARY KEY, kind INTEGER, grp TEXT, name TEXT, norm TEXT, logo TEXT, url TEXT, year INTEGER, overview TEXT, backdrop TEXT, poster TEXT, sort INTEGER)""")
            db.execSQL("CREATE INDEX i_kind_grp ON items(kind, grp, sort)")
            db.execSQL("CREATE INDEX i_norm ON items(norm)")
            db.execSQL("CREATE UNIQUE INDEX i_url ON items(url)")
            db.execSQL("CREATE VIRTUAL TABLE items_fts USING fts4(content='items', norm)")
            db.execSQL("CREATE TABLE favs(url TEXT PRIMARY KEY, ts INTEGER)")
            db.execSQL("CREATE TABLE recents(url TEXT PRIMARY KEY, pos INTEGER, dur INTEGER, ts INTEGER)")
            // Metadata learned from TMDB survives playlist refreshes, keyed by normalised title + kind.
            db.execSQL("CREATE TABLE meta(norm TEXT, kind INTEGER, year INTEGER, overview TEXT, backdrop TEXT, poster TEXT, PRIMARY KEY(norm, kind))")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    private const val COLS = "id,kind,grp,name,logo,url,year,overview,backdrop,poster"
    private val kinds = Kind.values()

    private fun Cursor.item(extra: Boolean = false) = Item(
        id = getLong(0), kind = kinds[getInt(1)], group = getString(2) ?: "", name = getString(3) ?: "",
        logo = getString(4), url = getString(5) ?: "", year = if (isNull(6)) null else getInt(6),
        overview = getString(7), backdrop = getString(8), poster = getString(9),
        posMs = if (extra) getLong(10) else 0, durMs = if (extra) getLong(11) else 0,
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
            val st = db.compileStatement("INSERT OR IGNORE INTO items(kind,grp,name,norm,logo,url,year,sort) VALUES(?,?,?,?,?,?,?,?)")
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
                st.bindLong(8, n++)
                st.executeInsert()
            }
            // Re-apply cached TMDB metadata.
            db.execSQL("""UPDATE items SET
                overview=(SELECT overview FROM meta m WHERE m.norm=items.norm AND m.kind=items.kind),
                backdrop=(SELECT backdrop FROM meta m WHERE m.norm=items.norm AND m.kind=items.kind),
                poster=(SELECT poster FROM meta m WHERE m.norm=items.norm AND m.kind=items.kind)
                WHERE kind IN (1,2) AND EXISTS (SELECT 1 FROM meta m WHERE m.norm=items.norm AND m.kind=items.kind)""")
            db.execSQL("INSERT INTO items_fts(items_fts) VALUES('rebuild')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        version++
    }

    // ---- browsing -------------------------------------------------------------------------------

    fun groups(): List<Group> = rd.rawQuery(
        "SELECT kind, grp, COUNT(*) FROM items GROUP BY kind, grp ORDER BY kind, MIN(sort)", null
    ).use { c -> buildList { while (c.moveToNext()) add(Group(kinds[c.getInt(0)], c.getString(1) ?: "", c.getInt(2))) } }

    fun page(kind: Kind, group: String, offset: Int, limit: Int): List<Item> =
        query("SELECT $COLS FROM items WHERE kind=? AND grp=? ORDER BY sort LIMIT $limit OFFSET $offset", kind.ordinal.toString(), group)

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

    // ---- favorites / recents --------------------------------------------------------------------

    fun isFav(url: String) = rd.rawQuery("SELECT 1 FROM favs WHERE url=?", arrayOf(url)).use { it.moveToFirst() }

    fun toggleFav(url: String): Boolean {
        return if (isFav(url)) { wr.delete("favs", "url=?", arrayOf(url)); false }
        else { wr.insertWithOnConflict("favs", null, ContentValues().apply { put("url", url); put("ts", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE); true }
    }

    fun favorites(limit: Int = 200): List<Item> =
        query("SELECT ${COLS.split(',').joinToString(",") { "i.$it" }} FROM favs f JOIN items i ON i.url=f.url ORDER BY f.ts DESC LIMIT $limit")

    fun saveProgress(url: String, pos: Long, dur: Long) {
        val finished = dur > 0 && pos > dur * 0.95
        if (finished) { wr.delete("recents", "url=?", arrayOf(url)); return }
        wr.insertWithOnConflict("recents", null, ContentValues().apply {
            put("url", url); put("pos", pos); put("dur", dur); put("ts", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun progressOf(url: String): Long =
        rd.rawQuery("SELECT pos FROM recents WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun recents(limit: Int = 30): List<Item> = rd.rawQuery(
        "SELECT ${COLS.split(',').joinToString(",") { "i.$it" }}, r.pos, r.dur FROM recents r JOIN items i ON i.url=r.url WHERE r.pos > 10000 ORDER BY r.ts DESC LIMIT $limit", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.item(extra = true)) } }

    // ---- metadata -------------------------------------------------------------------------------

    fun saveMeta(item: Item, year: Int?, overview: String?, backdrop: String?, poster: String?) {
        val db = wr
        val norm = normTitle(item.name)
        db.insertWithOnConflict("meta", null, ContentValues().apply {
            put("norm", norm); put("kind", item.kind.ordinal); put("year", year); put("overview", overview); put("backdrop", backdrop); put("poster", poster)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "UPDATE items SET overview=?, backdrop=?, poster=?, year=COALESCE(year, ?) WHERE norm=? AND kind=?",
            arrayOf<Any?>(overview, backdrop, poster, year, norm, item.kind.ordinal)
        )
    }
}
