package `in`.tecrepair.sms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.gson.Gson
import `in`.tecrepair.sms.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val client = OkHttpClient()
    private val gson = Gson()

    // Register modern Activity Result contract for requesting runtime permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[Manifest.permission.SEND_SMS] ?: false
        val notifGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        
        if (smsGranted) {
            Toast.makeText(this, "SMS Permission Granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "SMS sending permission is required for Gateway to function.", Toast.LENGTH_LONG).show()
        }
        
        if (!notifGranted) {
            Toast.makeText(this, "Notification permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inflate using view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if device is verified
        val prefs = getSharedPreferences("TEC_SMS_PREFS", Context.MODE_PRIVATE)
        val isVerified = prefs.getBoolean("isVerified", false)

        if (isVerified) {
            // Show main control dashboard
            binding.layoutVerification.visibility = View.GONE
            binding.layoutDashboard.visibility = View.VISIBLE
            loadConfigurations()
            setupClickListeners()
            observeServiceState()
        } else {
            // Show email verification onboarding screen
            binding.layoutVerification.visibility = View.VISIBLE
            binding.layoutDashboard.visibility = View.GONE
            setupVerificationListeners()
        }

        // Request permissions immediately on startup
        checkAndRequestPermissions()
    }

    private fun loadConfigurations() {
        val prefs = getSharedPreferences("TEC_SMS_PREFS", Context.MODE_PRIVATE)
        binding.etBaseUrl.setText(prefs.getString("baseUrl", "https://sms.tecrepair.in/"))
        binding.etApiKey.setText(prefs.getString("apiKey", "TEC_SMS_k8J2n9X4p0w7Q3v1M5"))
        binding.etDeviceId.setText(prefs.getString("deviceId", "device_1"))
    }

    private fun setupVerificationListeners() {
        // Send OTP verification request
        binding.btnSendOtp.setOnClickListener {
            val email = binding.etVerificationEmail.text.toString().trim()
            if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.etVerificationEmail.error = "Please enter a valid email address"
                return@setOnClickListener
            }
            sendOtp(email)
        }

        // Verify OTP and provision device
        binding.btnVerifyOtp.setOnClickListener {
            val email = binding.etVerificationEmail.text.toString().trim()
            val otp = binding.etVerificationOtp.text.toString().trim()
            if (otp.length < 6) {
                binding.etVerificationOtp.error = "Please enter a valid 6-digit OTP code"
                return@setOnClickListener
            }
            verifyOtp(email, otp)
        }
    }

    private fun sendOtp(email: String) {
        binding.btnSendOtp.isEnabled = false
        binding.btnSendOtp.text = "Sending OTP code..."

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "dev_${System.currentTimeMillis()}"
        val baseUrl = "https://sms.tecrepair.in/"
        val requestUrl = "${baseUrl}verify_email.php?action=send_otp&email=$email&device_id=$deviceId"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(requestUrl).build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    val result = gson.fromJson(body, VerificationResponse::class.java)

                    withContext(Dispatchers.Main) {
                        binding.btnSendOtp.isEnabled = true
                        binding.btnSendOtp.text = "Send Verification Code"

                        if (response.isSuccessful && result?.success == true) {
                            Toast.makeText(this@MainActivity, "OTP code sent to email!", Toast.LENGTH_SHORT).show()
                            binding.cardOtp.visibility = View.VISIBLE
                            binding.etVerificationEmail.isEnabled = false
                        } else {
                            val errorMsg = result?.error ?: "Failed to deliver OTP."
                            Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnSendOtp.isEnabled = true
                    binding.btnSendOtp.text = "Send Verification Code"
                    Toast.makeText(this@MainActivity, "Network Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun verifyOtp(email: String, otp: String) {
        binding.btnVerifyOtp.isEnabled = false
        binding.btnVerifyOtp.text = "Verifying..."

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "dev_${System.currentTimeMillis()}"
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val baseUrl = "https://sms.tecrepair.in/"
        
        val encodedDeviceName = java.net.URLEncoder.encode(deviceName, "UTF-8")
        val requestUrl = "${baseUrl}verify_email.php?action=verify_otp&email=$email&otp=$otp&device_id=$deviceId&device_name=$encodedDeviceName"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(requestUrl).build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    val result = gson.fromJson(body, VerificationResponse::class.java)

                    withContext(Dispatchers.Main) {
                        binding.btnVerifyOtp.isEnabled = true
                        binding.btnVerifyOtp.text = "Verify & Activate Device"

                        if (response.isSuccessful && result?.success == true && result.api_key != null) {
                            Toast.makeText(this@MainActivity, "Verification successful! Gateway active.", Toast.LENGTH_LONG).show()

                            // Persist configurations
                            getSharedPreferences("TEC_SMS_PREFS", Context.MODE_PRIVATE).edit().apply {
                                putBoolean("isVerified", true)
                                putString("baseUrl", baseUrl)
                                putString("apiKey", result.api_key)
                                putString("deviceId", deviceId)
                                apply()
                            }

                            // Load configurations into main dashboard layout
                            loadConfigurations()
                            setupClickListeners()
                            observeServiceState()

                            // Swap Views
                            binding.layoutVerification.visibility = View.GONE
                            binding.layoutDashboard.visibility = View.VISIBLE

                            // Start background polling immediately
                            startGatewayService()
                        } else {
                            val errorMsg = result?.error ?: "Invalid or expired OTP."
                            Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnVerifyOtp.isEnabled = true
                    binding.btnVerifyOtp.text = "Verify & Activate Device"
                    Toast.makeText(this@MainActivity, "Verification Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupClickListeners() {
        // Toggle running state of foreground service
        binding.btnToggleService.setOnClickListener {
            if (SmsGatewayService.isRunning.value) {
                stopGatewayService()
            } else {
                if (hasSmsPermission()) {
                    startGatewayService()
                } else {
                    checkAndRequestPermissions()
                }
            }
        }

        // Force an immediate API polling request
        binding.btnForcePoll.setOnClickListener {
            if (SmsGatewayService.isRunning.value) {
                val intent = Intent(this, SmsGatewayService::class.java).apply {
                    action = SmsGatewayService.ACTION_FORCE_POLL
                }
                startService(intent)
                Toast.makeText(this, "Immediate check triggered...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Start the gateway service first.", Toast.LENGTH_SHORT).show()
            }
        }

        // Save server URL configurations
        binding.btnSaveConfig.setOnClickListener {
            val url = binding.etBaseUrl.text.toString().trim()
            val key = binding.etApiKey.text.toString().trim()
            val deviceId = binding.etDeviceId.text.toString().trim()

            if (url.isEmpty()) {
                binding.etBaseUrl.error = "Server URL cannot be empty"
                return@setOnClickListener
            }
            if (deviceId.isEmpty()) {
                binding.etDeviceId.error = "Device Identifier cannot be empty"
                return@setOnClickListener
            }

            getSharedPreferences("TEC_SMS_PREFS", Context.MODE_PRIVATE).edit().apply {
                putString("baseUrl", url)
                putString("apiKey", key)
                putString("deviceId", deviceId)
                apply()
            }

            Toast.makeText(this, "Configuration updated.", Toast.LENGTH_SHORT).show()

            // If running, restart the service to apply changes
            if (SmsGatewayService.isRunning.value) {
                stopGatewayService()
                startGatewayService()
            }
        }

        // Reset statistics count
        binding.btnResetCount.setOnClickListener {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            getSharedPreferences("TEC_SMS_PREFS", Context.MODE_PRIVATE).edit().apply {
                putInt("sent_count", 0)
                putString("sent_date", today)
                apply()
            }
            SmsGatewayService.sentTodayCount.value = 0
            Toast.makeText(this, "Today's statistics reset.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SmsGatewayService.isRunning.collect { running ->
                        updateUiState(running)
                    }
                }
                
                launch {
                    SmsGatewayService.lastChecked.collect { lastCheckStatus ->
                        binding.tvLastSync.text = "Last Checked: $lastCheckStatus"
                    }
                }

                launch {
                    SmsGatewayService.sentTodayCount.collect { count ->
                        binding.tvSentTodayCount.text = count.toString()
                    }
                }
            }
        }
    }

    private fun updateUiState(running: Boolean) {
        if (running) {
            binding.tvStatusLabel.text = "Service: Running"
            binding.viewStatusIndicator.setBackgroundResource(R.drawable.circle_green)
            binding.btnToggleService.text = "STOP SERVICE"
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DC3545"))
        } else {
            binding.tvStatusLabel.text = "Service: Stopped"
            binding.viewStatusIndicator.setBackgroundResource(R.drawable.circle_red)
            binding.btnToggleService.text = "START SERVICE"
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#198754"))
        }
    }

    private fun startGatewayService() {
        val serviceIntent = Intent(this, SmsGatewayService::class.java).apply {
            action = SmsGatewayService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopGatewayService() {
        val serviceIntent = Intent(this, SmsGatewayService::class.java).apply {
            action = SmsGatewayService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (!hasSmsPermission()) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    data class VerificationResponse(val success: Boolean, val api_key: String?, val error: String?)
}
