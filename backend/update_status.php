<?php
// update_status.php - Endpoint to update SMS status (sent/failed) in the queue

require_once 'db_config.php';

// Retrieve parameters from GET or POST
$id = isset($_REQUEST['id']) ? (int)$_REQUEST['id'] : null;
$status = isset($_REQUEST['status']) ? trim($_REQUEST['status']) : null;
$device_id = isset($_REQUEST['device_id']) ? trim($_REQUEST['device_id']) : null;

// Authenticate request using Device specific credentials
$providedKey = null;
if (isset($_SERVER['HTTP_X_API_KEY'])) {
    $providedKey = $_SERVER['HTTP_X_API_KEY'];
} elseif (isset($_REQUEST['api_key'])) {
    $providedKey = $_REQUEST['api_key'];
}
verifyDeviceCredentials($device_id, $providedKey);

// Validate parameters
if (empty($id) || empty($status)) {
    header('Content-Type: application/json', true, 400);
    echo json_encode([
        'success' => false,
        'error' => 'Missing id or status parameters.'
    ]);
    exit;
}

// Verify target status is allowed
if (!in_array($status, ['sent', 'failed'])) {
    header('Content-Type: application/json', true, 400);
    echo json_encode([
        'success' => false,
        'error' => "Invalid status value. Must be 'sent' or 'failed'."
    ]);
    exit;
}

try {
    $pdo = getDBConnection();
    
    // Update the database record status
    $stmt = $pdo->prepare("UPDATE sms_queue SET status = :status WHERE id = :id");
    $stmt->execute([
        ':status' => $status,
        ':id' => $id
    ]);
    
    header('Content-Type: application/json');
    echo json_encode([
        'success' => true,
        'message' => "SMS ID {$id} status updated to '{$status}' successfully."
    ]);
} catch (Exception $e) {
    header('Content-Type: application/json', true, 500);
    echo json_encode([
        'success' => false,
        'error' => 'Failed to update SMS status in database: ' . $e->getMessage()
    ]);
}
