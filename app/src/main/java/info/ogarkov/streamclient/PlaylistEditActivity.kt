package info.ogarkov.streamclient

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

/**
 * Playlist editor, like the web UI: tab "Contents" = the music tree with a check box at every track
 * (and "all tracks" per folder), tab "Order" = the chosen tracks, reordered by drag and drop.
 * Changes are sent to the server with Save (PUT /api/playlists/:id).
 */
class PlaylistEditActivity : AppCompatActivity() {
    private lateinit var playlistId: String
    private lateinit var nameField: EditText
    private lateinit var contents: RecyclerView
    private lateinit var order: RecyclerView
    private lateinit var hint: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var summary: TextView
    private lateinit var saveButton: Button

    /** the playlist being edited, in play order */
    private val selected = ArrayList<Track>()
    private val selectedIds = HashSet<String>()
    private var savedName = ""
    private var savedIds: List<String> = emptyList()
    private var ready = false
    private var busy = false

    // Contents tab: its own open folders, folders being loaded, load errors
    private val open = HashSet<String>()
    private val loading = HashSet<String>()
    private val errors = HashMap<String, String>()
    private var selectedPerFolder: Map<String, Int> = emptyMap()

    private val pickAdapter = PickAdapter()
    private val orderAdapter = OrderAdapter()
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_edit)
        applySystemBarPadding(findViewById(R.id.root))

        playlistId = intent.getStringExtra("id") ?: return finish()
        nameField = findViewById(R.id.name)
        contents = findViewById(R.id.contents)
        order = findViewById(R.id.order)
        hint = findViewById(R.id.hint)
        spinner = findViewById(R.id.spinner)
        summary = findViewById(R.id.summary)
        saveButton = findViewById(R.id.save)

        contents.layoutManager = LinearLayoutManager(this)
        contents.adapter = pickAdapter
        order.layoutManager = LinearLayoutManager(this)
        order.adapter = orderAdapter
        touchHelper = ItemTouchHelper(dragCallback)
        touchHelper.attachToRecyclerView(order)

        val tabs = findViewById<TabLayout>(R.id.tabs)
        tabs.addTab(tabs.newTab().setText("Contents"))
        tabs.addTab(tabs.newTab().setText("Order"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        findViewById<ImageButton>(R.id.back).setOnClickListener { leave() }
        findViewById<ImageButton>(R.id.delete).setOnClickListener { confirmDelete() }
        saveButton.setOnClickListener { save(thenFinish = true) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        load()

        // keep the play / pause buttons of the Contents tab in step with the player
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Engine.changes.collect { if (ready) pickAdapter.notifyItemRangeChanged(0, pickAdapter.itemCount) }
            }
        }
    }

    /** The play button of a track: start it (its folder goes on after it), or pause / resume it. */
    private fun playTrack(t: Track) {
        val c = Engine.control
        if (Engine.currentTrack()?.id == t.id) {
            if (c.playWhenReady) c.pause() else c.play()
            return
        }
        val tracks = Library.cachedTracks(t.folder) ?: return
        Engine.playMusic(t.folder, tracks, tracks.indexOfFirst { it.id == t.id }.coerceAtLeast(0), 0.0)
    }

    private fun load() {
        spinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                if (!Library.loaded) Library.load(false)
                val (name, tracks) = Library.playlist(playlistId)
                savedName = name
                savedIds = tracks.map { it.id }
                nameField.setText(name)
                selected.clear()
                selected += tracks
                selectedIds.clear()
                tracks.forEach { selectedIds += it.id }
                ready = true
                changed()
            } catch (e: Exception) {
                hint.text = "Can't load the playlist: ${e.message ?: e}"
                hint.visibility = View.VISIBLE
            } finally {
                spinner.visibility = View.GONE
            }
        }
    }

    private fun showTab(i: Int) {
        contents.visibility = if (i == 0) View.VISIBLE else View.GONE
        order.visibility = if (i == 1) View.VISIBLE else View.GONE
        updateHint(i)
    }

    private fun updateHint(tab: Int) {
        val empty = ready && tab == 1 && selected.isEmpty()
        hint.text = if (empty) "No tracks yet — tick them on the Contents tab" else hint.text
        if (ready) hint.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun currentTab() = if (order.visibility == View.VISIBLE) 1 else 0

    /** the selection changed: counts, both lists, summary */
    @SuppressLint("NotifyDataSetChanged")
    private fun changed() {
        val counts = HashMap<String, Int>()
        for (t in selected) {
            var f = t.folder
            while (true) {
                counts[f] = (counts[f] ?: 0) + 1
                if (f.isEmpty()) break
                f = if (f.contains('/')) f.substringBeforeLast('/') else ""
            }
        }
        selectedPerFolder = counts
        pickAdapter.rebuild()
        orderAdapter.notifyDataSetChanged()
        val total = selected.sumOf { it.duration }
        val missing = selected.count { it.missing }
        summary.text = "${selected.size} tracks · ${fmtTime(total)}" + if (missing > 0) " · $missing missing" else ""
        updateHint(currentTab())
    }

    private fun isDirty(): Boolean =
        ready && (nameField.text.toString().trim() != savedName || selected.map { it.id } != savedIds)

    private fun setIn(tracks: List<Track>, on: Boolean) {
        if (on) {
            for (t in tracks) if (selectedIds.add(t.id)) selected += t
        } else {
            val ids = tracks.map { it.id }.toSet()
            selected.removeAll { it.id in ids }
            selectedIds.removeAll(ids)
        }
        changed()
    }

    // ---------- save / delete / leave ----------

    private fun save(thenFinish: Boolean) {
        if (!ready || busy) return
        val name = nameField.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        saveButton.isEnabled = false
        lifecycleScope.launch {
            try {
                Library.savePlaylist(playlistId, name, selected.toList())
                savedName = name
                savedIds = selected.map { it.id }
                setResult(Activity.RESULT_OK)
                Toast.makeText(this@PlaylistEditActivity, "Saved", Toast.LENGTH_SHORT).show()
                if (thenFinish) finish()
            } catch (e: Exception) {
                Toast.makeText(this@PlaylistEditActivity, "Not saved: ${e.message ?: e}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                saveButton.isEnabled = true
            }
        }
    }

    private fun leave() {
        if (!isDirty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Save changes?")
            .setPositiveButton("Save") { _, _ -> save(thenFinish = true) }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete playlist?")
            .setMessage("\"${nameField.text.toString().ifBlank { savedName }}\" will be deleted. The music files stay on the drive.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        Library.deletePlaylist(playlistId)
                        setResult(Activity.RESULT_OK)
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(this@PlaylistEditActivity, "Not deleted: ${e.message ?: e}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Contents tab: music tree with check boxes ----------

    private sealed class Pick {
        data class Folder(val node: Node, val depth: Int, val open: Boolean) : Pick()
        data class All(val tracks: List<Track>, val depth: Int) : Pick()
        data class Item(val track: Track, val depth: Int) : Pick()
        data class Info(val text: String, val depth: Int, val retry: String?) : Pick()
    }

    private fun loadFolder(path: String) {
        if (!loading.add(path)) return
        errors.remove(path)
        lifecycleScope.launch {
            try {
                Library.tracks(path)
            } catch (e: Exception) {
                errors[path] = e.message ?: e.toString()
            } finally {
                loading.remove(path)
                pickAdapter.rebuild()
            }
        }
    }

    private inner class PickAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Pick> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun rebuild() {
            val out = ArrayList<Pick>()
            val root = Library.musicRoot
            root.folders.forEach { add(it, 0, out) }
            if (root.hasTracks) addTracks(root, 0, out)
            rows = out
            notifyDataSetChanged()
        }

        private fun add(n: Node, depth: Int, out: MutableList<Pick>) {
            val isOpen = n.path in open
            out += Pick.Folder(n, depth, isOpen)
            if (!isOpen) return
            n.folders.forEach { add(it, depth + 1, out) }
            if (n.hasTracks) addTracks(n, depth + 1, out)
        }

        private fun addTracks(n: Node, depth: Int, out: MutableList<Pick>) {
            val tracks = Library.cachedTracks(n.path)
            when {
                tracks != null -> {
                    if (tracks.size > 1) out += Pick.All(tracks, depth)
                    tracks.forEach { out += Pick.Item(it, depth) }
                }
                errors[n.path] != null -> out += Pick.Info("Failed: ${errors[n.path]} — tap to retry", depth, n.path)
                else -> {
                    out += Pick.Info("Loading…", depth, null)
                    loadFolder(n.path)
                }
            }
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_pick, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val chevron = v.findViewById<ImageView>(R.id.chevron)
            val icon = v.findViewById<ImageView>(R.id.icon)
            val name = v.findViewById<TextView>(R.id.name)
            val meta = v.findViewById<TextView>(R.id.meta)
            val count = v.findViewById<TextView>(R.id.count)
            val check = v.findViewById<CheckBox>(R.id.check)
            val play = v.findViewById<ImageButton>(R.id.play)
            val dp = resources.displayMetrics.density
            fun indent(depth: Int) = v.setPaddingRelative((8 * dp + depth * 18 * dp).toInt(), v.paddingTop, v.paddingEnd, v.paddingBottom)
            fun setMeta(text: String) {
                meta.text = text
                meta.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }

            chevron.visibility = View.INVISIBLE
            icon.visibility = View.VISIBLE
            check.visibility = View.GONE
            play.visibility = View.GONE
            count.text = ""
            name.setTextColor(ContextCompat.getColor(this@PlaylistEditActivity, R.color.text))

            when (val row = rows[position]) {
                is Pick.Folder -> {
                    indent(row.depth)
                    chevron.visibility = View.VISIBLE
                    chevron.setImageResource(if (row.open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
                    icon.setImageResource(R.drawable.ic_folder)
                    name.text = row.node.name
                    setMeta("")
                    val n = selectedPerFolder[row.node.path] ?: 0
                    count.text = if (n > 0) "✓ $n" else ""
                    v.setOnClickListener {
                        if (!open.remove(row.node.path)) open += row.node.path
                        rebuild()
                    }
                }
                is Pick.All -> {
                    indent(row.depth)
                    icon.visibility = View.INVISIBLE
                    val n = row.tracks.count { it.id in selectedIds }
                    name.text = "All tracks"
                    name.setTextColor(ContextCompat.getColor(this@PlaylistEditActivity, R.color.muted))
                    setMeta("")
                    count.text = if (n > 0) "$n / ${row.tracks.size}" else ""
                    check.visibility = View.VISIBLE
                    check.isChecked = n == row.tracks.size
                    v.setOnClickListener { setIn(row.tracks, n != row.tracks.size) }
                }
                is Pick.Item -> {
                    val t = row.track
                    indent(row.depth)
                    icon.setImageResource(R.drawable.ic_music)
                    name.text = if (t.n > 0) "${t.n}. ${t.title}" else t.title
                    setMeta(listOf(t.artist, fmtTime(t.duration), t.quality).filter { it.isNotBlank() }.joinToString(" · "))
                    check.visibility = View.VISIBLE
                    val on = t.id in selectedIds
                    check.isChecked = on
                    v.setOnClickListener { setIn(listOf(t), !on) }
                    val current = Engine.currentTrack()?.id == t.id
                    if (current) name.setTextColor(ContextCompat.getColor(this@PlaylistEditActivity, R.color.accent))
                    play.visibility = View.VISIBLE
                    play.setImageResource(if (current && Engine.control.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
                    play.contentDescription = if (current && Engine.control.playWhenReady) "Pause" else "Play"
                    play.setOnClickListener { playTrack(t) }
                }
                is Pick.Info -> {
                    indent(row.depth)
                    icon.visibility = View.INVISIBLE
                    name.text = row.text
                    name.setTextColor(ContextCompat.getColor(this@PlaylistEditActivity, R.color.muted))
                    setMeta("")
                    val retry = row.retry
                    v.setOnClickListener { if (retry != null) loadFolder(retry) }
                }
            }
        }
    }

    // ---------- Order tab: drag and drop ----------

    private inner class OrderAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = selected.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_order, parent, false)) {}

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val t = selected[position]
            v.findViewById<TextView>(R.id.index).text = "${position + 1}."
            val name = v.findViewById<TextView>(R.id.name)
            name.text = t.title
            name.setTextColor(ContextCompat.getColor(this@PlaylistEditActivity, if (t.missing) R.color.muted else R.color.text))
            val meta = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" — ")
            v.findViewById<TextView>(R.id.meta).text = if (t.missing) "file is missing · ${t.folder}" else meta.ifBlank { t.folder }
            v.findViewById<TextView>(R.id.dur).text = fmtTime(t.duration)
            // touching the handle starts dragging at once; a long press anywhere on the row works too
            v.findViewById<ImageView>(R.id.handle).setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                false
            }
            v.findViewById<ImageButton>(R.id.remove).setOnClickListener {
                val i = holder.bindingAdapterPosition
                if (i == RecyclerView.NO_POSITION) return@setOnClickListener
                setIn(listOf(selected[i]), false)
            }
        }
    }

    private val dragCallback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false
            selected.add(b, selected.removeAt(a))
            orderAdapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled() = true

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            // lift the dragged row a little
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) viewHolder?.itemView?.alpha = 0.85f
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            viewHolder.itemView.alpha = 1f
            // renumber after the drop
            rv.post { orderAdapter.notifyDataSetChanged() }
        }
    }
}
