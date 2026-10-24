package com.blank.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.blank.app.R
import com.blank.app.dns.BlocklistMatcher
import com.blank.app.service.BlankVpnService
import com.blank.app.util.StatsManager
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var viewStatusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: AppCompatButton
    private lateinit var tvBlockedCount: TextView
    private lateinit var tvTotalCount: TextView

    private val numberFormat = NumberFormat.getNumberInstance(Locale.US)

    // Launcher for Android 13+ POST_NOTIFICATIONS permission
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Proceed regardless of outcome — notification is informational only
    }

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        StatsManager.init(applicationContext)
        if (!BlocklistMatcher.isLoaded) {
            BlocklistMatcher.load(applicationContext)
        }

        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        tvBlockedCount = findViewById(R.id.tvBlockedCount)
        tvTotalCount = findViewById(R.id.tvTotalCount)

        btnToggle.setOnClickListener {
            if (BlankVpnService.isRunning.get()) {
                stopVpnService()
            } else {
                requestNotificationPermissionIfNeeded()
                prepareAndStartVpn()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BlankVpnService.stateChangeListener = {
            runOnUiThread { updateUi() }
        }
        StatsManager.onStatsUpdated = {
            runOnUiThread { updateStatsUi() }
        }
        updateUi()
        updateStatsUi()
    }

    override fun onPause() {
        super.onPause()
        BlankVpnService.stateChangeListener = null
        StatsManager.onStatsUpdated = null
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun prepareAndStartVpn() {
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            vpnConsentLauncher.launch(consentIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, BlankVpnService::class.java).apply {
            action = BlankVpnService.ACTION_START
        }
        // Android 8+ requires startForegroundService for long-running services
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUi()
    }

    private fun stopVpnService() {
        val intent = Intent(this, BlankVpnService::class.java).apply {
            action = BlankVpnService.ACTION_STOP
        }
        startService(intent)
        updateUi()
    }

    private fun updateUi() {
        val isRunning = BlankVpnService.isRunning.get()
        if (isRunning) {
            viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_active)
            tvStatus.setText(R.string.status_active)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            btnToggle.setText(R.string.btn_stop)
            btnToggle.setBackgroundResource(R.drawable.bg_button_stop)
            btnToggle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_inactive)
            tvStatus.setText(R.string.status_inactive)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            btnToggle.setText(R.string.btn_start)
            btnToggle.setBackgroundResource(R.drawable.bg_button_start)
            btnToggle.setTextColor(ContextCompat.getColor(this, R.color.bg_dark))
        }
    }

    private fun updateStatsUi() {
        val blocked = StatsManager.blockedAds.get()
        val total = StatsManager.totalQueries.get()
        tvBlockedCount.text = numberFormat.format(blocked)
        tvTotalCount.text = numberFormat.format(total)
    }
}
