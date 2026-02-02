package com.example.batterylogger

import android.Manifest
import android.app.*
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.FileReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class LoggingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private var writer: OutputStreamWriter? = null
    private var startElapsed: Long = 0L

    companion object {
        const val CHANNEL_ID = "BatteryLoggerChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "STOP_LOGGING"
        var isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent?.action == ACTION_STOP) {
                stopLogging()
                stopSelf()
                return START_NOT_STICKY
            }

            if (isRunning) {
                // Determine if we need to restart/update or just ignore
                // For now, ignore if already running
                return START_STICKY 
            }
            isRunning = true

            createNotificationChannel() // Ensure channel exists first

            // Acquire WakeLock
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryLogger::LogWakeLock")
            wakeLock?.acquire(300 * 60 * 1000L /* 5 hours max */)

            // Start Foreground immediately
            val notification = createNotification("Initializing data logger...")
            
            // Android 14 specific: dataSync requires type declaration in startForeground if targetSdk >= 34
            if (Build.VERSION.SDK_INT >= 29) {
                 if (Build.VERSION.SDK_INT >= 34) {
                     startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                 } else {
                     startForeground(NOTIFICATION_ID, notification)
                 }
            } else {
                 startForeground(NOTIFICATION_ID, notification)
            }

            val intervalSec = intent?.getLongExtra("interval", 5L) ?: 5L
            startLogging(intervalSec)
        } catch (e: Exception) {
            e.printStackTrace()
            isRunning = false
            // If we can't start foreground, we can't run.
            stopSelf()
        }

        return START_STICKY
    }

    private fun stopLogging() {
        isRunning = false
        job?.cancel()
        job = null
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }


    private fun createNotification(text: String): Notification {
        val stopIntent = Intent(this, LoggingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Logger Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Logger Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startLogging(intervalSec: Long) {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "battery_log_$ts.csv"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        
        if (uri != null) {
            try {
                val os = resolver.openOutputStream(uri)
                writer = OutputStreamWriter(os)
                writer!!.write(
                    listOf(
                        "ts_iso","t_sec",
                        "level_pct","temp_C","voltage_mV",
                        "status","plugged",
                        "current_now_uA","current_avg_uA",
                        "charge_counter_uAh","energy_counter",
                        "power_W",
                        "wifi_rssi_dbm", "mobile_dbm",
                        "mobile_rx_bytes", "mobile_tx_bytes",
                        "wifi_rx_bytes", "wifi_tx_bytes",
                        "cpu_freq_ghz", "foreground_app_count",
                        "screen_brightness", "mem_usage_pct"
                    ).joinToString(",") + "\n"
                )
                writer!!.flush()
            } catch (e: Exception) {
                // Log error
            }
        }

        startElapsed = SystemClock.elapsedRealtime()

        job = CoroutineScope(Dispatchers.IO).launch {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            while (isActive) {
                try {
                    val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                    val tSec = (SystemClock.elapsedRealtime() - startElapsed) / 1000.0

                    // 1. Battery
                    val batIntent = registerReceiver(null, filter)
                    val level = batIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                    val levelPct = if (level >= 0 && scale > 0) (level * 100.0 / scale) else Double.NaN

                    val tempTenth = batIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
                    val tempC = if (tempTenth != Int.MIN_VALUE) tempTenth / 10.0 else Double.NaN

                    val voltage = batIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) ?: Int.MIN_VALUE
                    val voltageMv = if (voltage != Int.MIN_VALUE) voltage else null
                    val status = batIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val plugged = batIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

                    val curNow = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    val curAvg = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                    val chargeCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    val energyCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)

                    var currentUa = curNow
                    if (abs(curNow) < 10000 && curNow != 0L && curNow != Long.MIN_VALUE) {
                        currentUa = curNow * 1000
                    }
                    
                    var powerW = ""
                    if (voltageMv != null && curNow != Long.MIN_VALUE) {
                        val v = voltageMv / 1000.0
                        val iA = abs(currentUa) / 1e6
                        powerW = (v * iA).toString()
                    }

                    // 2. Network Signal
                    var wifiRssi = ""
                    try {
                        wifiRssi = wifiManager.connectionInfo.rssi.toString()
                    } catch (_: Exception) {}

                    var mobileDbm = ""
                    try {
                        if (ActivityCompat.checkSelfPermission(this@LoggingService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            val cellInfos = telephonyManager.allCellInfo
                            if (cellInfos != null) {
                                for (info in cellInfos) {
                                    if (info.isRegistered) {
                                        val dbm = when (info) {
                                            is CellInfoLte -> info.cellSignalStrength.dbm
                                            is CellInfoNr -> (info as? CellInfoNr)?.cellSignalStrength?.dbm ?: -1
                                            is CellInfoWcdma -> info.cellSignalStrength.dbm
                                            is CellInfoGsm -> info.cellSignalStrength.dbm
                                            is CellInfoCdma -> info.cellSignalStrength.dbm
                                            else -> Int.MAX_VALUE
                                        }
                                        if (dbm != Int.MAX_VALUE) {
                                            mobileDbm = dbm.toString()
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    // 3. Traffic
                    val mobileRx = TrafficStats.getMobileRxBytes()
                    val mobileTx = TrafficStats.getMobileTxBytes()
                    val totalRx = TrafficStats.getTotalRxBytes()
                    val totalTx = TrafficStats.getTotalTxBytes()
                    val wifiRx = if (totalRx != -1L && mobileRx != -1L) totalRx - mobileRx else -1
                    val wifiTx = if (totalTx != -1L && mobileTx != -1L) totalTx - mobileTx else -1

                    // 4. CPU Freq
                    var cpuFreqGhz = ""
                    try {
                        val reader = BufferedReader(FileReader("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"))
                        val freqKhz = reader.readLine().trim().toLongOrNull()
                        reader.close()
                        if (freqKhz != null) {
                            cpuFreqGhz = String.format(Locale.US, "%.3f", freqKhz / 1000000.0)
                        }
                    } catch (_: Exception) {}

                    // 5. Foreground Apps
                    var foregroundAppCount = ""
                    try {
                        val end = System.currentTimeMillis()
                        val start = end - (intervalSec * 1000)
                        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                        if (stats != null) {
                            val count = stats.count { it.lastTimeUsed >= start }
                            foregroundAppCount = count.toString()
                        }
                    } catch (_: Exception) {}

                    // 6. Brightness
                    var brightness = ""
                    try {
                        val b = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                        brightness = b.toString()
                    } catch (_: Exception) {}

                    // 7. Memory
                    var memUsage = ""
                    try {
                        val memInfo = ActivityManager.MemoryInfo()
                        activityManager.getMemoryInfo(memInfo)
                        val percent = (memInfo.totalMem - memInfo.availMem) * 100.0 / memInfo.totalMem
                        memUsage = String.format(Locale.US, "%.1f", percent)
                    } catch (_: Exception) {}

                    fun norm(x: Long): String = if (x == Long.MIN_VALUE || x == -1L) "" else x.toString()

                    val row = listOf(
                        nowIso,
                        String.format(Locale.US, "%.1f", tSec),
                        String.format(Locale.US, "%.2f", levelPct),
                        String.format(Locale.US, "%.2f", tempC),
                        voltageMv?.toString() ?: "",
                        status.toString(),
                        plugged.toString(),
                        norm(currentUa),
                        norm(curAvg),
                        norm(chargeCounter),
                        norm(energyCounter),
                        powerW,
                        wifiRssi, mobileDbm,
                        norm(mobileRx), norm(mobileTx),
                        norm(wifiRx), norm(wifiTx),
                        cpuFreqGhz, foregroundAppCount,
                        brightness, memUsage
                    ).joinToString(",") + "\n"

                    writer?.write(row)
                    writer?.flush()

                    // Update notification
                    val notification = createNotification("Recording: ${String.format(Locale.US, "%.0f", tSec)}s elapsed | File: $fileName")
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, notification)

                } catch (e: Exception) {
                    e.printStackTrace()
                    // Continue logging even if one iteration fails
                }

                delay(intervalSec * 1000L)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogging()
    }
}
