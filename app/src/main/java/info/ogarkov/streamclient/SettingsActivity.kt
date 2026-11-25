package info.ogarkov.streamclient

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var local: EditText
    private lateinit var global: EditText
    private lateinit var password: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBarPadding(findViewById(R.id.root))

        local = findViewById(R.id.local)
        global = findViewById(R.id.global)
        password = findViewById(R.id.password)
        val result = findViewById<TextView>(R.id.result)
        val save = findViewById<Button>(R.id.save)

        local.setText(Prefs.localUrl)
        global.setText(Prefs.globalUrl)
        password.setText(Prefs.password)

        // back arrow and the system Back both keep what was typed (see onPause)
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }

        setupLocalFolders()

        save.setOnClickListener {
            store()
            val l = Prefs.localUrl
            val g = Prefs.globalUrl
            val notes = mutableListOf<String>()
            if (l.isNotEmpty() && !l.startsWith("http://")) notes += "the local address usually starts with http://"
            if (g.isNotEmpty() && !g.startsWith("https://")) notes += "the domain address usually starts with https://"
            if (Prefs.password.isEmpty()) notes += "the password is empty"

            save.isEnabled = false
            result.setTextColor(ContextCompat.getColor(this, R.color.muted))
            result.text = "Checking…"
            lifecycleScope.launch {
                val lines = mutableListOf<String>()
                for ((label, url) in listOf("Local" to l, "Domain" to g)) {
                    if (url.isEmpty()) continue
                    lines += "$label: " + withContext(Dispatchers.IO) { check(url) }
                }
                Conn.select()
                lines += notes.map { "Note: $it" }
                save.isEnabled = true
                result.text = lines.joinToString("\n")
                if (Conn.base != null) {
                    // connected through one of the addresses: back to the library
                    Toast.makeText(this@SettingsActivity, lines.joinToString("\n"), Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    result.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.offline))
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        store()
    }

    private fun store() {
        Prefs.localUrl = local.text.toString().trim().trimEnd('/')
        Prefs.globalUrl = global.text.toString().trim().trimEnd('/')
        Prefs.password = password.text.toString()
    }

    // ---------- local media folders ----------

    private var pickingCat = "a"
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            LocalMedia.setFolder(pickingCat, uri)
        } catch (e: Exception) {
            Toast.makeText(this, "No access to this folder: ${e.message ?: e}", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        showLocalFolders()
        scanLocal() // a new folder is scanned right away
    }

    private fun setupLocalFolders() {
        findViewById<Button>(R.id.scan).setOnClickListener { scanLocal() }
        showLocalFolders()
        showScanResult()
    }

    private fun showLocalFolders() {
        val box = findViewById<LinearLayout>(R.id.localFolders)
        box.removeAllViews()
        val dp = resources.displayMetrics.density
        for ((cat, label) in LocalMedia.CATEGORIES) {
            val uri = LocalMedia.folderUri(cat)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, (12 * dp).toInt(), 0, 0)

            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            val title = TextView(this)
            title.text = label
            title.setTextColor(ContextCompat.getColor(this, R.color.text))
            title.textSize = 15f
            val path = TextView(this)
            path.text = LocalMedia.displayPath(uri)
            path.setTextColor(ContextCompat.getColor(this, R.color.muted))
            path.textSize = 12f
            texts.addView(title)
            texts.addView(path)
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val choose = Button(this, null, android.R.attr.borderlessButtonStyle)
            choose.text = if (uri == null) "Choose" else "Change"
            choose.setOnClickListener {
                pickingCat = cat
                pickFolder.launch(uri?.let { u -> Uri.parse(u) })
            }
            row.addView(choose)
            if (uri != null) {
                val remove = Button(this, null, android.R.attr.borderlessButtonStyle)
                remove.text = "Remove"
                remove.setOnClickListener {
                    LocalMedia.setFolder(cat, null)
                    showLocalFolders()
                    showScanResult()
                }
                row.addView(remove)
            }
            box.addView(row)
        }
    }

    private fun showScanResult() {
        val t = findViewById<TextView>(R.id.scanResult)
        t.text = when {
            LocalMedia.scanning -> "Scanning…"
            !LocalMedia.configured() -> ""
            LocalMedia.lastScan == 0L -> "Not scanned yet"
            else -> {
                val counts = LocalMedia.CATEGORIES.mapNotNull { (cat, label) ->
                    if (LocalMedia.folderUri(cat) == null) null else "$label: ${LocalMedia.items.count { it.cat == cat }}"
                }
                val at = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(LocalMedia.lastScan))
                counts.joinToString(" · ") + "\nLast scan: $at"
            }
        }
    }

    private fun scanLocal() {
        if (!LocalMedia.configured()) {
            Toast.makeText(this, "Choose a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        val button = findViewById<Button>(R.id.scan)
        button.isEnabled = false
        findViewById<TextView>(R.id.scanResult).text = "Scanning…"
        lifecycleScope.launch {
            try {
                LocalMedia.scan()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Scan failed: ${e.message ?: e}", Toast.LENGTH_LONG).show()
            } finally {
                button.isEnabled = true
                showScanResult()
            }
        }
    }

    private fun check(url: String): String {
        if (!Api.probe(url)) return "not reachable"
        return try {
            Api.login(url)
            "OK"
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }
}
