package `in`.tecrepair.sms

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsGatewayService : Service() {

    private val job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + job)
    private val client = OkHttpClient()
    private val gson = Gson()

    private var pollJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val TAG = "SmsGatewayService"
        private const val NOTIFICATION_ID = 8888
        private const val CHANNEL_ID = "sms_gateway_channel"
        private const val PREFS_NAME = "TEC_SMS_PREFS"

        // State flows to update UI in real-time
        val isRunning = MutableStateFlow(false)
        val lastChecked = MutableStateFlow("Never")
        val sentTodayCount = MutableStateFlow(0)

        // Control Actions
        const val ACTION_START = "in.tecrepair.sms.START"
        const val ACTION_STOP = "in.tecrepair.sms.STOP"
        const val ACTION_FORCE_POLL = "in.tecrepair.sms.FORCE_POLL"
        
        // SMS Sent Broadcast action
        private const val ACTION_SMS_SENT = "in.tecrepair.sms.SMS_SENT"
    }

    // Dynamic Broadcast Receiver to listen for SMS Sent outcomes
    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val smsId = intent.getIntExtra("id", -1)
            if (smsId == -1) return

            val status = when (resultCode) {
                Activity.RESULT_OK -> "sent"
                else -> {
                    Log.e(TAG, "SMS dispatch failed for ID: $smsId with code: $resultCode")
                    "failed"
                }
            }

            if (status == "sent") {
                incrementSentCount()
            }

            serviceScope.launch {
                updateSmsStatusOnServer(smsId, status)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // 1. Promote to foreground immediately to satisfy OS 5-second requirement and prevent ANRs
        val notification = createNotification("Initializing SMS Gateway...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning.value = true

        // 2. Check if device is verified. If not, stop service immediately.
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isVerified = prefs.getBoolean("isVerified", false)
        if (!isVerified) {
            Log.w(TAG, "Service started but device is not verified. Stopping.")
            stopForegroundService()
            return
        }
        
        // 3. Register SMS transmission receiver
        val filter = IntentFilter(ACTION_SMS_SENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsSentReceiver, filter)
        }
        
        // 4. Initialize sent count stats
        loadSentCount()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isVerified = prefs.getBoolean("isVerified", false)
        if (!isVerified) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START, null -> {
                startPolling()
            }
            ACTION_STOP -> {
                stopForegroundService()
            }
            ACTION_FORCE_POLL -> {
                serviceScope.launch {
                    pollPendingSms()
                }
            }
        }
        return START_STICKY
    }

    private fun stopForegroundService() {
        isRunning.value = false
        stopPolling()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startPolling() {
        isRunning.value = true
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (isRunning.value) {
                pollPendingSms()
                // Poll every 12 seconds (within 10-15s specification)
                delay(12000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
    }

    private suspend fun pollPendingSms() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "https://tecrepair.in/api/") ?: "https://tecrepair.in/api/"
        val apiKey = prefs.getString("apiKey", "TEC_SMS_k8J2n9X4p0w7Q3v1M5") ?: "TEC_SMS_k8J2n9X4p0w7Q3v1M5"
        val deviceId = prefs.getString("deviceId", "device_1") ?: "device_1"

        val encodedDeviceId = java.net.URLEncoder.encode(deviceId, "UTF-8")
        val requestUrl = buildString {
            append(if (baseUrl.endsWith("/")) "${baseUrl}get_pending_sms.php" else "$baseUrl/get_pending_sms.php")
            append("?device_id=")
            append(encodedDeviceId)
        }
        
        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentTime = timeFormatter.format(Date())

        try {
            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("X-API-KEY", apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val result = gson.fromJson(body, ApiResponse::class.java)

                    if (result?.success == true && result.messages != null) {
                        lastChecked.value = "Connected • $currentTime"
                        updateNotification("Connected. Processing queue...")
                        
                        for (message in result.messages) {
                            sendSms(message)
                        }
                    } else {
                        val errMsg = result?.error ?: "Invalid server payload"
                        lastChecked.value = "Error: $errMsg • $currentTime"
                        updateNotification("Status Error: $errMsg")
                    }
                } else {
                    lastChecked.value = "HTTP Error: ${response.code} • $currentTime"
                    updateNotification("HTTP Connection Error (${response.code})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Polling exception occurred", e)
            lastChecked.value = "Connection Failed • $currentTime"
            updateNotification("Connection failed: ${e.localizedMessage}")
        }
    }

    private fun sendSms(sms: SmsItem) {
        try {
            val context = applicationContext
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Put database SMS ID as an extra in sent outcome broadcast
            val intent = Intent(ACTION_SMS_SENT).apply {
                putExtra("id", sms.id)
                // Set package name to ensure it's delivered only to this app (explicit broadcast)
                setPackage(packageName)
            }
            
            // Flags depending on API level
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val sentPI = PendingIntent.getBroadcast(
                context, 
                sms.id, 
                intent, 
                flags
            )

            // Send standard SMS silently (no delivery confirmation receiver required, sent outcome is enough)
            smsManager.sendTextMessage(sms.phone, null, sms.msg, sentPI, null)
            Log.d(TAG, "Dispatched SMS ID: ${sms.id} to ${sms.phone}")
        } catch (e: Exception) {
            Log.e(TAG, "SmsManager exception sending ID: ${sms.id}", e)
            serviceScope.launch {
                updateSmsStatusOnServer(sms.id, "failed")
            }
        }
    }

    private suspend fun updateSmsStatusOnServer(id: Int, status: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "https://tecrepair.in/api/") ?: "https://tecrepair.in/api/"
        val apiKey = prefs.getString("apiKey", "TEC_SMS_k8J2n9X4p0w7Q3v1M5") ?: "TEC_SMS_k8J2n9X4p0w7Q3v1M5"
        val deviceId = prefs.getString("deviceId", "device_1") ?: "device_1"

        val requestUrl = if (baseUrl.endsWith("/")) "${baseUrl}update_status.php" else "$baseUrl/update_status.php"

        val formBody = FormBody.Builder()
            .add("id", id.toString())
            .add("status", status)
            .add("device_id", deviceId)
            .build()

        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("X-API-KEY", apiKey)
            .post(formBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed updating server status for SMS $id. Code: ${response.code}")
                } else {
                    Log.d(TAG, "Successfully updated status on server for SMS $id to: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network failure reporting status to server for SMS $id", e)
        }
    }

    private fun loadSentCount() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = prefs.getString("sent_date", "")

        if (savedDate != today) {
            prefs.edit().putString("sent_date", today).putInt("sent_count", 0).apply()
            sentTodayCount.value = 0
        } else {
            sentTodayCount.value = prefs.getInt("sent_count", 0)
        }
    }

    private fun incrementSentCount() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = prefs.getString("sent_date", "")
        
        var count = prefs.getInt("sent_count", 0)
        
        if (savedDate != today) {
            count = 1
            prefs.edit().putString("sent_date", today).putInt("sent_count", count).apply()
        } else {
            count += 1
            prefs.edit().putInt("sent_count", count).apply()
        }
        sentTodayCount.value = count
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TEC SMS Gateway Active")
            .setContentText(content)
            // Using standard Android system icon so it does not crash if custom resource is missing
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "TEC SMS Gateway Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(smsSentReceiver)
        } catch (e: Exception) {
            Log.d(TAG, "Receiver not registered or already unregistered")
        }
        job.cancel() // Cancel all active coroutines
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // JSON Data structures
    data class SmsItem(val id: Int, val phone: String, val msg: String)
    data class ApiResponse(val success: Boolean, val messages: List<SmsItem>?, val error: String?)
}
