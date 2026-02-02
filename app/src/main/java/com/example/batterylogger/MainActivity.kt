package com.example.batterylogger

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoCdma
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.FileReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnToggle: Button
    private lateinit var etInterval: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvHint = findViewById(R.id.tvHint)
        btnToggle = findViewById(R.id.btnToggle)
        etInterval = findViewById(R.id.etInterval)

        updateUiState()

        btnToggle.setOnClickListener {
            if (LoggingService.isRunning) {
                stopLoggingService()
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun updateUiState() {
        if (LoggingService.isRunning) {
            tvStatus.text = "Recording..."
            tvStatus.setTextColor(0xFFF44336.toInt()) // Red
            btnToggle.text = "STOP LOGGING"
            btnToggle.setBackgroundColor(0xFFF44336.toInt()) // Red
            tvHint.text = "Service is running in background. Check notification for details.\nLog files are saved in Downloads folder."
        } else {
            tvStatus.text = "Ready"
            tvStatus.setTextColor(0xFF4CAF50.toInt()) // Green
            btnToggle.text = "START LOGGING"
            btnToggle.setBackgroundColor(0xFF2196F3.toInt()) // Blue
            tvHint.text = "CSV files will be saved to the Downloads folder."
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkPermissionsAndStart() {
        if (!hasUsageStatsPermission()) {
            tvStatus.text = "Usage Access Permission Required"
            tvHint.text = "Please grant 'Usage Access' permission in Settings to monitor app usage statistics."
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                tvHint.text = "Cannot open Settings. Please manually enable Usage Access permission for BatteryLogger."
            }
            return
        }

        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val needed = permissions.filter { 
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
            tvStatus.text = "Permissions Required"
            tvHint.text = "Please grant all requested permissions to start logging."
        } else {
            startLoggingService()
        }
    }

    private fun startLoggingService() {
        val intervalSec = (etInterval.text.toString().toLongOrNull() ?: 5L).coerceAtLeast(1L)
        val serviceIntent = Intent(this, LoggingService::class.java).apply {
            putExtra("interval", intervalSec)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        updateUiState()
    }

    private fun stopLoggingService() {
        val serviceIntent = Intent(this, LoggingService::class.java).apply {
            action = LoggingService.ACTION_STOP
        }
        startService(serviceIntent)
        tvStatus.text = "Stopping..."
        tvStatus.setTextColor(0xFFFF9800.toInt()) // Orange
        tvHint.text = "Service is shutting down. Please wait..."
        // UI will update in onResume after service stops
    }
}
