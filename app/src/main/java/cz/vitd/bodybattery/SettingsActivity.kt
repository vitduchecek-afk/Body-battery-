package cz.vitd.bodybattery

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class SettingsActivity : ComponentActivity() {
    private lateinit var accountState: TextView
    private lateinit var disconnect: Button
    private lateinit var diag: TextView
    private lateinit var diagToggle: Button
    private lateinit var notifSwitch: Switch
    private lateinit var notifTime: Button
    private var ignoreSwitch = false

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableNotification() else {
            Toast.makeText(this, R.string.notif_permission_denied, Toast.LENGTH_LONG).show()
            setSwitch(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        accountState = findViewById(R.id.account_state)
        disconnect = findViewById(R.id.disconnect)
        diag = findViewById(R.id.diag)
        diagToggle = findViewById(R.id.diag_toggle)
        notifSwitch = findViewById(R.id.notif_switch)
        notifTime = findViewById(R.id.notif_time)

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.version).text = getString(R.string.about_version, Repo.appVersion(this))

        disconnect.setOnClickListener {
            Repo.client(this).logout()
            Repo.notifyChanged()
            BatteryWidget.updateAll(this)
            Toast.makeText(this, R.string.toast_disconnected, Toast.LENGTH_SHORT).show()
            finish()
        }
        diagToggle.setOnClickListener {
            val show = diag.visibility != View.VISIBLE
            diag.visibility = if (show) View.VISIBLE else View.GONE
            diagToggle.setText(if (show) R.string.diag_hide else R.string.diag_show)
        }
        findViewById<Button>(R.id.diag_copy).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.settings_diag_title), Diagnostics.text(this)))
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }

        notifSwitch.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            if (checked) askAndEnable() else {
                Repo.setNotificationEnabled(this, false)
                DailyNotifier.schedule(this)
            }
        }
        notifTime.setOnClickListener {
            val (h, m) = Repo.notificationTime(this)
            TimePickerDialog(this, { _, hour, minute ->
                Repo.setNotificationTime(this, hour, minute)
                DailyNotifier.schedule(this)
                renderNotification()
            }, h, m, DateFormat.is24HourFormat(this)).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val loggedIn = Repo.client(this).isLoggedIn()
        accountState.setText(if (loggedIn) R.string.settings_connected else R.string.settings_not_connected)
        disconnect.visibility = if (loggedIn) View.VISIBLE else View.GONE
        diag.text = Diagnostics.text(this)
        // permission may have been revoked in system settings meanwhile
        if (Repo.notificationEnabled(this) && !DailyNotifier.canPost(this)) {
            Repo.setNotificationEnabled(this, false)
            DailyNotifier.schedule(this)
        }
        renderNotification()
    }

    private fun askAndEnable() {
        if (DailyNotifier.canPost(this) || Build.VERSION.SDK_INT < 33) {
            enableNotification()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.notif_permission_title)
            .setMessage(R.string.notif_permission_text)
            .setPositiveButton(R.string.action_continue) { _, _ -> requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            .setNegativeButton(R.string.action_cancel) { _, _ -> setSwitch(false) }
            .setOnCancelListener { setSwitch(false) }
            .show()
    }

    private fun enableNotification() {
        Repo.setNotificationEnabled(this, true)
        DailyNotifier.ensureChannel(this)
        DailyNotifier.schedule(this)
        renderNotification()
    }

    private fun setSwitch(on: Boolean) {
        ignoreSwitch = true
        notifSwitch.isChecked = on
        ignoreSwitch = false
        renderNotification()
    }

    private fun renderNotification() {
        val on = Repo.notificationEnabled(this)
        if (notifSwitch.isChecked != on) setSwitch(on)
        val (h, m) = Repo.notificationTime(this)
        notifTime.text = getString(R.string.settings_notif_time, Texts.clock(this, h, m))
        notifTime.isEnabled = on
        notifTime.alpha = if (on) 1f else 0.5f
    }
}
