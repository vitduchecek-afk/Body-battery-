package cz.vitd.bodybattery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cz.vitd.bodybattery.core.LoginPoll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var ring: ImageView
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var timeline: TimelineView
    private lateinit var connect: Button
    private lateinit var refresh: Button
    private lateinit var diagToggle: Button
    private lateinit var diagCopy: Button
    private lateinit var diag: TextView
    private var pollJob: Job? = null
    private var busy = false
    private val fmt = DateTimeFormatter.ofPattern("HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ring = findViewById(R.id.ring)
        status = findViewById(R.id.status)
        details = findViewById(R.id.details)
        timeline = findViewById(R.id.timeline)
        connect = findViewById(R.id.connect)
        refresh = findViewById(R.id.refresh)
        diagToggle = findViewById(R.id.diag_toggle)
        diagCopy = findViewById(R.id.diag_copy)
        diag = findViewById(R.id.diag)

        connect.setOnClickListener { onConnectClicked() }
        refresh.setOnClickListener { doRefresh() }
        diagToggle.setOnClickListener {
            val show = diag.visibility != View.VISIBLE
            diag.visibility = if (show) View.VISIBLE else View.GONE
            diagCopy.visibility = diag.visibility
            diagToggle.text = if (show) "Skrýt diagnostiku" else "Diagnostika (co posílá Koros)"
        }
        diagCopy.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Body Battery diagnostika", Repo.diagnostics(this)))
            Toast.makeText(this, "Zkopírováno", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Repo.version.collect { render() }
            }
        }
        Repo.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        val client = Repo.client(this)
        if (client.hasPendingLogin()) startPolling()
        else if (client.isLoggedIn()) {
            val last = Repo.lastResult(this)
            if (last == null || System.currentTimeMillis() / 1000 - last.computedAt > 10 * 60) doRefresh()
        }
        render()
    }

    private fun onConnectClicked() {
        val client = Repo.client(this)
        if (client.isLoggedIn()) {
            client.logout()
            Repo.notifyChanged()
            BatteryWidget.updateAll(this)
            return
        }
        busy = true
        render()
        lifecycleScope.launch {
            try {
                val url = withContext(Dispatchers.IO) { client.startLogin() }
                Repo.setError(this@MainActivity, null)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                startPolling()
            } catch (e: Exception) {
                Repo.setError(this@MainActivity, "Nepodařilo se zahájit přihlášení: ${e.message}")
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            val client = Repo.client(this@MainActivity)
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000
            while (System.currentTimeMillis() < deadline) {
                val r = try {
                    withContext(Dispatchers.IO) { client.pollLogin() }
                } catch (e: Exception) {
                    LoginPoll.Failed(e.message ?: e.toString())
                }
                when (r) {
                    LoginPoll.Pending -> { render(); delay(3000) }
                    LoginPoll.Done -> {
                        Repo.setError(this@MainActivity, null)
                        Toast.makeText(this@MainActivity, "Koros připojen", Toast.LENGTH_SHORT).show()
                        doRefresh()
                        return@launch
                    }
                    is LoginPoll.Failed -> {
                        Repo.setError(this@MainActivity, r.reason)
                        return@launch
                    }
                }
            }
            client.logout()
            Repo.setError(this@MainActivity, "Přihlášení nebylo dokončeno včas, zkuste to znovu")
        }
    }

    private fun doRefresh() {
        if (busy) return
        busy = true
        render()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { Repo.refresh(this@MainActivity) }
            busy = false
            render()
        }
    }

    private fun render() {
        val client = Repo.client(this)
        val loggedIn = client.isLoggedIn()
        val pending = client.hasPendingLogin()
        val result = Repo.lastResult(this)
        val zone = ZoneId.systemDefault()

        ring.setImageBitmap(RingRenderer.render(720, if (loggedIn) result?.level else null, null))
        connect.text = when {
            loggedIn -> "Odpojit Koros"
            pending -> "Začít přihlášení znovu"
            else -> "Připojit Koros"
        }
        refresh.isEnabled = loggedIn && !busy
        refresh.text = if (busy && loggedIn) "Načítám…" else "Obnovit"

        status.text = when {
            pending -> "Dokončete přihlášení v prohlížeči a vraťte se sem…"
            !loggedIn -> "Připojte svůj účet COROS"
            result == null -> if (busy) "Načítám data z Korosu…" else "Zatím žádná data"
            else -> "Aktualizováno " + Instant.ofEpochSecond(result.computedAt).atZone(zone).format(fmt)
        }
        val err = Repo.lastError(this)
        details.text = buildString {
            if (err != null) append("⚠ ").append(err).append("\n\n")
            if (result != null && loggedIn) {
                append("Ráno po probuzení: ${result.wakeLevel}\n")
                append("Nabito: +${result.charged}   Vybito: −${result.drained}\n")
                append("Spánek: ${Instant.ofEpochSecond(result.sleepStart).atZone(zone).format(fmt)}–")
                append(Instant.ofEpochSecond(result.wakeTime).atZone(zone).format(fmt))
                if (!result.detailed) append("\n(odhad – málo průběžných dat)")
                result.notes.forEach { append("\n• ").append(it) }
            }
        }
        if (result != null && loggedIn) {
            timeline.visibility = View.VISIBLE
            timeline.setData(result.points, result.wakeTime)
        } else {
            timeline.visibility = View.GONE
        }
        diag.text = Repo.diagnostics(this)
    }
}
