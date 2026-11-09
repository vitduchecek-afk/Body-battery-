package cz.vitd.bodybattery

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageButton
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
    private lateinit var error: TextView
    private lateinit var connect: Button
    private lateinit var refresh: ImageButton
    private lateinit var stats: View
    private lateinit var chartCard: View
    private lateinit var statWake: TextView
    private lateinit var statCharged: TextView
    private lateinit var statDrained: TextView
    private lateinit var sleepInfo: TextView
    private lateinit var timeline: TimelineView
    private lateinit var notes: TextView
    private var spinner: ObjectAnimator? = null
    private var pollJob: Job? = null
    private var busy = false
    private val fmt = DateTimeFormatter.ofPattern("HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ring = findViewById(R.id.ring)
        status = findViewById(R.id.status)
        error = findViewById(R.id.error)
        connect = findViewById(R.id.connect)
        refresh = findViewById(R.id.refresh)
        stats = findViewById(R.id.stats)
        chartCard = findViewById(R.id.chart_card)
        statWake = findViewById(R.id.stat_wake)
        statCharged = findViewById(R.id.stat_charged)
        statDrained = findViewById(R.id.stat_drained)
        sleepInfo = findViewById(R.id.sleep_info)
        timeline = findViewById(R.id.timeline)
        notes = findViewById(R.id.notes)

        connect.setOnClickListener { startLogin() }
        refresh.setOnClickListener { doRefresh() }
        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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

    private fun startLogin() {
        val client = Repo.client(this)
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
        if (busy || !Repo.client(this).isLoggedIn()) return
        busy = true
        render()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { Repo.refresh(this@MainActivity) }
            busy = false
            render()
        }
    }

    private fun setSpinning(on: Boolean) {
        if (on) {
            if (spinner?.isRunning == true) return
            spinner = ObjectAnimator.ofFloat(refresh, View.ROTATION, refresh.rotation % 360f, refresh.rotation % 360f + 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            spinner?.cancel()
            spinner = null
            refresh.animate().rotation(0f).setDuration(200).start()
        }
    }

    private fun render() {
        val client = Repo.client(this)
        val loggedIn = client.isLoggedIn()
        val pending = client.hasPendingLogin()
        val result = if (loggedIn) Repo.lastResult(this) else null
        val zone = ZoneId.systemDefault()
        fun t(e: Long) = Instant.ofEpochSecond(e).atZone(zone).format(fmt)

        ring.setImageBitmap(RingRenderer.render(760, result?.level, result?.let { RingRenderer.labelFor(it.level) }, glow = true))
        setSpinning(busy && loggedIn)
        refresh.visibility = if (loggedIn) View.VISIBLE else View.GONE
        refresh.isEnabled = !busy

        connect.visibility = if (loggedIn) View.GONE else View.VISIBLE
        connect.isEnabled = !busy
        connect.text = if (pending) "Začít přihlášení znovu" else "Připojit Koros"

        status.text = when {
            pending -> "Dokončete přihlášení v prohlížeči a vraťte se sem…"
            !loggedIn -> "Připojte svůj účet COROS"
            busy && result == null -> "Načítám data z Korosu…"
            result == null -> "Zatím žádná data"
            busy -> "Aktualizuji…"
            else -> "Aktualizováno " + t(result.computedAt)
        }

        val err = Repo.lastError(this)
        error.visibility = if (err != null) View.VISIBLE else View.GONE
        error.text = err

        val hasData = result != null
        stats.visibility = if (hasData) View.VISIBLE else View.GONE
        chartCard.visibility = if (hasData) View.VISIBLE else View.GONE
        notes.visibility = if (hasData && (result!!.notes.isNotEmpty() || !result.detailed)) View.VISIBLE else View.GONE
        if (result != null) {
            statWake.text = result.wakeLevel.toString()
            statCharged.text = "+${result.charged}"
            statDrained.text = "−${result.drained}"
            sleepInfo.text = "☾ ${t(result.sleepStart)}–${t(result.wakeTime)}"
            timeline.setData(result.points, result.sleepStart, result.wakeTime)
            notes.text = result.notes.joinToString("\n") { "• $it" }
        }
    }
}
