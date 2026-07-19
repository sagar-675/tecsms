<?php
// db_config.php - Database connection & API Key Authentication

// Database configuration
define('DB_HOST', 'localhost');
define('DB_USER', 'a178413f_tecsmsadmin');
define('DB_PASS', 'Sagar@365#');
define('DB_NAME', 'a178413f_tecsms');

// Security settings
define('MASTER_API_KEY', 'TEC_SMS_k8J2n9X4p0w7Q3v1M5'); // Master key for billing/sending systems

// SMTP Email Configurations (Gmail SMTP Relay)
define('SMTP_HOST', 'ssl://smtp.gmail.com');
define('SMTP_PORT', 465);
define('SMTP_USER', 'tecrepairinsurat@gmail.com');
define('SMTP_PASS', 'lbntktlhzqqyitnp'); // Google App Password without spaces

// Function to send secure emails via SMTP sockets in pure PHP
function sendSmtpEmail($to, $subject, $body) {
    $socket = @fsockopen(SMTP_HOST, SMTP_PORT, $errno, $errstr, 10);
    if (!$socket) {
        return false;
    }

    // Helper function to read SMTP server socket responses
    $readResponse = function($socket, $expectedCode) {
        $response = "";
        while ($str = fgets($socket, 515)) {
            $response .= $str;
            if (substr($str, 3, 1) === " ") {
                break;
            }
        }
        return (int)substr($response, 0, 3) === $expectedCode;
    };

    if (!$readResponse($socket, 220)) { fclose($socket); return false; }

    fwrite($socket, "EHLO localhost\r\n");
    if (!$readResponse($socket, 250)) { fclose($socket); return false; }

    fwrite($socket, "AUTH LOGIN\r\n");
    if (!$readResponse($socket, 334)) { fclose($socket); return false; }

    fwrite($socket, base64_encode(SMTP_USER) . "\r\n");
    if (!$readResponse($socket, 334)) { fclose($socket); return false; }

    fwrite($socket, base64_encode(SMTP_PASS) . "\r\n");
    if (!$readResponse($socket, 235)) { fclose($socket); return false; }

    fwrite($socket, "MAIL FROM: <" . SMTP_USER . ">\r\n");
    if (!$readResponse($socket, 250)) { fclose($socket); return false; }

    fwrite($socket, "RCPT TO: <$to>\r\n");
    if (!$readResponse($socket, 250)) { fclose($socket); return false; }

    fwrite($socket, "DATA\r\n");
    if (!$readResponse($socket, 354)) { fclose($socket); return false; }

    // Format proper email headers to prevent spam detection
    $headers = "MIME-Version: 1.0\r\n";
    $headers .= "Content-type: text/plain; charset=utf-8\r\n";
    $headers .= "To: <$to>\r\n";
    $headers .= "From: TEC SMS Gateway <" . SMTP_USER . ">\r\n";
    $headers .= "Subject: $subject\r\n";
    $headers .= "Date: " . date("r") . "\r\n";

    fwrite($socket, $headers . "\r\n" . $body . "\r\n.\r\n");
    if (!$readResponse($socket, 250)) { fclose($socket); return false; }

    fwrite($socket, "QUIT\r\n");
    fclose($socket);
    return true;
}

// Establish database connection using PDO
function getDBConnection() {
    try {
        $dsn = "mysql:host=" . DB_HOST . ";dbname=" . DB_NAME . ";charset=utf8mb4";
        $options = [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES   => false,
        ];
        return new PDO($dsn, DB_USER, DB_PASS, $options);
    } catch (PDOException $e) {
        header('Content-Type: application/json', true, 500);
        echo json_encode(['error' => 'Database connection failed: ' . $e->getMessage()]);
        exit;
    }
}

// Verify Master API Key (for queueing SMS)
function verifyMasterApiKey() {
    $providedKey = null;
    
    // Check HTTP X-API-KEY header
    if (isset($_SERVER['HTTP_X_API_KEY'])) {
        $providedKey = $_SERVER['HTTP_X_API_KEY'];
    } 
    // Check GET/POST request parameter (api_key)
    elseif (isset($_REQUEST['api_key'])) {
        $providedKey = $_REQUEST['api_key'];
    }
    
    if ($providedKey === null || $providedKey !== MASTER_API_KEY) {
        header('Content-Type: application/json', true, 401);
        echo json_encode(['error' => 'Unauthorized. Invalid or missing Master API Key.']);
        exit;
    }
}

// Verify Device specific credentials dynamically from DB
function verifyDeviceCredentials($deviceId, $apiKey) {
    if (empty($deviceId) || empty($apiKey)) {
        header('Content-Type: application/json', true, 401);
        echo json_encode(['error' => 'Unauthorized. Missing device identifier or security key.']);
        exit;
    }
    
    try {
        $pdo = getDBConnection();
        $stmt = $pdo->prepare("SELECT id FROM devices WHERE device_id = :device_id AND api_key = :api_key AND status = 'active'");
        $stmt->execute([
            ':device_id' => $deviceId,
            ':api_key' => $apiKey
        ]);
        
        if (!$stmt->fetch()) {
            header('Content-Type: application/json', true, 401);
            echo json_encode(['error' => 'Unauthorized. Invalid or inactive Device credentials.']);
            exit;
        }
    } catch (Exception $e) {
        header('Content-Type: application/json', true, 500);
        echo json_encode(['error' => 'Authentication database check failed: ' . $e->getMessage()]);
        exit;
    }
}
