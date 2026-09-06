package com.cfddns.updater

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cfddns.updater.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val KEY_TOKEN = "cf_api_token"
        private const val KEY_ZONE_ID = "cf_zone_id"
        private const val KEY_RECORD_NAME = "cf_record_name"
        private const val KEY_LAST_IP = "last_ip"
        private const val KEY_LAST_TIME = "last_time"
        private const val EXTRA_AUTO_RUN = "auto_run"
        private const val SHORTCUT_ID = "update_now"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            this,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        refreshHostnameText()
        refreshStatusText()

        binding.updateButton.setOnClickListener { performUpdate() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        binding.pinShortcutButton.setOnClickListener { pinHomeScreenShortcut() }

        if (intent.hasExtra(EXTRA_AUTO_RUN)) {
            performUpdate()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_AUTO_RUN)) {
            performUpdate()
        }
    }

    private fun refreshHostnameText() {
        val recordName = prefs.getString(KEY_RECORD_NAME, null)
        binding.hostnameText.text = recordName ?: "Not configured yet"
    }

    private fun refreshStatusText() {
        val lastIp = prefs.getString(KEY_LAST_IP, null)
        val lastTime = prefs.getString(KEY_LAST_TIME, null)
        binding.statusText.text = if (lastIp != null && lastTime != null) {
            "Last set: $lastIp at $lastTime"
        } else {
            "Tap the gear icon to set up your Cloudflare zone, hostname, and API token"
        }
    }

    private fun showSettingsDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val recordInput = EditText(this).apply {
            hint = "Hostname (e.g. home.example.com)"
            setText(prefs.getString(KEY_RECORD_NAME, ""))
        }
        val zoneInput = EditText(this).apply {
            hint = "Cloudflare Zone ID"
            setText(prefs.getString(KEY_ZONE_ID, ""))
        }
        val tokenInput = EditText(this).apply {
            hint = "Cloudflare API Token"
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
            setText(prefs.getString(KEY_TOKEN, ""))
        }

        container.addView(recordInput)
        container.addView(zoneInput)
        container.addView(tokenInput)

        AlertDialog.Builder(this)
            .setTitle("Cloudflare Settings")
            .setMessage(
                "Find your Zone ID on the Cloudflare dashboard's zone overview page. " +
                "Create an API token with \"Zone > DNS > Edit\" permission scoped to that zone."
            )
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(KEY_RECORD_NAME, recordInput.text.toString().trim())
                    .putString(KEY_ZONE_ID, zoneInput.text.toString().trim())
                    .putString(KEY_TOKEN, tokenInput.text.toString().trim())
                    .apply()
                refreshHostnameText()
                refreshStatusText()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pinHomeScreenShortcut() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, "This launcher doesn't support pinned shortcuts", Toast.LENGTH_LONG).show()
            return
        }
        val shortcutIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_AUTO_RUN, "true")
        }
        val shortcut = ShortcutInfoCompat.Builder(this, SHORTCUT_ID)
            .setShortLabel(getString(R.string.shortcut_short_label))
            .setLongLabel(getString(R.string.shortcut_long_label))
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(shortcutIntent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
    }

    private fun performUpdate() {
        val token = prefs.getString(KEY_TOKEN, null)
        val zoneId = prefs.getString(KEY_ZONE_ID, null)
        val recordName = prefs.getString(KEY_RECORD_NAME, null)
        if (token.isNullOrBlank() || zoneId.isNullOrBlank() || recordName.isNullOrBlank()) {
            binding.statusText.text = "Set up your hostname, zone ID, and API token first (gear icon)"
            return
        }
        binding.updateButton.isEnabled = false
        binding.statusText.text = "Fetching public IP..."

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val ip = withContext(Dispatchers.IO) { fetchPublicIp() }
                binding.statusText.text = "Checking Cloudflare record..."
                val existing = withContext(Dispatchers.IO) { findRecord(token, zoneId, recordName) }

                if (existing != null && existing.second == ip) {
                    binding.statusText.text = "Already up to date ($ip)"
                    return@launch
                }

                if (existing == null) {
                    binding.statusText.text = "Creating record..."
                    withContext(Dispatchers.IO) { createRecord(token, zoneId, recordName, ip) }
                } else {
                    binding.statusText.text = "Updating record..."
                    withContext(Dispatchers.IO) { updateRecord(token, zoneId, existing.first, recordName, ip) }
                }

                val timestamp = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date())
                prefs.edit()
                    .putString(KEY_LAST_IP, ip)
                    .putString(KEY_LAST_TIME, timestamp)
                    .apply()
                binding.statusText.text = "Updated! $recordName -> $ip"
            } catch (e: Exception) {
                binding.statusText.text = "Error: ${e.message}"
            } finally {
                binding.updateButton.isEnabled = true
            }
        }
    }

    private fun fetchPublicIp(): String {
        val conn = URL("https://api.ipify.org").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        try {
            val code = conn.responseCode
            if (code != 200) throw Exception("IP lookup failed (HTTP $code)")
            return conn.inputStream.bufferedReader().readText().trim()
        } finally {
            conn.disconnect()
        }
    }

    /** Returns Pair(recordId, currentIp), or null if no A record exists yet. */
    private fun findRecord(token: String, zoneId: String, recordName: String): Pair<String, String>? {
        val url = URL("https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records?type=A&name=$recordName")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        try {
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                       else conn.errorStream.bufferedReader().readText()
            val json = JSONObject(body)
            if (!json.getBoolean("success")) throw Exception(parseCfError(body))
            val results = json.getJSONArray("result")
            if (results.length() == 0) return null
            val record = results.getJSONObject(0)
            return Pair(record.getString("id"), record.getString("content"))
        } finally {
            conn.disconnect()
        }
    }

    private fun createRecord(token: String, zoneId: String, recordName: String, ip: String) {
        sendCfRequest(URL("https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records"), "POST", token, buildRecordBody(recordName, ip))
    }

    private fun updateRecord(token: String, zoneId: String, recordId: String, recordName: String, ip: String) {
        sendCfRequest(URL("https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records/$recordId"), "PUT", token, buildRecordBody(recordName, ip))
    }

    private fun buildRecordBody(recordName: String, ip: String): JSONObject = JSONObject().apply {
        put("type", "A")
        put("name", recordName)
        put("content", ip)
        put("ttl", 1)
        put("proxied", false)
    }

    private fun sendCfRequest(url: URL, method: String, token: String, body: JSONObject) {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val respBody = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                           else conn.errorStream.bufferedReader().readText()
            val json = JSONObject(respBody)
            if (!json.getBoolean("success")) throw Exception(parseCfError(respBody))
        } finally {
            conn.disconnect()
        }
    }

    private fun parseCfError(body: String): String {
        return try {
            val errors: JSONArray = JSONObject(body).getJSONArray("errors")
            if (errors.length() > 0) errors.getJSONObject(0).getString("message") else "Unknown Cloudflare error"
        } catch (e: Exception) {
            body.take(200)
        }
    }
}
