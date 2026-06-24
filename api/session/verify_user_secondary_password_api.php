<?php
session_start();
header('Content-Type: application/json; charset=utf-8');

require_once __DIR__ . '/../../includes/config.php';

if (!isset($_SESSION['user_id']) || !isset($_SESSION['user_type']) || $_SESSION['user_type'] !== 'user') {
    http_response_code(401);
    echo json_encode(['success' => false, 'message' => 'Unauthorized'], JSON_UNESCAPED_UNICODE);
    exit;
}

$companyId = $_SESSION['company_id'] ?? null;
$isC168 = false;
if ($companyId) {
    try {
        $stmtCompany = $pdo->prepare("SELECT id FROM company WHERE id = ? AND UPPER(company_id) = 'C168'");
        $stmtCompany->execute([$companyId]);
        $isC168 = (bool)$stmtCompany->fetch(PDO::FETCH_ASSOC);
    } catch (PDOException $e) {
        error_log('User secondary company check error: ' . $e->getMessage());
    }
}

// Non-C168 users do not require this step.
if (!$isC168) {
    $_SESSION['secondary_password_verified'] = true;
    echo json_encode(['success' => true, 'message' => 'Verified'], JSON_UNESCAPED_UNICODE);
    exit;
}

if (isset($_SESSION['secondary_password_verified']) && $_SESSION['secondary_password_verified'] === true) {
    echo json_encode(['success' => true, 'message' => 'Already verified'], JSON_UNESCAPED_UNICODE);
    exit;
}

$secondaryPassword = trim((string)($_POST['secondary_password'] ?? ''));
if ($secondaryPassword === '') {
    echo json_encode(['success' => false, 'message' => 'Please enter secondary password'], JSON_UNESCAPED_UNICODE);
    exit;
}
if (!preg_match('/^\d{6}$/', $secondaryPassword)) {
    echo json_encode(['success' => false, 'message' => 'Secondary password must be exactly 6 digits'], JSON_UNESCAPED_UNICODE);
    exit;
}

try {
    $userId = (int)$_SESSION['user_id'];
    $stmt = $pdo->prepare("SELECT secondary_password FROM user WHERE id = ?");
    $stmt->execute([$userId]);
    $user = $stmt->fetch(PDO::FETCH_ASSOC);

    if ($user && !empty($user['secondary_password'])) {
        if (!password_verify($secondaryPassword, $user['secondary_password'])) {
            echo json_encode(['success' => false, 'message' => 'Secondary password is incorrect'], JSON_UNESCAPED_UNICODE);
            exit;
        }
    }

    $_SESSION['secondary_password_verified'] = true;
    echo json_encode(['success' => true, 'message' => 'Verified'], JSON_UNESCAPED_UNICODE);
} catch (PDOException $e) {
    error_log('User secondary password verify error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['success' => false, 'message' => 'An error occurred. Please try again.'], JSON_UNESCAPED_UNICODE);
}
