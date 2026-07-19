package `in`.tecrepair.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("TEC_SMS_PREFS", Context.MODE_PRIVATE)
            val isVerified = prefs.getBoolean("isVerified", false)
            if (!isVerified) {
                Log.d("BootReceiver", "Device not verified. Skipping background service startup.")
                return
            }

            Log.d("BootReceiver", "Boot completed. Starting TEC SMS Gateway service...")
            
            val serviceIntent = Intent(context, SmsGatewayService::class.java).apply {
                action = SmsGatewayService.ACTION_START
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start service on boot", e)
            }
        }
    }
}
