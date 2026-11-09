package cz.vitd.bodybattery

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class SettingsActivity : ComponentActivity() {
    private lateinit var accountState: TextView
    private lateinit var disconnect: Button
    private lateinit var diag: TextView
    private lateinit var diagToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        accountState = findViewById(R.id.account_state)
        disconnect = findViewById(R.id.disconnect)
        diag = findViewById(R.id.diag)
        diagToggle = findViewById(R.id.diag_toggle)

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.version).text = "Body Battery v" + Repo.appVersion(this)

        disconnect.setOnClickListener {
            Repo.client(this).logout()
            Repo.notifyChanged()
            BatteryWidget.updateAll(this)
            Toast.makeText(this, "Koros odpojen", Toast.LENGTH_SHORT).show()
            finish()
        }
        diagToggle.setOnClickListener {
            val show = diag.visibility != View.VISIBLE
            diag.visibility = if (show) View.VISIBLE else View.GONE
            diagToggle.text = if (show) "Skrýt" else "Zobrazit"
        }
        findViewById<Button>(R.id.diag_copy).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Body Battery diagnostika", Repo.diagnostics(this)))
            Toast.makeText(this, "Zkopírováno", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val loggedIn = Repo.client(this).isLoggedIn()
        accountState.text = if (loggedIn) "Připojeno – data se obnovují zhruba každých 15 minut" else "Nepřipojeno – připojte se na hlavní obrazovce"
        disconnect.visibility = if (loggedIn) View.VISIBLE else View.GONE
        diag.text = Repo.diagnostics(this)
    }
}
