<?php
// send_sms.php - Endpoint to queue a new SMS

require_once 'db_config.php';

// Accept parameters from GET, POST, or raw JSON body
$input = json_decode(file_get_contents('php://input'), true);

$api_key = isset($_SERVER['HTTP_X_API_KEY']) ? trim($_SERVER['HTTP_X_API_KEY']) : null;
if (empty($api_key)) {
    $api_key = isset($_REQUEST['api_key']) ? trim($_REQUEST['api_key']) : (isset($input['api_key']) ? trim($input['api_key']) : null);
}

$phone = isset($_REQUEST['phone']) ? trim($_REQUEST['phone']) : (isset($input['phone']) ? trim($input['phone']) : null);
$msg = isset($_REQUEST['msg']) ? trim($_REQUEST['msg']) : (isset($input['msg']) ? trim($input['msg']) : null);
$device_id = isset($_REQUEST['device_id']) ? trim($_REQUEST['device_id']) : (isset($input['device_id']) ? trim($input['device_id']) : null);

if (empty($device_id)) {
    $device_id = null;
}

// Validate basic parameter presence
if (empty($api_key) || empty($phone) || empty($msg)) {
    header('Content-Type: application/json', true, 400);
    echo json_encode([
        'success' => false,
        'error' => 'Missing api_key, phone, or msg parameters.'
    ]);
    exit;
}

// Sanitize phone number (allow numbers and optional + prefix)
$phoneClean = preg_replace('/[^0-9+]/', '', $phone);
if (strlen($phoneClean) < 7) {
    header('Content-Type: application/json', true, 400);
    echo json_encode([
        'success' => false,
        'error' => 'Invalid phone number format.'
    ]);
    exit;
}

try {
    $pdo = getDBConnection();
    
    // 1. Check authentication
    if ($api_key === MASTER_API_KEY) {
        // Authenticated via Master API Key (Can target any device_id, or load balance if null)
    } else {
        // Authenticated via Device specific API Key
        $devStmt = $pdo->prepare("SELECT device_id FROM devices WHERE api_key = :api_key AND status = 'active'");
        $devStmt->execute([':api_key' => $api_key]);
        $device = $devStmt->fetch();
        
        if ($device) {
            // Force message routing exclusively through this key's associated device ID
            $device_id = $device['device_id'];
        } else {
            header('Content-Type: application/json', true, 401);
            echo json_encode([
                'success' => false,
                'error' => 'Unauthorized. Invalid or inactive API Key.'
            ]);
            exit;
        }
    }
    
    // 2. Insert pending message into the queue table
    $stmt = $pdo->prepare("INSERT INTO sms_queue (phone, msg, device_id, status) VALUES (:phone, :msg, :device_id, 'pending')");
    $stmt->execute([
        ':phone' => $phoneClean,
        ':msg' => $msg,
        ':device_id' => $device_id
    ]);
    
    $lastId = $pdo->lastInsertId();
    
    header('Content-Type: application/json');
    echo json_encode([
        'success' => true,
        'message' => 'SMS queued successfully.',
        'id' => (int)$lastId,
        'device_id' => $device_id
    ]);
} catch (Exception $e) {
    header('Content-Type: application/json', true, 500);
    echo json_encode([
        'success' => false,
        'error' => 'Failed to write to queue database: ' . $e->getMessage()
    ]);
}
