package cz.vitd.bodybattery

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
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
import cz.vitd.bodybattery.core.Vo2Estimator
import cz.vitd.bodybattery.core.Vo2Result
import cz.vitd.bodybattery.core.Vo2Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

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
    private lateinit var vo2Card: View
    private lateinit var vo2Value: TextView
    private lateinit var vo2Unit: TextView
    private lateinit var vo2Source: TextView
    private lateinit var vo2Detail: TextView
    private lateinit var notes: TextView
    private var spinner: ObjectAnimator? = null
    private var pollJob: Job? = null
    private var busy = false

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
        vo2Card = findViewById(R.id.vo2_card)
        vo2Value = findViewById(R.id.vo2_value)
        vo2Unit = findViewById(R.id.vo2_unit)
        vo2Source = findViewById(R.id.vo2_source)
        vo2Detail = findViewById(R.id.vo2_detail)
        notes = findViewById(R.id.notes)

        connect.setOnClickListener { startLogin() }
        refresh.setOnClickListener { doRefresh() }
        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        vo2Card.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.vo2_explain_title)
                .setMessage(Texts.vo2Explanation(this, Repo.vo2(this)))
                .setPositiveButton(R.string.action_ok, null)
                .show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Repo.version.collect { render() }
            }
        }
        Repo.schedule(this)
        DailyNotifier.schedule(this)
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
                Repo.setError(this@MainActivity, Texts.ERR_LOGIN_START, e.message)
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
                    LoginPoll.Failed(cz.vitd.bodybattery.core.Problem.SERVER, e.message)
                }
                when (r) {
                    LoginPoll.Pending -> { render(); delay(3000) }
                    LoginPoll.Done -> {
                        Repo.setError(this@MainActivity, null)
                        Toast.makeText(this@MainActivity, R.string.toast_connected, Toast.LENGTH_SHORT).show()
                        doRefresh()
                        return@launch
                    }
                    is LoginPoll.Failed -> {
                        Repo.setError(this@MainActivity, r.problem.name, r.detail)
                        return@launch
                    }
                }
            }
            client.logout()
            Repo.setError(this@MainActivity, Texts.ERR_LOGIN_TIMEOUT)
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

        ring.setImageBitmap(RingRenderer.render(760, result?.level, result?.let { Texts.levelLabel(this, it.level) }, glow = true))
        ring.contentDescription = result?.let { getString(R.string.ring_cd, it.level) } ?: getString(R.string.ring_cd_unknown)
        setSpinning(busy && loggedIn)
        refresh.visibility = if (loggedIn) View.VISIBLE else View.GONE
        refresh.isEnabled = !busy

        connect.visibility = if (loggedIn) View.GONE else View.VISIBLE
        connect.isEnabled = !busy
        connect.setText(if (pending) R.string.connect_restart else R.string.connect_button)

        status.text = when {
            pending -> getString(R.string.status_pending)
            !loggedIn -> getString(R.string.status_not_connected)
            busy && result == null -> getString(R.string.status_loading)
            result == null -> getString(R.string.status_no_data)
            busy -> getString(R.string.status_updating)
            else -> getString(R.string.status_updated, Texts.time(this, result.computedAt))
        }

        val err = Repo.lastError(this)
        error.visibility = if (err != null) View.VISIBLE else View.GONE
        error.text = err

        val hasData = result != null
        stats.visibility = if (hasData) View.VISIBLE else View.GONE
        chartCard.visibility = if (hasData) View.VISIBLE else View.GONE
        notes.visibility = if (hasData && result!!.notes.isNotEmpty()) View.VISIBLE else View.GONE
        if (result != null) {
            statWake.text = result.wakeLevel.toString()
            statCharged.text = getString(R.string.fmt_plus, result.charged)
            statDrained.text = getString(R.string.fmt_minus, result.drained)
            sleepInfo.text = "☾ " + getString(R.string.fmt_range, Texts.time(this, result.sleepStart), Texts.time(this, result.wakeTime))
            timeline.setData(result.points, result.sleepStart, result.wakeTime)
            notes.text = result.notes.joinToString("\n") { "• " + Texts.note(this, it) }
        }
        renderVo2(loggedIn)
    }

    private fun renderVo2(loggedIn: Boolean) {
        val v = if (loggedIn) Repo.vo2(this) else null
        vo2Card.visibility = if (v != null) View.VISIBLE else View.GONE
        when (v) {
            is Vo2Result.Value -> {
                vo2Value.text = Texts.vo2Value(this, v.vo2)
                vo2Unit.visibility = View.VISIBLE
                vo2Source.text = Texts.vo2Source(this, v)
                vo2Source.setTextColor(getColor(if (v.source == Vo2Source.COROS) R.color.fg else R.color.charge_low))
                val change = Vo2Estimator.monthChange(Repo.vo2History(this), LocalDate.now(), v.vo2)
                vo2Detail.text = change?.let { getString(R.string.vo2_change, getString(R.string.fmt_vo2_delta, it)) }
                    ?: getString(R.string.vo2_no_change)
            }
            is Vo2Result.Insufficient -> {
                vo2Value.text = "—"
                vo2Unit.visibility = View.GONE
                vo2Source.text = getString(R.string.vo2_no_data)
                vo2Source.setTextColor(getColor(R.color.muted))
                vo2Detail.text = Texts.vo2Missing(this, v.missing)
            }
            null -> Unit
        }
    }
}
