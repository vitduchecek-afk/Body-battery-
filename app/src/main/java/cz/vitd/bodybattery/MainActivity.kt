package cz.vitd.bodybattery

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
    private lateinit var dials: ViewPager2
    private val dialAdapter = DialAdapter()
    private lateinit var dots: List<View>
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dials = findViewById(R.id.dials)
        dials.adapter = dialAdapter
        dots = listOf(findViewById(R.id.dot1), findViewById(R.id.dot2))
        dialAdapter.onClick = { page -> if (page == 1) showVo2Explanation() }
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
        status.gravity = Gravity.CENTER_HORIZONTAL
        dials.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = render()
        })
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

    private fun showVo2Explanation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.vo2_explain_title)
            .setMessage(Texts.vo2Explanation(this, Repo.vo2(this)))
            .setPositiveButton(R.string.action_ok, null)
            .show()
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

        val chargeDial = RingRenderer.render(760, result?.level, result?.let { Texts.levelLabel(this, it.level) }, glow = true)
        val chargeCd = result?.let { getString(R.string.ring_cd, it.level) } ?: getString(R.string.ring_cd_unknown)
        val vo2 = if (loggedIn) Repo.vo2(this) else null
        val vo2Dial = when (vo2) {
            is Vo2Result.Value -> RingRenderer.renderVo2(760, vo2.vo2, Texts.vo2Value(this, vo2.vo2), getString(R.string.vo2_title))
            else -> RingRenderer.renderVo2(760, null, "–", getString(R.string.vo2_title))
        }
        val vo2Cd = getString(R.string.vo2_title) + ": " + when (vo2) {
            is Vo2Result.Value -> Texts.vo2Value(this, vo2.vo2) + " " + getString(R.string.vo2_unit)
            else -> getString(R.string.vo2_no_data)
        }
        dialAdapter.submit(listOf(chargeDial to chargeCd, vo2Dial to vo2Cd))
        val page = dials.currentItem
        dots.forEachIndexed { i, d -> d.alpha = if (i == page) 1f else 0.3f }
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
        if (loggedIn && page == 1) status.text = vo2Status(vo2)

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
    }

    /** Line under the VO2 dial: source and change vs. last month, or what is missing. */
    private fun vo2Status(v: Vo2Result?): String = when (v) {
        is Vo2Result.Value -> {
            val change = Vo2Estimator.monthChange(Repo.vo2History(this), LocalDate.now(), v.vo2)
            val changeText = change?.let { getString(R.string.vo2_change, getString(R.string.fmt_vo2_delta, it)) }
                ?: getString(R.string.vo2_no_change)
            Texts.vo2Source(this, v) + " · " + getString(R.string.vo2_unit) + "\n" + changeText
        }
        is Vo2Result.Insufficient -> getString(R.string.vo2_no_data) + "\n" + Texts.vo2Missing(this, v.missing)
        null -> getString(R.string.status_no_data)
    }
}

/** Two swipeable dials (charge, VO2 max) rendered as bitmaps. */
private class DialAdapter : RecyclerView.Adapter<DialAdapter.Holder>() {
    private var pages: List<Pair<Bitmap, String>> = emptyList()
    var onClick: (Int) -> Unit = {}

    class Holder(val image: ImageView, root: FrameLayout) : RecyclerView.ViewHolder(root)

    fun submit(p: List<Pair<Bitmap, String>>) {
        val sizeChanged = p.size != pages.size
        pages = p
        if (sizeChanged) notifyDataSetChanged() else notifyItemRangeChanged(0, p.size, Unit)
    }

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val d = parent.resources.displayMetrics.density
        val root = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val img = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams((250 * d).toInt(), (250 * d).toInt(), Gravity.CENTER)
        }
        root.addView(img)
        return Holder(img, root)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.image.setImageBitmap(pages[position].first)
        holder.image.contentDescription = pages[position].second
        holder.image.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }
}
