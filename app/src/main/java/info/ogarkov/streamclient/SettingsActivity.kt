package info.ogarkov.streamclient

import android.os.Bundle
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
