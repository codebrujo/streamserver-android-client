package info.ogarkov.streamclient

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applySystemBarPadding(findViewById(R.id.root))

        val local = findViewById<EditText>(R.id.local)
        val global = findViewById<EditText>(R.id.global)
        val password = findViewById<EditText>(R.id.password)
        val result = findViewById<TextView>(R.id.result)
        val save = findViewById<Button>(R.id.save)

        local.setText(Prefs.localUrl)
        global.setText(Prefs.globalUrl)
        password.setText(Prefs.password)

        save.setOnClickListener {
            val l = local.text.toString().trim().trimEnd('/')
            val g = global.text.toString().trim().trimEnd('/')
            val problems = mutableListOf<String>()
            if (l.isNotEmpty() && !l.startsWith("http://")) problems += "the local address should start with http://"
            if (g.isNotEmpty() && !g.startsWith("https://")) problems += "the domain address should start with https://"
            if (password.text.isEmpty()) problems += "the password is empty"
            Prefs.localUrl = l
            Prefs.globalUrl = g
            Prefs.password = password.text.toString()

            save.isEnabled = false
            result.setTextColor(ContextCompat.getColor(this, R.color.muted))
            result.text = "Checking…"
            lifecycleScope.launch {
                // check both addresses and the password, then let Conn pick one
                val lines = mutableListOf<String>()
                for ((label, url) in listOf("Local" to l, "Domain" to g)) {
                    if (url.isEmpty()) continue
                    lines += "$label: " + withContext(Dispatchers.IO) { check(url) }
                }
                Conn.select()
                lines += problems.map { "Note: $it" }
                save.isEnabled = true
                result.text = lines.joinToString("\n")
                if (Conn.base != null && problems.isEmpty()) {
                    result.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.local))
                    finish()
                } else {
                    result.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.offline))
                }
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
