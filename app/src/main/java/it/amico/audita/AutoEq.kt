package it.amico.audita

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Headphone profiles from the AutoEq project (github.com/jaakkopasanen/AutoEq, MIT license).
 * results/INDEX.md lists every profile as "- [Name](./source/rig/Name) by Source on Rig"; next to each one there is
 * "<Name> ParametricEQ.txt" (Equalizer APO format), which the Audita firmware reads directly.
 * The index (about 850 kB) is kept in the app's files and refreshed every 14 days, or on request.
 */
class AutoEq(private val ctx: Context, private val js: (String) -> Unit) {
    companion object {
        const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
        const val MAX_AGE_MS = 14L * 24 * 3600 * 1000
        private val LINE = Regex("""^- \[(.+?)\]\((\./.+)\) by (.+)$""")   // path up to the last ") by "
    }

    private data class Entry(val name: String, val path: String, val source: String, val key: String)

    private val ex = Executors.newSingleThreadExecutor()
    private var index: List<Entry>? = null
    private val file get() = File(ctx.filesDir, "autoeq_index.md")

    fun shutdown() = ex.shutdownNow()

    private fun reply(id: Int, data: String?, err: String?) =
        js("window.auEq && window.auEq($id, ${if (data == null) "null" else JSONObject.quote(data)}, ${if (err == null) "null" else JSONObject.quote(err)})")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Audita-app")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>(9000)
        for (raw in text.lineSequence()) {
            val m = LINE.find(raw.trim()) ?: continue
            val (name, path, source) = m.destructured
            out.add(Entry(name, path, source, (name + " " + source).lowercase()))
        }
        return out
    }

    /** loads the index: memory, then file (if recent), then network; an old file is used if the network fails */
    private fun load(refresh: Boolean): List<Entry> {
        index?.let { if (!refresh) return it }
        val f = file
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS
        if (f.exists() && fresh && !refresh) {
            val l = parse(f.readText())
            if (l.isNotEmpty()) { index = l; return l }
        }
        try {
            val t = get(BASE + "INDEX.md")
            val l = parse(t)
            if (l.isEmpty()) throw RuntimeException("index format not recognised")
            f.writeText(t)
            index = l
            return l
        } catch (e: Exception) {
            if (f.exists()) { val l = parse(f.readText()); if (l.isNotEmpty()) { index = l; return l } }
            throw e
        }
    }

    fun info(id: Int, refresh: Boolean) = ex.execute {
        try {
            val l = load(refresh)
            val o = JSONObject().put("count", l.size).put("updated", file.lastModified())
            reply(id, o.toString(), null)
        } catch (e: Exception) { reply(id, null, "AutoEq index not available: ${e.message}") }
    }

    fun search(id: Int, query: String) = ex.execute {
        try {
            val l = load(false)
            val q = query.trim().lowercase()
            val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val hits = if (tokens.isEmpty()) emptyList() else l.filter { e -> tokens.all { e.key.contains(it) } }
                .sortedWith(compareBy<Entry>({ if (it.name.lowercase().startsWith(q)) 0 else if (it.name.lowercase().contains(q)) 1 else 2 }, { it.name.length }, { it.name }))
                .take(60)
            val a = JSONArray()
            for (e in hits) a.put(JSONObject().put("name", e.name).put("path", e.path).put("source", e.source))
            reply(id, a.toString(), null)
        } catch (e: Exception) { reply(id, null, "AutoEq index not available: ${e.message}") }
    }

    fun profile(id: Int, path: String, name: String) = ex.execute {
        try {
            if (!path.startsWith("./") || path.contains("..")) throw RuntimeException("bad path")
            val url = BASE + path.removePrefix("./") + "/" + Uri.encode("$name ParametricEQ.txt")
            reply(id, get(url), null)
        } catch (e: Exception) { reply(id, null, "Profile not downloaded: ${e.message}") }
    }
}
