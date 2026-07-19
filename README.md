# TEC SMS Gateway - System Documentation

Welcome to the **TEC SMS Gateway** documentation. This system consists of an **Android Client Application** (which runs as a background service on mobile devices to dispatch messages using actual SIM cards) and a **PHP MySQL Backend Server** (which hosts the API queue, handles secure OTP verification, and serves the diagnostics console).

---

## 📂 System Architecture & File Structure

```text
├── android/                   # Android Client codebase (Kotlin)
├── backend/                   # PHP Backend scripts for cPanel
│   ├── db_config.php          # Database configurations & SMTP mailer
│   ├── verify_email.php       # Dynamic email verification & OTP handler
│   ├── send_sms.php           # API endpoint to queue new SMS
│   ├── get_pending_sms.php    # Polling endpoint for the mobile app
│   ├── update_status.php      # Dispatch status updates from phone to DB
│   ├── diagnostics.php        # Secure admin panel console (Session Login)
│   └── schema.sql             # MySQL database tables schema
└── README.md                  # System documentation guide
```

---

## 🗄️ Database Schema & Configuration

Import the queries inside `backend/schema.sql` inside your cPanel phpMyAdmin database. It initializes three tables:
1. **`devices`**: Keeps track of verified phones, hardware IDs, custom device names, and their secure API keys.
2. **`sms_queue`**: Stores queued SMS messages, target phone numbers, statuses (`pending`, `sent`, `failed`), and timestamps.
3. **`email_otp`**: Manages secure 6-digit email codes used during dynamic app verification.

To connect your backend scripts to the database, update your credentials in [db_config.php](file:///c:/Users/Admin/Desktop/TEC%20SMS/backend/db_config.php):
```php
define('DB_HOST', 'localhost');
define('DB_USER', 'a178413f_tecsmsadmin');
define('DB_PASS', 'Sagar@365#');
define('DB_NAME', 'a178413f_tecsms');
```

---

## ✉️ Gmail SMTP Configuration for OTP
The backend uses native PHP sockets (`fsockopen`) to connect directly to Google's SMTP servers securely, ensuring fast OTP delivery without heavy external dependencies.

Configure these constants in [db_config.php](file:///c:/Users/Admin/Desktop/TEC%20SMS/backend/db_config.php):
```php
define('SMTP_HOST', 'ssl://smtp.gmail.com');
define('SMTP_PORT', 465);
define('SMTP_USER', 'tecrepairinsurat@gmail.com');
define('SMTP_PASS', 'lbntktlhzqqyitnp'); // Gmail App Password
```

---

## 📱 Mobile Onboarding Flow

1. **Permissions Hook:** On first install, the app requests runtime access for **Send SMS** and **Notifications** to run background tasks safely.
2. **Verification Stage:** The user enters their email address. The app makes a request to `verify_email.php`, which delivers a 6-digit OTP code to the user's Gmail.
3. **Activation Stage:** Once the correct OTP is entered:
   - The server registers the phone's unique hardware Android ID.
   - The server generates a secure Device API Key (e.g. `dev_key_de19a5...`).
   - The app persists these configuration details locally.
4. **Foreground Service:** The app launches `SmsGatewayService`, promoting a notification to prevent task suspension. It checks the queue every 12 seconds automatically.

---

## ⚡ API Integration Specifications (Developer Guide)

To queue messages from external software (like billing portals, CRMs, or custom scripts), use `send_sms.php`.

### 1. Authentication Methods
The API supports two authorization keys passed either as header `X-API-KEY` or query parameter `api_key`:

*   **Master API Key (`TEC_SMS_k8J2n9X4p0w7Q3v1M5`):**
    Allows queueing messages to **any** device. Leaving the `device_id` field empty automatically load-balances SMS across all active connected phones.
*   **Device API Key (e.g., `dev_key_de19a54e5293ecac8f3a26cf8d4f3a53`):**
    Allows client applications to authenticate using their own phone's unique security key. The backend automatically forces message delivery through the key's matching phone.

---

### 2. Code Request Snippets

#### PHP (cURL Request)
```php
<?php
$url = "https://sms.tecrepair.in/send_sms.php";
$data = [
    "api_key" => "dev_key_de19a54e5293ecac8f3a26cf8d4f3a53", // Device API Key
    "phone" => "918160540360", // Recipient with Country Code
    "msg" => "Hello Sagar, this is a test SMS."
];

$ch = curl_init($url);
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
curl_setopt($ch, CURLOPT_POST, true);
curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($data));
curl_setopt($ch, CURLOPT_HTTPHEADER, ["Content-Type: application/json"]);

$response = curl_exec($ch);
curl_close($ch);
echo $response;
?>
```

#### Python (Requests)
```python
import requests
import json

url = "https://sms.tecrepair.in/send_sms.php"
payload = {
    "api_key": "dev_key_de19a54e5293ecac8f3a26cf8d4f3a53",
    "phone": "918160540360",
    "msg": "Hello Sagar, this is a python request SMS test."
}

response = requests.post(url, headers={"Content-Type": "application/json"}, data=json.dumps(payload))
print(response.json())
```

---

## 🖥️ Diagnostics Dashboard Console

Access the live server control center here:
👉 **`https://sms.tecrepair.in/diagnostics.php`**

### Security Login
- The dashboard is protected by a session security form.
- You must enter the **Master API Key** (`TEC_SMS_k8J2n9X4p0w7Q3v1M5`) to unlock the data. 
- Use the **Logout** button to clear session variables and lock access.

### Features
- **SMS Queue:** View, search, and filter SMS statuses (*Pending*, *Sent*, *Failed*) dynamically using fast JavaScript.
- **Devices Registry:** Inspect active devices, register times, and show/hide API keys with quick clipboard copies.
- **System Maintenance:** Run table optimization queries to clear uncommitted row locks instantly.
