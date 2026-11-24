package info.ogarkov.streamclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/** targetSdk 35 draws edge-to-edge: keep content out from under the system bars and the keyboard. */
fun applySystemBarPadding(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        insets
    }
}

sealed class Row {
    data class Header(val text: String, val clear: Boolean) : Row()
    data class Folder(val node: Node, val depth: Int, val open: Boolean) : Row()
    data class VideoFile(val video: Video, val depth: Int, val showPath: Boolean) : Row()
    data class TrackFile(val track: Track, val depth: Int) : Row()
    data class MusicFile(val folder: String, val file: String) : Row()
    data class RecentEntry(val item: RecentItem) : Row()
    data class Info(val text: String, val depth: Int, val retryFolder: String?) : Row()
}

class MainActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var spinner: ProgressBar
    private lateinit var mini: View
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton
    private val adapter = Adapter()

    private val expanded = HashSet<String>()
    private val loadingFolders = HashSet<String>()
    private val folderErrors = HashMap<String, String>()
    private var libraryLoading = false
    private var libraryError: String? = null
    private var miniFolder: String? = null

    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Prefs.password.isEmpty()) finish() else Conn.recheck()
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarPadding(findViewById(R.id.root))
        Engine.connectService(this)

        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        spinner = findViewById(R.id.spinner)
        mini = findViewById(R.id.mini)
        miniCover = findViewById(R.id.miniCover)
        miniTitle = findViewById(R.id.miniTitle)
        miniSub = findViewById(R.id.miniSub)
        miniPlay = findViewById(R.id.miniPlay)

        expanded += Prefs.expanded
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<ImageButton>(R.id.refresh).setOnClickListener { loadLibrary(true) }
        findViewById<ImageButton>(R.id.settings).setOnClickListener { openSettings() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        mini.setOnClickListener { startActivity(Intent(this, NowPlayingActivity::class.java)) }
        miniPlay.setOnClickListener { val p = Engine.player; if (p.isPlaying) p.pause() else p.play() }
        findViewById<ImageButton>(R.id.miniNext).setOnClickListener { Engine.player.seekToNextMediaItem() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (search.text.isNotEmpty()) search.setText("")
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (Prefs.password.isEmpty()) openSettings()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Conn.state.collect {
                        showStatus()
                        if (it.base != null && !Library.loaded && !libraryLoading) loadLibrary(false)
                    }
                }
                launch { Library.version.collect { render() } }
                launch {
                    Engine.changes.collect {
                        updateMini()
                        adapter.notifyDataSetChanged() // highlight of the playing item
                    }
                }
            }
        }
    }

    private fun openSettings() = settings.launch(Intent(this, SettingsActivity::class.java))

    private fun showStatus() {
        val s = Conn.state.value
        val host = s.base?.substringAfter("://") ?: ""
        val (text, color) = when (s.mode) {
            Conn.Mode.CHECKING -> "Connecting…" to R.color.muted
            Conn.Mode.LOCAL -> "● Local connection · $host" to R.color.local
            Conn.Mode.GLOBAL -> "● Global connection · $host" to R.color.global
            Conn.Mode.OFFLINE -> (if (Prefs.password.isEmpty()) "● No connection: set the password in Settings" else "● No connection") to R.color.offline
        }
        val extra = when {
            libraryLoading -> " · loading library…"
            libraryError != null -> " · $libraryError"
            Library.loaded -> " · ${Library.videos.size} videos" + if (Library.musicEnabled) ", ${Library.musicFolders.size} albums" else ""
            else -> ""
        }
        status.text = text + extra
        status.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun loadLibrary(refresh: Boolean) {
        if (libraryLoading) return
        if (Conn.base == null) {
            Toast.makeText(this, "No connection to the server", Toast.LENGTH_SHORT).show()
            return
        }
        libraryLoading = true
        libraryError = null
        spinner.visibility = if (Library.loaded) View.GONE else View.VISIBLE
        showStatus()
        lifecycleScope.launch {
            try {
                Library.load(refresh)
                if (refresh) {
                    folderErrors.clear()
                    Toast.makeText(this@MainActivity, "Library rescanned", Toast.LENGTH_SHORT).show()
                }
            } catch (e: AuthException) {
                libraryError = e.message
                openSettings()
            } catch (e: Exception) {
                libraryError = e.message ?: e.toString()
            } finally {
                libraryLoading = false
                spinner.visibility = View.GONE
                showStatus()
                render()
            }
        }
    }

    // ---------- list content ----------

    private fun render() {
        val rows = ArrayList<Row>()
        val q = search.text.toString().trim().lowercase()
        if (q.isNotEmpty()) {
            searchRows(q, rows)
        } else {
            val recent = Recent.all()
            if (recent.isNotEmpty()) {
                rows += Row.Header("Recently played", true)
                recent.forEach { rows += Row.RecentEntry(it) }
            }
            if (Library.loaded) {
                rows += Row.Header("Library", false)
                addNode(Library.videoRoot, 0, rows)
                if (Library.musicEnabled) addNode(Library.musicRoot, 0, rows)
            }
        }
        adapter.submit(rows)
    }

    private fun searchRows(q: String, rows: MutableList<Row>) {
        val limit = 300
        val videos = Library.videos.filter { it.file.lowercase().contains(q) }
        val music = ArrayList<Row.MusicFile>()
        for (f in Library.musicFolders) {
            for (file in f.files) if (file.lowercase().contains(q)) music += Row.MusicFile(f.path, file)
            if (music.size >= limit) break
        }
        if (videos.isEmpty() && music.isEmpty()) {
            rows += Row.Info(if (Library.loaded) "Nothing found" else "The library is not loaded yet", 0, null)
            return
        }
        if (videos.isNotEmpty()) {
            rows += Row.Header("Video · ${videos.size}", false)
            videos.take(limit).forEach { rows += Row.VideoFile(it, 0, true) }
        }
        if (music.isNotEmpty()) {
            rows += Row.Header("Music · ${music.size}", false)
            rows += music
        }
    }

    private fun addNode(n: Node, depth: Int, rows: MutableList<Row>) {
        val open = n.key in expanded
        rows += Row.Folder(n, depth, open)
        if (!open) return
        n.folders.forEach { addNode(it, depth + 1, rows) }
        if (!n.music) {
            n.videos.forEach { rows += Row.VideoFile(it, depth + 1, false) }
        } else if (n.hasTracks) {
            val tracks = Library.cachedTracks(n.path)
            when {
                tracks != null -> tracks.forEach { rows += Row.TrackFile(it, depth + 1) }
                folderErrors[n.path] != null -> rows += Row.Info("Failed: ${folderErrors[n.path]} — tap to retry", depth + 1, n.path)
                else -> {
                    rows += Row.Info("Loading…", depth + 1, null)
                    loadTracks(n.path)
                }
            }
        }
    }

    private fun loadTracks(path: String) {
        if (!loadingFolders.add(path)) return
        folderErrors.remove(path)
        lifecycleScope.launch {
            try {
                Library.tracks(path)
            } catch (e: Exception) {
                folderErrors[path] = e.message ?: e.toString()
            } finally {
                loadingFolders.remove(path)
                render()
            }
        }
    }

    private fun toggle(n: Node) {
        if (!expanded.remove(n.key)) expanded += n.key
        Prefs.expanded = expanded.toSet()
        render()
    }

    // ---------- actions ----------

    private fun openVideo(id: String) {
        startActivity(Intent(this, VideoActivity::class.java).putExtra("id", id))
    }

    private fun playTrack(t: Track) {
        val tracks = Library.cachedTracks(t.folder) ?: return
        Engine.playMusic(t.folder, tracks, tracks.indexOf(t).coerceAtLeast(0), Recent.resumeAt(t.id))
    }

    private fun playFrom(folder: String, trackId: String?, file: String?, startSec: Double) {
        lifecycleScope.launch {
            try {
                if (!Engine.playFromFolder(folder, trackId, file, startSec)) {
                    Toast.makeText(this@MainActivity, "Track not found — rescan the library", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: e.toString(), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openRecent(r: RecentItem) {
        if (r.kind == "video") {
            if (Library.loaded && Library.video(r.id) == null) {
                Toast.makeText(this, "This video is no longer on the server", Toast.LENGTH_SHORT).show()
                return
            }
            openVideo(r.id)
        } else {
            playFrom(r.folder, r.id, null, Recent.resumeAt(r.id))
        }
    }

    private fun updateMini() {
        val t = Engine.currentTrack()
        if (t == null) {
            mini.visibility = View.GONE
            return
        }
        mini.visibility = View.VISIBLE
        miniTitle.text = t.title
        miniSub.text = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" — ")
        miniPlay.setImageResource(if (Engine.player.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        if (miniFolder != t.folder) {
            miniFolder = t.folder
            miniCover.setImageResource(R.drawable.ic_album)
            val folder = t.folder
            lifecycleScope.launch {
                val bmp = Covers.get(folder, 200)
                if (bmp != null && miniFolder == folder) miniCover.setImageBitmap(bmp)
            }
        }
    }

    // ---------- adapter ----------

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(r: List<Row>) {
            rows = r
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == 0) R.layout.item_header else R.layout.item_node
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val row = rows[position]
            if (row is Row.Header) {
                v.findViewById<TextView>(R.id.text).text = row.text
                val b = v.findViewById<Button>(R.id.action)
                b.visibility = if (row.clear) View.VISIBLE else View.GONE
                b.setOnClickListener { Recent.clear() }
                return
            }
            val chevron = v.findViewById<ImageView>(R.id.chevron)
            val icon = v.findViewById<ImageView>(R.id.icon)
            val name = v.findViewById<TextView>(R.id.name)
            val meta = v.findViewById<TextView>(R.id.meta)
            val count = v.findViewById<TextView>(R.id.count)
            val bar = v.findViewById<ProgressBar>(R.id.progress)
            val dp = resources.displayMetrics.density
            fun indent(depth: Int) = v.setPaddingRelative((8 * dp + depth * 18 * dp).toInt(), v.paddingTop, v.paddingEnd, v.paddingBottom)
            fun setMeta(text: String) {
                meta.text = text
                meta.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }

            chevron.visibility = View.INVISIBLE
            icon.visibility = View.VISIBLE
            bar.visibility = View.GONE
            count.text = ""
            name.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            v.setOnLongClickListener(null)
            val playingId = Engine.currentTrack()?.id

            when (row) {
                is Row.Folder -> {
                    val n = row.node
                    indent(row.depth)
                    chevron.visibility = View.VISIBLE
                    chevron.setImageResource(if (row.open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
                    icon.setImageResource(if (row.depth == 0) (if (n.music) R.drawable.ic_music else R.drawable.ic_movie) else R.drawable.ic_folder)
                    name.text = n.name
                    setMeta("")
                    count.text = n.count.toString()
                    v.setOnClickListener { toggle(n) }
                    if (n.music && n.hasTracks) v.setOnLongClickListener {
                        playFrom(n.path, null, null, 0.0)
                        Toast.makeText(this@MainActivity, "Playing ${n.name}", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
                is Row.VideoFile -> {
                    val video = row.video
                    indent(row.depth)
                    icon.setImageResource(R.drawable.ic_movie)
                    name.text = video.file
                    setMeta(listOfNotNull(video.folder.takeIf { row.showPath && it.isNotEmpty() }, fmtSize(video.size)).joinToString(" · "))
                    v.setOnClickListener { openVideo(video.id) }
                }
                is Row.TrackFile -> {
                    val t = row.track
                    indent(row.depth)
                    icon.setImageResource(R.drawable.ic_music)
                    name.text = if (t.n > 0) "${t.n}. ${t.title}" else t.title
                    setMeta(listOf(t.artist, t.quality).filter { it.isNotBlank() }.joinToString(" · "))
                    count.text = fmtTime(t.duration)
                    if (t.id == playingId) name.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    v.setOnClickListener { playTrack(t) }
                }
                is Row.MusicFile -> {
                    indent(0)
                    icon.setImageResource(R.drawable.ic_music)
                    name.text = row.file
                    setMeta(row.folder)
                    v.setOnClickListener { playFrom(row.folder, null, row.file, 0.0) }
                }
                is Row.RecentEntry -> {
                    val r = row.item
                    indent(0)
                    icon.setImageResource(if (r.kind == "video") R.drawable.ic_movie else R.drawable.ic_music)
                    name.text = r.title
                    if (r.id == playingId) name.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    setMeta(listOf(r.subtitle, r.folder).filter { it.isNotBlank() }.joinToString(" · "))
                    count.text = if (r.dur > 0) "${fmtTime(r.pos)} / ${fmtTime(r.dur)}" else fmtTime(r.pos)
                    if (r.dur > 0) {
                        bar.visibility = View.VISIBLE
                        bar.progress = ((r.pos / r.dur) * 1000).toInt().coerceIn(0, 1000)
                    }
                    v.setOnClickListener { openRecent(r) }
                }
                is Row.Info -> {
                    indent(row.depth)
                    icon.visibility = View.INVISIBLE
                    name.text = row.text
                    name.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                    setMeta("")
                    val retry = row.retryFolder
                    v.setOnClickListener { if (retry != null) loadTracks(retry) }
                }
                is Row.Header -> {}
            }
        }
    }
}
