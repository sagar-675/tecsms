<?php
// show_processes.php - Check active database connections and locks
require_once 'db_config.php';

echo "<h3>MySQL Active Process List</h3>";

try {
    $pdo = getDBConnection();
    
    $stmt = $pdo->query("SHOW FULL PROCESSLIST");
    $processes = $stmt->fetchAll(PDO::FETCH_ASSOC);
    
    echo "<table border='1' cellpadding='5' style='border-collapse:collapse;'>";
    echo "<tr><th>Id</th><th>User</th><th>Host</th><th>db</th><th>Command</th><th>Time</th><th>State</th><th>Info</th></tr>";
    
    foreach ($processes as $proc) {
        $info = htmlspecialchars($proc['Info'] ?? '');
        echo "<tr>";
        echo "<td>{$proc['Id']}</td>";
        echo "<td>{$proc['User']}</td>";
        echo "<td>{$proc['Host']}</td>";
        echo "<td>{$proc['db']}</td>";
        echo "<td>{$proc['Command']}</td>";
        echo "<td>{$proc['Time']}</td>";
        echo "<td>{$proc['State']}</td>";
        echo "<td>{$info}</td>";
        echo "</tr>";
    }
    echo "</table>";

} catch (Exception $e) {
    echo "<span style='color:red;font-weight:bold;'>Database Error: " . htmlspecialchars($e->getMessage()) . "</span>";
}
