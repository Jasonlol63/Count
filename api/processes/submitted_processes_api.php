<?php
require_once __DIR__ . '/../../includes/config.php';
require_once __DIR__ . '/../../includes/permissions.php';
require_once __DIR__ . '/../includes/partnership_audit_readonly.php';
require_once __DIR__ . '/../datacapture/data_capture_scope_common.php';

dcEnsureSubmittedProcessesScopeColumns($pdo);

// 开启 session
if (session_status() === PHP_SESSION_NONE) {
    session_start();
session_write_close(); // 释放 session 锁，允许并发 AJAX 请求并行执行
}

header('Content-Type: application/json');

// 检查用户是否已登录
if (!isset($_SESSION['user_id'])) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'User not authenticated']);
    exit;
}

// 检查用户是否登录
if (!isset($_SESSION['user_id'])) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => '用户未登录']);
    exit;
}

$scopeParams = array_merge($_GET, $_POST);
$capture_scope_group = false;
$capture_scope_ctx = [];

try {
    if (dcRequestHasExplicitScope($scopeParams)) {
        $scopeResolved = resolveDataCaptureRequestScope($pdo, $scopeParams);
        $capture_scope_ctx = dcFinalizeDualTenantCaptureScope($pdo, $scopeResolved, $scopeParams);
        $company_id = (int) $capture_scope_ctx['company_id'];
        $capture_scope_group = (bool) $capture_scope_ctx['is_group_scope'];
    } else {
        $company_id = null;
        if (isset($scopeParams['company_id']) && $scopeParams['company_id'] !== '') {
            $company_id = (int) $scopeParams['company_id'];
        } elseif (isset($_SESSION['company_id'])) {
            $company_id = (int) $_SESSION['company_id'];
        }
        $capture_scope_group = false;
        $capture_scope_ctx = [
            'company_id' => (int) ($company_id ?? 0),
            'anchor_company_id' => (int) ($company_id ?? 0),
            'is_group_scope' => false,
            'dual_tenant' => tenant_table_has_scope_columns($pdo, 'data_captures'),
            'submitted_dual_tenant' => dcSubmittedProcessesDualTenantEnabled($pdo),
            'scope_process_sql' => '',
        ];
    }
} catch (Exception $scopeException) {
    http_response_code(403);
    echo json_encode(['success' => false, 'error' => $scopeException->getMessage()]);
    exit;
}

if (!$company_id) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => '缺少公司信息']);
    exit;
}

$user_id = $_SESSION['user_id'];
$action = $_GET['action'] ?? $_POST['action'] ?? '';

$groupIdForAccess = dcNormalizeGroupId($scopeParams['group_id'] ?? '');
if (!checkReportGamesAccess($pdo, $company_id, $groupIdForAccess !== '' ? $groupIdForAccess : null)) {
    http_response_code(403);
    echo json_encode(['success' => false, 'error' => 'Unauthorized category permission (Games required)']);
    exit;
}

function dcSubmittedProcessScopeFilter(string $processAlias = 'p'): string
{
    global $capture_scope_group, $pdo, $company_id, $capture_scope_ctx;
    if (!empty($capture_scope_ctx['scope_process_sql'])) {
        return (string) $capture_scope_ctx['scope_process_sql'];
    }
    if ($capture_scope_group) {
        return dcSqlGroupProcessFilter($processAlias);
    }
    return dcSqlDataCaptureCompanyProcessFilter($pdo, (int) ($company_id ?? 0), $processAlias);
}

function dcSubmittedLedgerFilter(string $alias, string $table = 'submitted_processes'): array
{
    global $pdo, $capture_scope_ctx, $company_id, $capture_scope_group;
    if (!empty($capture_scope_ctx)) {
        return dcBuildCaptureLedgerFilter($pdo, $capture_scope_ctx, $alias, $table);
    }
    return [
        'sql' => ' AND ' . preg_replace('/[^a-zA-Z0-9_]/', '', $alias) . '.company_id = ? ',
        'bind' => (int) $company_id,
        'uses_dual_tenant' => false,
    ];
}

try {
    switch ($action) {
        case 'get_week_submissions':
            getWeekSubmissions($user_id);
            break;

        case 'get_submissions_by_date':
            getSubmissionsByDate($user_id);
            break;

        case 'get_submissions_by_capture_date':
            getSubmissionsByCaptureDate($user_id);
            break;

        case 'get_processes_by_day':
            getProcessesByDay($user_id);
            break;

        case 'get_today_entries':
            getTodayEntries($user_id);
            break;

        case 'save_submission':
            saveSubmission($user_id);
            break;

        case 'get_group_process_id':
            getGroupProcessId();
            break;

        default:
            http_response_code(400);
            echo json_encode(['success' => false, 'error' => 'Invalid action']);
            break;
    }
} catch (Exception $e) {
    error_log("Submitted Processes API Error: " . $e->getMessage());
    http_response_code(500);
    echo json_encode(['success' => false, 'error' => 'Internal server error']);
}

// 获取本周提交的processes（根据用户权限）
function getWeekSubmissions($user_id)
{
    global $pdo, $company_id;

    // 使用全局的 $company_id（已经过验证）
    $currentCompanyId = $company_id;

    if (!$currentCompanyId) {
        echo json_encode([
            'success' => false,
            'error' => 'User company_id not found'
        ]);
        return;
    }

    // 获取本周的开始和结束日期
    $start_of_week = date('Y-m-d', strtotime('monday this week'));
    $end_of_week = date('Y-m-d', strtotime('sunday this week'));

    // 获取用户权限（仅对 user 类型，owner 有所有权限）
    $processIds = [];
    $user_type = isset($_SESSION['user_type']) && $_SESSION['user_type'] === 'owner' ? 'owner' : 'user';

    if ($user_type === 'user') {
        $userStmt = $pdo->prepare("SELECT process_permissions FROM user_company_permissions WHERE user_id = ? AND company_id = ?");
        $userStmt->execute([$user_id, $currentCompanyId]);
        $user = $userStmt->fetch(PDO::FETCH_ASSOC);

        // 检查 process_permissions 字段是否存在且非空
        if ($user && !empty($user['process_permissions'])) {
            $processPermissions = json_decode($user['process_permissions'], true);

            // 处理权限数据：可能是对象数组（每个对象有 id 字段）或简单的 ID 数组
            if (is_array($processPermissions) && !empty($processPermissions)) {
                // 检查第一个元素是否是对象（有 id 字段）
                if (isset($processPermissions[0]) && is_array($processPermissions[0]) && isset($processPermissions[0]['id'])) {
                    // 对象数组格式，提取 id
                    $processIds = array_column($processPermissions, 'id');
                } else {
                    // 简单的 ID 数组格式，直接使用
                    $processIds = $processPermissions;
                }
            }
        }
        // 如果 process_permissions 为空或不存在，$processIds 保持为空数组，表示可以看见所有 process
    }
    // owner 类型不需要权限限制，$processIds 保持为空数组

    // 构建权限过滤条件
    $permissionCondition = "";

    // 只有当用户设置了权限（$processIds 不为空）时才添加权限过滤
    if (!empty($processIds)) {
        $placeholders = str_repeat('?,', count($processIds) - 1) . '?';
        $permissionCondition = "AND p.id IN ($placeholders)";
    }

    $stmt = $pdo->prepare("
        SELECT 
            sp.id,
            sp.process_id,
            sp.date_submitted,
            sp.created_at,
            sp.user_type,
            p.process_id as process_code,
            d.name as description_name,
            COALESCE(u.login_id, o.owner_code) as submitted_by
        FROM submitted_processes sp
        JOIN process p ON sp.process_id = p.id
        LEFT JOIN description d ON p.description_id = d.id
        LEFT JOIN user u ON sp.user_id = u.id AND sp.user_type = 'user'
        LEFT JOIN owner o ON sp.user_id = o.id AND sp.user_type = 'owner'
        WHERE sp.company_id = ?
          AND sp.date_submitted BETWEEN ? AND ?
          AND p.company_id = ?
        $permissionCondition
        ORDER BY sp.date_submitted DESC, sp.created_at DESC
    ");

    // 调整参数顺序：company_id, start_date, end_date, company_id (for process), processIds...
    $params = array_merge([$currentCompanyId, $start_of_week, $end_of_week, $currentCompanyId], !empty($processIds) ? $processIds : []);

    try {
        $stmt->execute($params);
        $submissions = $stmt->fetchAll(PDO::FETCH_ASSOC);

        echo json_encode([
            'success' => true,
            'data' => $submissions,
            'week_range' => [
                'start' => $start_of_week,
                'end' => $end_of_week
            ]
        ]);
    } catch (PDOException $e) {
        error_log("SQL Error in getWeekSubmissions: " . $e->getMessage());
        echo json_encode([
            'success' => false,
            'error' => 'Database error: ' . $e->getMessage()
        ]);
    }
}

// 根据特定日期获取提交的processes（根据用户权限）
function getSubmissionsByDate($user_id)
{
    global $pdo, $company_id;

    try {
        // 使用全局的 $company_id（已经过验证）
        $currentCompanyId = $company_id;

        if (!$currentCompanyId) {
            echo json_encode([
                'success' => false,
                'error' => 'User company_id not found'
            ]);
            return;
        }

        // 获取选择的日期，默认为今天
        $selected_date = $_GET['date'] ?? date('Y-m-d');

        // 验证日期格式
        if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $selected_date)) {
            echo json_encode([
                'success' => false,
                'error' => 'Invalid date format'
            ]);
            return;
        }

        // 获取用户权限（仅对 user 类型，owner 有所有权限）
        $processIds = [];
        $user_type = isset($_SESSION['user_type']) && $_SESSION['user_type'] === 'owner' ? 'owner' : 'user';

        if ($user_type === 'user') {
            try {
                $userStmt = $pdo->prepare("SELECT process_permissions FROM user WHERE id = ?");
                $userStmt->execute([$user_id]);
                $user = $userStmt->fetch(PDO::FETCH_ASSOC);

                // 检查 process_permissions 字段是否存在且非空
                if ($user && isset($user['process_permissions']) && !empty($user['process_permissions'])) {
                    $processPermissions = json_decode($user['process_permissions'], true);

                    // 处理权限数据：可能是对象数组（每个对象有 id 字段）或简单的 ID 数组
                    if (is_array($processPermissions) && !empty($processPermissions)) {
                        // 检查第一个元素是否是对象（有 id 字段）
                        if (isset($processPermissions[0]) && is_array($processPermissions[0]) && isset($processPermissions[0]['id'])) {
                            // 对象数组格式，提取 id
                            $processIds = array_column($processPermissions, 'id');
                        } else {
                            // 简单的 ID 数组格式，直接使用
                            $processIds = $processPermissions;
                        }
                    }
                }
                // 如果 process_permissions 为空或不存在，$processIds 保持为空数组，表示可以看见所有 process
            } catch (PDOException $e) {
                error_log("Error fetching user permissions in getSubmissionsByDate: " . $e->getMessage());
                // 继续执行，使用空数组（表示可以看见所有 process）
            }
        }
        // owner 类型不需要权限限制，$processIds 保持为空数组

        // 构建权限过滤条件
        $permissionCondition = "";

        // 只有当用户设置了权限（$processIds 不为空）时才添加权限过滤
        if (!empty($processIds) && is_array($processIds)) {
            // 过滤掉非数字的 ID
            $processIds = array_filter($processIds, function ($id) {
                return is_numeric($id);
            });
            $processIds = array_values($processIds); // 重新索引数组

            if (!empty($processIds)) {
                $placeholders = str_repeat('?,', count($processIds) - 1) . '?';
                $permissionCondition = "AND p.id IN ($placeholders)";
            }
        }

        $stmt = $pdo->prepare("
            SELECT 
                sp.id,
                sp.process_id,
                sp.date_submitted,
                sp.capture_date,
                sp.created_at,
                sp.user_type,
                p.process_id as process_code,
                d.name as description_name,
                COALESCE(u.login_id, o.owner_code) as submitted_by
            FROM submitted_processes sp
            JOIN process p ON sp.process_id = p.id
            LEFT JOIN description d ON p.description_id = d.id
            LEFT JOIN user u ON sp.user_id = u.id AND sp.user_type = 'user'
            LEFT JOIN owner o ON sp.user_id = o.id AND sp.user_type = 'owner'
            WHERE sp.company_id = ?
              AND DATE(sp.date_submitted) = ?
              AND p.company_id = ?
            $permissionCondition
            ORDER BY sp.created_at DESC
        ");

        // 调整参数顺序：company_id, date, company_id (for process), processIds...
        $params = array_merge([$currentCompanyId, $selected_date, $currentCompanyId], !empty($processIds) ? $processIds : []);

        $stmt->execute($params);
        $submissions = $stmt->fetchAll(PDO::FETCH_ASSOC);

        echo json_encode([
            'success' => true,
            'data' => $submissions,
            'selected_date' => $selected_date
        ]);
    } catch (PDOException $e) {
        error_log("SQL Error in getSubmissionsByDate: " . $e->getMessage());
        error_log("Stack trace: " . $e->getTraceAsString());
        echo json_encode([
            'success' => false,
            'error' => 'Database error: ' . $e->getMessage()
        ]);
    } catch (Exception $e) {
        error_log("Error in getSubmissionsByDate: " . $e->getMessage());
        error_log("Stack trace: " . $e->getTraceAsString());
        echo json_encode([
            'success' => false,
            'error' => 'Internal error: ' . $e->getMessage()
        ]);
    }
}

// 根据 capture_date 获取提交的processes（按选择的日期归类，显示提交日期）
/**
 * Group scope: one submitted-process row per data_captures row (allows multiple SALARY/COMMISSION/BONUS per day).
 *
 * @param array<int, string|int> $permissionProcessIds
 * @return array<int, array<string, mixed>>
 */
function dcFetchGroupPayrollSubmissionsByCaptureDate(
    PDO $pdo,
    array $captureScopeCtx,
    int $processCompanyId,
    string $captureDate,
    string $permissionCondition,
    array $permissionProcessIds
): array {
    $ledgerDc = dcSubmittedLedgerFilter('dc', 'data_captures');
    $scopeProcessFilter = dcSubmittedProcessScopeFilter('p');

    $stmt = $pdo->prepare("
        SELECT
            dc.id AS capture_id,
            dc.process_id,
            DATE_FORMAT(dc.capture_date, '%Y-%m-%d') AS date_submitted,
            dc.capture_date,
            dc.created_at,
            dc.user_type,
            p.process_id AS process_code,
            d.name AS description_name,
            COALESCE(u.login_id, o.owner_code) AS submitted_by
        FROM data_captures dc
        JOIN process p ON dc.process_id = p.id
        LEFT JOIN description d ON p.description_id = d.id
        LEFT JOIN user u ON dc.created_by = u.id AND dc.user_type = 'user'
        LEFT JOIN owner o ON dc.created_by = o.id AND dc.user_type = 'owner'
        WHERE 1=1
          {$ledgerDc['sql']}
          AND DATE(dc.capture_date) = ?
          AND p.company_id = ?
        {$scopeProcessFilter}
        {$permissionCondition}
        ORDER BY dc.created_at ASC, dc.id ASC
    ");

    $params = array_merge(
        dcCaptureLedgerBindParams($ledgerDc),
        [$captureDate, $processCompanyId],
        $permissionProcessIds
    );
    $stmt->execute($params);
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
    $labeled = dcAnnotateSameDayPayrollSubmissionLabels($rows);

    return array_reverse($labeled);
}

function getSubmissionsByCaptureDate($user_id)
{
    global $pdo, $company_id, $capture_scope_ctx, $capture_scope_group;

    try {
        // 使用全局的 $company_id（已经过验证）
        $currentCompanyId = $company_id;
        $processCompanyId = !empty($capture_scope_ctx)
            ? dcCaptureProcessCompanyId($capture_scope_ctx)
            : $currentCompanyId;
        $ledgerSp = dcSubmittedLedgerFilter('sp', 'submitted_processes');
        $ledgerDc = dcSubmittedLedgerFilter('dc', 'data_captures');

        if (!$currentCompanyId) {
            echo json_encode([
                'success' => false,
                'error' => 'User company_id not found'
            ]);
            return;
        }

        // 获取选择的 capture_date，默认为今天
        $capture_date = $_GET['capture_date'] ?? date('Y-m-d');

        // 验证日期格式
        if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $capture_date)) {
            echo json_encode([
                'success' => false,
                'error' => 'Invalid date format'
            ]);
            return;
        }

        // 获取用户权限（仅对 user 类型，owner 有所有权限）
        $processIds = [];
        $user_type = isset($_SESSION['user_type']) && $_SESSION['user_type'] === 'owner' ? 'owner' : 'user';

        if ($user_type === 'user') {
            try {
                $userStmt = $pdo->prepare("SELECT process_permissions FROM user WHERE id = ?");
                $userStmt->execute([$user_id]);
                $user = $userStmt->fetch(PDO::FETCH_ASSOC);

                // 检查 process_permissions 字段是否存在且非空
                if ($user && isset($user['process_permissions']) && !empty($user['process_permissions'])) {
                    $processPermissions = json_decode($user['process_permissions'], true);

                    // 处理权限数据：可能是对象数组（每个对象有 id 字段）或简单的 ID 数组
                    if (is_array($processPermissions) && !empty($processPermissions)) {
                        // 检查第一个元素是否是对象（有 id 字段）
                        if (isset($processPermissions[0]) && is_array($processPermissions[0]) && isset($processPermissions[0]['id'])) {
                            // 对象数组格式，提取 id
                            $processIds = array_column($processPermissions, 'id');
                        } else {
                            // 简单的 ID 数组格式，直接使用
                            $processIds = $processPermissions;
                        }
                    }
                }
                // 如果 process_permissions 为空或不存在，$processIds 保持为空数组，表示可以看见所有 process
            } catch (PDOException $e) {
                error_log("Error fetching user permissions in getSubmissionsByCaptureDate: " . $e->getMessage());
                // 继续执行，使用空数组（表示可以看见所有 process）
            }
        }
        // owner 类型不需要权限限制，$processIds 保持为空数组

        // 构建权限过滤条件
        $permissionCondition = "";

        // 只有当用户设置了权限（$processIds 不为空）时才添加权限过滤
        if (!empty($processIds) && is_array($processIds)) {
            // 过滤掉非数字的 ID
            $processIds = array_filter($processIds, function ($id) {
                return is_numeric($id);
            });
            $processIds = array_values($processIds); // 重新索引数组

            if (!empty($processIds)) {
                $placeholders = str_repeat('?,', count($processIds) - 1) . '?';
                $permissionCondition = "AND p.id IN ($placeholders)";
            }
        }

        // Check if capture_date column exists by trying to query it
        // If it doesn't exist, fall back to using date_submitted for filtering
        try {
            $testStmt = $pdo->prepare("SELECT capture_date FROM submitted_processes LIMIT 1");
            $testStmt->execute();
            $hasCaptureDateColumn = true;
        } catch (PDOException $e) {
            $hasCaptureDateColumn = false;
        }

        // 账务日：capture_date 为空时退回 date_submitted（与维护页删除、下拉排除一致）
        $spDateFilter = $hasCaptureDateColumn
            ? "DATE(COALESCE(sp.capture_date, sp.date_submitted)) = ?"
            : "DATE(sp.date_submitted) = ?";
        $dateParam = $capture_date;

        // NOT EXISTS 内用关联表达式，避免与账务日列不一致时漏列
        $notExistsSpDateClause = $hasCaptureDateColumn
            ? "DATE(COALESCE(spx.capture_date, spx.date_submitted)) = DATE(dc.capture_date)"
            : "DATE(spx.date_submitted) = DATE(dc.capture_date)";

        $scopeProcessFilter = dcSubmittedProcessScopeFilter('p');

        if ($capture_scope_group) {
            $submissions = dcFetchGroupPayrollSubmissionsByCaptureDate(
                $pdo,
                $capture_scope_ctx,
                (int) $processCompanyId,
                $capture_date,
                $permissionCondition,
                !empty($processIds) ? $processIds : []
            );
            echo json_encode([
                'success' => true,
                'data' => $submissions,
                'capture_date' => $capture_date,
            ]);
            return;
        }

        // 合并 submitted_processes 与已有 data_captures（Summary 成功但 save_submission 未写入时仍能显示/去重）
        $stmt = $pdo->prepare("
            SELECT * FROM (
                SELECT 
                    sp.id,
                    sp.process_id,
                    sp.date_submitted,
                    sp.created_at,
                    sp.user_type,
                    p.process_id as process_code,
                    d.name as description_name,
                    COALESCE(u.login_id, o.owner_code) as submitted_by
                FROM submitted_processes sp
                JOIN process p ON sp.process_id = p.id
                LEFT JOIN description d ON p.description_id = d.id
                LEFT JOIN user u ON sp.user_id = u.id AND sp.user_type = 'user'
                LEFT JOIN owner o ON sp.user_id = o.id AND sp.user_type = 'owner'
                WHERE 1=1
                  {$ledgerSp['sql']}
                  AND $spDateFilter
                  AND p.company_id = ?
                $scopeProcessFilter
                $permissionCondition

                UNION ALL

                SELECT 
                    NULL AS id,
                    dc.process_id,
                    DATE_FORMAT(dc.capture_date, '%Y-%m-%d') AS date_submitted,
                    dc.created_at,
                    dc.user_type,
                    p.process_id as process_code,
                    d.name as description_name,
                    COALESCE(u.login_id, o.owner_code) as submitted_by
                FROM data_captures dc
                JOIN process p ON dc.process_id = p.id
                LEFT JOIN description d ON p.description_id = d.id
                LEFT JOIN user u ON dc.created_by = u.id AND dc.user_type = 'user'
                LEFT JOIN owner o ON dc.created_by = o.id AND dc.user_type = 'owner'
                WHERE 1=1
                  {$ledgerDc['sql']}
                  AND DATE(dc.capture_date) = ?
                  AND p.company_id = ?
                $scopeProcessFilter
                  AND NOT EXISTS (
                      SELECT 1 FROM submitted_processes spx
                      WHERE spx.process_id = dc.process_id
                        AND spx.company_id = dc.company_id
                        AND $notExistsSpDateClause
                  )
                  $permissionCondition
            ) AS merged
            ORDER BY merged.created_at DESC
        ");

        $paramsSegment = array_merge(
            dcCaptureLedgerBindParams($ledgerSp),
            [$dateParam, $processCompanyId],
            !empty($processIds) ? $processIds : []
        );
        $paramsDcSegment = array_merge(
            dcCaptureLedgerBindParams($ledgerDc),
            [$dateParam, $processCompanyId],
            !empty($processIds) ? $processIds : []
        );
        $params = array_merge($paramsSegment, $paramsDcSegment);

        $stmt->execute($params);
        $submissions = $stmt->fetchAll(PDO::FETCH_ASSOC);

        echo json_encode([
            'success' => true,
            'data' => $submissions,
            'capture_date' => $capture_date
        ]);
    } catch (PDOException $e) {
        error_log("SQL Error in getSubmissionsByCaptureDate: " . $e->getMessage());
        error_log("Stack trace: " . $e->getTraceAsString());
        echo json_encode([
            'success' => false,
            'error' => 'Database error: ' . $e->getMessage()
        ]);
    } catch (Exception $e) {
        error_log("Error in getSubmissionsByCaptureDate: " . $e->getMessage());
        error_log("Stack trace: " . $e->getTraceAsString());
        echo json_encode([
            'success' => false,
            'error' => 'Internal error: ' . $e->getMessage()
        ]);
    }
}

// 根据星期几获取processes
function getProcessesByDay($user_id)
{
    global $pdo, $company_id, $capture_scope_ctx;

    // 使用全局的 $company_id（已经过验证）
    $currentCompanyId = $company_id;
    $processCompanyId = !empty($capture_scope_ctx)
        ? dcCaptureProcessCompanyId($capture_scope_ctx)
        : $currentCompanyId;
    $ledgerSp = dcSubmittedLedgerFilter('sp', 'submitted_processes');
    $ledgerDc = dcSubmittedLedgerFilter('dc', 'data_captures');

    if (!$currentCompanyId) {
        echo json_encode([
            'success' => false,
            'error' => 'User company_id not found'
        ]);
        return;
    }

    $selected_date = $_GET['date'] ?? date('Y-m-d');

    // 获取选择的日期是星期几
    $day_of_week = date('N', strtotime($selected_date)); // 1=Monday, 7=Sunday

    // 与 getSubmissionsByCaptureDate、维护页删除 submitted_processes 的逻辑一致：按账务日判断是否已提交
    try {
        $testStmt = $pdo->prepare("SELECT capture_date FROM submitted_processes LIMIT 1");
        $testStmt->execute();
        $hasCaptureDateColumn = true;
    } catch (PDOException $e) {
        $hasCaptureDateColumn = false;
    }
    $submittedDateMatchSql = $hasCaptureDateColumn
        ? "DATE(COALESCE(sp.capture_date, sp.date_submitted)) = ?"
        : "DATE(sp.date_submitted) = ?";

    $scopeProcessFilter = dcSubmittedProcessScopeFilter('p');

    // 已提交：submitted_processes 或已有 data_captures（与维护页一致，避免仅一侧有数据时下拉仍可选）
    // 按 process.process_id（业务代码，如 MGALAXYDM683）排除：同一公司+账务日下任一变体（不同 id / 不同币别描述）已提交则整组不再出现在下拉
    $baseSql = "
        SELECT 
            p.id,
            p.process_id,
            d.name as description_name,
            day.day_name
        FROM process p
        LEFT JOIN description d ON p.description_id = d.id
        JOIN process_day pd ON p.id = pd.process_id
        JOIN day ON pd.day_id = day.id
        WHERE day.id = ?
        AND p.status = 'active'
        AND p.company_id = ?
        $scopeProcessFilter
        AND NOT EXISTS (
            SELECT 1 FROM submitted_processes sp
            WHERE sp.process_id = p.id
              {$ledgerSp['sql']}
              AND $submittedDateMatchSql
        )
        AND NOT EXISTS (
            SELECT 1 FROM data_captures dc
            WHERE dc.process_id = p.id
              {$ledgerDc['sql']}
              AND DATE(dc.capture_date) = ?
        )";

    // 参数顺序：day_of_week, p.company_id, sp scope bind, sp账务日, dc scope bind, dc.capture_date
    $baseParams = array_merge(
        [$day_of_week, $processCompanyId],
        dcCaptureLedgerBindParams($ledgerSp),
        [$selected_date],
        dcCaptureLedgerBindParams($ledgerDc),
        [$selected_date]
    );

    // 应用权限过滤（与查询的 company_id 一致，勿用可能滞后的 session 公司）
    list($baseSql, $baseParams) = filterProcessesByPermissions($pdo, $baseSql, $baseParams, $currentCompanyId);

    // 添加排序
    $baseSql .= " ORDER BY p.process_id ASC";

    try {
        $stmt = $pdo->prepare($baseSql);
        $stmt->execute($baseParams);
        $processes = $stmt->fetchAll(PDO::FETCH_ASSOC);
        // 抓取 Process 时返回完整显示值，例如 F9EJMSUB (JOKER API)
        foreach ($processes as &$proc) {
            $proc['process_display'] = (!empty($proc['description_name']))
                ? $proc['process_id'] . ' (' . $proc['description_name'] . ')'
                : $proc['process_id'];
        }
        unset($proc);

        echo json_encode([
            'success' => true,
            'data' => $processes,
            'selected_date' => $selected_date,
            'day_of_week' => $day_of_week
        ]);
    } catch (PDOException $e) {
        error_log("SQL Error in getProcessesByDay: " . $e->getMessage());
        echo json_encode([
            'success' => false,
            'error' => 'Database error: ' . $e->getMessage()
        ]);
    }
}

// 保存新的提交记录
function saveSubmission($user_id)
{
    global $pdo, $company_id, $capture_scope_ctx, $capture_scope_group;

    try {
        if (is_partnership_audit_read_only_active($pdo)) {
            echo json_encode(['success' => false, 'error' => '只读账号无法执行此操作']);
            return;
        }

        // 获取POST数据
        $process_id = $_POST['process_id'] ?? '';
        $date_submitted = $_POST['date_submitted'] ?? date('Y-m-d');
        $capture_date = $_POST['capture_date'] ?? $date_submitted; // Default to date_submitted if not provided

        // 检查当前用户是 owner 还是 user
        $user_type = isset($_SESSION['user_type']) && $_SESSION['user_type'] === 'owner' ? 'owner' : 'user';

        // 添加调试日志
        error_log("Save submission - User ID: $user_id, User Type: $user_type, Process ID: $process_id, Date: $date_submitted");

        // 验证必需字段
        if (empty($process_id)) {
            error_log("Missing process_id in saveSubmission");
            echo json_encode(['success' => false, 'error' => 'Missing process_id']);
            return;
        }

        // 确保 process_id 是整数
        $process_id = (int) $process_id;
        if ($process_id <= 0) {
            error_log("Invalid process_id in saveSubmission: " . $_POST['process_id']);
            echo json_encode(['success' => false, 'error' => 'Invalid process_id']);
            return;
        }

        // 验证日期格式
        if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $date_submitted)) {
            error_log("Invalid date format in saveSubmission: $date_submitted");
            echo json_encode(['success' => false, 'error' => 'Invalid date format']);
            return;
        }

        // 验证 capture_date 格式
        if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $capture_date)) {
            error_log("Invalid capture_date format in saveSubmission: $capture_date");
            echo json_encode(['success' => false, 'error' => 'Invalid capture_date format']);
            return;
        }

        // 获取 company_id（通过 process 表）
        $processStmt = $pdo->prepare("SELECT company_id FROM process WHERE id = ? LIMIT 1");
        $processStmt->execute([$process_id]);
        $process = $processStmt->fetch(PDO::FETCH_ASSOC);

        if (!$process || !isset($process['company_id'])) {
            error_log("Failed to get process company_id for process_id: $process_id");
            echo json_encode(['success' => false, 'error' => '无法获取 process 的 company_id']);
            return;
        }

        $processCompanyId = (int) $process['company_id'];

        $expectedProcessCompanyId = !empty($capture_scope_ctx)
            ? dcCaptureProcessCompanyId($capture_scope_ctx)
            : (int) $company_id;
        if (!$expectedProcessCompanyId) {
            error_log("Missing company_id in session for saveSubmission");
            echo json_encode(['success' => false, 'error' => '缺少公司信息']);
            return;
        }

        if ($processCompanyId != $expectedProcessCompanyId) {
            error_log("Process company_id ($processCompanyId) does not match scope ($expectedProcessCompanyId)");
            echo json_encode(['success' => false, 'error' => 'Process 不属于当前公司']);
            return;
        }

        dcAssertProcessIdInCaptureScope(
            $pdo,
            $process_id,
            $expectedProcessCompanyId,
            (bool) $capture_scope_group
        );

        $scopeInsert = !empty($capture_scope_ctx)
            ? dcCaptureScopeInsertValues($capture_scope_ctx)
            : ['company_id' => $expectedProcessCompanyId, 'scope_type' => null, 'scope_id' => null];
        $storeCompanyId = (int) ($scopeInsert['company_id'] ?? $expectedProcessCompanyId);
        $useScopeColumns = !empty($capture_scope_ctx['submitted_dual_tenant']);

        // Group payroll: allow multiple submissions per process per capture day (list uses data_captures).
        if (!$capture_scope_group) {
            if ($useScopeColumns) {
                $checkStmt = $pdo->prepare("
                    SELECT id FROM submitted_processes 
                    WHERE scope_type = ?
                      AND scope_id = ?
                      AND user_id = ? 
                      AND user_type = ? 
                      AND process_id = ? 
                      AND date_submitted = ?
                    LIMIT 1
                ");
                $checkStmt->execute([
                    $scopeInsert['scope_type'],
                    $scopeInsert['scope_id'],
                    $user_id,
                    $user_type,
                    $process_id,
                    $date_submitted,
                ]);
            } else {
                $checkStmt = $pdo->prepare("
                    SELECT id FROM submitted_processes 
                    WHERE company_id = ? 
                      AND user_id = ? 
                      AND user_type = ? 
                      AND process_id = ? 
                      AND date_submitted = ?
                    LIMIT 1
                ");
                $checkStmt->execute([$storeCompanyId, $user_id, $user_type, $process_id, $date_submitted]);
            }
            $existing = $checkStmt->fetch(PDO::FETCH_ASSOC);

            if ($existing) {
                error_log("Submission already exists with ID: " . $existing['id']);
                echo json_encode([
                    'success' => true,
                    'submission_id' => $existing['id'],
                    'message' => 'Submission already exists',
                    'already_exists' => true,
                ]);
                return;
            }
        }

        // Try to insert with capture_date field (if it exists in the table)
        // If the field doesn't exist, the SQL will fail and we'll try without it
        try {
            if ($useScopeColumns) {
                $stmt = $pdo->prepare("
                    INSERT INTO submitted_processes (company_id, scope_type, scope_id, user_id, user_type, process_id, date_submitted, capture_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ");
                $success = $stmt->execute([
                    $storeCompanyId,
                    $scopeInsert['scope_type'],
                    $scopeInsert['scope_id'],
                    $user_id,
                    $user_type,
                    $process_id,
                    $date_submitted,
                    $capture_date,
                ]);
            } else {
                $stmt = $pdo->prepare("
                    INSERT INTO submitted_processes (company_id, user_id, user_type, process_id, date_submitted, capture_date)
                    VALUES (?, ?, ?, ?, ?, ?)
                ");
                $success = $stmt->execute([$storeCompanyId, $user_id, $user_type, $process_id, $date_submitted, $capture_date]);
            }
        } catch (PDOException $e) {
            // If capture_date column doesn't exist, try without it
            if (strpos($e->getMessage(), 'Unknown column') !== false && strpos($e->getMessage(), 'capture_date') !== false) {
                error_log("capture_date column doesn't exist, inserting without it");
                if ($useScopeColumns) {
                    $stmt = $pdo->prepare("
                        INSERT INTO submitted_processes (company_id, scope_type, scope_id, user_id, user_type, process_id, date_submitted)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                    ");
                    $success = $stmt->execute([
                        $storeCompanyId,
                        $scopeInsert['scope_type'],
                        $scopeInsert['scope_id'],
                        $user_id,
                        $user_type,
                        $process_id,
                        $date_submitted,
                    ]);
                } else {
                    $stmt = $pdo->prepare("
                        INSERT INTO submitted_processes (company_id, user_id, user_type, process_id, date_submitted)
                        VALUES (?, ?, ?, ?, ?)
                    ");
                    $success = $stmt->execute([$storeCompanyId, $user_id, $user_type, $process_id, $date_submitted]);
                }
            } else {
                throw $e; // Re-throw if it's a different error
            }
        }

        if ($success) {
            $submission_id = $pdo->lastInsertId();
            error_log("Submission saved successfully with ID: $submission_id (Type: $user_type)");
            echo json_encode([
                'success' => true,
                'submission_id' => $submission_id,
                'message' => 'Submission saved successfully'
            ]);
        } else {
            $error = $stmt->errorInfo();
            error_log("Failed to save submission: " . $error[2]);
            echo json_encode(['success' => false, 'error' => 'Failed to save submission: ' . $error[2]]);
        }
    } catch (PDOException $e) {
        error_log("SQL Error in saveSubmission: " . $e->getMessage());
        error_log("Stack trace: " . $e->getTraceAsString());
        echo json_encode(['success' => false, 'error' => 'Database error: ' . $e->getMessage()]);
    } catch (Exception $e) {
        error_log("Error in saveSubmission: " . $e->getMessage());
        error_log("Stack trace: " . $e->getTraceAsString());
        echo json_encode(['success' => false, 'error' => 'Internal error: ' . $e->getMessage()]);
    }
}

// 获取今天物理提交的记录 (不管 capture_date 是哪一天)
function getTodayEntries($user_id)
{
    global $pdo, $company_id;

    try {
        $currentCompanyId = $company_id;
        if (!$currentCompanyId) {
            echo json_encode(['success' => false, 'error' => 'Company ID not found']);
            return;
        }

        // 获取当前用户的权限
        $processIds = [];
        $user_type = $_SESSION['user_type'] ?? 'user';
        if ($user_type === 'user') {
            $userStmt = $pdo->prepare("SELECT process_permissions FROM user_company_permissions WHERE user_id = ? AND company_id = ?");
            $userStmt->execute([$user_id, $currentCompanyId]);
            $user = $userStmt->fetch(PDO::FETCH_ASSOC);
            if ($user && !empty($user['process_permissions'])) {
                $processPermissions = json_decode($user['process_permissions'], true);
                if (is_array($processPermissions)) {
                    if (isset($processPermissions[0]['id'])) {
                        $processIds = array_column($processPermissions, 'id');
                    } else {
                        $processIds = $processPermissions;
                    }
                }
            }
        }

        // 构建权限过滤条件
        $permissionCondition = "";
        $params = [$currentCompanyId];

        if (!empty($processIds)) {
            $placeholders = str_repeat('?,', count($processIds) - 1) . '?';
            $permissionCondition = "AND p.id IN ($placeholders)";
            $params = array_merge($params, $processIds);
        }

        $stmt = $pdo->prepare("
            SELECT 
                sp.id, sp.process_id, sp.date_submitted, sp.capture_date, sp.created_at,
                p.process_id as process_code, d.name as description_name,
                COALESCE(u.login_id, o.owner_code) as submitted_by
            FROM submitted_processes sp
            JOIN process p ON sp.process_id = p.id
            LEFT JOIN description d ON p.description_id = d.id
            LEFT JOIN user u ON sp.user_id = u.id AND sp.user_type = 'user'
            LEFT JOIN owner o ON sp.user_id = o.id AND sp.user_type = 'owner'
            WHERE sp.company_id = ?
              AND DATE(sp.created_at) = CURDATE()
              $permissionCondition
            ORDER BY sp.created_at DESC
        ");

        $stmt->execute($params);
        $submissions = $stmt->fetchAll(PDO::FETCH_ASSOC);

        echo json_encode([
            'success' => true,
            'data' => $submissions
        ]);
    } catch (Exception $e) {
        error_log("Error in getTodayEntries: " . $e->getMessage());
        echo json_encode(['success' => false, 'error' => $e->getMessage()]);
    }
}

function getGroupProcessId()
{
    global $pdo, $company_id, $capture_scope_group, $scopeParams, $capture_scope_ctx;

    $processCode = strtoupper(trim((string) ($_GET['process_code'] ?? '')));
    if ($processCode === '') {
        echo json_encode(['success' => false, 'error' => 'Missing process_code']);
        return;
    }

    $groupIdForEnsure = dcNormalizeGroupId(
        $scopeParams['group_id'] ?? $scopeParams['view_group'] ?? ''
    );
    $preferredCurrencyId = isset($_GET['currency_id']) ? (int) $_GET['currency_id'] : 0;
    if ($preferredCurrencyId <= 0 && isset($_POST['currency_id'])) {
        $preferredCurrencyId = (int) $_POST['currency_id'];
    }

    $entityCompanyId = !empty($capture_scope_ctx)
        ? dcCaptureProcessCompanyId($capture_scope_ctx)
        : (int) $company_id;
    if ($entityCompanyId <= 0 && $capture_scope_group && $groupIdForEnsure !== '') {
        $entityCompanyId = gc_resolve_group_anchor_company_id($pdo, $groupIdForEnsure);
        if ($entityCompanyId <= 0) {
            $resolvedEntity = tx_resolve_group_entity_company_id($pdo, $groupIdForEnsure);
            if ($resolvedEntity > 0) {
                $entityCompanyId = $resolvedEntity;
            }
        }
    }

    $processId = dcEnsureProcessIdByCode(
        $pdo,
        $entityCompanyId,
        $processCode,
        (bool) $capture_scope_group,
        $groupIdForEnsure !== '' ? $groupIdForEnsure : null,
        $preferredCurrencyId > 0 ? $preferredCurrencyId : null
    );
    if ($processId === null) {
        $detail = dcGroupProcessEnsureLastError();
        echo json_encode([
            'success' => false,
            'error' => $detail !== '' ? $detail : 'Process not found for scope',
        ]);
        return;
    }

    dcFixGroupPayrollProcessDescription($pdo, (int) $processId);

    echo json_encode([
        'success' => true,
        'data' => [
            'process_id' => $processId,
            'process_code' => $processCode,
        ],
    ]);
}
?>