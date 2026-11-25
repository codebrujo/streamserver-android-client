package info.ogarkov.streamclient

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** A media file found in one of the local folders. [cat]: "a" audiobooks, "m" music, "v" films. */
data class LocalItem(val cat: String, val uri: String, val name: String, val folder: String, val size: Long) {
    val isVideo get() = cat == "v"
    val title get() = name.substringBeforeLast('.')
}

/**
 * Media folders on the phone, picked with the system folder picker (Storage Access Framework: no
 * "all files" permission needed; the grant survives restarts). A scan walks the folders and keeps the
 * list in a file, shown as the top-level "Local media" folder. Plays without the server.
 */
object LocalMedia {
    lateinit var context: Context
        private set

    val CATEGORIES = listOf("a" to "Audiobooks", "m" to "Music", "v" to "Films")

    private val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "ape", "wv", "aiff", "aif", "alac", "mka")
    private val BOOK_EXT = AUDIO_EXT + setOf("m4b", "mp4")
    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "m2ts", "3gp", "mpg", "mpeg", "wmv", "flv")

    @Volatile var items: List<LocalItem> = emptyList()
        private set
    @Volatile var scanning = false
        private set
    @Volatile var lastScan = 0L
        private set

    private var order: Map<String, List<LocalItem>> = emptyMap() // category -> files in tree order
    var root = Node("l:", "Local media", "", false)
        private set

    private val file get() = File(context.filesDir, "local_media.json")

    fun init(ctx: Context) {
        context = ctx.applicationContext
        try {
            val o = JSONObject(file.readText())
            lastScan = o.optLong("at")
            val arr = o.getJSONArray("items")
            items = (0 until arr.length()).map {
                val x = arr.getJSONObject(it)
                LocalItem(x.getString("cat"), x.getString("uri"), x.getString("name"), x.optString("folder"), x.optLong("size"))
            }
        } catch (e: Exception) {
            items = emptyList()
        }
        rebuild()
    }

    // ---------- folders ----------

    fun folderUri(cat: String): String? = Prefs.localFolder(cat).ifEmpty { null }

    /** Any local folder chosen? (then "Local media" is shown) */
    fun configured() = CATEGORIES.any { folderUri(it.first) != null }

    /** Remember the folder picked for [cat] and keep read access to it after restarts. */
    fun setFolder(cat: String, uri: Uri?) {
        val old = folderUri(cat)
        if (old != null && old != uri?.toString()) {
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
        }
        if (uri != null) context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Prefs.setLocalFolder(cat, uri?.toString() ?: "")
        if (uri == null) {
            items = items.filter { it.cat != cat }
            persist()
            rebuild()
        }
    }

    /** "primary:Music/Books" → "Internal storage/Music/Books", "1A2B-3C4D:Films" → "SD card 1A2B-3C4D/Films" */
    fun displayPath(uri: String?): String {
        if (uri == null) return "not set"
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri))
            val volume = id.substringBefore(':')
            val path = id.substringAfter(':', "")
            val vol = if (volume == "primary") "Internal storage" else "SD card $volume"
            if (path.isEmpty()) vol else "$vol/$path"
        } catch (e: Exception) {
            uri
        }
    }

    // ---------- scan ----------

    /** Walk all chosen folders. Returns how many media files were found. */
    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        if (scanning) return@withContext items.size
        scanning = true
        try {
            val found = ArrayList<LocalItem>()
            for ((cat, _) in CATEGORIES) {
                val tree = folderUri(cat) ?: continue
                try {
                    val t = Uri.parse(tree)
                    walk(cat, t, DocumentsContract.getTreeDocumentId(t), "", found, 0)
                } catch (e: Exception) {
                    // folder removed or access revoked: keep scanning the others
                }
            }
            items = found
            lastScan = System.currentTimeMillis()
            persist()
            withContext(Dispatchers.Main) { rebuild() }
            found.size
        } finally {
            scanning = false
        }
    }

    private fun walk(cat: String, tree: Uri, docId: String, rel: String, out: MutableList<LocalItem>, depth: Int) {
        if (depth > 25) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val dirs = ArrayList<Pair<String, String>>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    dirs += id to name
                } else if (accepts(cat, name)) {
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    out += LocalItem(cat, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, rel, size)
                }
            }
        }
        for ((id, name) in dirs) walk(cat, tree, id, if (rel.isEmpty()) name else "$rel/$name", out, depth + 1)
    }

    private fun accepts(cat: String, name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (cat) {
            "a" -> ext in BOOK_EXT
            "m" -> ext in AUDIO_EXT
            else -> ext in VIDEO_EXT
        }
    }

    private fun persist() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("cat", it.cat).put("uri", it.uri).put("name", it.name).put("folder", it.folder).put("size", it.size))
        }
        try {
            file.writeText(JSONObject().put("at", lastScan).put("items", arr).toString())
        } catch (e: Exception) { }
    }

    // ---------- tree ----------

    /** Tree: Local media / Audiobooks|Music|Films / folders / files; keys "l:", "l:<cat>:<path>". */
    private fun rebuild() {
        val r = Node("l:", "Local media", "", false)
        val ord = HashMap<String, List<LocalItem>>()
        for ((cat, label) in CATEGORIES) {
            if (folderUri(cat) == null) continue
            val c = Node("l:$cat:", label, "", cat != "v")
            for (it in items) {
                if (it.cat != cat) continue
                var n = c
                c.count++
                if (it.folder.isNotEmpty()) for (part in it.folder.split('/')) {
                    val path = if (n.path.isEmpty()) part else "${n.path}/$part"
                    n = n.folders.firstOrNull { f -> f.name == part } ?: Node("l:$cat:$path", part, path, cat != "v").also { f -> n.folders += f }
                    n.count++
                }
                n.localFiles += it
            }
            sort(c)
            r.count += c.count
            r.folders += c
            ord[cat] = ArrayList<LocalItem>().also { collect(c, it) }
        }
        root = r
        order = ord
        Library.bump()
    }

    private fun sort(n: Node) {
        n.folders.sortWith { a, b -> Library.natural.compare(a.name, b.name) }
        n.localFiles.sortWith { a, b -> Library.natural.compare(a.name, b.name) }
        n.folders.forEach { sort(it) }
    }

    // same order as on screen: subfolders first, then the files
    private fun collect(n: Node, out: MutableList<LocalItem>) {
        n.folders.forEach { collect(it, out) }
        out += n.localFiles
    }

    fun item(uri: String): LocalItem? = items.firstOrNull { it.uri == uri }

    // ---------- playing ----------

    fun track(it: LocalItem) = Track(
        id = it.uri,
        n = 0,
        title = it.title,
        artist = "",
        album = it.folder.substringAfterLast('/'),
        duration = 0.0,
        format = it.name.substringAfterLast('.', ""),
        quality = "",
        deliverOrig = null,
        deliverEco = null,
        folder = "local:${it.cat}:${it.folder}",
        localUri = it.uri,
        localName = it.name,
    )

    /** Audio: the whole category in tree order from this file, so it goes on file after file, folder after folder. */
    fun playAudio(it: LocalItem, startSec: Double) {
        val list = order[it.cat] ?: return
        val i = list.indexOfFirst { x -> x.uri == it.uri }
        if (i < 0) return
        Engine.playLocal(list.map { x -> track(x) }, i, startSec)
    }

    fun asVideo(it: LocalItem) = Video(it.uri, it.title, it.name, it.folder, it.size, emptyList())

    fun video(uri: String): Video? = item(uri)?.takeIf { it.isVideo }?.let { asVideo(it) }

    fun nextVideo(uri: String): Video? {
        val list = order["v"] ?: return null
        val i = list.indexOfFirst { it.uri == uri }
        return if (i >= 0) list.getOrNull(i + 1)?.let { asVideo(it) } else null
    }

    fun isLocal(id: String) = id.startsWith("content:")
}
