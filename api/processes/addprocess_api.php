<?php
/**
 * 流程/工艺添加与表单数据 API（规范化版）
 * 路径：api/processes/addprocess_api.php
 * 统一响应格式：{ success: bool, message: string, data: mixed }
 */
header('Content-Type: application/json');
require_once __DIR__ . '/../../includes/config.php';
require_once __DIR__ . '/../../includes/group_company_access.php';
require_once __DIR__ . '/../../includes/permissions.php';
require_once __DIR__ . '/../includes/partnership_audit_readonly.php';
require_once __DIR__ . '/../includes/money_decimal.php';

if (session_status() === PHP_SESSION_NONE) {
    session_start();
session_write_close(); // 释放 session 锁，允许并发 AJAX 请求并行执行
}

// ---------- 统一响应 ----------
function jsonResponse(bool $success, string $message, $data = null): void {
    $out = ['success' => $success, 'message' => $message, 'data' => $data];
    if (!$success) {
        $out['error'] = $message; // 兼容前端 result.error
    }
    echo json_encode($out, JSON_UNESCAPED_UNICODE);
}

function bankProcessHasColumn(PDO $pdo, string $column): bool {
    try {
        $stmt = $pdo->prepare("SHOW COLUMNS FROM bank_process LIKE ?");
        $stmt->execute([$column]);
        return $stmt && $stmt->rowCount() > 0;
    } catch (Throwable $e) {
        return false;
    }
}

// ---------- 权限与用户 ----------
function validateCompanyAccessProcess(PDO $pdo, int $companyId): void
{
    $viewGroup = null;
    if (isset($_GET['group_id']) && trim((string) $_GET['group_id']) !== '') {
        $viewGroup = gc_normalize_view_group((string) $_GET['group_id']);
    } elseif (isset($_POST['group_id']) && trim((string) $_POST['group_id']) !== '') {
        $viewGroup = gc_normalize_view_group((string) $_POST['group_id']);
    } elseif (isset($_GET['view_group']) && trim((string) $_GET['view_group']) !== '') {
        $viewGroup = gc_normalize_view_group((string) $_GET['view_group']);
    } elseif (isset($_POST['view_group']) && trim((string) $_POST['view_group']) !== '') {
        $viewGroup = gc_normalize_view_group((string) $_POST['view_group']);
    } elseif (gc_is_group_login()) {
        $viewGroup = gc_session_login_identifier();
    }
    gc_assert_api_company_access($pdo, $companyId, $viewGroup);
}

function getCurrentUserId(PDO $pdo): int {
    if (isset($_SESSION['user_id']) && is_numeric($_SESSION['user_id'])) {
        $userId = (int)$_SESSION['user_id'];
        $stmt = $pdo->prepare("SELECT id FROM user WHERE id = ? LIMIT 1");
        $stmt->execute([$userId]);
        if ($stmt->fetchColumn()) {
            return $userId;
        }
    }
    if (!empty($_SESSION['login_id'])) {
        $stmt = $pdo->prepare("SELECT id FROM user WHERE login_id = ? LIMIT 1");
        $stmt->execute([$_SESSION['login_id']]);
        $userId = $stmt->fetchColumn();
        if ($userId) {
            return (int)$userId;
        }
    }
    try {
        $stmt = $pdo->query("SELECT id FROM user WHERE status = 'active' ORDER BY id ASC LIMIT 1");
        $fallbackId = $stmt->fetchColumn();
        if ($fallbackId) return (int)$fallbackId;
        $stmt = $pdo->query("SELECT id FROM user ORDER BY id ASC LIMIT 1");
        $fallbackId = $stmt->fetchColumn();
        if ($fallbackId) return (int)$fallbackId;
    } catch (Exception $e) {
        error_log("getCurrentUserId: " . $e->getMessage());
    }
    throw new Exception("无法获取有效的用户 ID。请确保已登录并且 user 表中有有效的用户记录。");
}

// ---------- 数据层：表单与列表 ----------
function getCurrenciesByCompany(PDO $pdo, int $companyId): array {
    if (!function_exists('tenant_fetch_currencies')) {
        require_once __DIR__ . '/../../includes/tenant_scope.php';
    }
    $rows = tenant_fetch_currencies($pdo, [
        'mode' => 'company',
        'company_id' => $companyId,
    ]);

    return array_map(static function (array $row): array {
        return [
            'id' => (int) ($row['id'] ?? 0),
            'code' => (string) ($row['code'] ?? ''),
        ];
    }, $rows);
}

function getProcessesForForm(PDO $pdo, int $companyId): array {
    $stmt = $pdo->prepare("
        SELECT p.id as process_id, p.process_id as process_name, d.name as description_name
        FROM process p
        LEFT JOIN description d ON p.description_id = d.id
        WHERE p.status = 'active' AND p.company_id = ?
        ORDER BY p.process_id
    ");
    $stmt->execute([$companyId]);
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

function getDescriptionsByCompany(PDO $pdo, int $companyId): array {
    $stmt = $pdo->prepare("SELECT id, name FROM description WHERE company_id = ? ORDER BY name");
    $stmt->execute([$companyId]);
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

function getDays(PDO $pdo): array {
    $stmt = $pdo->query("SELECT id, day_name FROM day ORDER BY id");
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

function getExistingProcessesForCopy(PDO $pdo, int $companyId): array {
    $stmt = $pdo->prepare("
        SELECT p.id as process_id, p.process_id as process_name, d.name as description_name
        FROM process p
        LEFT JOIN description d ON p.description_id = d.id
        WHERE p.company_id = ? AND p.status IN ('active', 'inactive')
        ORDER BY p.process_id, p.dts_created DESC
    ");
    $stmt->execute([$companyId]);
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

// ---------- 数据层：copy_from ----------
function getProcessForCopyFrom(PDO $pdo, string $processIdParam, int $companyId): ?array {
    $isNumeric = is_numeric($processIdParam);
    $whereClause = $isNumeric ? 'p.id = ?' : 'p.process_id = ?';
    $sql = "SELECT p.id, p.currency_id, c.code AS currency_code, c.company_id AS currency_company_id,
            p.description_id, d.name AS description_name, p.remove_word, p.replace_word_from, p.replace_word_to,
            p.remark, p.process_id,
            GROUP_CONCAT(pd.day_id ORDER BY pd.day_id SEPARATOR ',') as day_ids
            FROM process p
            LEFT JOIN currency c ON p.currency_id = c.id
            LEFT JOIN description d ON p.description_id = d.id
            LEFT JOIN process_day pd ON p.id = pd.process_id
            WHERE $whereClause AND p.company_id = ?
            GROUP BY p.id";
    $stmt = $pdo->prepare($sql);
    $stmt->execute([$processIdParam, $companyId]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);
    return $row ?: null;
}

// ---------- 数据层：描述 ----------
function descriptionExistsForCompany(PDO $pdo, int $companyId, string $name): bool {
    $stmt = $pdo->prepare("SELECT COUNT(*) FROM description WHERE company_id = ? AND name = ?");
    $stmt->execute([$companyId, $name]);
    return (int)$stmt->fetchColumn() > 0;
}

function insertDescription(PDO $pdo, int $companyId, string $name): int {
    $stmt = $pdo->prepare("INSERT INTO description (name, company_id) VALUES (?, ?)");
    $stmt->execute([$name, $companyId]);
    return (int)$pdo->lastInsertId();
}

function getDescriptionById(PDO $pdo, int $descriptionId): ?array {
    $stmt = $pdo->prepare("SELECT id, name, company_id FROM description WHERE id = ? LIMIT 1");
    $stmt->execute([$descriptionId]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);
    return $row ?: null;
}

function getProcessUsageCountForDescription(PDO $pdo, int $descriptionId, int $companyId): int {
    $stmt = $pdo->prepare("SELECT COUNT(*) FROM process WHERE description_id = ? AND company_id = ?");
    $stmt->execute([$descriptionId, $companyId]);
    return (int)$stmt->fetchColumn();
}

function deleteDescription(PDO $pdo, int $descriptionId, int $companyId): void {
    $stmt = $pdo->prepare("DELETE FROM description WHERE id = ? AND company_id = ?");
    $stmt->execute([$descriptionId, $companyId]);
}

// ---------- 数据层：复制模板与流程 ----------
function resolveCopyFromProcessId(PDO $pdo, $copyFromProcessId, int $companyId): ?int {
    $copyFromProcessId = is_string($copyFromProcessId) ? trim($copyFromProcessId) : $copyFromProcessId;
    if ($copyFromProcessId === '' || $copyFromProcessId === null) {
        return null;
    }
    $stmt = $pdo->prepare("SELECT id FROM process WHERE process_id = ? AND company_id = ? LIMIT 1");
    $stmt->execute([$copyFromProcessId, $companyId]);
    $val = $stmt->fetchColumn();
    if ($val !== false && $val !== null) {
        return (int)$val;
    }
    if (is_numeric($copyFromProcessId) && (int)$copyFromProcessId > 0) {
        $stmt = $pdo->prepare("SELECT id FROM process WHERE id = ? AND company_id = ? LIMIT 1");
        $stmt->execute([(int)$copyFromProcessId, $companyId]);
        $val = $stmt->fetchColumn();
        return ($val !== false && $val !== null) ? (int)$val : null;
    }
    return null;
}

function getSourceTemplatesForCopy(PDO $pdo, $processIdOrDbId, int $companyId): array {
    $stmt = $pdo->prepare("SELECT * FROM data_capture_templates WHERE process_id = ? AND company_id = ?");
    $stmt->execute([$processIdOrDbId, $companyId]);
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

function processExists(PDO $pdo, string $processId, $descriptionId, int $companyId): bool {
    $stmt = $pdo->prepare("
        SELECT id
        FROM process
        WHERE process_id = ?
          AND description_id = ?
          AND company_id = ?
          AND status IN ('active', 'inactive')
        LIMIT 1
    ");
    $stmt->execute([$processId, $descriptionId, $companyId]);
    return $stmt->fetch() !== false;
}

function insertProcess(PDO $pdo, array $row): int {
    $stmt = $pdo->prepare("
        INSERT INTO process (
            process_id, description_id, currency_id, remove_word, replace_word_from, replace_word_to, remark,
            created_by, created_by_type, created_by_owner_id, dts_created, company_id, sync_source_process_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ");
    $stmt->execute([
        $row['process_id'], $row['description_id'], $row['currency_id'], $row['remove_word'],
        $row['replace_word_from'], $row['replace_word_to'], $row['remark'],
        $row['created_by'], $row['created_by_type'], $row['created_by_owner_id'],
        $row['dts_created'], $row['company_id'], $row['sync_source_process_id']
    ]);
    return (int)$pdo->lastInsertId();
}

function insertProcessDays(PDO $pdo, int $processId, array $dayIds): void {
    if (empty($dayIds)) return;
    $stmt = $pdo->prepare("INSERT INTO process_day (process_id, day_id) VALUES (?, ?)");
    foreach ($dayIds as $dayId) {
        $stmt->execute([$processId, $dayId]);
    }
}

function copyTemplatesToNewProcess(PDO $pdo, int $companyId, int $newProcessId, array $sourceTemplates): int {
    $count = 0;
    $sql = "INSERT INTO data_capture_templates (
        company_id, process_id, data_capture_id, row_index, sub_order,
        id_product, product_type, formula_variant, parent_id_product,
        template_key, description, account_id, account_display, currency_id, currency_display,
        source_columns, formula_operators, source_percent, enable_source_percent,
        input_method, enable_input_method, batch_selection, columns_display, formula_display,
        last_source_value, last_processed_amount, updated_at, created_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";
    $stmt = $pdo->prepare($sql);
    foreach ($sourceTemplates as $t) {
        try {
            $stmt->execute([
                $companyId, $newProcessId, $t['data_capture_id'], $t['row_index'],
                isset($t['sub_order']) && $t['sub_order'] !== null && $t['sub_order'] !== '' ? $t['sub_order'] : null,
                $t['id_product'], $t['product_type'], $t['formula_variant'], $t['parent_id_product'],
                $t['template_key'], $t['description'], $t['account_id'], $t['account_display'],
                $t['currency_id'], $t['currency_display'], $t['source_columns'], $t['formula_operators'],
                isset($t['source_percent']) && $t['source_percent'] !== '' ? $t['source_percent'] : '1',
                isset($t['enable_source_percent']) ? (int)$t['enable_source_percent'] : 1,
                $t['input_method'], isset($t['enable_input_method']) ? (int)$t['enable_input_method'] : 0,
                $t['batch_selection'], $t['columns_display'], $t['formula_display'],
                $t['last_source_value'], $t['last_processed_amount']
            ]);
            $count++;
        } catch (Exception $e) {
            error_log("Copy template to process $newProcessId: " . $e->getMessage());
        }
    }
    return $count;
}

// ---------- 数据层：Bank ----------
function insertBankProcess(PDO $pdo, array $params): int {
    $columns = [
        'company_id', 'country', 'bank', 'type', 'name', 'card_merchant_id', 'customer_id', 'profit_account_id',
        'contract', 'insurance'
    ];
    $values = [
        $params['company_id'], $params['country'], $params['bank'], $params['type'], $params['name'],
        $params['card_merchant_id'], $params['customer_id'], $params['profit_account_id'],
        $params['contract'], $params['insurance']
    ];

    if (bankProcessHasColumn($pdo, 'sop')) {
        $columns[] = 'sop';
        $values[] = $params['sop'] ?? '';
    }

    $columns = array_merge($columns, [
        'remark', 'cost', 'price', 'profit', 'profit_sharing', 'day_start', 'day_start_frequency', 'day_end', 'status',
        'created_by', 'created_by_type', 'created_by_owner_id'
    ]);
    $values = array_merge($values, [
        $params['remark'], $params['cost'], $params['price'], $params['profit'],
        $params['profit_sharing'], $params['day_start'], $params['day_start_frequency'], $params['day_end'], 'active',
        $params['created_by'], $params['created_by_type'], $params['created_by_owner_id']
    ]);

    $placeholders = implode(', ', array_fill(0, count($columns), '?'));
    $stmt = $pdo->prepare("
        INSERT INTO bank_process (" . implode(', ', $columns) . ")
        VALUES (" . $placeholders . ")
    ");
    $stmt->execute($values);
    return (int)$pdo->lastInsertId();
}

function ensureCountryBank(PDO $pdo, int $companyId, string $country, string $bank): void {
    if ($country === '' || $bank === '') return;
    try {
        $stmt = $pdo->prepare("INSERT IGNORE INTO country_bank (company_id, country, bank) VALUES (?, ?, ?)");
        $stmt->execute([$companyId, $country, $bank]);
    } catch (Exception $e) { /* ignore */ }
}

// ---------- 数据层：权限分配 ----------
function assignNewProcessesToRestrictedUsers(PDO $pdo, int $companyId, array $createdProcesses): void {
    if (empty($createdProcesses)) return;
    $processIds = array_unique(array_map('intval', array_column($createdProcesses, 'id')));
    if (empty($processIds)) return;

    $placeholders = str_repeat('?,', count($processIds) - 1) . '?';
    $stmt = $pdo->prepare("
        SELECT p.id, p.process_id, d.name AS description_name
        FROM process p
        LEFT JOIN description d ON p.description_id = d.id
        WHERE p.id IN ($placeholders) AND p.company_id = ?
    ");
    $stmt->execute(array_merge($processIds, [$companyId]));
    $processDetails = $stmt->fetchAll(PDO::FETCH_ASSOC);
    if (empty($processDetails)) return;

    $usersStmt = $pdo->prepare("
        SELECT u.id FROM user u
        INNER JOIN user_company_map ucm ON u.id = ucm.user_id
        WHERE ucm.company_id = ?
    ");
    $usersStmt->execute([$companyId]);
    $users = $usersStmt->fetchAll(PDO::FETCH_ASSOC);

    $selectPermStmt = $pdo->prepare("SELECT process_permissions FROM user_company_permissions WHERE user_id = ? AND company_id = ?");
    $updatePermStmt = $pdo->prepare("
        INSERT INTO user_company_permissions (user_id, company_id, process_permissions)
        VALUES (?, ?, ?)
        ON DUPLICATE KEY UPDATE process_permissions = VALUES(process_permissions)
    ");

    foreach ($users as $user) {
        $userId = (int)$user['id'];
        $selectPermStmt->execute([$userId, $companyId]);
        $permissionRow = $selectPermStmt->fetch(PDO::FETCH_ASSOC);
        if (!$permissionRow || $permissionRow['process_permissions'] === null) continue;

        $permissions = json_decode($permissionRow['process_permissions'], true);
        if (!is_array($permissions)) $permissions = [];
        $existingIds = [];
        foreach ($permissions as $p) {
            if (isset($p['id'])) $existingIds[(int)$p['id']] = true;
        }
        $added = false;
        foreach ($processDetails as $process) {
            $pid = (int)$process['id'];
            if (isset($existingIds[$pid])) continue;
            $permissions[] = [
                'id' => $pid,
                'process_id' => $process['process_id'],
                'process_description' => $process['description_name'] ?? ''
            ];
            $added = true;
        }
        if ($added) {
            $updatePermStmt->execute([$userId, $companyId, json_encode($permissions, JSON_UNESCAPED_UNICODE)]);
        }
    }
}

// ---------- 主入口：鉴权 ----------
if (!isset($_SESSION['user_id'])) {
    jsonResponse(false, '用户未登录', null);
    exit;
}

$companyId = null;
if (isset($_POST['company_id']) && $_POST['company_id'] !== '') {
    $companyId = (int)$_POST['company_id'];
} elseif (isset($_GET['company_id']) && $_GET['company_id'] !== '') {
    $companyId = (int)$_GET['company_id'];
} elseif (isset($_SESSION['company_id'])) {
    $companyId = (int)$_SESSION['company_id'];
}

if (!$companyId) {
    jsonResponse(false, '缺少公司信息', null);
    exit;
}

try {
    validateCompanyAccessProcess($pdo, $companyId);
} catch (Exception $e) {
    jsonResponse(false, $e->getMessage(), null);
    exit;
}

// ---------- 路由 ----------
try {
    if ($_SERVER['REQUEST_METHOD'] === 'POST' && is_partnership_audit_read_only_active($pdo)) {
        jsonResponse(false, '只读账号无法执行此操作', null);
        exit;
    }

    // GET: copy_from
    if ($_SERVER['REQUEST_METHOD'] === 'GET' && isset($_GET['action']) && $_GET['action'] === 'copy_from') {
        $processIdParam = isset($_GET['process_id']) ? trim($_GET['process_id']) : '';
        if ($processIdParam === '') {
            jsonResponse(false, 'process_id is required', null);
            exit;
        }
        $process = getProcessForCopyFrom($pdo, $processIdParam, $companyId);
        if (!$process) {
            jsonResponse(false, 'Process not found', null);
            exit;
        }
        $currencyId = null;
        if (!empty($process['currency_id']) && (int)$process['currency_company_id'] === $companyId) {
            $currencyId = $process['currency_id'];
        }
        $data = [
            'currency_id' => $currencyId,
            'currency_code' => $process['currency_code'],
            'currency_warning' => !empty($process['currency_id']) && (int)$process['currency_company_id'] !== $companyId ? 'Currency does not belong to current company' : null,
            'description_id' => $process['description_id'],
            'description_name' => $process['description_name'],
            'remove_word' => $process['remove_word'],
            'replace_word_from' => $process['replace_word_from'],
            'replace_word_to' => $process['replace_word_to'],
            'replace_word' => $process['replace_word_from'] . ' == ' . $process['replace_word_to'],
            'remark' => $process['remark'],
            'day_use' => $process['day_ids'],
            'source_process_id' => $process['process_id']
        ];
        jsonResponse(true, 'OK', $data);
        exit;
    }

    // POST: Bank
    if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['permission']) && $_POST['permission'] === 'Bank') {
        $country = trim($_POST['country'] ?? '');
        $bank = trim($_POST['bank'] ?? '');
        $type = trim($_POST['type'] ?? '');
        $name = trim($_POST['name'] ?? '');
        if ($country === '' || $bank === '' || $type === '' || $name === '') {
            jsonResponse(false, 'Country, Bank, Type and Name are required', null);
            exit;
        }
        $day_start_frequency = trim($_POST['day_start_frequency'] ?? '1st_of_every_month');
        if (!in_array($day_start_frequency, ['1st_of_every_month', 'monthly', 'week', 'day', 'once'], true)) {
            $day_start_frequency = '1st_of_every_month';
        }
        if ($day_start_frequency === 'once' || $day_start_frequency === 'week' || $day_start_frequency === 'day') {
            $_POST['day_end'] = '';
            if ($day_start_frequency === 'week' || $day_start_frequency === 'day') {
                $_POST['contract'] = '';
            }
        }
        $currentUserId = null;
        $createdByType = 'user';
        $createdByOwnerId = null;
        if (!empty($_SESSION['user_type']) && $_SESSION['user_type'] === 'owner') {
            $createdByType = 'owner';
            $createdByOwnerId = $_SESSION['owner_id'] ?? null;
        } else {
            $currentUserId = getCurrentUserId($pdo);
        }
        $params = [
            'company_id' => $companyId,
            'country' => $country,
            'bank' => $bank,
            'type' => $type,
            'name' => $name,
            'card_merchant_id' => (isset($_POST['card_merchant_id']) && $_POST['card_merchant_id'] !== '') ? (int)$_POST['card_merchant_id'] : null,
            'customer_id' => (isset($_POST['customer_id']) && $_POST['customer_id'] !== '') ? (int)$_POST['customer_id'] : null,
            'profit_account_id' => (isset($_POST['profit_account_id']) && $_POST['profit_account_id'] !== '') ? (int)$_POST['profit_account_id'] : null,
            'contract' => trim($_POST['contract'] ?? ''),
            'insurance' => money_optional($_POST['insurance'] ?? null),
            'sop' => trim($_POST['sop'] ?? ''),
            'remark' => trim($_POST['remark'] ?? ''),
            'cost' => money_optional($_POST['cost'] ?? null),
            'price' => money_optional($_POST['price'] ?? null),
            'profit' => money_optional($_POST['profit'] ?? null),
            'profit_sharing' => trim($_POST['profit_sharing'] ?? ''),
            'day_start' => trim($_POST['day_start'] ?? '') ?: null,
            'day_start_frequency' => $day_start_frequency,
            'day_end' => trim($_POST['day_end'] ?? '') ?: null,
            'created_by' => $currentUserId,
            'created_by_type' => $createdByType,
            'created_by_owner_id' => $createdByOwnerId
        ];
        $id = insertBankProcess($pdo, $params);
        ensureCountryBank($pdo, $companyId, $country, $bank);
        $data = ['created_processes' => [['id' => $id, 'process_id' => $name, 'description_id' => null]]];
        jsonResponse(true, 'Bank process added successfully', $data);
        exit;
    }

    // POST: add_description
    if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['action']) && $_POST['action'] === 'add_description') {
        $descriptionName = trim($_POST['description_name'] ?? '');
        if ($descriptionName === '') {
            jsonResponse(false, 'Description name is required', null);
            exit;
        }
        if (descriptionExistsForCompany($pdo, $companyId, $descriptionName)) {
            jsonResponse(false, 'Description name already exists for this company', ['duplicate' => true]);
            exit;
        }
        $descriptionId = insertDescription($pdo, $companyId, $descriptionName);
        jsonResponse(true, 'Description added successfully', ['description_id' => $descriptionId]);
        exit;
    }

    // POST: delete_description
    if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['action']) && $_POST['action'] === 'delete_description') {
        $descriptionId = isset($_POST['description_id']) ? (int)$_POST['description_id'] : 0;
        if (!$descriptionId) {
            jsonResponse(false, 'Description ID is required', null);
            exit;
        }
        $description = getDescriptionById($pdo, $descriptionId);
        if (!$description) {
            jsonResponse(false, 'Description not found', null);
            exit;
        }
        if ((int)$description['company_id'] !== $companyId) {
            jsonResponse(false, '无权限删除该描述', null);
            exit;
        }
        if (getProcessUsageCountForDescription($pdo, $descriptionId, $companyId) > 0) {
            jsonResponse(false, '该描述正在被流程使用，无法删除', null);
            exit;
        }
        deleteDescription($pdo, $descriptionId, $companyId);
        jsonResponse(true, 'Description deleted successfully', null);
        exit;
    }

    // POST: 添加 process（主流程）
    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $processIds = [];
        if (!empty($_POST['selected_processes'])) {
            $raw = $_POST['selected_processes'];
            $selectedProcesses = is_array($raw) ? $raw : json_decode($raw, true);
            if (is_array($selectedProcesses) && !empty($selectedProcesses)) {
                $processIds = array_values($selectedProcesses);
            }
        }
        if (empty($processIds) && !empty($_POST['process_id'])) {
            $processIds = [trim($_POST['process_id'])];
        }

        $descriptionIds = [];
        if (!empty($_POST['selected_descriptions'])) {
            $selectedDescriptions = json_decode($_POST['selected_descriptions'], true);
            if (is_array($selectedDescriptions) && !empty($selectedDescriptions)) {
                $placeholders = str_repeat('?,', count($selectedDescriptions) - 1) . '?';
                $stmt = $pdo->prepare("SELECT id FROM description WHERE name IN ($placeholders) AND company_id = ?");
                $stmt->execute(array_merge($selectedDescriptions, [$companyId]));
                $descriptionIds = $stmt->fetchAll(PDO::FETCH_COLUMN);
            }
        } elseif (!empty($_POST['description_id'])) {
            $descriptionIds = [$_POST['description_id']];
        }

        $currencyId = $_POST['currency_id'] ?? '';
        $removeWord = $_POST['remove_word'] ?? '';
        $replaceWordFrom = $_POST['replace_word_from'] ?? '';
        $replaceWordTo = $_POST['replace_word_to'] ?? '';
        $remark = $_POST['remark'] ?? '';
        $dayUse = $_POST['day_use'] ?? '';
        $copyFromProcessId = $_POST['copy_from'] ?? '';

        if (empty($processIds)) {
            jsonResponse(false, 'At least one process ID must be selected', null);
            exit;
        }
        if (empty($descriptionIds)) {
            jsonResponse(false, 'At least one description must be selected', null);
            exit;
        }
        if (empty($currencyId)) {
            jsonResponse(false, 'Currency must be selected', null);
            exit;
        }

        $dayIds = !empty($dayUse) ? array_filter(array_map('trim', explode(',', $dayUse))) : [];
        $copyFromProcessDbId = resolveCopyFromProcessId($pdo, $copyFromProcessId, $companyId);
        $sourceTemplates = [];
        if ($copyFromProcessDbId !== null) {
            $sourceTemplates = getSourceTemplatesForCopy($pdo, $copyFromProcessDbId, $companyId);
        }
        if (empty($sourceTemplates) && $copyFromProcessId !== '' && $copyFromProcessId !== null) {
            $sourceTemplates = getSourceTemplatesForCopy($pdo, $copyFromProcessId, $companyId);
        }

        $currentUserId = null;
        $createdByType = 'user';
        $createdByOwnerId = null;
        if (!empty($_SESSION['user_type']) && $_SESSION['user_type'] === 'owner') {
            $createdByType = 'owner';
            $createdByOwnerId = $_SESSION['owner_id'] ?? null;
        } else {
            $currentUserId = getCurrentUserId($pdo);
        }

        $createdProcesses = [];
        $errors = [];
        $copiedTemplatesCount = 0;
        $pdo->beginTransaction();
        try {
            foreach ($processIds as $processId) {
                foreach ($descriptionIds as $descriptionId) {
                    if (processExists($pdo, $processId, $descriptionId, $companyId)) {
                        $errors[] = "Process already exists for process_id $processId and description $descriptionId";
                        continue;
                    }
                    $row = [
                        'process_id' => $processId,
                        'description_id' => $descriptionId,
                        'currency_id' => $currencyId,
                        'remove_word' => $removeWord,
                        'replace_word_from' => $replaceWordFrom,
                        'replace_word_to' => $replaceWordTo,
                        'remark' => $remark,
                        'created_by' => $currentUserId,
                        'created_by_type' => $createdByType,
                        'created_by_owner_id' => $createdByOwnerId,
                        'dts_created' => date('Y-m-d H:i:s'),
                        'company_id' => $companyId,
                        'sync_source_process_id' => $copyFromProcessDbId
                    ];
                    $newProcessId = insertProcess($pdo, $row);
                    insertProcessDays($pdo, (int)$newProcessId, $dayIds);
                    $createdProcesses[] = ['id' => (int)$newProcessId, 'process_id' => $processId, 'description_id' => $descriptionId];
                    if (!empty($sourceTemplates)) {
                        $copiedTemplatesCount += copyTemplatesToNewProcess($pdo, $companyId, (int)$newProcessId, $sourceTemplates);
                    }
                }
            }
            assignNewProcessesToRestrictedUsers($pdo, $companyId, $createdProcesses);
            $pdo->commit();
        } catch (Exception $e) {
            $pdo->rollBack();
            throw $e;
        }

        $message = "Successfully created " . count($createdProcesses) . " process(es)";
        if ($copiedTemplatesCount > 0) {
            $message .= " and copied " . $copiedTemplatesCount . " template(s)";
        } elseif ($copyFromProcessId !== '') {
            $message .= ". Note: No templates were copied from source process.";
        }
        if (!empty($errors)) {
            $message .= ". " . count($errors) . " process(es) were skipped due to conflicts.";
        }
        $data = [
            'created_processes' => $createdProcesses,
            'copied_templates_count' => $copiedTemplatesCount,
            'copy_from_used' => $copyFromProcessId !== '',
            'sync_source_set' => $copyFromProcessDbId !== null,
            'source_templates_found' => count($sourceTemplates),
            'errors' => $errors
        ];
        jsonResponse(true, $message, $data);
        exit;
    }

    // GET: 表单数据（兼容前端 result.currencies / result.descriptions 等）
    $currencies = getCurrenciesByCompany($pdo, $companyId);
    $processes = getProcessesForForm($pdo, $companyId);
    $descriptions = getDescriptionsByCompany($pdo, $companyId);
    $days = getDays($pdo);
    $existingProcesses = getExistingProcessesForCopy($pdo, $companyId);
    $payload = [
        'currencies' => $currencies,
        'processes' => $processes,
        'descriptions' => $descriptions,
        'days' => $days,
        'existingProcesses' => $existingProcesses
    ];
    echo json_encode(array_merge(
        ['success' => true, 'message' => 'OK', 'data' => $payload],
        $payload
    ), JSON_UNESCAPED_UNICODE);

} catch (Exception $e) {
    jsonResponse(false, $e->getMessage(), null);
}