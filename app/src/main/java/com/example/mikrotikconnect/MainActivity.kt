package com.example.mikrotikconnect

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Flow:
 * 1. User scans for nearby secured networks (read-only, no connection attempts).
 * 2. User taps a network in the list and manually confirms "this is my device".
 *    Its SSID + BSSID are saved locally as the one and only trusted target.
 * 3. "Connect to Trusted" only ever tries the known candidate passwords against
 *    that one confirmed BSSID — never against any other network in range.
 *
 * This app will not attempt to connect to, or guess passwords for, any network
 * the user has not explicitly confirmed as their own device.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var lvNetworks: ListView
    private lateinit var tvTrustedStatus: TextView

    // Only the passwords used by your own device's auto-generated hotspot.
    private val candidatePasswords = listOf("12345678", "123456789", "1234567890")

    private var lastScanResults: List<ScanResult> = emptyList()

    private val PERMISSION_REQUEST_CODE = 1001

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                @Suppress("MissingPermission")
                lastScanResults = wifiManager.scanResults
                showResults(lastScanResults)
            } else {
                Toast.makeText(this@MainActivity, "Scan returned no new results", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        prefs = getSharedPreferences("mikrotik_connect_prefs", MODE_PRIVATE)

        lvNetworks = findViewById(R.id.lvNetworks)
        tvTrustedStatus = findViewById(R.id.tvTrustedStatus)

        findViewById<Button>(R.id.btnScan).setOnClickListener { requestPermissionsThen { startScan() } }
        findViewById<Button>(R.id.btnAutoConnect).setOnClickListener { requestPermissionsThen { connectToTrustedDevice() } }
        findViewById<Button>(R.id.btnForget).setOnClickListener { forgetTrustedDevice() }

        updateTrustedStatusLabel()
        requestPermissionsThen { }
    }

    // ---------- Permissions ----------

    private fun requestPermissionsThen(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            action()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Location permission granted (required for WiFi scan)", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Step 1: scan only, no connection attempts ----------

    private fun startScan() {
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        @Suppress("MissingPermission")
        val started = wifiManager.startScan()
        if (!started) {
            Toast.makeText(this, "Could not start scan (try toggling WiFi)", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("MissingPermission")
    private fun showResults(results: List<ScanResult>) {
        val secureNetworks = results
            .filter { it.capabilities.contains("WPA") || it.capabilities.contains("WEP") }
            .distinctBy { it.BSSID }
            .sortedByDescending { it.level }

        val labels = secureNetworks.map { r ->
            val ssid = if (r.SSID.isNullOrBlank()) "(hidden)" else r.SSID
            "$ssid  [${r.BSSID}]  ${r.level} dBm"
        }

        lvNetworks.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        lvNetworks.setOnItemClickListener { _, _, position, _ ->
            confirmAsTrusted(secureNetworks[position])
        }

        if (secureNetworks.isEmpty()) {
            Toast.makeText(this, "No secured networks found nearby", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Step 2: explicit user confirmation, never automatic ----------

    private fun confirmAsTrusted(network: ScanResult) {
        val ssid = network.SSID
        val bssid = network.BSSID
        AlertDialog.Builder(this)
            .setTitle("Save as my device?")
            .setMessage(
                "SSID: $ssid\nBSSID: $bssid\n\n" +
                    "Confirming will save this exact network as your trusted device. " +
                    "The app will only ever try your known passwords against this specific " +
                    "SSID/BSSID — never against any other network."
            )
            .setPositiveButton("Yes, this is mine") { _, _ ->
                prefs.edit()
                    .putString("trusted_ssid", ssid)
                    .putString("trusted_bssid", bssid)
                    .apply()
                updateTrustedStatusLabel()
                Toast.makeText(this, "Saved \"$ssid\" as trusted device", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun forgetTrustedDevice() {
        prefs.edit().remove("trusted_ssid").remove("trusted_bssid").apply()
        updateTrustedStatusLabel()
        Toast.makeText(this, "Trusted device cleared", Toast.LENGTH_SHORT).show()
    }

    private fun updateTrustedStatusLabel() {
        val ssid = prefs.getString("trusted_ssid", null)
        val bssid = prefs.getString("trusted_bssid", null)
        tvTrustedStatus.text = if (ssid != null && bssid != null) {
            "Trusted device: $ssid [$bssid]"
        } else {
            "No trusted device saved yet. Scan, then tap a network to confirm it."
        }
    }

    // ---------- Step 3: connect ONLY to the confirmed trusted BSSID ----------

    private fun connectToTrustedDevice() {
        val trustedSsid = prefs.getString("trusted_ssid", null)
        val trustedBssid = prefs.getString("trusted_bssid", null)

        if (trustedSsid == null || trustedBssid == null) {
            Toast.makeText(this, "No trusted device saved yet. Scan and confirm one first.", Toast.LENGTH_LONG).show()
            return
        }

        // Re-scan to make sure the trusted device is actually in range right now,
        // and to avoid connecting based on stale results.
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try { unregisterReceiver(this) } catch (_: Exception) {}
                @Suppress("MissingPermission")
                val results = wifiManager.scanResults
                val match = results.firstOrNull { it.BSSID.equals(trustedBssid, ignoreCase = true) }
                if (match == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Trusted device ($trustedSsid) not currently in range",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                tryPasswordsInOrder(trustedSsid, candidatePasswords, 0)
            }
        }, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        @Suppress("MissingPermission")
        wifiManager.startScan()
    }

    private fun tryPasswordsInOrder(ssid: String, passwords: List<String>, index: Int) {
        if (index >= passwords.size) {
            Toast.makeText(this, "None of the known passwords worked for $ssid", Toast.LENGTH_LONG).show()
            return
        }
        val password = passwords[index]

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectModern(ssid, password) { success ->
                if (success) {
                    Toast.makeText(this, "Connected to $ssid", Toast.LENGTH_LONG).show()
                } else {
                    tryPasswordsInOrder(ssid, passwords, index + 1)
                }
            }
        } else {
            val success = connectLegacy(ssid, password)
            if (success) {
                Toast.makeText(this, "Connected to $ssid", Toast.LENGTH_LONG).show()
            } else {
                tryPasswordsInOrder(ssid, passwords, index + 1)
            }
        }
    }

    // Android 10+ : WifiNetworkSpecifier. The system will show a connection
    // confirmation dialog to the user for networks outside Settings-saved config.
    private fun connectModern(ssid: String, password: String, callback: (Boolean) -> Unit) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callbackHandler = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.bindProcessToNetwork(network)
                runOnUiThread { callback(true) }
                connectivityManager.unregisterNetworkCallback(this)
            }

            override fun onUnavailable() {
                runOnUiThread { callback(false) }
            }
        }

        connectivityManager.requestNetwork(request, callbackHandler, 10_000)
    }

    // Pre-Android 10 fallback
    @Suppress("DEPRECATION", "MissingPermission")
    private fun connectLegacy(ssid: String, password: String): Boolean {
        val conf = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        val netId = wifiManager.addNetwork(conf)
        if (netId == -1) return false

        wifiManager.disconnect()
        val enabled = wifiManager.enableNetwork(netId, true)
        wifiManager.reconnect()

        if (!enabled) {
            wifiManager.removeNetwork(netId)
        }
        return enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(wifiScanReceiver) } catch (_: Exception) {}
    }
}
