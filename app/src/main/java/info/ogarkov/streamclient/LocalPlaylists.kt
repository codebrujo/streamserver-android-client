package info.ogarkov.streamclient

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A playlist kept on the phone: can mix server tracks and files from the local Audiobooks / Music folders. */
data class LocalPlaylist(val id: String, val name: String, val loop: Boolean, val tracks: List<Track>)

/** Playlists stored in filesDir/local_playlists.json; ids start with "local-". */
object LocalPlaylists {
    private lateinit var file: File
    private var list: List<LocalPlaylist> = emptyList()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "local_playlists.json")
        list = try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val t = o.optJSONArray("tracks") ?: JSONArray()
                LocalPlaylist(
                    o.getString("id"), o.optString("name"), o.optBoolean("loop"),
                    (0 until t.length()).mapNotNull { i ->
                        try {
                            val x = t.getJSONObject(i)
                            Library.parseTrack(x, x.optString("folder"))
                        } catch (e: Exception) {
                            null
                        }
                    },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isLocal(id: String) = id.startsWith("local-")

    fun get(id: String): LocalPlaylist? = synchronized(this) { list.firstOrNull { it.id == id } }

    /** Tracks of a playlist; local files no longer found by the last scan are marked missing. */
    fun tracks(id: String): List<Track>? = get(id)?.tracks?.map { withLocalInfo(it) }

    /** local files: missing if the last scan didn't find them; length from what the phone has measured */
    private fun withLocalInfo(t: Track): Track {
        val uri = t.localUri ?: return t
        if (LocalMedia.item(uri) == null) return t.copy(missing = true)
        val d = LocalMedia.duration(uri)
        return if (t.duration <= 0 && d > 0) t.copy(duration = d) else t
    }

    fun infos(): List<PlaylistInfo> = synchronized(this) {
        list.map { p ->
            PlaylistInfo(p.id, p.name, p.tracks.size, p.tracks.sumOf { withLocalInfo(it).duration }, p.tracks.firstOrNull()?.folder, local = true, loop = p.loop)
        }
    }

    fun create(name: String): String {
        val p = LocalPlaylist("local-" + UUID.randomUUID(), name, false, emptyList())
        synchronized(this) { list = list + p }
        persist()
        return p.id
    }

    fun update(id: String, name: String, loop: Boolean, tracks: List<Track>) {
        // "missing" is worked out on reading, never stored
        val clean = tracks.map { it.copy(missing = false) }
        synchronized(this) { list = list.map { if (it.id == id) LocalPlaylist(id, name, loop, clean) else it } }
        persist()
    }

    fun delete(id: String) {
        synchronized(this) { list = list.filter { it.id != id } }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        synchronized(this) {
            for (p in list) {
                val t = JSONArray()
                p.tracks.forEach { t.put(Library.trackJson(it)) }
                arr.put(JSONObject().put("id", p.id).put("name", p.name).put("loop", p.loop).put("tracks", t))
            }
        }
        // write to a temp file first, so a crash never leaves half a file
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
