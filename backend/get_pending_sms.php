<?php
// get_pending_sms.php - Endpoint for fetching pending SMS to be sent by Android App

require_once 'db_config.php';

// Retrieve device identifier
$device_id = isset($_REQUEST['device_id']) ? trim($_REQUEST['device_id']) : null;
if (empty($device_id)) {
    header('Content-Type: application/json', true, 400);
    echo json_encode([
        'success' => false,
        'error' => 'Missing device_id parameter.'
    ]);
    exit;
}

// Authenticate request using Device specific credentials
$providedKey = null;
if (isset($_SERVER['HTTP_X_API_KEY'])) {
    $providedKey = $_SERVER['HTTP_X_API_KEY'];
} elseif (isset($_REQUEST['api_key'])) {
    $providedKey = $_REQUEST['api_key'];
}
verifyDeviceCredentials($device_id, $providedKey);

try {
    $pdo = getDBConnection();
    
    // Start database transaction
    $pdo->beginTransaction();
    
    // Fetch pending messages specifically targeted for this device OR load-balanced messages (device_id IS NULL)
    // We lock the matched rows using FOR UPDATE to prevent race conditions from concurrent device polls
    $stmt = $pdo->prepare("SELECT id, phone, msg, device_id FROM sms_queue 
                           WHERE status = 'pending' AND (device_id = :device_id OR device_id IS NULL) 
                           ORDER BY id ASC LIMIT 10 FOR UPDATE");
    $stmt->execute([':device_id' => $device_id]);
    $messages = $stmt->fetchAll();
    
    // If any load-balanced messages (device_id IS NULL) were picked, bind them to this device_id
    if (!empty($messages)) {
        $idsToAssign = [];
        foreach ($messages as $msg) {
            if ($msg['device_id'] === null) {
                $idsToAssign[] = (int)$msg['id'];
            }
        }
        
        if (!empty($idsToAssign)) {
            $inClause = implode(',', $idsToAssign);
            $updateStmt = $pdo->prepare("UPDATE sms_queue SET device_id = :device_id WHERE id IN ($inClause)");
            $updateStmt->execute([':device_id' => $device_id]);
        }
    }
    
    // Commit transaction to release row locks
    $pdo->commit();
    
    // Format response (clean array mapping so we don't expose internal db parameters like device_id if unwanted)
    $cleanMessages = [];
    foreach ($messages as $msg) {
        $cleanMessages[] = [
            'id' => (int)$msg['id'],
            'phone' => $msg['phone'],
            'msg' => $msg['msg']
        ];
    }
    
    header('Content-Type: application/json');
    echo json_encode([
        'success' => true,
        'messages' => $cleanMessages
    ]);
} catch (Exception $e) {
    if (isset($pdo) && $pdo->inTransaction()) {
        $pdo->rollBack();
    }
    header('Content-Type: application/json', true, 500);
    echo json_encode([
        'success' => false,
        'error' => 'Failed to fetch pending SMS: ' . $e->getMessage()
    ]);
}
