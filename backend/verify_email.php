<?php
// verify_email.php - Endpoint for sending and verifying Email OTP codes

require_once 'db_config.php';

// Accept request parameters
$action = isset($_REQUEST['action']) ? trim($_REQUEST['action']) : null;
$email = isset($_REQUEST['email']) ? trim($_REQUEST['email']) : null;
$device_id = isset($_REQUEST['device_id']) ? trim($_REQUEST['device_id']) : null;

if (empty($action) || empty($email) || empty($device_id)) {
    header('Content-Type: application/json', true, 400);
    echo json_encode([
        'success' => false,
        'error' => 'Missing action, email, or device_id parameters.'
    ]);
    exit;
}

// Validate email format
if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
    header('Content-Type: application/json', true, 400);
    echo json_encode([
        'success' => false,
        'error' => 'Invalid email address format.'
    ]);
    exit;
}

try {
    $pdo = getDBConnection();

    if ($action === 'send_otp') {
        // Generate a random 6-digit verification code
        $otp = sprintf('%06d', rand(0, 999999));
        
        // Remove older active verification codes for this device/email
        $delStmt = $pdo->prepare("DELETE FROM email_otp WHERE email = :email OR device_id = :device_id");
        $delStmt->execute([
            ':email' => $email,
            ':device_id' => $device_id
        ]);

        // Insert new OTP
        $insStmt = $pdo->prepare("INSERT INTO email_otp (email, otp, device_id) VALUES (:email, :otp, :device_id)");
        $insStmt->execute([
            ':email' => $email,
            ':otp' => $otp,
            ':device_id' => $device_id
        ]);

        // Send Email via Gmail SMTP Relay
        $subject = "TEC SMS Gateway - Verification Code";
        $message = "Hello,\n\nYour 6-digit verification OTP code for TEC SMS Gateway is:\n\n" . $otp . "\n\nThis code will expire in 15 minutes.\n\nThank you,\nTEC SMS Support";

        // Use socket-based SMTP mailer function (Gmail SMTP Relay)
        if (sendSmtpEmail($email, $subject, $message)) {
            header('Content-Type: application/json');
            echo json_encode([
                'success' => true,
                'message' => 'Verification code sent successfully.'
            ]);
        } else {
            header('Content-Type: application/json', true, 500);
            echo json_encode([
                'success' => false,
                'error' => 'Failed to deliver verification email. Please check server SMTP configuration.'
            ]);
        }
        exit;
    } 
    
    elseif ($action === 'verify_otp') {
        $otp = isset($_REQUEST['otp']) ? trim($_REQUEST['otp']) : null;
        $device_name = isset($_REQUEST['device_name']) ? trim($_REQUEST['device_name']) : 'Android Device';

        if (empty($otp)) {
            header('Content-Type: application/json', true, 400);
            echo json_encode([
                'success' => false,
                'error' => 'Missing verification OTP code.'
            ]);
            exit;
        }

        // Validate OTP (Must match and be created in the last 15 minutes)
        $chkStmt = $pdo->prepare("SELECT id FROM email_otp 
                                  WHERE email = :email AND otp = :otp AND device_id = :device_id 
                                  AND created_at >= NOW() - INTERVAL 15 MINUTE");
        $chkStmt->execute([
            ':email' => $email,
            ':otp' => $otp,
            ':device_id' => $device_id
        ]);

        if (!$chkStmt->fetch()) {
            header('Content-Type: application/json', true, 400);
            echo json_encode([
                'success' => false,
                'error' => 'Invalid, expired, or incorrect verification OTP code.'
            ]);
            exit;
        }

        // Generate a new secure unique API key for this device
        $deviceApiKey = 'dev_key_' . bin2hex(random_bytes(16));

        // Delete from temporary verification table
        $delStmt = $pdo->prepare("DELETE FROM email_otp WHERE email = :email");
        $delStmt->execute([':email' => $email]);

        // Insert or Update inside devices table
        // We use ON DUPLICATE KEY UPDATE so the user can re-verify or reset credentials if needed
        $stmt = $pdo->prepare("INSERT INTO devices (device_id, api_key, device_name, email, status) 
                               VALUES (:device_id, :api_key, :device_name, :email, 'active') 
                               ON DUPLICATE KEY UPDATE 
                               api_key = VALUES(api_key), 
                               device_name = VALUES(device_name), 
                               email = VALUES(email), 
                               status = 'active'");
        $stmt->execute([
            ':device_id' => $device_id,
            ':api_key' => $deviceApiKey,
            ':device_name' => $device_name,
            ':email' => $email
        ]);

        header('Content-Type: application/json');
        echo json_encode([
            'success' => true,
            'message' => 'Verification successful! Device registered.',
            'api_key' => $deviceApiKey
        ]);
        exit;
    } 
    
    else {
        header('Content-Type: application/json', true, 400);
        echo json_encode([
            'success' => false,
            'error' => 'Invalid action parameter.'
        ]);
        exit;
    }
} catch (Exception $e) {
    header('Content-Type: application/json', true, 500);
    echo json_encode([
        'success' => false,
        'error' => 'Database operation failure: ' . $e->getMessage()
    ]);
    exit;
}
