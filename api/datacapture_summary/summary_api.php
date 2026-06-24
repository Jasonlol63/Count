<?php
// 确保 Session Cookie 在同站 POST（如 fetch 提交）时会被发送，避免无痕/部分环境下 403
if (PHP_VERSION_ID >= 70300) {
    session_set_cookie_params([
        'lifetime' => 0,
        'path' => '/',
        'secure' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') || (!empty($_SERVER['HTTP_X_FORWARDED_PROTO']) && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https'),
        'httponly' => true,
        'samesite' => 'Lax'
    ]);
}
session_start();
session_write_close(); // 释放 session 锁，允许并发 AJAX 请求并行执行
header('Content-Type: application/json');
require_once __DIR__ . '/../../includes/config.php';
require_once __DIR__ . '/../includes/money_decimal.php';

// Helper function to convert PHP ini size values to bytes
function return_bytes($val) {
    $val = trim($val);
    $last = strtolower($val[strlen($val)-1]);
    $val = (int)$val;
    switch($last) {
        case 'g':
            $val *= 1024;
        case 'm':
            $val *= 1024;
        case 'k':
            $val *= 1024;
    }
    return $val;
}

/**
 * 根据 company_id 校验/解析 currency_id，必要时根据 currency_code 匹配。
 */
function resolveCompanyCurrencyId(PDO $pdo, int $companyId, $currencyId = null, ?string $currencyCode = null) {
    static $cacheById = [];
    static $cacheByCode = [];

    if ($currencyId !== null && $currencyId !== '') {
        $currencyId = (int)$currencyId;
        $cacheKey = $companyId . ':' . $currencyId;
        if (array_key_exists($cacheKey, $cacheById)) {
            return $cacheById[$cacheKey];
        }
        $stmt = $pdo->prepare("SELECT id, UPPER(code) AS code FROM currency WHERE company_id = ? AND id = ? LIMIT 1");
        $stmt->execute([$companyId, $currencyId]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        if ($row) {
            $cacheById[$cacheKey] = (int)$row['id'];
            $cacheByCode[$companyId . ':' . $row['code']] = (int)$row['id'];
            return $cacheById[$cacheKey];
        }
        $cacheById[$cacheKey] = null;
    }

    if ($currencyCode) {
        $currencyCode = strtoupper(trim($currencyCode));
        $cacheCodeKey = $companyId . ':' . $currencyCode;
        if (array_key_exists($cacheCodeKey, $cacheByCode)) {
            return $cacheByCode[$cacheCodeKey];
        }
        $stmt = $pdo->prepare("SELECT id FROM currency WHERE company_id = ? AND UPPER(code) = ? LIMIT 1");
        $stmt->execute([$companyId, $currencyCode]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        if ($row) {
            $cacheByCode[$cacheCodeKey] = (int)$row['id'];
            $cacheById[$companyId . ':' . (int)$row['id']] = (int)$row['id'];
            return (int)$row['id'];
        }
        $cacheByCode[$cacheCodeKey] = null;
    }

    return null;
}

/** data_capture_details.display_order 是否存在（请求内只查一次） */
function summaryApiHasDisplayOrder(PDO $pdo): bool
{
    static $v = null;
    if ($v === null) {
        try {
            $st = $pdo->query("SHOW COLUMNS FROM data_capture_details LIKE 'display_order'");
            $v = $st && $st->fetch(PDO::FETCH_ASSOC) !== false;
        } catch (Throwable $e) { $v = false; }
    }
    return $v;
}

function ensureTemplateSchema(PDO $pdo) {
    static $checked = false;
    if ($checked) {
        return;
    }
    $checked = true;

    try {
        $columnStmt = $pdo->query("SHOW COLUMNS FROM data_capture_templates LIKE 'product_type'");
        $hasProductType = $columnStmt && $columnStmt->fetch(PDO::FETCH_ASSOC);

        if (!$hasProductType) {
            $pdo->exec("
                ALTER TABLE data_capture_templates
                ADD COLUMN product_type ENUM('main','sub') NOT NULL DEFAULT 'main' AFTER id_product,
                ADD COLUMN parent_id_product VARCHAR(255) NULL AFTER product_type,
                ADD COLUMN template_key VARCHAR(255) NOT NULL DEFAULT '' AFTER parent_id_product
            ");

            try {
                $pdo->exec("ALTER TABLE data_capture_templates DROP INDEX id_product");
            } catch (Exception $e) {
                error_log('Template schema drop index warning: ' . $e->getMessage());
            }

            // Drop old unique index if exists
            try {
                $pdo->exec("ALTER TABLE data_capture_templates DROP INDEX template_unique");
            } catch (Exception $e) {
                error_log('Template schema drop old unique index warning: ' . $e->getMessage());
            }

            // Add new unique index that includes process_id to prevent duplicates within same process
            // For templates (data_capture_id IS NULL), uniqueness is based on (process_id, product_type, template_key)
            // For capture-specific templates (data_capture_id IS NOT NULL), they can coexist with general templates
            try {
                $pdo->exec("ALTER TABLE data_capture_templates ADD UNIQUE KEY template_unique (process_id, product_type, template_key, data_capture_id)");
            } catch (Exception $e) {
                error_log('Template schema add index warning: ' . $e->getMessage());
            }

            $pdo->exec("
                UPDATE data_capture_templates
                SET product_type = 'main',
                    template_key = CASE WHEN template_key = '' THEN id_product ELSE template_key END
            ");
        } else {
            $indexStmt = $pdo->query("SHOW INDEX FROM data_capture_templates WHERE Key_name = 'template_unique'");
            $hasTemplateIndex = $indexStmt && $indexStmt->fetch(PDO::FETCH_ASSOC);
            if (!$hasTemplateIndex) {
                // Drop old unique index if exists (in case it has different columns)
                try {
                    $pdo->exec("ALTER TABLE data_capture_templates DROP INDEX template_unique");
                } catch (Exception $e) {
                    error_log('Template schema drop old unique index warning: ' . $e->getMessage());
                }
                
                // Add new unique index that includes process_id to prevent duplicates within same process
                try {
                    $pdo->exec("ALTER TABLE data_capture_templates ADD UNIQUE KEY template_unique (process_id, product_type, template_key, data_capture_id)");
                } catch (Exception $e) {
                    error_log('Template schema add index warning: ' . $e->getMessage());
                }
            } else {
                // Check if the index has the correct columns
                $indexStmt = $pdo->query("SHOW INDEX FROM data_capture_templates WHERE Key_name = 'template_unique'");
                $indexColumns = [];
                while ($row = $indexStmt->fetch(PDO::FETCH_ASSOC)) {
                    $indexColumns[] = $row['Column_name'];
                }
                
                // If index doesn't include process_id or data_capture_id, recreate it
                if (!in_array('process_id', $indexColumns) || !in_array('data_capture_id', $indexColumns)) {
                    try {
                        $pdo->exec("ALTER TABLE data_capture_templates DROP INDEX template_unique");
                        $pdo->exec("ALTER TABLE data_capture_templates ADD UNIQUE KEY template_unique (process_id, product_type, template_key, data_capture_id)");
                        error_log('Template schema: Recreated unique index with process_id and data_capture_id');
                    } catch (Exception $e) {
                        error_log('Template schema recreate index warning: ' . $e->getMessage());
                    }
                }
            }
        }
        
        // Ensure process_id column is INT(11) to store process.id (not process.process_id)
        try {
            $processIdColumnStmt = $pdo->query("SHOW COLUMNS FROM data_capture_templates LIKE 'process_id'");
            $processIdColumn = $processIdColumnStmt ? $processIdColumnStmt->fetch(PDO::FETCH_ASSOC) : null;
            if ($processIdColumn && stripos($processIdColumn['Type'] ?? '', 'int') === false) {
                // If column exists but is not INT, we need to migrate it
                // This should be done via the migration script first
                error_log('Template schema: process_id column should be INT(11), but found: ' . ($processIdColumn['Type'] ?? 'unknown'));
                error_log('Please run migrate_data_capture_templates_process_id_to_int.sql migration script first');
            }
        } catch (Exception $columnException) {
            error_log('Template schema process_id check warning: ' . $columnException->getMessage());
        }

        // Ensure row_index column exists to preserve row ordering in summary table
        try {
            $rowIndexColumnStmt = $pdo->query("SHOW COLUMNS FROM data_capture_templates LIKE 'row_index'");
            $hasRowIndex = $rowIndexColumnStmt && $rowIndexColumnStmt->fetch(PDO::FETCH_ASSOC);
            if (!$hasRowIndex) {
                $pdo->exec("ALTER TABLE data_capture_templates ADD COLUMN row_index INT NULL AFTER data_capture_id");
                error_log('Template schema: Added row_index column to data_capture_templates');
            }
        } catch (Exception $columnException) {
            error_log('Template schema row_index alteration warning: ' . $columnException->getMessage());
        }

        // Ensure data_capture_details.rate supports at least 8 decimal places
        // so Payment History can display the same precision as Data Summary Rate Value.
        try {
            $rateColumnStmt = $pdo->query("SHOW COLUMNS FROM data_capture_details LIKE 'rate'");
            $rateColumn = $rateColumnStmt ? $rateColumnStmt->fetch(PDO::FETCH_ASSOC) : null;
            if ($rateColumn) {
                $rateType = strtolower((string)($rateColumn['Type'] ?? ''));
                $needsUpgrade = false;

                // Examples: decimal(10,4), decimal(15,6)
                if (preg_match('/decimal\(\s*\d+\s*,\s*(\d+)\s*\)/i', $rateType, $matches)) {
                    $scale = (int)$matches[1];
                    $needsUpgrade = $scale < 8;
                } elseif ($rateType !== '' && strpos($rateType, 'decimal') !== 0) {
                    // Non-decimal numeric type: normalize to decimal for stable precision.
                    $needsUpgrade = true;
                }

                if ($needsUpgrade) {
                    $pdo->exec("ALTER TABLE data_capture_details MODIFY COLUMN rate DECIMAL(25,8) NULL");
                    error_log('Template schema: Upgraded data_capture_details.rate to DECIMAL(25,8)');
                }
            }
        } catch (Exception $columnException) {
            error_log('Template schema rate precision alteration warning: ' . $columnException->getMessage());
        }
    } catch (Exception $e) {
        error_log('Template schema ensure error: ' . $e->getMessage());
    }
}

/**
 * 确保 data_capture_summary_state 表存在，用于服务端持久化 Summary 行顺序与公式/ Rate 等，避免仅依赖 localStorage 导致刷新后顺序不稳或数据丢失。
 */
function ensureSummaryStateTable(PDO $pdo) {
    static $checked = false;
    if ($checked) {
        return;
    }
    $checked = true;
    try {
        $pdo->exec("
            CREATE TABLE IF NOT EXISTS data_capture_summary_state (
                id INT AUTO_INCREMENT PRIMARY KEY,
                company_id INT NOT NULL,
                process_key VARCHAR(255) NOT NULL,
                state_json LONGTEXT NOT NULL,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                UNIQUE KEY uk_company_process (company_id, process_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        ");
    } catch (Exception $e) {
        error_log('Summary state table ensure error: ' . $e->getMessage());
    }
}

/** Ensure data_capture_summary_state has scope_type / scope_id (idempotent). */
function dcEnsureSummaryStateScopeColumns(PDO $pdo): bool
{
    static $checked = false;
    static $hasScope = false;
    if ($checked) {
        return $hasScope;
    }
    $checked = true;
    try {
        ensureSummaryStateTable($pdo);
        if ($pdo->query("SHOW COLUMNS FROM data_capture_summary_state LIKE 'scope_type'")->rowCount() > 0) {
            $hasScope = true;
            return true;
        }
        $pdo->exec("
            ALTER TABLE data_capture_summary_state
              ADD COLUMN scope_type ENUM('company','group') NOT NULL DEFAULT 'company' AFTER company_id,
              ADD COLUMN scope_id BIGINT UNSIGNED NULL AFTER scope_type
        ");
        $pdo->exec("
            UPDATE data_capture_summary_state
            SET scope_type = 'company', scope_id = company_id
            WHERE scope_id IS NULL OR scope_id = 0
        ");
        try {
            $pdo->exec("ALTER TABLE data_capture_summary_state DROP INDEX uk_company_process");
        } catch (Exception $dropException) {
            error_log('Summary state drop legacy unique key: ' . $dropException->getMessage());
        }
        try {
            $pdo->exec("
                ALTER TABLE data_capture_summary_state
                ADD UNIQUE KEY uk_company_process_scope (company_id, process_key, scope_type, scope_id)
            ");
        } catch (Exception $addException) {
            error_log('Summary state add scoped unique key: ' . $addException->getMessage());
        }
        $hasScope = true;
    } catch (Exception $e) {
        error_log('dcEnsureSummaryStateScopeColumns: ' . $e->getMessage());
    }
    return $hasScope;
}

/** Scope bind values for summary state read/write. */
function resolveSummaryStateScopeBind(?array $captureScopeCtx, int $companyId): array
{
    if (is_array($captureScopeCtx) && $captureScopeCtx !== []) {
        $ctx = $captureScopeCtx;
        $ctx['dual_tenant'] = true;
        $insert = dcCaptureScopeInsertValues($ctx);
        return [
            'scope_type' => (string) ($insert['scope_type'] ?? 'company'),
            'scope_id' => (int) ($insert['scope_id'] ?? $companyId),
        ];
    }

    return [
        'scope_type' => 'company',
        'scope_id' => $companyId,
    ];
}

/**
 * 快速提交队列（用于“先立即回前端，再后台处理”）。
 */
function ensureSummarySubmitQueueTable(PDO $pdo) {
    static $checked = false;
    if ($checked) {
        return;
    }
    $checked = true;
    try {
        $pdo->exec("
            CREATE TABLE IF NOT EXISTS data_capture_submit_queue (
                id INT AUTO_INCREMENT PRIMARY KEY,
                company_id INT NOT NULL,
                user_id INT NULL,
                status VARCHAR(20) NOT NULL DEFAULT 'processing',
                request_json LONGTEXT NOT NULL,
                capture_id INT NULL,
                rows_count INT NOT NULL DEFAULT 0,
                error_message TEXT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                finished_at DATETIME NULL,
                INDEX idx_company_status (company_id, status),
                INDEX idx_created_at (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        ");
    } catch (Exception $e) {
        error_log('Submit queue table ensure error: ' . $e->getMessage());
    }
}

function computeTemplateKey(array $row): string {
    $productType = $row['product_type'] ?? 'main';

    if ($productType === 'sub') {
        $parent = trim((string)($row['parent_id_product'] ?? $row['id_product_main'] ?? ''));
        $subId = trim((string)($row['id_product_sub'] ?? $row['id_product'] ?? ''));
        $description = trim((string)($row['description_sub'] ?? $row['description'] ?? ''));
        $accountId = trim((string)($row['account_id'] ?? ''));
        $subOrder = isset($row['sub_order']) && $row['sub_order'] !== null && $row['sub_order'] !== '' ? (string)$row['sub_order'] : '';

        if ($subId === '' && $parent === '') {
            $parent = 'sub';
        }

        // 与 main 一致：sub 的 template_key 使用 parent_id_product，并加上 account_id 区分同 parent 下多 account（避免 2 条 sub 共用一个 key 互相覆盖或产生重复）
        $baseKey = $parent !== '' ? $parent : ($subId !== '' ? $subId : '');
        $accountId = trim((string)($row['account_id'] ?? ''));
        if ($baseKey !== '') {
            $key = $accountId !== '' ? $baseKey . '_' . $accountId : $baseKey;
            if ($subOrder !== '') {
                $key .= '_so' . $subOrder;
            }
            return substr($key, 0, 250);
        }

        // 无 parent/sub 时用长格式保证唯一
        $keyParts = [$parent, $subId !== '' ? $subId : $parent, $description, $accountId, $subOrder];
        $key = implode('::', array_map(static function ($part) {
            return trim((string)$part);
        }, $keyParts));
        if ($key === '::::' || $key === ':::::') {
            $key = 'sub-' . md5(json_encode($row));
        }
        return substr($key, 0, 250);
    }

    $idProduct = trim((string)($row['id_product'] ?? $row['id_product_main'] ?? ''));
    if ($idProduct === '') {
        $idProduct = 'main-' . md5(json_encode($row));
    }

    return substr($idProduct, 0, 250);
}

function summary_money_value(array $row, string $key, string $default = '0'): string
{
    if (!array_key_exists($key, $row) || $row[$key] === null || trim((string)$row[$key]) === '') {
        return money_normalize($default);
    }
    return money_normalize($row[$key]);
}

ensureTemplateSchema($pdo);

/**
 * 获取与当前 process 处于同一 copy/sync 组的其它流程（双向）。
 * 规则：
 * - 若当前是子流程（sync_source_process_id 有值），锚点为其源流程；
 * - 若当前是源流程（存在子流程指向它），锚点为自己；
 * - 同步目标为：源流程 + 全部同源子流程，排除当前流程自身。
 */
function getLinkedProcessTargets(PDO $pdo, int $processId, int $companyId): array
{
    $currentStmt = $pdo->prepare("
        SELECT id, process_id, sync_source_process_id
        FROM process
        WHERE id = ? AND company_id = ?
        LIMIT 1
    ");
    $currentStmt->execute([$processId, $companyId]);
    $current = $currentStmt->fetch(PDO::FETCH_ASSOC);
    if (!$current) {
        return [];
    }

    $anchorId = !empty($current['sync_source_process_id'])
        ? (int)$current['sync_source_process_id']
        : (int)$current['id'];

    $targetStmt = $pdo->prepare("
        SELECT id, process_id
        FROM process
        WHERE company_id = ?
          AND (id = ? OR sync_source_process_id = ?)
          AND id <> ?
    ");
    $targetStmt->execute([$companyId, $anchorId, $anchorId, $processId]);

    return $targetStmt->fetchAll(PDO::FETCH_ASSOC) ?: [];
}

/**
 * 同步 Formula 到所有关联的 Multi-use Processes
 * 当源 Process 的 Formula 更新时，自动同步到所有 sync_source_process_id 指向该源 Process 的 Processes
 */
function syncFormulaToMultiUseProcesses(PDO $pdo, int $sourceProcessId, array $templateData, int $companyId) {
    global $capture_scope_group;
    if (!empty($capture_scope_group)) {
        return;
    }
    try {
        $syncedProcesses = getLinkedProcessTargets($pdo, $sourceProcessId, $companyId);
        
        if (empty($syncedProcesses)) {
            return; // 没有需要同步的 Processes
        }
        
        error_log("Syncing formula to " . count($syncedProcesses) . " multi-use processes for source process ID: $sourceProcessId");
        
        // 为每个关联的 Process 同步 Formula
        foreach ($syncedProcesses as $syncedProcess) {
            $targetProcessId = $syncedProcess['id'];
            $targetProcessCode = $syncedProcess['process_id'];
            
            try {
                // 查找目标 Process 中对应的 template（基于 id_product, account_id, product_type, formula_variant；sub 行另加 sub_order）
                $productType = $templateData['product_type'] ?? 'main';
                $subOrder = isset($templateData['sub_order']) && $templateData['sub_order'] !== null && $templateData['sub_order'] !== '' ? (float)$templateData['sub_order'] : null;
                $hasSubOrder = $productType === 'sub' && $subOrder !== null;
                $sql = "
                    SELECT id FROM data_capture_templates 
                    WHERE process_id = ? 
                      AND company_id = ?
                      AND id_product = ?
                      AND account_id = ?
                      AND product_type = ?
                      AND formula_variant = ?
                " . ($hasSubOrder ? " AND (COALESCE(sub_order, 0) = COALESCE(?, 0))" : "") . "
                    LIMIT 1
                ";
                $findTemplateStmt = $pdo->prepare($sql);
                $params = [
                    $targetProcessId,
                    $companyId,
                    $templateData['id_product'],
                    $templateData['account_id'],
                    $productType,
                    $templateData['formula_variant']
                ];
                if ($hasSubOrder) {
                    $params[] = $subOrder;
                }
                $findTemplateStmt->execute($params);
                $targetTemplate = $findTemplateStmt->fetch(PDO::FETCH_ASSOC);
                
                if ($targetTemplate) {
                    // 更新已存在的 template（Source、Rate、Formula 等全部覆盖）
                    $updateStmt = $pdo->prepare("
                        UPDATE data_capture_templates SET
                            source_columns = ?,
                            formula_operators = ?,
                            source_percent = ?,
                            enable_source_percent = ?,
                            input_method = ?,
                            enable_input_method = ?,
                            batch_selection = COALESCE(?, batch_selection),
                            columns_display = ?,
                            formula_display = ?,
                            description = ?,
                            account_display = ?,
                            currency_id = ?,
                            currency_display = ?,
                            last_source_value = COALESCE(?, last_source_value),
                            last_processed_amount = COALESCE(?, last_processed_amount),
                            updated_at = NOW()
                        WHERE id = ?
                    ");
                    $updateStmt->execute([
                        $templateData['source_columns'],
                        $templateData['formula_operators'],
                        $templateData['source_percent'],
                        $templateData['enable_source_percent'],
                        $templateData['input_method'],
                        $templateData['enable_input_method'],
                        isset($templateData['batch_selection']) ? (int)$templateData['batch_selection'] : null,
                        $templateData['columns_display'],
                        $templateData['formula_display'],
                        $templateData['description'],
                        $templateData['account_display'],
                        $templateData['currency_id'],
                        $templateData['currency_display'],
                        isset($templateData['last_source_value']) ? $templateData['last_source_value'] : null,
                        isset($templateData['last_processed_amount']) ? money_normalize($templateData['last_processed_amount']) : null,
                        $targetTemplate['id']
                    ]);
                    error_log("Updated template ID {$targetTemplate['id']} for process $targetProcessCode (ID: $targetProcessId)");
                } else {
                    // 新增同步：目标无该 Id_Product 行则插入对应 template
                    $insStmt = $pdo->prepare("
                        INSERT INTO data_capture_templates (
                            company_id, process_id, id_product, product_type, parent_id_product,
                            template_key, description, account_id, account_display,
                            currency_id, currency_display, source_columns, formula_operators,
                            source_percent, enable_source_percent, input_method, enable_input_method,
                            batch_selection, columns_display, formula_display,
                            last_source_value, last_processed_amount, row_index, sub_order, formula_variant, data_capture_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ");
                    $templateKey = isset($templateData['template_key']) && $templateData['template_key'] !== '' ? $templateData['template_key'] : null;
                    if ($templateKey === null && !empty($templateData['id_product'])) {
                        $templateKey = $templateData['id_product'] . '_' . ($templateData['account_id'] ?? '') . '_' . ($templateData['formula_variant'] ?? 0);
                    }
                    $insStmt->execute([
                        $companyId,
                        $targetProcessId,
                        $templateData['id_product'],
                        $productType,
                        isset($templateData['parent_id_product']) ? $templateData['parent_id_product'] : null,
                        $templateKey,
                        isset($templateData['description']) ? $templateData['description'] : null,
                        $templateData['account_id'],
                        isset($templateData['account_display']) ? $templateData['account_display'] : null,
                        isset($templateData['currency_id']) ? $templateData['currency_id'] : null,
                        isset($templateData['currency_display']) ? $templateData['currency_display'] : null,
                        $templateData['source_columns'],
                        $templateData['formula_operators'],
                        isset($templateData['source_percent']) ? $templateData['source_percent'] : '1',
                        isset($templateData['enable_source_percent']) ? (int)$templateData['enable_source_percent'] : 1,
                        isset($templateData['input_method']) ? $templateData['input_method'] : null,
                        isset($templateData['enable_input_method']) ? (int)$templateData['enable_input_method'] : 0,
                        isset($templateData['batch_selection']) ? (int)$templateData['batch_selection'] : 0,
                        isset($templateData['columns_display']) ? $templateData['columns_display'] : null,
                        isset($templateData['formula_display']) ? $templateData['formula_display'] : null,
                        isset($templateData['last_source_value']) ? $templateData['last_source_value'] : null,
                        isset($templateData['last_processed_amount']) ? money_normalize($templateData['last_processed_amount']) : money_normalize('0'),
                        isset($templateData['row_index']) ? (int)$templateData['row_index'] : null,
                        $subOrder,
                        $templateData['formula_variant'],
                        isset($templateData['data_capture_id']) ? (int)$templateData['data_capture_id'] : null
                    ]);
                    error_log("Inserted new template for process $targetProcessCode (ID: $targetProcessId) - id_product={$templateData['id_product']}");
                }
            } catch (Exception $e) {
                error_log("Error syncing formula to process $targetProcessCode (ID: $targetProcessId): " . $e->getMessage());
                // 继续同步其他 Processes，不中断
            }
        }
    } catch (Exception $e) {
        error_log("Error in syncFormulaToMultiUseProcesses: " . $e->getMessage());
        // 不抛出异常，避免影响主流程
    }
}

/**
 * A_ID 删除某行时，同步删除所有 sync_source_process_id = A_ID 的 process 中对应行（按 id_product/account_id/product_type/formula_variant/sub_order 匹配）
 */
function syncDeleteTemplateToMultiUseProcesses(PDO $pdo, int $sourceProcessId, string $idProduct, $accountId, string $productType, $formulaVariant, $subOrder, int $companyId) {
    global $capture_scope_group;
    if (!empty($capture_scope_group)) {
        return;
    }
    try {
        $syncedProcesses = getLinkedProcessTargets($pdo, $sourceProcessId, $companyId);
        if (empty($syncedProcesses)) {
            return;
        }
        $hasSubOrder = $productType === 'sub' && $subOrder !== null && $subOrder !== '';
        $sql = "
            DELETE FROM data_capture_templates 
            WHERE process_id = ? AND company_id = ?
              AND id_product = ? AND account_id = ?
              AND product_type = ? AND formula_variant = ?
        " . ($hasSubOrder ? " AND (COALESCE(sub_order, 0) = COALESCE(?, 0))" : "");
        $delStmt = $pdo->prepare($sql);
        foreach ($syncedProcesses as $synced) {
            $targetProcessId = $synced['id'];
            $params = [$targetProcessId, $companyId, $idProduct, $accountId, $productType, $formulaVariant];
            if ($hasSubOrder) {
                $params[] = $subOrder;
            }
            $delStmt->execute($params);
            $n = $delStmt->rowCount();
            if ($n > 0) {
                error_log("Sync delete: removed template for process {$synced['process_id']} (ID: $targetProcessId)");
            }
        }
    } catch (Exception $e) {
        error_log("Error in syncDeleteTemplateToMultiUseProcesses: " . $e->getMessage());
    }
}

/**
 * Scope columns for data_capture_templates — aligns with Submit / Formula Maintenance ledger filter.
 *
 * @return array{scope_type: ?string, scope_id: ?int}|null null when table has no scope columns
 */
function resolveTemplateScopeInsertForSave(PDO $pdo, int $companyId): ?array
{
    if (!tenant_table_has_scope_columns($pdo, 'data_capture_templates')) {
        return null;
    }

    global $capture_scope_ctx;
    $scopeCtx = is_array($capture_scope_ctx) && $capture_scope_ctx !== []
        ? $capture_scope_ctx
        : [
            'company_id' => $companyId,
            'is_group_scope' => false,
        ];
    $scopeCtx['dual_tenant'] = true;

    $insert = dcCaptureScopeInsertValues($scopeCtx);

    return [
        'scope_type' => $insert['scope_type'],
        'scope_id' => $insert['scope_id'] !== null ? (int) $insert['scope_id'] : null,
    ];
}

/** Backfill templates saved before scope columns were populated (company or group ledger). */
function backfillTemplateScope(PDO $pdo, int $companyId, ?array $scopeInsert): void
{
    if ($scopeInsert === null) {
        return;
    }

    $scopeType = (string) ($scopeInsert['scope_type'] ?? '');
    $scopeId = (int) ($scopeInsert['scope_id'] ?? 0);
    if ($scopeId <= 0 || !in_array($scopeType, ['company', 'group'], true)) {
        return;
    }

    try {
        $stmt = $pdo->prepare("
            UPDATE data_capture_templates
            SET scope_type = :scope_type,
                scope_id = :scope_id
            WHERE company_id = :company_id
              AND (scope_type IS NULL OR TRIM(scope_type) = '')
              AND (scope_id IS NULL OR scope_id = 0)
        ");
        $stmt->execute([
            ':scope_type' => $scopeType,
            ':scope_id' => $scopeId,
            ':company_id' => $companyId,
        ]);
    } catch (Exception $e) {
        error_log('Template scope backfill warning: ' . $e->getMessage());
    }
}

function saveTemplateRow(PDO $pdo, array $row, int $companyId) {
    // Ensure required keys exist
    if (empty($row['id_product']) || empty($row['account_id'])) {
        return null;
    }

    $productType = $row['product_type'] ?? 'main';
    $parentIdProduct = $row['parent_id_product'] ?? null;

    if ($productType === 'sub' && !$parentIdProduct) {
        $parentIdProduct = $row['id_product_main'] ?? null;
    }

    $templateKey = $row['template_key'] ?? computeTemplateKey(array_merge($row, [
        'product_type' => $productType,
        'parent_id_product' => $parentIdProduct,
    ]));
    
    // process_id should be process.id (int), not process.process_id (varchar string)
    $processId = null;
    if (isset($row['process_id'])) {
        $processIdValue = $row['process_id'];
        // Convert to integer (process.id)
        if (is_numeric($processIdValue)) {
            $processId = (int)$processIdValue;
        } elseif (is_string($processIdValue) && trim($processIdValue) !== '') {
            // If it's a string (process.process_id like 'KKKAB'), try to find process.id
            // This is for backward compatibility during migration
            global $capture_scope_group;
            $resolvedPid = dcResolveProcessIdByCode(
                $pdo,
                (int) $companyId,
                trim($processIdValue),
                (bool) $capture_scope_group
            );
            if ($resolvedPid !== null) {
                $processId = $resolvedPid;
                error_log("Converted process_id from string '{$processIdValue}' to int {$processId}");
            } else {
                error_log("Warning: Could not find process.id for process_id '{$processIdValue}'");
            }
        }
    }
    $hasProcessId = $processId !== null && $processId > 0;
    $templateScopeInsert = resolveTemplateScopeInsertForSave($pdo, $companyId);
    $dataCaptureId = isset($row['data_capture_id']) && !empty($row['data_capture_id']) ? (int)$row['data_capture_id'] : null;
    
    // Get formula_display to determine formula_variant
    $formulaDisplay = $row['formula_display'] ?? '';
    $batchSelection = isset($row['batch_selection']) ? (int)$row['batch_selection'] : 0;
    
    // Get sub_order for sub rows (used to distinguish multiple sub rows with same account)
    $subOrder = isset($row['sub_order']) && $row['sub_order'] !== null && $row['sub_order'] !== '' ? (float)$row['sub_order'] : null;
    
    // 如果提供了 template_id，优先使用它来查找现有模板（编辑模式）
    $templateId = isset($row['template_id']) && !empty($row['template_id']) ? (int)$row['template_id'] : null;
    
    // Determine formula_variant: if provided, use it; otherwise find the next available variant
    $formulaVariant = isset($row['formula_variant']) && $row['formula_variant'] !== null && $row['formula_variant'] !== '' ? (int)$row['formula_variant'] : null;

    $rowIndexForHierarchy = isset($row['row_index']) && $row['row_index'] !== null && $row['row_index'] !== ''
        ? (int)$row['row_index']
        : null;

    // Sub：按 parent_id_product 层级定位已有行，避免 template_unique 不含 parent 时重复 INSERT
    if ($productType === 'sub' && $templateId === null && $parentIdProduct) {
        $hierarchyHit = findSubTemplateByHierarchy(
            $pdo,
            $companyId,
            $hasProcessId ? $processId : null,
            (string)$parentIdProduct,
            (int)$row['account_id'],
            $rowIndexForHierarchy,
            $subOrder
        );
        if ($hierarchyHit) {
            $templateId = (int)$hierarchyHit['id'];
            $formulaVariant = (int)$hierarchyHit['formula_variant'];
        }
    }
    
    // 如果提供了 template_id，直接使用它来查找现有模板并获取 formula_variant
    if ($templateId !== null) {
        $existingTemplateStmt = $pdo->prepare("
            SELECT formula_variant FROM data_capture_templates 
            WHERE id = :template_id
              AND company_id = :company_id
            LIMIT 1
        ");
        $existingTemplateStmt->execute([
            ':template_id' => $templateId,
            ':company_id' => $companyId
        ]);
        $existingTemplate = $existingTemplateStmt->fetch();
        if ($existingTemplate) {
            // 使用现有模板的 formula_variant
            $formulaVariant = (int)$existingTemplate['formula_variant'];
        }
    }
    
    // If formula_variant not provided, check if a record with same id_product, account_id, batch_selection, AND formula_display exists
    // If exists, use its formula_variant (update existing record)
    // If not exists, find the next available formula_variant (create new record)
    // This allows multiple rows with same id_product and account_id but different formulas (different formula_variant)
    if ($formulaVariant === null) {
        // First, try to find existing template with same id_product, account_id, batch_selection, AND formula_display
        // This handles the case where the same formula is being updated
        // For sub rows, also check sub_order to distinguish multiple sub rows with same account
        if ($productType === 'sub') {
            $existingTemplateStmt = $pdo->prepare("
                SELECT formula_variant FROM data_capture_templates 
                WHERE company_id = :company_id
                  AND process_id " . ($hasProcessId ? "= :process_id" : "IS NULL") . "
                  AND product_type = 'sub'
                  AND COALESCE(parent_id_product, '') = COALESCE(:parent_id_product, '')
                  AND COALESCE(id_product, '') = COALESCE(:id_product, '')
                  AND account_id = :account_id
                  AND batch_selection = :batch_selection
                  AND COALESCE(formula_display, '') = COALESCE(:formula_display, '')
                  AND (COALESCE(sub_order, 0) = COALESCE(:sub_order, 0))
                  AND data_capture_id " . ($dataCaptureId ? "= :data_capture_id" : "IS NULL") . "
                ORDER BY updated_at DESC
                LIMIT 1
            ");
            
            $existingTemplateParams = [
                ':company_id' => $companyId,
                ':parent_id_product' => $parentIdProduct,
                ':id_product' => $row['id_product'],
                ':account_id' => $row['account_id'],
                ':batch_selection' => $batchSelection,
                ':formula_display' => $formulaDisplay,
                ':sub_order' => $subOrder
            ];
            
            if ($hasProcessId) {
                $existingTemplateParams[':process_id'] = $processId;
            }
            if ($dataCaptureId) {
                $existingTemplateParams[':data_capture_id'] = $dataCaptureId;
            }
        } else {
            $existingTemplateStmt = $pdo->prepare("
                SELECT formula_variant FROM data_capture_templates 
                WHERE company_id = :company_id
                  AND process_id " . ($hasProcessId ? "= :process_id" : "IS NULL") . "
                  AND product_type = 'main'
                  AND COALESCE(id_product, '') = COALESCE(:id_product, '')
                  AND account_id = :account_id
                  AND batch_selection = :batch_selection
                  AND COALESCE(formula_display, '') = COALESCE(:formula_display, '')
                  AND data_capture_id " . ($dataCaptureId ? "= :data_capture_id" : "IS NULL") . "
                ORDER BY updated_at DESC
                LIMIT 1
            ");
            
            $existingTemplateParams = [
                ':company_id' => $companyId,
                ':id_product' => $row['id_product'],
                ':account_id' => $row['account_id'],
                ':batch_selection' => $batchSelection,
                ':formula_display' => $formulaDisplay
            ];
            
            if ($hasProcessId) {
                $existingTemplateParams[':process_id'] = $processId;
            }
            if ($dataCaptureId) {
                $existingTemplateParams[':data_capture_id'] = $dataCaptureId;
            }
        }
        
        $existingTemplateStmt->execute($existingTemplateParams);
        $existingTemplate = $existingTemplateStmt->fetch();
        
        if ($existingTemplate) {
            // Use existing formula_variant for the same batch_selection state AND formula_display
            // This means it's the same template, just being updated
            $formulaVariant = (int)$existingTemplate['formula_variant'];
        } else {
            // No existing template with same formula_display found
            // Find the next available formula_variant for this id_product and account_id
            // This allows multiple rows with same id_product and account_id but different formulas
            // For sub rows, also consider sub_order to distinguish multiple sub rows with same account
            if ($productType === 'sub') {
                $maxVariantStmt = $pdo->prepare("
                    SELECT MAX(formula_variant) as max_variant FROM data_capture_templates 
                    WHERE company_id = :company_id
                      AND process_id " . ($hasProcessId ? "= :process_id" : "IS NULL") . "
                      AND product_type = 'sub'
                      AND COALESCE(parent_id_product, '') = COALESCE(:parent_id_product, '')
                      AND COALESCE(id_product, '') = COALESCE(:id_product, '')
                      AND account_id = :account_id
                      AND (COALESCE(sub_order, 0) = COALESCE(:sub_order, 0))
                      AND data_capture_id " . ($dataCaptureId ? "= :data_capture_id" : "IS NULL") . "
                ");
                
                $maxVariantParams = [
                    ':company_id' => $companyId,
                    ':parent_id_product' => $parentIdProduct,
                    ':id_product' => $row['id_product'],
                    ':account_id' => $row['account_id'],
                    ':sub_order' => $subOrder
                ];
                
                if ($hasProcessId) {
                    $maxVariantParams[':process_id'] = $processId;
                }
                if ($dataCaptureId) {
                    $maxVariantParams[':data_capture_id'] = $dataCaptureId;
                }
            } else {
                $maxVariantStmt = $pdo->prepare("
                    SELECT MAX(formula_variant) as max_variant FROM data_capture_templates 
                    WHERE company_id = :company_id
                      AND process_id " . ($hasProcessId ? "= :process_id" : "IS NULL") . "
                      AND product_type = 'main'
                      AND COALESCE(id_product, '') = COALESCE(:id_product, '')
                      AND account_id = :account_id
                      AND data_capture_id " . ($dataCaptureId ? "= :data_capture_id" : "IS NULL") . "
                ");
                
                $maxVariantParams = [
                    ':company_id' => $companyId,
                    ':id_product' => $row['id_product'],
                    ':account_id' => $row['account_id']
                ];
                
                if ($hasProcessId) {
                    $maxVariantParams[':process_id'] = $processId;
                }
                if ($dataCaptureId) {
                    $maxVariantParams[':data_capture_id'] = $dataCaptureId;
                }
            }
            
            $maxVariantStmt->execute($maxVariantParams);
            $maxVariantResult = $maxVariantStmt->fetch();
            $maxVariant = $maxVariantResult && $maxVariantResult['max_variant'] !== null ? (int)$maxVariantResult['max_variant'] : 0;
            $formulaVariant = $maxVariant + 1;
        }
    }
    
    // Check for duplicate before inserting/updating
    // Now includes formula_variant in the check
    // 如果提供了 template_id，优先使用它来查找现有记录（编辑模式）
    $existingRecord = null;
    if ($templateId !== null) {
        // 直接使用 template_id 查找现有记录
        $checkStmt = $pdo->prepare("
            SELECT id FROM data_capture_templates 
            WHERE id = :template_id
              AND company_id = :company_id
            LIMIT 1
        ");
        $checkStmt->execute([
            ':template_id' => $templateId,
            ':company_id' => $companyId
        ]);
        $existingRecord = $checkStmt->fetch();
    }
    
    // 同 (process, type, product, account) 且 同 formula、同 input_method 才视为同一条并更新；不同 formula 或不同 input_method 则保留为多条
    $formulaForMatch = trim((string)($row['formula_operators'] ?? $row['formula_display'] ?? ''));
    $inputMethodForMatch = trim((string)($row['input_method'] ?? ''));
    if (!$existingRecord && $dataCaptureId === null) {
        if ($productType === 'sub') {
            $anyStmt = $pdo->prepare("
                SELECT id, formula_variant FROM data_capture_templates 
                WHERE company_id = ? AND process_id " . ($hasProcessId ? "= ?" : "IS NULL") . "
                  AND product_type = 'sub' AND COALESCE(TRIM(parent_id_product), '') = COALESCE(TRIM(?), '')
                  AND COALESCE(TRIM(id_product), '') = COALESCE(TRIM(?), '') AND account_id = ?
                  AND COALESCE(TRIM(formula_operators), TRIM(formula_display), '') = ?
                  AND COALESCE(TRIM(input_method), '') = ?
                  AND (COALESCE(sub_order, 0) = COALESCE(?, 0))
                  AND (data_capture_id IS NULL OR data_capture_id = 0)
                ORDER BY updated_at DESC LIMIT 1
            ");
            $anyParams = [$companyId, $parentIdProduct, $row['id_product'], $row['account_id'], $formulaForMatch, $inputMethodForMatch, $subOrder];
            if ($hasProcessId) {
                array_splice($anyParams, 1, 0, [$processId]);
            }
            $anyStmt->execute($anyParams);
            $anyRow = $anyStmt->fetch(PDO::FETCH_ASSOC);
            if ($anyRow) {
                $existingRecord = ['id' => $anyRow['id']];
                $formulaVariant = (int)$anyRow['formula_variant'];
            }
        } else {
            $anyStmt = $pdo->prepare("
                SELECT id, formula_variant FROM data_capture_templates 
                WHERE company_id = ? AND process_id " . ($hasProcessId ? "= ?" : "IS NULL") . "
                  AND product_type = 'main' AND COALESCE(TRIM(id_product), '') = COALESCE(TRIM(?), '')
                  AND account_id = ?
                  AND COALESCE(TRIM(formula_operators), TRIM(formula_display), '') = ?
                  AND COALESCE(TRIM(input_method), '') = ?
                  AND (data_capture_id IS NULL OR data_capture_id = 0)
                ORDER BY updated_at DESC LIMIT 1
            ");
            $anyParams = [$companyId, $row['id_product'], $row['account_id'], $formulaForMatch, $inputMethodForMatch];
            if ($hasProcessId) {
                array_splice($anyParams, 1, 0, [$processId]);
            }
            $anyStmt->execute($anyParams);
            $anyRow = $anyStmt->fetch(PDO::FETCH_ASSOC);
            if ($anyRow) {
                $existingRecord = ['id' => $anyRow['id']];
                $formulaVariant = (int)$anyRow['formula_variant'];
            }
        }
    }
    
    // 如果没有通过 template_id 找到记录，使用原来的逻辑查找（按 formula_variant 精确匹配）
    if (!$existingRecord) {
        if ($productType === 'sub') {
            // For sub type, check by parent_id_product, id_product, account_id, formula_variant, sub_order, process_id, data_capture_id
            $checkStmt = $pdo->prepare("
                SELECT id FROM data_capture_templates 
                WHERE company_id = :company_id
                  AND process_id " . ($hasProcessId ? "= :process_id" : "IS NULL") . "
                  AND product_type = 'sub'
                  AND COALESCE(parent_id_product, '') = COALESCE(:parent_id_product, '')
                  AND COALESCE(id_product, '') = COALESCE(:id_product, '')
                  AND account_id = :account_id
                  AND formula_variant = :formula_variant
                  AND (COALESCE(sub_order, 0) = COALESCE(:sub_order, 0))
                  AND data_capture_id " . ($dataCaptureId ? "= :data_capture_id" : "IS NULL") . "
                LIMIT 1
            ");
            
            $checkParams = [
                ':company_id' => $companyId,
                ':parent_id_product' => $parentIdProduct,
                ':id_product' => $row['id_product'],
                ':account_id' => $row['account_id'],
                ':formula_variant' => $formulaVariant,
                ':sub_order' => $subOrder
            ];
            
            if ($hasProcessId) {
                $checkParams[':process_id'] = $processId;
            }
            if ($dataCaptureId) {
                $checkParams[':data_capture_id'] = $dataCaptureId;
            }
        } else {
            // For main type, check by id_product, account_id, formula_variant, process_id, data_capture_id
            $checkStmt = $pdo->prepare("
                SELECT id FROM data_capture_templates 
                WHERE company_id = :company_id
                  AND process_id " . ($hasProcessId ? "= :process_id" : "IS NULL") . "
                  AND product_type = 'main'
                  AND COALESCE(id_product, '') = COALESCE(:id_product, '')
                  AND account_id = :account_id
                  AND formula_variant = :formula_variant
                  AND data_capture_id " . ($dataCaptureId ? "= :data_capture_id" : "IS NULL") . "
                LIMIT 1
            ");
            
            $checkParams = [
                ':company_id' => $companyId,
                ':id_product' => $row['id_product'],
                ':account_id' => $row['account_id'],
                ':formula_variant' => $formulaVariant
            ];
            
            if ($hasProcessId) {
                $checkParams[':process_id'] = $processId;
            }
            if ($dataCaptureId) {
                $checkParams[':data_capture_id'] = $dataCaptureId;
            }
        }
        
        $checkStmt->execute($checkParams);
        $existingRecord = $checkStmt->fetch();
    }
    
    // If record exists, use UPDATE instead of INSERT to avoid duplicates
    if ($existingRecord) {
        $existingId = $existingRecord['id'];
        error_log("Found duplicate template record (ID: $existingId) - product_type=$productType, id_product=" . ($row['id_product'] ?? 'NULL') . ", account_id=" . ($row['account_id'] ?? 'NULL') . ", formula_variant=$formulaVariant, process_id=" . ($processId ?? 'NULL') . ", data_capture_id=" . ($dataCaptureId ?? 'NULL') . " - Updating instead of inserting");
        
        $scopeUpdateSql = $templateScopeInsert !== null
            ? "scope_type = :scope_type,\n                scope_id = :scope_id,\n                "
            : '';

        $stmt = $pdo->prepare("
            UPDATE data_capture_templates SET
                id_product = :id_product,
                parent_id_product = :parent_id_product,
                template_key = :template_key,
                description = :description,
                account_id = :account_id,
                account_display = :account_display,
                currency_id = :currency_id,
                currency_display = :currency_display,
                source_columns = :source_columns,
                formula_operators = :formula_operators,
                source_percent = :source_percent,
                enable_source_percent = :enable_source_percent,
                input_method = :input_method,
                enable_input_method = :enable_input_method,
                batch_selection = :batch_selection,
                columns_display = :columns_display,
                formula_display = :formula_display,
                last_source_value = :last_source_value,
                last_processed_amount = :last_processed_amount,
                process_id = :process_id,
                data_capture_id = :data_capture_id,
                row_index = :row_index,
                sub_order = :sub_order,
                formula_variant = :formula_variant,
                {$scopeUpdateSql}updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
        ");

        $updateParams = [
            ':id' => $existingId,
            ':id_product' => $row['id_product'],
            ':parent_id_product' => $parentIdProduct,
            ':template_key' => $templateKey, // Update template_key to keep it consistent
            ':description' => $row['description'] ?? null,
            ':account_id' => $row['account_id'],
            ':account_display' => $row['account_display'] ?? null,
            ':currency_id' => $row['currency_id'] ?? null,
            ':currency_display' => $row['currency_display'] ?? null,
            ':source_columns' => $row['source_columns'] ?? '',
            ':formula_operators' => $row['formula_operators'] ?? '',
            // source_percent: default to '1' (multiplier, 1 = multiply by 1), auto-enable if has value
            ':source_percent' => isset($row['source_percent']) && $row['source_percent'] !== '' ? (string)$row['source_percent'] : '1',
            ':enable_source_percent' => (isset($row['source_percent']) && $row['source_percent'] !== '' && $row['source_percent'] !== '0') ? 1 : 0,
            ':input_method' => $row['input_method'] ?? null,
            ':enable_input_method' => isset($row['enable_input_method']) ? (int)$row['enable_input_method'] : 0,
            ':batch_selection' => isset($row['batch_selection']) ? (int)$row['batch_selection'] : 0,
            ':columns_display' => $row['columns_display'] ?? null,
            ':formula_display' => $row['formula_display'] ?? null,
            ':last_source_value' => $row['last_source_value'] ?? null,
            ':last_processed_amount' => summary_money_value($row, 'last_processed_amount'),
            ':process_id' => $processId,
            ':data_capture_id' => $dataCaptureId,
            ':row_index' => isset($row['row_index']) ? (int)$row['row_index'] : null,
            ':sub_order' => isset($row['sub_order']) && $row['sub_order'] !== null && $row['sub_order'] !== '' ? (float)$row['sub_order'] : null,
            ':formula_variant' => $formulaVariant,
        ];
        if ($templateScopeInsert !== null) {
            $updateParams[':scope_type'] = $templateScopeInsert['scope_type'];
            $updateParams[':scope_id'] = $templateScopeInsert['scope_id'];
        }

        $stmt->execute($updateParams);
        
        // 如果当前 Process 是源 Process，同步 Formula 到所有关联的 Multi-use Processes
        if ($hasProcessId && $processId) {
            $syncTemplateData = [
                'id_product' => $row['id_product'],
                'account_id' => $row['account_id'],
                'product_type' => $productType,
                'formula_variant' => $formulaVariant,
                'source_columns' => $row['source_columns'] ?? '',
                'formula_operators' => $row['formula_operators'] ?? '',
                'source_percent' => isset($row['source_percent']) && $row['source_percent'] !== '' ? (string)$row['source_percent'] : '1',
                'enable_source_percent' => (isset($row['source_percent']) && $row['source_percent'] !== '' && $row['source_percent'] !== '0') ? 1 : 0,
                'input_method' => $row['input_method'] ?? null,
                'enable_input_method' => isset($row['enable_input_method']) ? (int)$row['enable_input_method'] : 0,
                'columns_display' => $row['columns_display'] ?? null,
                'formula_display' => $row['formula_display'] ?? null,
                'last_processed_amount' => summary_money_value($row, 'last_processed_amount'),
                'description' => $row['description'] ?? null,
                'account_display' => $row['account_display'] ?? null,
                'currency_id' => $row['currency_id'] ?? null,
                'currency_display' => $row['currency_display'] ?? null,
            ];
            syncFormulaToMultiUseProcesses($pdo, $processId, $syncTemplateData, $companyId);
        }
        
        return [
            'template_key' => $templateKey,
            'template_id' => $existingId,
            'formula_variant' => $formulaVariant
        ]; // Return template info after update
    }

    $scopeInsertColumns = $templateScopeInsert !== null ? "scope_type,\n            scope_id,\n            " : '';
    $scopeInsertValues = $templateScopeInsert !== null ? ":scope_type,\n            :scope_id,\n            " : '';
    $scopeDuplicateUpdate = $templateScopeInsert !== null
        ? "scope_type = VALUES(scope_type),\n            scope_id = VALUES(scope_id),\n            "
        : '';

    $stmt = $pdo->prepare("
        INSERT INTO data_capture_templates (
            company_id,
            {$scopeInsertColumns}id_product,
            product_type,
            parent_id_product,
            template_key,
            description,
            account_id,
            account_display,
            currency_id,
            currency_display,
            source_columns,
            formula_operators,
            source_percent,
            enable_source_percent,
            input_method,
            enable_input_method,
            batch_selection,
            columns_display,
            formula_display,
            last_source_value,
            last_processed_amount,
            process_id,
            data_capture_id,
            row_index,
            sub_order,
            formula_variant
        ) VALUES (
            :company_id,
            {$scopeInsertValues}:id_product,
            :product_type,
            :parent_id_product,
            :template_key,
            :description,
            :account_id,
            :account_display,
            :currency_id,
            :currency_display,
            :source_columns,
            :formula_operators,
            :source_percent,
            :enable_source_percent,
            :input_method,
            :enable_input_method,
            :batch_selection,
            :columns_display,
            :formula_display,
            :last_source_value,
            :last_processed_amount,
            :process_id,
            :data_capture_id,
            :row_index,
            :sub_order,
            :formula_variant
        )
        ON DUPLICATE KEY UPDATE
            description = VALUES(description),
            account_id = VALUES(account_id),
            account_display = VALUES(account_display),
            currency_id = VALUES(currency_id),
            currency_display = VALUES(currency_display),
            source_columns = VALUES(source_columns),
            formula_operators = VALUES(formula_operators),
            source_percent = VALUES(source_percent),
            enable_source_percent = VALUES(enable_source_percent),
            input_method = VALUES(input_method),
            enable_input_method = VALUES(enable_input_method),
            batch_selection = VALUES(batch_selection),
            columns_display = VALUES(columns_display),
            formula_display = VALUES(formula_display),
            last_source_value = VALUES(last_source_value),
            last_processed_amount = VALUES(last_processed_amount),
            parent_id_product = VALUES(parent_id_product),
            template_key = VALUES(template_key),
            product_type = VALUES(product_type),
            process_id = VALUES(process_id),
            data_capture_id = VALUES(data_capture_id),
            row_index = VALUES(row_index),
            sub_order = VALUES(sub_order),
            formula_variant = VALUES(formula_variant),
            {$scopeDuplicateUpdate}updated_at = CURRENT_TIMESTAMP
    ");

    $insertParams = [
        ':company_id' => $companyId,
        ':id_product' => $row['id_product'],
        ':product_type' => $productType,
        ':parent_id_product' => $parentIdProduct,
        ':template_key' => $templateKey,
        ':description' => $row['description'] ?? null,
        ':account_id' => $row['account_id'],
        ':account_display' => $row['account_display'] ?? null,
        ':currency_id' => $row['currency_id'] ?? null,
        ':currency_display' => $row['currency_display'] ?? null,
        ':source_columns' => $row['source_columns'] ?? '',
        ':formula_operators' => $row['formula_operators'] ?? '',
        ':source_percent' => isset($row['source_percent']) && $row['source_percent'] !== '' ? (string)$row['source_percent'] : '1', // Store as string to preserve expressions like "1/2", default to '1' (multiplier)
        ':enable_source_percent' => isset($row['enable_source_percent']) ? (int)$row['enable_source_percent'] : 1,
        ':input_method' => $row['input_method'] ?? null,
        ':enable_input_method' => isset($row['enable_input_method']) ? (int)$row['enable_input_method'] : 0,
        ':batch_selection' => isset($row['batch_selection']) ? (int)$row['batch_selection'] : 0,
        ':columns_display' => $row['columns_display'] ?? null,
        ':formula_display' => $row['formula_display'] ?? null,
        ':last_source_value' => $row['last_source_value'] ?? null,
        ':last_processed_amount' => summary_money_value($row, 'last_processed_amount'),
        ':process_id' => $processId,
        ':data_capture_id' => isset($row['data_capture_id']) && !empty($row['data_capture_id']) ? (int)$row['data_capture_id'] : null,
        ':row_index' => isset($row['row_index']) ? (int)$row['row_index'] : null,
        ':sub_order' => isset($row['sub_order']) && $row['sub_order'] !== null && $row['sub_order'] !== '' ? (float)$row['sub_order'] : null,
        ':formula_variant' => $formulaVariant,
    ];
    if ($templateScopeInsert !== null) {
        $insertParams[':scope_type'] = $templateScopeInsert['scope_type'];
        $insertParams[':scope_id'] = $templateScopeInsert['scope_id'];
    }

    $stmt->execute($insertParams);
    
    $templateId = $pdo->lastInsertId();
    
    // 如果当前 Process 是源 Process，同步 Formula 到所有关联的 Multi-use Processes
    if ($hasProcessId && $processId) {
        $syncTemplateData = [
            'id_product' => $row['id_product'],
            'account_id' => $row['account_id'],
            'product_type' => $productType,
            'formula_variant' => $formulaVariant,
            'source_columns' => $row['source_columns'] ?? '',
            'formula_operators' => $row['formula_operators'] ?? '',
            'source_percent' => isset($row['source_percent']) && $row['source_percent'] !== '' ? (string)$row['source_percent'] : '1',
            'enable_source_percent' => isset($row['enable_source_percent']) ? (int)$row['enable_source_percent'] : 1,
            'input_method' => $row['input_method'] ?? null,
            'enable_input_method' => isset($row['enable_input_method']) ? (int)$row['enable_input_method'] : 0,
            'columns_display' => $row['columns_display'] ?? null,
            'formula_display' => $row['formula_display'] ?? null,
            'last_processed_amount' => summary_money_value($row, 'last_processed_amount'),
            'description' => $row['description'] ?? null,
            'account_display' => $row['account_display'] ?? null,
            'currency_id' => $row['currency_id'] ?? null,
            'currency_display' => $row['currency_display'] ?? null,
        ];
        syncFormulaToMultiUseProcesses($pdo, $processId, $syncTemplateData, $companyId);
    }
    
    return [
        'template_key' => $templateKey,
        'template_id' => $templateId,
        'formula_variant' => $formulaVariant
    ]; // Return template info after insert
}

/**
 * Normalize id_product for use as template key (strip trailing " (description)").
 * Matches frontend normalizeIdProductText so that templates group under the same key.
 */
function normalizeIdProductForKey($text) {
    if ($text === null || $text === '') {
        return '';
    }
    $trimmed = trim((string)$text);
    if ($trimmed === '') {
        return '';
    }
    // Strip trailing " (anything)" to match frontend normalized key
    $normalized = preg_replace('/\s*\([^)]+\)\s*$/', '', $trimmed);
    return trim($normalized);
}

/**
 * Base part of id_product (before first "(") for grouping.
 * 与前端 normalizeIdProductText 一致，便于 Summary 用 ALLBET95MS 取到 ALLBET95MS(SV)MYR 等模板。
 */
function baseIdProductForKey($text) {
    if ($text === null || $text === '') {
        return '';
    }
    $trimmed = trim((string)$text);
    if ($trimmed === '') {
        return '';
    }
    $pos = strpos($trimmed, '(');
    return $pos > 0 ? trim(substr($trimmed, 0, $pos)) : $trimmed;
}

/**
 * Normalized key for template grouping: only trim trailing spaces, preserve colon (e.g. VM365-21:).
 * 与前端一致：id_product 完整进资料库、完整查找，不剔除末尾冒号。
 */
function baseIdProductForKeyNormalized($text) {
    $base = baseIdProductForKey($text);
    if ($base === '') {
        return '';
    }
    return trim($base);
}

/**
 * Merge (id_product, account_id) pairs from data_capture_details into templates
 * so that accounts that exist in details but have no template still get a row (synthetic template).
 * 修复：data_capture_details 有该账目但 data_capture_templates 没有时，仍能在 Summary 中显示。
 */
function mergeDetailOnlyTemplates(PDO $pdo, int $companyId, int $captureId, array $ids, array $templates) {
    $hasDisplayOrder = summaryApiHasDisplayOrder($pdo); // static 缓存，不重复 SHOW
    $orderBy = $hasDisplayOrder ? "ORDER BY COALESCE(display_order, 999), id" : "ORDER BY id";
    $cols = $hasDisplayOrder ? "id_product_main, id_product_sub, product_type, account_id, display_order, rate" : "id_product_main, id_product_sub, product_type, account_id, rate";
    $detailStmt = $pdo->prepare("
        SELECT $cols
        FROM data_capture_details
        WHERE company_id = ? AND capture_id = ?
        $orderBy
    ");
    $detailStmt->execute([$companyId, $captureId]);
    $details = $detailStmt->fetchAll(PDO::FETCH_ASSOC);

    $pairsByKey = [];
    $detailIndex = 0;
    foreach ($details as $row) {
        $accountId = isset($row['account_id']) ? trim((string)$row['account_id']) : '';
        if ($accountId === '') {
            continue;
        }
        $productType = $row['product_type'] ?? 'main';
        $idProductMain = isset($row['id_product_main']) ? trim((string)$row['id_product_main']) : '';
        $idProductSub  = isset($row['id_product_sub'])  ? trim((string)$row['id_product_sub'])  : '';
        if ($productType === 'main') {
            $idForKey = $idProductMain !== '' ? $idProductMain : $idProductSub;
        } else {
            $idForKey = $idProductMain !== '' ? $idProductMain : $idProductSub;
        }
        if ($idForKey === '') {
            continue;
        }
        // 与 fetchTemplates 一致：用完整 id 作 key，避免 GAMS(SV)HKD 与 GAMS(SV)MYR 混组
        $key = trim((string) $idForKey);
        if ($key === '') {
            $key = baseIdProductForKeyNormalized($idForKey);
            if ($key === '') {
                $key = $idForKey;
            }
        }
        if (!isset($pairsByKey[$key])) {
            $pairsByKey[$key] = [];
        }
        $displayOrder = $hasDisplayOrder && isset($row['display_order']) ? (int)$row['display_order'] : $detailIndex;
        $pairsByKey[$key][$accountId] = [
            'id_product' => $idForKey,
            'account_id' => $accountId,
            'display_order' => $displayOrder,
            'rate' => isset($row['rate']) && $row['rate'] !== null && $row['rate'] !== '' ? (string)$row['rate'] : null,
        ];
        $detailIndex++;
    }

    $accountIds = [];
    foreach ($pairsByKey as $pairs) {
        foreach ($pairs as $accId => $_) {
            if (is_numeric($accId)) {
                $accountIds[(int)$accId] = true;
            }
        }
    }
    $accountIds = array_keys($accountIds);
    $accountDisplayMap = [];
    if (!empty($accountIds)) {
        $placeholders = implode(',', array_fill(0, count($accountIds), '?'));
        $accStmt = $pdo->prepare("
            SELECT a.id, a.account_id AS code, a.name
            FROM account a
            INNER JOIN account_company ac ON a.id = ac.account_id
            WHERE ac.company_id = ? AND a.id IN ($placeholders)
        ");
        $accStmt->execute(array_merge([$companyId], $accountIds));
        while ($row = $accStmt->fetch(PDO::FETCH_ASSOC)) {
            $id = (int)$row['id'];
            $code = $row['code'] ?? '';
            $name = $row['name'] ?? '';
            $accountDisplayMap[$id] = $code !== '' && $name !== '' ? ($code . ' [' . $name . ']') : ($code ?: (string)$id);
            $accountDisplayMap[(string)$id] = $accountDisplayMap[$id];
        }
    }

    $requestedKeys = [];
    foreach ($ids as $id) {
        $tid = trim((string) $id);
        if ($tid !== '') {
            $requestedKeys[$tid] = true;
        }
        $n = baseIdProductForKeyNormalized($tid);
        if ($n !== '' && $n !== $tid) {
            $requestedKeys[$n] = true;
        }
    }
    foreach ($pairsByKey as $key => $pairs) {
        $keyInRequest = isset($templates[$key]) || isset($requestedKeys[$key]);
        if (!$keyInRequest) {
            continue;
        }
        if (!isset($templates[$key])) {
            $templates[$key] = ['main' => null, 'subs' => [], 'allMains' => []];
        }
        $allMains = $templates[$key]['allMains'] ?? [];
        $existingAccountIds = [];
        foreach ($allMains as $m) {
            $aid = isset($m['account_id']) ? (string)$m['account_id'] : '';
            if ($aid !== '') {
                $existingAccountIds[$aid] = true;
            }
        }
        foreach ($pairs as $accId => $info) {
            if (isset($existingAccountIds[(string)$accId])) {
                continue;
            }
            $display = $accountDisplayMap[(int)$accId] ?? $accountDisplayMap[(string)$accId] ?? (string)$accId;
            $synthetic = [
                'id' => null,
                'id_product' => $info['id_product'],
                'product_type' => 'main',
                'parent_id_product' => null,
                'template_key' => $info['id_product'],
                'description' => '',
                'account_id' => $accId,
                'account_display' => $display,
                'currency_id' => null,
                'currency_display' => null,
                'source_columns' => '',
                'formula_operators' => '',
                'source_percent' => '1',
                'enable_source_percent' => 1,
                'input_method' => null,
                'enable_input_method' => 0,
                'batch_selection' => 0,
                'columns_display' => null,
                'formula_display' => '',
                'last_source_value' => null,
                'last_processed_amount' => 0,
                'process_id' => null,
                'data_capture_id' => null,
                'row_index' => $info['display_order'],
                'sub_order' => null,
                'formula_variant' => 1,
                'updated_at' => null,
                'rate' => $info['rate'] ?? null,
            ];
            $allMains[] = $synthetic;
            $existingAccountIds[(string)$accId] = true;
        }
        // 按 display_order（来自 data_capture_details）重排 allMains，避免从 Data Capture Submit 进入后 NO/API GSC 等行顺序错乱
        usort($allMains, function ($a, $b) use ($key, $pairs) {
            $aOrder = isset($pairs[(string)($a['account_id'] ?? '')]['display_order'])
                ? (int)$pairs[(string)($a['account_id'] ?? '')]['display_order']
                : (isset($a['row_index']) && $a['row_index'] !== null ? (int)$a['row_index'] : 999999);
            $bOrder = isset($pairs[(string)($b['account_id'] ?? '')]['display_order'])
                ? (int)$pairs[(string)($b['account_id'] ?? '')]['display_order']
                : (isset($b['row_index']) && $b['row_index'] !== null ? (int)$b['row_index'] : 999999);
            return $aOrder - $bOrder;
        });
        // 为来自 templates 的 main 行补充 rate（从 details 取），以便前端显示 Rate Value
        foreach ($allMains as &$m) {
            $accId = (string)($m['account_id'] ?? '');
            if ($accId !== '' && isset($pairs[$accId]['rate']) && $pairs[$accId]['rate'] !== null && $pairs[$accId]['rate'] !== '') {
                $m['rate'] = $pairs[$accId]['rate'];
            }
        }
        unset($m);
        $templates[$key]['allMains'] = $allMains;
        if ($templates[$key]['main'] === null && !empty($allMains)) {
            $templates[$key]['main'] = $allMains[0];
        }
    }
    return $templates;
}

/**
 * Apply account display labels from group ledger accounts (not subsidiary company).
 */
function resolveAccountDisplayInTemplatesForGroup(PDO $pdo, string $groupCode, array &$templates): void
{
    $accounts = dcSummaryLoadAccountsForGroup($pdo, $groupCode);
    if ($accounts === []) {
        return;
    }
    $map = [];
    foreach ($accounts as $row) {
        $id = (int) ($row['id'] ?? 0);
        if ($id <= 0) {
            continue;
        }
        $code = isset($row['account_id']) ? trim((string) $row['account_id']) : '';
        $name = isset($row['name']) ? trim((string) $row['name']) : '';
        $label = ($code !== '' && $name !== '') ? ($code . ' [' . $name . ']') : ($code !== '' ? $code : (string) $id);
        $map[$id] = $map[(string) $id] = $label;
    }
    foreach ($templates as $key => &$group) {
        if (!empty($group['main']['account_id'])) {
            $aid = $group['main']['account_id'];
            $sid = is_numeric($aid) ? (int) $aid : $aid;
            if (isset($map[$sid]) || isset($map[(string) $aid])) {
                $group['main']['account_display'] = $map[$sid] ?? $map[(string) $aid];
            }
        }
        if (!empty($group['allMains']) && is_array($group['allMains'])) {
            foreach ($group['allMains'] as $i => $m) {
                if (!empty($m['account_id'])) {
                    $aid = $m['account_id'];
                    $sid = is_numeric($aid) ? (int) $aid : $aid;
                    if (isset($map[$sid]) || isset($map[(string) $aid])) {
                        $templates[$key]['allMains'][$i]['account_display'] = $map[$sid] ?? $map[(string) $aid];
                    }
                }
            }
        }
        if (!empty($group['subs']) && is_array($group['subs'])) {
            foreach ($group['subs'] as $i => $s) {
                if (!empty($s['account_id'])) {
                    $aid = $s['account_id'];
                    $sid = is_numeric($aid) ? (int) $aid : $aid;
                    if (isset($map[$sid]) || isset($map[(string) $aid])) {
                        $templates[$key]['subs'][$i]['account_display'] = $map[$sid] ?? $map[(string) $aid];
                    }
                }
            }
        }
    }
    unset($group);
}

/**
 * 用 account 表解析模板中的 account_display，与 Maintenance - Formula 的 Account 列一致，避免 Summary 显示错误。
 */
function resolveAccountDisplayInTemplates(PDO $pdo, int $companyId, array &$templates) {
    $accountIds = [];
    foreach ($templates as $key => $group) {
        if (!empty($group['main']) && !empty($group['main']['account_id'])) {
            $aid = $group['main']['account_id'];
            $accountIds[(is_string($aid) ? $aid : (string)$aid)] = true;
        }
        foreach ($group['allMains'] ?? [] as $m) {
            if (!empty($m['account_id'])) {
                $aid = $m['account_id'];
                $accountIds[(is_string($aid) ? $aid : (string)$aid)] = true;
            }
        }
        foreach ($group['subs'] ?? [] as $s) {
            if (!empty($s['account_id'])) {
                $aid = $s['account_id'];
                $accountIds[(is_string($aid) ? $aid : (string)$aid)] = true;
            }
        }
    }
    $accountIds = array_keys($accountIds);
    if (empty($accountIds)) {
        return;
    }
    $placeholders = implode(',', array_fill(0, count($accountIds), '?'));
    $stmt = $pdo->prepare("
        SELECT a.id, a.account_id AS code, a.name
        FROM account a
        INNER JOIN account_company ac ON a.id = ac.account_id
        WHERE ac.company_id = ? AND a.id IN ($placeholders)
    ");
    $stmt->execute(array_merge([$companyId], $accountIds));
    $map = [];
    while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
        $id = (int)$row['id'];
        $code = isset($row['code']) ? trim((string)$row['code']) : '';
        $name = isset($row['name']) ? trim((string)$row['name']) : '';
        $map[$id] = $map[(string)$id] = ($code !== '' && $name !== '') ? ($code . ' [' . $name . ']') : ($code !== '' ? $code : (string)$id);
    }
    foreach ($templates as $key => &$group) {
        if (!empty($group['main']['account_id'])) {
            $aid = $group['main']['account_id'];
            $sid = is_numeric($aid) ? (int)$aid : $aid;
            if (isset($map[$sid]) || isset($map[(string)$aid])) {
                $group['main']['account_display'] = $map[$sid] ?? $map[(string)$aid];
            }
        }
        if (isset($group['allMains'])) {
            foreach ($group['allMains'] as $i => $m) {
                if (!empty($m['account_id'])) {
                    $aid = $m['account_id'];
                    $sid = is_numeric($aid) ? (int)$aid : $aid;
                    if (isset($map[$sid]) || isset($map[(string)$aid])) {
                        $templates[$key]['allMains'][$i]['account_display'] = $map[$sid] ?? $map[(string)$aid];
                    }
                }
            }
        }
        if (isset($group['subs'])) {
            foreach ($group['subs'] as $i => $s) {
                if (!empty($s['account_id'])) {
                    $aid = $s['account_id'];
                    $sid = is_numeric($aid) ? (int)$aid : $aid;
                    if (isset($map[$sid]) || isset($map[(string)$aid])) {
                        $templates[$key]['subs'][$i]['account_display'] = $map[$sid] ?? $map[(string)$aid];
                    }
                }
            }
        }
    }
    unset($group);
}

/**
 * 层级唯一键：parent_id_product + account_id + row_index + sub_order（不含 formula_variant，避免同账户重复行）。
 */
function subTemplateHierarchyKey(array $sub): string {
    $parent = trim((string)($sub['parent_id_product'] ?? ''));
    $accountId = (int)($sub['account_id'] ?? 0);
    $rowIndex = isset($sub['row_index']) && $sub['row_index'] !== null && $sub['row_index'] !== ''
        ? (int)$sub['row_index']
        : -1;
    $subOrder = isset($sub['sub_order']) && $sub['sub_order'] !== null && $sub['sub_order'] !== ''
        ? (string)(float)$sub['sub_order']
        : '0';
    return strtolower($parent) . '|' . $accountId . '|' . $rowIndex . '|' . $subOrder;
}

/**
 * 同一 parent + account + row_index + sub_order 只保留一条；DB 优先于 account_link 继承副本。
 */
function dedupeTemplateGroupSubs(array $subs): array {
    if (count($subs) <= 1) {
        return $subs;
    }
    $byKey = [];
    foreach ($subs as $sub) {
        if (!is_array($sub)) {
            continue;
        }
        $key = subTemplateHierarchyKey($sub);
        if (!isset($byKey[$key])) {
            $byKey[$key] = $sub;
            continue;
        }
        $byKey[$key] = pickPreferredSubTemplateRow($byKey[$key], $sub);
    }
    return array_values($byKey);
}

function pickPreferredSubTemplateRow(array $existing, array $candidate): array {
    $existingInherited = !empty($existing['inherited_from_account_link'])
        || (isset($existing['id']) && is_string($existing['id']) && strpos((string)$existing['id'], 'inherit_') === 0);
    $candidateInherited = !empty($candidate['inherited_from_account_link'])
        || (isset($candidate['id']) && is_string($candidate['id']) && strpos((string)$candidate['id'], 'inherit_') === 0);
    if ($existingInherited && !$candidateInherited) {
        return $candidate;
    }
    if ($candidateInherited && !$existingInherited) {
        return $existing;
    }
    $existingId = isset($existing['id']) && is_numeric($existing['id']) ? (int)$existing['id'] : 0;
    $candidateId = isset($candidate['id']) && is_numeric($candidate['id']) ? (int)$candidate['id'] : 0;
    if ($candidateId > $existingId) {
        return $candidate;
    }
    if ($existingId > $candidateId) {
        return $existing;
    }
    $existingUpdated = $existing['updated_at'] ?? '';
    $candidateUpdated = $candidate['updated_at'] ?? '';
    return ($candidateUpdated > $existingUpdated) ? $candidate : $existing;
}

/**
 * 按精确 parent_id_product 分组 subs，供 Summary 单次套用（避免多 template key 重复 iterate）。
 */
function buildSubsByParentForApi(array $templates): array {
    $byParent = [];
    foreach ($templates as $group) {
        if (empty($group['subs']) || !is_array($group['subs'])) {
            continue;
        }
        foreach ($group['subs'] as $sub) {
            if (!is_array($sub)) {
                continue;
            }
            $parent = trim((string)($sub['parent_id_product'] ?? ''));
            if ($parent === '') {
                continue;
            }
            if (!isset($byParent[$parent])) {
                $byParent[$parent] = [];
            }
            $byParent[$parent][] = $sub;
        }
    }
    foreach ($byParent as $parent => $subs) {
        $byParent[$parent] = dedupeTemplateGroupSubs($subs);
    }
    return $byParent;
}

/**
 * Debug: 统计 API 层 sub 数量，确认重复发生在哪一层。
 */
function buildTemplateFetchDiagnostics(array $templates, array $subsByParent, array $rawRows = []): array {
    $subsPerParent = [];
    $totalSubsInGroups = 0;
    foreach ($templates as $key => $group) {
        $count = is_array($group['subs'] ?? null) ? count($group['subs']) : 0;
        $totalSubsInGroups += $count;
        if ($count > 0) {
            $subsPerParent[$key] = $count;
        }
    }
    $subsPerParentExact = [];
    $totalSubsExact = 0;
    foreach ($subsByParent as $parent => $subs) {
        $c = count($subs);
        $totalSubsExact += $c;
        $subsPerParentExact[$parent] = $c;
    }
    $rawSubCount = 0;
    $rawSubDupes = [];
    if (!empty($rawRows)) {
        $seen = [];
        foreach ($rawRows as $row) {
            if (($row['product_type'] ?? '') !== 'sub') {
                continue;
            }
            $rawSubCount++;
            $k = subTemplateHierarchyKey($row);
            if (!isset($seen[$k])) {
                $seen[$k] = [];
            }
            $seen[$k][] = (int)($row['id'] ?? 0);
        }
        foreach ($seen as $k => $ids) {
            if (count($ids) > 1) {
                $rawSubDupes[$k] = $ids;
            }
        }
    }
    return [
        'sql_sub_row_count' => $rawSubCount,
        'sql_duplicate_hierarchy_keys' => $rawSubDupes,
        'grouped_sub_count_by_template_key' => $subsPerParent,
        'total_subs_inside_template_groups' => $totalSubsInGroups,
        'subs_by_parent_exact' => $subsPerParentExact,
        'total_subs_after_dedupe' => $totalSubsExact,
    ];
}

/**
 * 按 parent_id_product + account_id + row_index (+ sub_order) 查找已保存的 sub 模板，防止重复 INSERT。
 */
function findSubTemplateByHierarchy(
    PDO $pdo,
    int $companyId,
    ?int $processId,
    string $parentIdProduct,
    int $accountId,
    ?int $rowIndex,
    ?float $subOrder
): ?array {
    $parentIdProduct = trim($parentIdProduct);
    if ($parentIdProduct === '' || $accountId <= 0) {
        return null;
    }
    $sql = "
        SELECT id, formula_variant
        FROM data_capture_templates
        WHERE company_id = :company_id
          AND product_type = 'sub'
          AND TRIM(COALESCE(parent_id_product, '')) = :parent_id_product
          AND account_id = :account_id
    ";
    $params = [
        ':company_id' => $companyId,
        ':parent_id_product' => $parentIdProduct,
        ':account_id' => $accountId,
    ];
    if ($processId !== null && $processId > 0) {
        $sql .= " AND process_id = :process_id";
        $params[':process_id'] = $processId;
    } else {
        $sql .= " AND (process_id IS NULL OR process_id = 0)";
    }
    if ($rowIndex !== null && $rowIndex >= 0 && $rowIndex < 999999) {
        $sql .= " AND row_index = :row_index";
        $params[':row_index'] = $rowIndex;
    }
    if ($subOrder !== null) {
        $sql .= " AND (COALESCE(sub_order, 0) = COALESCE(:sub_order, 0))";
        $params[':sub_order'] = $subOrder;
    }
    $sql .= " ORDER BY id DESC LIMIT 1";
    $stmt = $pdo->prepare($sql);
    $stmt->execute($params);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);
    return $row ?: null;
}

/**
 * 把 Main Acc 的 Formula 动态派生一份给 Sub Acc，通过 account_link 表中的 unidirectional 映射。
 * Summary 页面不再调用：仅使用 data_capture_templates 中带 parent_id_product 的显式 sub 记录。
 */
function inheritFormulasToSubAccounts(PDO $pdo, int $companyId, array $templates): array {
    try {
        // 先检查是否存在 link_type，如果不存在直接退出（防止表结构过旧报错）
        $check_column_stmt = $pdo->query("SHOW COLUMNS FROM account_link LIKE 'link_type'");
        if ($check_column_stmt->rowCount() === 0) {
            return $templates;
        }

        // 查找所有 unidirectional 相关的连接关系
        $stmt = $pdo->prepare("
            SELECT account_id_1, account_id_2, source_account_id 
            FROM account_link 
            WHERE company_id = ? AND link_type = 'unidirectional'
        ");
        $stmt->execute([$companyId]);
        $links = $stmt->fetchAll(PDO::FETCH_ASSOC);

        // 建立结构：Main Acc => [Sub Acc 1, Sub Acc 2, ...]
        $inheritanceMap = [];
        foreach ($links as $link) {
            $source = (int)$link['source_account_id'];
            $acc1 = (int)$link['account_id_1'];
            $acc2 = (int)$link['account_id_2'];
            if ($source > 0) {
                $sub = ($acc1 === $source) ? $acc2 : $acc1;
                $inheritanceMap[$source][] = $sub;
            }
        }

        if (empty($inheritanceMap)) {
            return $templates;
        }

        // 提取一下所有受影响的 Sub Acc 的 Display Name
        $subAccountDisplayMap = [];
        foreach ($inheritanceMap as $source => $subs) {
            foreach ($subs as $sub) {
                $subAccountDisplayMap[$sub] = null;
            }
        }
        
        if (!empty($subAccountDisplayMap)) {
            $subIds = array_keys($subAccountDisplayMap);
            $placeholders = implode(',', array_fill(0, count($subIds), '?'));
            $accStmt = $pdo->prepare("SELECT id, account_id, name FROM account WHERE id IN ($placeholders)");
            $accStmt->execute($subIds);
            foreach ($accStmt->fetchAll(PDO::FETCH_ASSOC) as $accRow) {
                $display = trim((string)$accRow['account_id']);
                if (!empty($accRow['name'])) {
                    $display .= ' (' . trim((string)$accRow['name']) . ')';
                }
                $subAccountDisplayMap[(int)$accRow['id']] = $display;
            }
        }

        // 把 Main Acc 公式派生到 Sub Acc：写入 subs（勿写入 allMains，否则前端会按 main 套用并可能与已有 sub 模板重复）
        foreach ($templates as $mainKey => $templateGroup) {
            $allMains = $templateGroup['allMains'] ?? [];
            if (!isset($templates[$mainKey]['subs']) || !is_array($templates[$mainKey]['subs'])) {
                $templates[$mainKey]['subs'] = [];
            }
            $addedForSubAcc = [];

            foreach ($allMains as $t) {
                $accId = (int)$t['account_id'];
                if (!isset($inheritanceMap[$accId])) {
                    continue;
                }
                $parentIdProduct = trim((string)($t['id_product'] ?? $mainKey));
                foreach ($inheritanceMap[$accId] as $subAccId) {
                    $dedupKey = $subAccId . '_' . ($t['process_id'] ?? 0) . '_' . $parentIdProduct . '_' . ($t['row_index'] ?? '') . '_' . ($t['formula_variant'] ?? 0);
                    if (isset($addedForSubAcc[$dedupKey])) {
                        continue;
                    }

                    // 若已存在同 parent + account 的 sub 模板（含 DB 保存项），不再注入继承副本
                    $alreadyInSubs = false;
                    foreach ($templates[$mainKey]['subs'] as $existingSub) {
                        if ((int)($existingSub['account_id'] ?? 0) === (int)$subAccId
                            && trim((string)($existingSub['parent_id_product'] ?? '')) === $parentIdProduct) {
                            $alreadyInSubs = true;
                            break;
                        }
                    }
                    if ($alreadyInSubs) {
                        $addedForSubAcc[$dedupKey] = true;
                        continue;
                    }

                    $subT = $t;
                    $subT['product_type'] = 'sub';
                    $subT['account_id'] = $subAccId;
                    $subT['account_display'] = $subAccountDisplayMap[$subAccId] ?? $t['account_display'];
                    $subT['parent_id_product'] = $parentIdProduct;
                    $subT['inherited_from_account_link'] = true;
                    // 合成 id 仅供去重；前端按 subs 路径套用，不以 allMains 处理
                    $subT['id'] = 'inherit_' . (int)($t['id'] ?? 0) . '_' . (int)$subAccId;

                    $templates[$mainKey]['subs'][] = $subT;
                    $addedForSubAcc[$dedupKey] = true;
                }
            }
        }
    } catch (Exception $e) {
        error_log('inheritFormulasToSubAccounts Error: ' . $e->getMessage());
    }

    return $templates;
}

function fetchTemplates(PDO $pdo, array $ids, ?int $processId = null, ?array &$rawSubRowsOut = null) {
    global $company_id, $capture_scope_group, $capture_scope_ctx, $scopeParams;

    if (empty($ids) || $processId === null || $processId <= 0) {
        return [];
    }

    // 查询时同时匹配完整 id 与 base（括号前），便于库中既有完整也有简写时都能取到
    $expandedIds = [];
    foreach ($ids as $id) {
        $tid = trim((string) $id);
        if ($tid !== '' && !in_array($tid, $expandedIds, true)) {
            $expandedIds[] = $tid;
        }
        $base = $tid !== '' ? baseIdProductForKey($tid) : '';
        if ($base !== '' && !in_array($base, $expandedIds, true)) {
            $expandedIds[] = $base;
        }
    }
    $ids = array_values($expandedIds);

    // Build case-insensitive query to match all case variants
    $placeholders = implode(',', array_fill(0, count($ids), '?'));
    $lowerIds = array_map('strtolower', $ids);

    $ledgerSql = ' AND dct.company_id = ? ';
    $ledgerParams = [];

    if (!empty($capture_scope_ctx) && is_array($capture_scope_ctx)) {
        require_once __DIR__ . '/../formula_maintenance/formula_maintenance_scope.php';
        $ledger = formulaMaintenanceBuildTemplateLedgerFilter($pdo, $capture_scope_ctx, 'dct');
        $ledgerSql = $ledger['sql'];
        $ledgerParams = $ledger['params'];
    } elseif (!empty($capture_scope_group)) {
        $groupCode = dcNormalizeGroupId($scopeParams['view_group'] ?? $scopeParams['group_id'] ?? '');
        $templateCompanyId = $groupCode !== '' ? dcResolveGroupCaptureCompanyId($pdo, $groupCode) : 0;
        if ($templateCompanyId <= 0) {
            $templateCompanyId = (int) ($company_id ?? 0);
        }
        if ($templateCompanyId <= 0) {
            throw new Exception('缺少公司信息');
        }
        $ledgerSql = ' AND dct.company_id = ? ' . dcSqlCaptureOnGroupEntityCompany('dct');
        $ledgerParams = [$templateCompanyId];
    } else {
        $companyId = (int) ($company_id ?? 0);
        if ($companyId <= 0) {
            if (isset($_SESSION['company_id'])) {
                $companyId = (int) $_SESSION['company_id'];
            } else {
                throw new Exception('缺少公司信息');
            }
        }
        $ledgerSql = ' AND dct.company_id = ? ' . dcSqlCaptureOnSubsidiaryCompany('dct');
        $ledgerParams = [$companyId];
    }

    // 前端传的是 normalize 后的 id（如 ALLBET95MS、MY EARNINGS），库里有完整 id（如 ALLBET95MS(SV)MYR、MY EARNINGS : (RINGGIT...)），
    // 需同时按「前缀」匹配；括号前带 " : " 的 id 再按「去掉尾部空格和冒号」匹配，与前端一致。
    $stmt = $pdo->prepare("
        SELECT
            dct.id,
            dct.id_product,
            dct.product_type,
            dct.parent_id_product,
            dct.template_key,
            dct.description,
            dct.account_id,
            dct.account_display,
            dct.currency_id,
            dct.currency_display,
            dct.source_columns,
            dct.formula_operators,
            dct.source_percent,
            dct.enable_source_percent,
            dct.input_method,
            dct.enable_input_method,
            dct.batch_selection,
            dct.columns_display,
            dct.formula_display,
            dct.last_source_value,
            dct.last_processed_amount,
            dct.process_id,
            dct.data_capture_id,
            dct.row_index,
            dct.sub_order,
            dct.formula_variant,
            dct.updated_at
        FROM data_capture_templates dct
        WHERE dct.process_id = ?
          {$ledgerSql}
          AND (
            (dct.product_type = 'main' AND (
                LOWER(dct.id_product) IN ($placeholders)
                OR LOWER(TRIM(SUBSTRING(dct.id_product, 1, IF(LOCATE('(', dct.id_product) > 0, LOCATE('(', dct.id_product) - 1, LENGTH(dct.id_product))))) IN ($placeholders)
                OR LOWER(TRIM(TRIM(TRAILING ':' FROM TRIM(SUBSTRING(dct.id_product, 1, IF(LOCATE('(', dct.id_product) > 0, LOCATE('(', dct.id_product) - 1, LENGTH(dct.id_product))))))) IN ($placeholders)
            ))
            OR (dct.product_type = 'sub' AND (
                LOWER(dct.parent_id_product) IN ($placeholders)
                OR LOWER(TRIM(SUBSTRING(dct.parent_id_product, 1, IF(LOCATE('(', dct.parent_id_product) > 0, LOCATE('(', dct.parent_id_product) - 1, LENGTH(dct.parent_id_product))))) IN ($placeholders)
                OR LOWER(TRIM(TRIM(TRAILING ':' FROM TRIM(SUBSTRING(dct.parent_id_product, 1, IF(LOCATE('(', dct.parent_id_product) > 0, LOCATE('(', dct.parent_id_product) - 1, LENGTH(dct.parent_id_product))))))) IN ($placeholders)
            ))
          )
        ORDER BY CASE WHEN dct.row_index IS NULL THEN 1 ELSE 0 END,
                 dct.row_index ASC,
                 dct.process_id DESC,
                 CASE 
                     WHEN dct.product_type = 'main' THEN COALESCE(dct.id_product, '')
                     WHEN dct.product_type = 'sub' THEN COALESCE(dct.parent_id_product, '')
                     ELSE COALESCE(dct.id_product, '')
                 END ASC,
                 dct.product_type ASC,
                 CASE WHEN dct.sub_order IS NULL THEN 1 ELSE 0 END,
                 dct.sub_order ASC,
                 dct.formula_variant ASC,
                 dct.id ASC
    ");

    $params = array_merge([$processId], $ledgerParams, $lowerIds, $lowerIds, $lowerIds, $lowerIds, $lowerIds, $lowerIds);
    $stmt->execute($params);
    $results = $stmt->fetchAll(PDO::FETCH_ASSOC);

    if ($rawSubRowsOut !== null) {
        $rawSubRowsOut = [];
        foreach ($results as $row) {
            if (($row['product_type'] ?? '') === 'sub') {
                $rawSubRowsOut[] = $row;
            }
        }
    }

    $templates = [];
    foreach ($results as $row) {
        // Formula 只绑定当前 process：不再 claim process_id IS NULL 的模板，避免在其他 process 呈现

        // Ensure source_percent is always a string to preserve decimal values and expressions
        // This is important because decimal fields might be returned as numbers, losing precision
        if (isset($row['source_percent'])) {
            $row['source_percent'] = (string)$row['source_percent'];
        }
        
        $productType = $row['product_type'] ?? 'main';

        if ($productType === 'sub') {
            $parentId = $row['parent_id_product'] ?? $row['id_product'];
            // 用完整 parent_id_product 作 key，避免 GAMS(SV)HKD 与 GAMS(SV)MYR 混在同一组（只检测 GAMS 会混掉）
            $parentKey = trim((string) $parentId);
            if ($parentKey === '') {
                $parentKey = baseIdProductForKeyNormalized($parentId);
                if ($parentKey === '') {
                    $parentKey = baseIdProductForKey($parentId);
                }
                if ($parentKey === '') {
                    $parentKey = $parentId;
                }
            }
            if (!isset($templates[$parentKey])) {
                $templates[$parentKey] = [
                    'main' => null,
                    'subs' => [],
                    'allMains' => [] // Store all main templates for this parent
                ];
            }
            // Check for duplicate sub templates (same id_product, account_id, batch_selection, formula_variant, AND sub_order)
            // Only remove duplicates if ALL these fields match, including formula_variant and sub_order
            // This allows multiple sub rows with same account but different sub_order or different formulas
            $isDuplicate = false;
            $currentSubOrder = isset($row['sub_order']) && $row['sub_order'] !== null ? (float)$row['sub_order'] : null;
            foreach ($templates[$parentKey]['subs'] as $index => $existingSub) {
                $existingSubOrder = isset($existingSub['sub_order']) && $existingSub['sub_order'] !== null ? (float)$existingSub['sub_order'] : null;
                if ($existingSub['id_product'] === $row['id_product'] 
                    && $existingSub['account_id'] === $row['account_id']
                    && (int)$existingSub['batch_selection'] === (int)$row['batch_selection']
                    && (int)($existingSub['formula_variant'] ?? 1) === (int)($row['formula_variant'] ?? 1)
                    && (($existingSubOrder === null && $currentSubOrder === null) || ($existingSubOrder !== null && $currentSubOrder !== null && abs($existingSubOrder - $currentSubOrder) < 0.0001))) {
                    // Found duplicate (same id_product, account_id, batch_selection, formula_variant, AND sub_order)
                    // Keep the one with latest updated_at
                    $existingUpdated = $existingSub['updated_at'] ?? '';
                    $currentUpdated = $row['updated_at'] ?? '';
                    if ($currentUpdated > $existingUpdated) {
                        // Replace with newer one
                        $templates[$parentKey]['subs'][$index] = $row;
                    }
                    $isDuplicate = true;
                    break;
                }
            }
            if (!$isDuplicate) {
                // Add sub templates for this process only (formula 仅绑定当前 process)
                // This allows multiple sub rows with same account but different formulas
                $templates[$parentKey]['subs'][] = $row;
            }
        } else {
            $idProduct = $row['id_product'];
            // 用完整 id_product 作 key，避免 GAMS(SV)HKD 与 GAMS(SV)MYR 混在同一组（不要只检测 GAMS 前面）
            $mainKey = trim((string) $idProduct);
            if ($mainKey === '') {
                $mainKey = baseIdProductForKeyNormalized($idProduct);
                if ($mainKey === '') {
                    $mainKey = baseIdProductForKey($idProduct);
                }
                if ($mainKey === '') {
                    $mainKey = $idProduct;
                }
            }
            if (!isset($templates[$mainKey])) {
                $templates[$mainKey] = [
                    'main' => null,
                    'subs' => [],
                    'allMains' => [] // Store all main templates for different process_id
                ];
            }
            
            // Store all main templates for current process only (formula 仅绑定当前 process)
            $templates[$mainKey]['allMains'][] = $row;
            
            // For backward compatibility, still set 'main' to the best default
            // But frontend should use 'allMains' to apply all templates
            // Priority: prefer template with process_id, then most recent
            if ($templates[$mainKey]['main'] === null) {
                $templates[$mainKey]['main'] = $row;
            } else {
                $existing = $templates[$mainKey]['main'];
                $existingProcessId = $existing['process_id'] ?? null;
                $currentProcessId = $row['process_id'] ?? null;
                
                // If existing is generic (NULL) and current is specific, use current
                if ($existingProcessId === null && $currentProcessId !== null) {
                    $templates[$mainKey]['main'] = $row;
                }
                // If both are specific or both are generic, prefer the one with more recent updated_at
                else if (($existingProcessId === null) === ($currentProcessId === null)) {
                    $existingUpdated = $existing['updated_at'] ?? '';
                    $currentUpdated = $row['updated_at'] ?? '';
                    if ($currentUpdated > $existingUpdated) {
                        $templates[$mainKey]['main'] = $row;
                    }
                }
                // Otherwise keep existing (existing is specific, current is generic)
            }
        }
    }

    // Summary 只使用 DB 中显式保存的 sub（含 parent_id_product），不再注入 account_link 虚拟继承行。
    // inheritFormulasToSubAccounts 曾导致「DB 一条 + inherit 一条」刷新后重复显示。

    foreach ($templates as $mainKey => $group) {
        if (!empty($group['subs']) && is_array($group['subs'])) {
            $templates[$mainKey]['subs'] = dedupeTemplateGroupSubs($group['subs']);
        }
    }

    return $templates;
}

require_once __DIR__ . '/../datacapture/data_capture_scope_common.php';

// 检查用户是否登录
if (!isset($_SESSION['user_id'])) {
    http_response_code(401);
    echo json_encode(['success' => false, 'message' => '用户未登录', 'data' => null]);
    exit;
}

$scopeParams = array_merge($_GET, $_POST);
$capture_scope_group = false;
$capture_scope_ctx = [];
$req_action_for_company = isset($_GET['action']) ? (string) $_GET['action'] : '';
$hasExplicitScope = dcRequestHasExplicitScope($scopeParams);

try {
    if ($hasExplicitScope) {
        $scopeResolved = resolveDataCaptureRequestScope($pdo, $scopeParams);
        $scopeCtx = dcFinalizeCaptureMaintenanceScope($pdo, $scopeResolved, $scopeParams);
        $capture_scope_ctx = $scopeCtx;
        $company_id = (int) $scopeCtx['company_id'];
        $capture_scope_group = (bool) $scopeCtx['is_group_scope'];
    } else {
        $company_id = null;
        if (isset($scopeParams['company_id']) && $scopeParams['company_id'] !== '') {
            $company_id = (int) $scopeParams['company_id'];
        } elseif (isset($_SESSION['company_id'])) {
            $company_id = (int) $_SESSION['company_id'];
        }
        if (
            !$hasExplicitScope
            && ($req_action_for_company === 'save_summary_state' || $req_action_for_company === 'get_summary_state')
            && isset($_SESSION['company_id'])
            && (int) $_SESSION['company_id'] > 0
        ) {
            $company_id = (int) $_SESSION['company_id'];
        }
        $capture_scope_group = false;
    }
} catch (Exception $scopeException) {
    http_response_code(403);
    echo json_encode(['success' => false, 'message' => $scopeException->getMessage(), 'data' => null]);
    exit;
}

if (!$company_id) {
    http_response_code(401);
    echo json_encode(['success' => false, 'message' => '缺少公司信息', 'data' => null]);
    exit;
}

$groupIdForAccess = dcNormalizeGroupId($scopeParams['group_id'] ?? '');
if (!checkReportGamesAccess($pdo, $company_id, $groupIdForAccess !== '' ? $groupIdForAccess : null)) {
    http_response_code(403);
    echo json_encode(['success' => false, 'message' => 'Unauthorized permission category', 'data' => null]);
    exit;
}

$viewGroupForAccess = dcNormalizeGroupId(
    $scopeParams['view_group'] ?? $scopeParams['group_id'] ?? ''
);
try {
    dcAssertUserCanAccessCompany(
        $pdo,
        (int) $company_id,
        $viewGroupForAccess !== '' ? $viewGroupForAccess : null
    );
} catch (Exception $accessException) {
    http_response_code(403);
    echo json_encode(['success' => false, 'message' => $accessException->getMessage(), 'data' => null]);
    exit;
}

$action = isset($_GET['action']) ? $_GET['action'] : 'load';

if ($action === 'save_template' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    // Handle save template action (auto-save when formula is saved)
    try {
        $jsonData = file_get_contents('php://input');
        $row = json_decode($jsonData, true);
        
        if (!$row) {
            throw new Exception('Invalid JSON data');
        }
        
        // Validate required fields
        if (empty($row['id_product']) || empty($row['account_id'])) {
            throw new Exception('Missing required fields: id_product or account_id');
        }
        
        // Prepare template payload
        $templatePayload = [
            'product_type' => $row['product_type'] ?? 'main',
            'id_product' => $row['id_product'],
            'parent_id_product' => $row['parent_id_product'] ?? null,
            'id_product_main' => $row['id_product_main'] ?? null,
            'id_product_sub' => $row['id_product_sub'] ?? null,
            'description' => $row['description'] ?? null,
            'description_sub' => $row['description_sub'] ?? null,
            'account_id' => $row['account_id'],
            'account_display' => $row['account_display'] ?? null,
            'currency_id' => $row['currency_id'] ?? null,
            'currency_display' => $row['currency_display'] ?? null,
            'source_columns' => $row['source_columns'] ?? '',
            'formula_operators' => $row['formula_operators'] ?? '',
            'source_percent' => isset($row['source_percent']) && $row['source_percent'] !== '' ? (string)$row['source_percent'] : '1', // Default to '1' (multiplier)
            'enable_source_percent' => isset($row['enable_source_percent']) ? (int)$row['enable_source_percent'] : 1,
            'input_method' => $row['input_method'] ?? null,
            'enable_input_method' => isset($row['enable_input_method']) ? (int)$row['enable_input_method'] : 0,
            'batch_selection' => isset($row['batch_selection']) ? (int)$row['batch_selection'] : 0,
            'columns_display' => $row['columns_display'] ?? null,
            'formula_display' => $row['formula_display'] ?? null,
            'last_source_value' => $row['last_source_value'] ?? null,
            'last_processed_amount' => isset($row['last_processed_amount']) ? $row['last_processed_amount'] : 0,
            'template_key' => $row['template_key'] ?? null,
            'process_id' => isset($row['process_id']) && is_numeric($row['process_id']) ? (int)$row['process_id'] : null,
            'data_capture_id' => isset($row['data_capture_id']) && !empty($row['data_capture_id']) ? (int)$row['data_capture_id'] : null,
            // Preserve row position in summary table if provided
            'row_index' => isset($row['row_index']) && $row['row_index'] !== null ? (int)$row['row_index'] : null,
            'sub_order' => isset($row['sub_order']) && $row['sub_order'] !== null && $row['sub_order'] !== '' ? (float)$row['sub_order'] : null,
            // Pass template_id and formula_variant for editing existing templates
            'template_id' => isset($row['template_id']) && !empty($row['template_id']) ? (int)$row['template_id'] : null,
            'formula_variant' => isset($row['formula_variant']) && $row['formula_variant'] !== null && $row['formula_variant'] !== '' ? (int)$row['formula_variant'] : null,
        ];

        if (!empty($templatePayload['process_id'])) {
            dcAssertProcessIdInCaptureScope(
                $pdo,
                (int) $templatePayload['process_id'],
                (int) $company_id,
                (bool) $capture_scope_group
            );
        }
        
        $templateResult = saveTemplateRow($pdo, $templatePayload, $company_id);

        if ($templateResult !== null) {
            backfillTemplateScope(
                $pdo,
                (int) $company_id,
                resolveTemplateScopeInsertForSave($pdo, (int) $company_id)
            );
        }
        
        // Handle both old format (string) and new format (array) for backward compatibility
        $templateKey = is_array($templateResult) ? $templateResult['template_key'] : $templateResult;
        $templateId = is_array($templateResult) ? $templateResult['template_id'] : null;
        $formulaVariant = is_array($templateResult) ? $templateResult['formula_variant'] : null;
        
        // 显式同步到所有 Multi-Process（Copy From 源账号修改 Formula 后，同步到 sync_source_process_id 指向该源的流程）
        $processIdForSync = isset($templatePayload['process_id']) && $templatePayload['process_id'] > 0 ? (int)$templatePayload['process_id'] : null;
        $formulaVariantForSync = $formulaVariant !== null ? $formulaVariant : (isset($templatePayload['formula_variant']) && $templatePayload['formula_variant'] !== '' ? (int)$templatePayload['formula_variant'] : null);
        if ($processIdForSync && $templateResult !== null && $formulaVariantForSync !== null) {
            $syncTemplateData = [
                'id_product' => $templatePayload['id_product'],
                'account_id' => $templatePayload['account_id'],
                'product_type' => $templatePayload['product_type'] ?? 'main',
                'formula_variant' => $formulaVariantForSync,
                'source_columns' => $templatePayload['source_columns'] ?? '',
                'formula_operators' => $templatePayload['formula_operators'] ?? '',
                'source_percent' => isset($templatePayload['source_percent']) && $templatePayload['source_percent'] !== '' ? (string)$templatePayload['source_percent'] : '1',
                'enable_source_percent' => (isset($templatePayload['source_percent']) && $templatePayload['source_percent'] !== '' && $templatePayload['source_percent'] !== '0') ? 1 : 0,
                'input_method' => $templatePayload['input_method'] ?? null,
                'enable_input_method' => isset($templatePayload['enable_input_method']) ? (int)$templatePayload['enable_input_method'] : 0,
                'columns_display' => $templatePayload['columns_display'] ?? null,
                'formula_display' => $templatePayload['formula_display'] ?? null,
                'description' => $templatePayload['description'] ?? null,
                'account_display' => $templatePayload['account_display'] ?? null,
                'currency_id' => $templatePayload['currency_id'] ?? null,
                'currency_display' => $templatePayload['currency_display'] ?? null,
                'sub_order' => isset($templatePayload['sub_order']) && $templatePayload['sub_order'] !== null && $templatePayload['sub_order'] !== '' ? (float)$templatePayload['sub_order'] : null,
                'template_key' => $templatePayload['template_key'] ?? null,
                'parent_id_product' => $templatePayload['parent_id_product'] ?? null,
                'batch_selection' => isset($templatePayload['batch_selection']) ? (int)$templatePayload['batch_selection'] : 0,
                'last_source_value' => $templatePayload['last_source_value'] ?? null,
                'last_processed_amount' => isset($templatePayload['last_processed_amount']) ? $templatePayload['last_processed_amount'] : 0,
                'row_index' => isset($templatePayload['row_index']) ? (int)$templatePayload['row_index'] : null,
                'data_capture_id' => isset($templatePayload['data_capture_id']) ? (int)$templatePayload['data_capture_id'] : null,
            ];
            syncFormulaToMultiUseProcesses($pdo, $processIdForSync, $syncTemplateData, $company_id);
        }
        
        echo json_encode([
            'success' => true,
            'message' => 'Template saved successfully',
            'template_key' => $templateKey, // Return the computed template_key so frontend can update DOM
            'template_id' => $templateId, // Return template ID for precise deletion
            'formula_variant' => $formulaVariant // Return formula_variant for precise deletion
        ]);
    } catch (Exception $e) {
        error_log('Template Save Error: ' . $e->getMessage());
        echo json_encode([
            'success' => false,
            'message' => $e->getMessage(),
            'data' => null,
        ]);
    }
    exit;
}

if ($action === 'delete_template' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    // Handle delete template action (when row is deleted)
    try {
        $jsonData = file_get_contents('php://input');
        $data = json_decode($jsonData, true);
        
        if (!$data) {
            throw new Exception('Invalid JSON data');
        }
        
        // Validate required fields
        if (empty($data['template_key']) || empty($data['product_type'])) {
            throw new Exception('Missing required fields: template_key or product_type');
        }
        
        $productType = $data['product_type'];
        $templateKey = $data['template_key'];
        $templateId = isset($data['template_id']) && !empty($data['template_id']) ? (int)$data['template_id'] : null;
        $formulaVariant = isset($data['formula_variant']) && $data['formula_variant'] !== null && $data['formula_variant'] !== '' ? (int)$data['formula_variant'] : null;
        $sourceProcessId = isset($data['process_id']) && is_numeric($data['process_id']) ? (int)$data['process_id'] : null;
        
        $companyId = $company_id;
        
        // 删除前先取出行数据，用于同步删除 B_ID/C_ID 的对应行
        $rowForSync = null;
        if ($templateId) {
            $sel = $pdo->prepare("SELECT id_product, account_id, product_type, formula_variant, sub_order, process_id FROM data_capture_templates WHERE id = ? AND company_id = ? LIMIT 1");
            $sel->execute([$templateId, $companyId]);
            $rowForSync = $sel->fetch(PDO::FETCH_ASSOC);
        } elseif ($sourceProcessId && $templateKey && $formulaVariant !== null) {
            $sel = $pdo->prepare("SELECT id_product, account_id, product_type, formula_variant, sub_order, process_id FROM data_capture_templates WHERE company_id = ? AND process_id = ? AND template_key = ? AND product_type = ? AND formula_variant = ? LIMIT 1");
            $sel->execute([$companyId, $sourceProcessId, $templateKey, $productType, $formulaVariant]);
            $rowForSync = $sel->fetch(PDO::FETCH_ASSOC);
        }
        
        if ($templateId) {
            $sql = "
                DELETE FROM data_capture_templates 
                WHERE company_id = :company_id
                  AND id = :template_id
            ";
            $stmt = $pdo->prepare($sql);
            $params = [
                ':company_id' => $companyId,
                ':template_id' => $templateId
            ];
        } else if ($formulaVariant !== null) {
            $sql = "
                DELETE FROM data_capture_templates 
                WHERE company_id = :company_id
                  AND product_type = :product_type 
                  AND template_key = :template_key
                  AND formula_variant = :formula_variant
            ";
            $stmt = $pdo->prepare($sql);
            $params = [
                ':company_id' => $companyId,
                ':product_type' => $productType,
                ':template_key' => $templateKey,
                ':formula_variant' => $formulaVariant
            ];
            if ($sourceProcessId) {
                $sql .= " AND process_id = :process_id";
                $params[':process_id'] = $sourceProcessId;
            }
            $stmt = $pdo->prepare($sql);
        } else {
            // 无 template_id 且无 formula_variant 时，不能按 template_key+product_type 批量删除，否则会误删同 key 的其他行（如 main 与 sub、或同 id_product 多 account）
            // 先查询匹配的行数；仅当恰好 1 条时按该行 id 删除，保证「没有勾选 delete 的数据都保留」
            $selSql = "
                SELECT id, id_product, account_id, product_type, formula_variant, sub_order, process_id 
                FROM data_capture_templates 
                WHERE company_id = :company_id
                  AND product_type = :product_type 
                  AND template_key = :template_key
            ";
            $selParams = [
                ':company_id' => $companyId,
                ':product_type' => $productType,
                ':template_key' => $templateKey
            ];
            if ($sourceProcessId) {
                $selSql .= " AND process_id = :process_id";
                $selParams[':process_id'] = $sourceProcessId;
            }
            $selStmt = $pdo->prepare($selSql);
            $selStmt->execute($selParams);
            $matchingRows = $selStmt->fetchAll(PDO::FETCH_ASSOC);
            $matchCount = count($matchingRows);
            if ($matchCount > 1) {
                echo json_encode([
                    'success' => false,
                    'message' => 'Multiple rows match (template_key + product_type). Please delete by selecting the specific row with template_id.',
                    'deleted_count' => 0
                ]);
                exit;
            }
            if ($matchCount === 0) {
                echo json_encode([
                    'success' => true,
                    'message' => 'Template not found (may have been already deleted)',
                    'deleted_count' => 0
                ]);
                exit;
            }
            $singleRow = $matchingRows[0];
            $rowForSync = $singleRow;
            $templateId = (int)$singleRow['id'];
            $sql = "
                DELETE FROM data_capture_templates 
                WHERE company_id = :company_id
                  AND id = :template_id
            ";
            $stmt = $pdo->prepare($sql);
            $params = [
                ':company_id' => $companyId,
                ':template_id' => $templateId
            ];
        }
        
        $stmt->execute($params);
        
        $deletedCount = $stmt->rowCount();
        
        // 删除同步：A_ID 删除后，同步删除所有 sync_source_process_id = A_ID 的 process 中对应行
        // 优先用请求的 process_id；若未传（如按 template_id 删除），则用 $rowForSync['process_id'] 作为源
        $effectiveSourceProcessId = $sourceProcessId !== null
            ? $sourceProcessId
            : (isset($rowForSync['process_id']) && $rowForSync['process_id'] !== null && $rowForSync['process_id'] !== '' ? (int)$rowForSync['process_id'] : null);
        if ($deletedCount > 0 && $effectiveSourceProcessId !== null && $rowForSync) {
            $subOrder = isset($rowForSync['sub_order']) && $rowForSync['sub_order'] !== null && $rowForSync['sub_order'] !== '' ? (float)$rowForSync['sub_order'] : null;
            syncDeleteTemplateToMultiUseProcesses(
                $pdo,
                $effectiveSourceProcessId,
                $rowForSync['id_product'],
                $rowForSync['account_id'],
                $rowForSync['product_type'],
                (int)$rowForSync['formula_variant'],
                $subOrder,
                $companyId
            );
        }
        
        if ($deletedCount > 0) {
            if ($templateId) {
                error_log("Deleted template by ID: template_id=$templateId");
            } else if ($formulaVariant) {
                error_log("Deleted template by key+variant: product_type=$productType, template_key=$templateKey, formula_variant=$formulaVariant");
            } else {
                error_log("Deleted template by key: product_type=$productType, template_key=$templateKey");
            }
            echo json_encode([
                'success' => true,
                'message' => 'Template deleted successfully',
                'deleted_count' => $deletedCount
            ]);
        } else {
            echo json_encode([
                'success' => true,
                'message' => 'Template not found (may have been already deleted)',
                'deleted_count' => 0
            ]);
        }
    } catch (Exception $e) {
        error_log('Template Delete Error: ' . $e->getMessage());
        echo json_encode([
            'success' => false,
            'message' => $e->getMessage(),
            'data' => null,
        ]);
    }
    exit;
}

// 获取 Summary 状态（行顺序 + 公式/Source/Rate 等），供刷新后恢复，减少对 localStorage 的依赖
if ($action === 'get_summary_state') {
    try {
        ensureSummaryStateTable($pdo);
        $hasScopeColumns = dcEnsureSummaryStateScopeColumns($pdo);
        global $capture_scope_ctx;
        $scopeBind = resolveSummaryStateScopeBind(
            is_array($capture_scope_ctx) ? $capture_scope_ctx : null,
            (int) $company_id
        );
        $processId = isset($_GET['process_id']) && $_GET['process_id'] !== '' && is_numeric($_GET['process_id']) ? (int)$_GET['process_id'] : null;
        $processCode = isset($_GET['process_code']) ? trim((string)$_GET['process_code']) : '';
        $processKey = $processId !== null ? ('pid_' . $processId) : ('code_' . ($processCode !== '' ? $processCode : 'none'));
        if ($hasScopeColumns) {
            $stmt = $pdo->prepare("
                SELECT state_json
                FROM data_capture_summary_state
                WHERE company_id = ?
                  AND process_key = ?
                  AND scope_type = ?
                  AND scope_id = ?
                LIMIT 1
            ");
            $stmt->execute([
                $company_id,
                $processKey,
                $scopeBind['scope_type'],
                $scopeBind['scope_id'],
            ]);
        } else {
            $stmt = $pdo->prepare("SELECT state_json FROM data_capture_summary_state WHERE company_id = ? AND process_key = ? LIMIT 1");
            $stmt->execute([$company_id, $processKey]);
        }
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        $data = null;
        if ($row && !empty($row['state_json'])) {
            $decoded = json_decode($row['state_json'], true);
            if (is_array($decoded)) {
                $data = $decoded;
            }
        }
        echo json_encode(['success' => true, 'data' => $data]);
    } catch (Exception $e) {
        error_log('get_summary_state error: ' . $e->getMessage());
        echo json_encode(['success' => false, 'message' => $e->getMessage(), 'data' => null]);
    }
    exit;
}

// 保存 Summary 状态到服务端（与 localStorage 双写，优先从服务端恢复）
if ($action === 'save_summary_state' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    try {
        $jsonData = file_get_contents('php://input');
        $payload = json_decode($jsonData, true);
        if (!is_array($payload) || !isset($payload['processId']) || !isset($payload['processCode'])) {
            echo json_encode(['success' => false, 'message' => 'Missing processId or processCode']);
            exit;
        }
        ensureSummaryStateTable($pdo);
        $hasScopeColumns = dcEnsureSummaryStateScopeColumns($pdo);
        global $capture_scope_ctx;
        $scopeBind = resolveSummaryStateScopeBind(
            is_array($capture_scope_ctx) ? $capture_scope_ctx : null,
            (int) $company_id
        );
        $processId = isset($payload['processId']) && $payload['processId'] !== null && $payload['processId'] !== '' && is_numeric($payload['processId']) ? (int)$payload['processId'] : null;
        $processCode = isset($payload['processCode']) ? trim((string)$payload['processCode']) : '';
        $processKey = $processId !== null ? ('pid_' . $processId) : ('code_' . ($processCode !== '' ? $processCode : 'none'));
        $stateJson = json_encode([
            'processId' => $payload['processId'] ?? null,
            'processCode' => $payload['processCode'] ?? '',
            'rowsByKey' => $payload['rowsByKey'] ?? [],
            'rowsByStableKey' => $payload['rowsByStableKey'] ?? [],
            'rowsByRowUid' => $payload['rowsByRowUid'] ?? [],
            'rowOrder' => $payload['rowOrder'] ?? [],
            'rateValuesByKey' => $payload['rateValuesByKey'] ?? [],
            'rateValuesByRowUid' => $payload['rateValuesByRowUid'] ?? [],
            'rateValuesByRateFingerprint' => $payload['rateValuesByRateFingerprint'] ?? [],
            'savedAt' => $payload['savedAt'] ?? null,
        ]);
        if ($hasScopeColumns) {
            $stmt = $pdo->prepare("
                INSERT INTO data_capture_summary_state
                    (company_id, scope_type, scope_id, process_key, state_json, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE state_json = VALUES(state_json), updated_at = NOW()
            ");
            $stmt->execute([
                $company_id,
                $scopeBind['scope_type'],
                $scopeBind['scope_id'],
                $processKey,
                $stateJson,
            ]);
        } else {
            $stmt = $pdo->prepare("
                INSERT INTO data_capture_summary_state (company_id, process_key, state_json, updated_at)
                VALUES (?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE state_json = VALUES(state_json), updated_at = NOW()
            ");
            $stmt->execute([$company_id, $processKey, $stateJson]);
        }
        echo json_encode(['success' => true]);
    } catch (Exception $e) {
        error_log('save_summary_state error: ' . $e->getMessage());
        echo json_encode(['success' => false, 'message' => $e->getMessage()]);
    }
    exit;
}

if ($action === 'templates') {
    try {
        $ids = [];
        $processId = null;
        $captureId = null;
        $payload = [];

        if ($_SERVER['REQUEST_METHOD'] === 'POST') {
            $jsonData = file_get_contents('php://input');
            $payload = json_decode($jsonData, true);
            if (!is_array($payload)) {
                $payload = [];
            }
            if (isset($payload['idProducts']) && is_array($payload['idProducts'])) {
                $ids = array_values(array_filter(array_map('trim', $payload['idProducts'])));
            }
            if (isset($payload['processId'])) {
                // processId should be process.id (int), not process.process_id (string)
                $processIdValue = $payload['processId'];
                if (is_numeric($processIdValue)) {
                    $processId = (int)$processIdValue;
                } elseif (is_string($processIdValue) && trim($processIdValue) !== '') {
                    $processId = (int)trim($processIdValue);
                }
            }
            if (isset($payload['captureId']) && $payload['captureId'] !== null && $payload['captureId'] !== '') {
                $captureIdVal = $payload['captureId'];
                if (is_numeric($captureIdVal)) {
                    $captureId = (int)$captureIdVal;
                } elseif (is_string($captureIdVal) && trim($captureIdVal) !== '') {
                    $captureId = (int)trim($captureIdVal);
                }
            }
        } elseif (!empty($_GET['ids'])) {
            $ids = array_values(array_filter(array_map('trim', explode(',', $_GET['ids']))));
        }

        if ($processId === null && !empty($_GET['processId'])) {
            // processId should be process.id (int)
            $getProcessId = $_GET['processId'];
            if (is_numeric($getProcessId)) {
                $processId = (int)$getProcessId;
            } elseif (is_string($getProcessId) && trim($getProcessId) !== '') {
                $processId = (int)trim($getProcessId);
            }
        }
        if (!empty($_GET['captureId']) && is_numeric($_GET['captureId'])) {
            $captureId = (int)$_GET['captureId'];
        }

        if (empty($ids)) {
            throw new Exception('No id products provided');
        }

        if ($processId === null) {
            throw new Exception('Process ID is required');
        }

        $processCompanyId = !empty($capture_scope_ctx)
            ? dcCaptureProcessCompanyId($capture_scope_ctx)
            : (int) $company_id;
        dcAssertProcessIdInCaptureScope($pdo, (int) $processId, (int) $processCompanyId, (bool) $capture_scope_group);

        // 在 Data Capture 选择的 Process 下设置的 formula 只在该 Process 显示；若该 Process 有 sync 到其他 Process 则同步显示
        // Summary 的 formula 仅来自 Maintenance（data_capture_templates）；Process 在 Maintenance 无记录则不显示 formula
        $rawSubRowsFromSql = [];
        $templates = fetchTemplates($pdo, $ids, $processId, $rawSubRowsFromSql);

        if ($captureId !== null && $captureId > 0 && $company_id && empty($capture_scope_group)) {
            $templates = mergeDetailOnlyTemplates($pdo, (int)$company_id, $captureId, $ids, $templates);
        }

        // 用 account 表统一解析 account_display，与 Maintenance - Formula 的 Account 列一致
        if (!empty($capture_scope_group)) {
            $groupCodeForTpl = dcNormalizeGroupId(
                $scopeParams['view_group'] ?? $scopeParams['group_id'] ?? ($groupIdForAccess ?? '')
            );
            if ($groupCodeForTpl !== '') {
                resolveAccountDisplayInTemplatesForGroup($pdo, $groupCodeForTpl, $templates);
            }
        } elseif ($company_id) {
            resolveAccountDisplayInTemplates($pdo, (int)$company_id, $templates);
        }

        $subsByParent = buildSubsByParentForApi($templates);
        $debug = false;
        if (isset($payload['debug']) && ($payload['debug'] === true || $payload['debug'] === 1 || $payload['debug'] === '1')) {
            $debug = true;
        } elseif (isset($_GET['debug']) && ($_GET['debug'] === '1' || $_GET['debug'] === 'true')) {
            $debug = true;
        }

        $response = [
            'success' => true,
            'templates' => $templates,
            'subsByParent' => $subsByParent,
        ];
        if ($debug) {
            $response['diagnostics'] = buildTemplateFetchDiagnostics($templates, $subsByParent, $rawSubRowsFromSql);
        }

        echo json_encode($response);
    } catch (Exception $e) {
        error_log('Template Fetch Error: ' . $e->getMessage());
        echo json_encode([
            'success' => false,
            'message' => $e->getMessage(),
            'data' => null,
        ]);
    }
    exit;
}

if ($action === 'submit' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    // Handle submit action
    $immediateAckMode = false;
    $queueJobId = null;
    try {
        // Check PHP configuration limits first
        $postMaxSize = ini_get('post_max_size');
        $postMaxSizeBytes = return_bytes($postMaxSize);
        $contentLength = isset($_SERVER['CONTENT_LENGTH']) ? (int)$_SERVER['CONTENT_LENGTH'] : 0;
        
        // Get all relevant PHP configuration values for error reporting
        $uploadMaxFilesize = ini_get('upload_max_filesize');
        $maxInputVars = ini_get('max_input_vars');
        $memoryLimit = ini_get('memory_limit');
        
        // Check if Content-Length exceeds post_max_size (before reading data)
        if ($contentLength > 0 && $contentLength > $postMaxSizeBytes) {
            $configInfo = "\n\n当前 PHP 配置：\n";
            $configInfo .= "- post_max_size: $postMaxSize\n";
            $configInfo .= "- upload_max_filesize: $uploadMaxFilesize\n";
            $configInfo .= "- max_input_vars: $maxInputVars\n";
            $configInfo .= "- memory_limit: $memoryLimit\n";
            $configInfo .= "\n实际数据大小: " . round($contentLength / 1024 / 1024, 2) . " MB";
            throw new Exception("数据太大（" . round($contentLength / 1024 / 1024, 2) . " MB），超过了 PHP post_max_size 限制（$postMaxSize）。" . $configInfo);
        }
        
        // IMPORTANT: For JSON requests (application/json), data is NOT in $_POST
        // It's only available via php://input, so we should NOT check $_POST for JSON requests
        // Only check for truncation if Content-Length exceeds post_max_size
        // For JSON requests, empty $_POST is normal and expected
        
        // Check if Content-Length exceeds post_max_size (this is the real check)
        // If it does, PHP will truncate the data before we can read it
        if ($contentLength > 0 && $contentLength > $postMaxSizeBytes) {
            $configInfo = "\n\n当前 PHP 配置：\n";
            $configInfo .= "- post_max_size: $postMaxSize (" . round($postMaxSizeBytes / 1024 / 1024, 2) . " MB)\n";
            $configInfo .= "- upload_max_filesize: $uploadMaxFilesize\n";
            $configInfo .= "- max_input_vars: $maxInputVars\n";
            $configInfo .= "- memory_limit: $memoryLimit\n";
            $configInfo .= "\n数据大小信息：\n";
            $configInfo .= "- Content-Length (请求头): " . round($contentLength / 1024 / 1024, 2) . " MB (" . round($contentLength / 1024, 2) . " KB)\n";
            $configInfo .= "\n⚠️ Content-Length (" . round($contentLength / 1024 / 1024, 2) . " MB) 超过了 post_max_size (" . round($postMaxSizeBytes / 1024 / 1024, 2) . " MB)";
            $configInfo .= "\n\n解决方案：\n";
            $configInfo .= "1. 检查 .htaccess 文件是否在网站根目录，且包含：php_value post_max_size 64M\n";
            $configInfo .= "2. 如果 .htaccess 不生效，通过 php.ini 或控制面板修改配置\n";
            $configInfo .= "3. 访问 check_php_config.php 查看当前配置状态\n";
            $configInfo .= "4. 如果数据确实很大，考虑分批提交";
            
            throw new Exception("数据太大（" . round($contentLength / 1024 / 1024, 2) . " MB），超过了 PHP post_max_size 限制（$postMaxSize）。" . $configInfo);
        }
        
        // Get POST data (php://input can only be read once)
        $jsonData = file_get_contents('php://input');
        $inputSize = strlen($jsonData);
        
        // Log data size for debugging
        error_log("Submit request - Input size: " . round($inputSize / 1024 / 1024, 2) . " MB, Content-Length: " . round($contentLength / 1024 / 1024, 2) . " MB, post_max_size: $postMaxSize");
        
        // Check if data exceeds post_max_size
        if ($inputSize > $postMaxSizeBytes) {
            $configInfo = "\n\n当前 PHP 配置：\n";
            $configInfo .= "- post_max_size: $postMaxSize (" . round($postMaxSizeBytes / 1024 / 1024, 2) . " MB)\n";
            $configInfo .= "- upload_max_filesize: $uploadMaxFilesize\n";
            $configInfo .= "- max_input_vars: $maxInputVars\n";
            $configInfo .= "- memory_limit: $memoryLimit\n";
            $configInfo .= "\n实际数据大小: " . round($inputSize / 1024 / 1024, 2) . " MB (" . round($inputSize / 1024, 2) . " KB)";
            $configInfo .= "\n\n解决方案：\n";
            $configInfo .= "1. 检查网站根目录的 .htaccess 文件是否包含：php_value post_max_size 64M\n";
            $configInfo .= "2. 如果 .htaccess 不生效，联系服务器管理员修改 php.ini\n";
            $configInfo .= "3. 访问 check_php_config.php 查看当前配置状态";
            throw new Exception("数据太大（" . round($inputSize / 1024 / 1024, 2) . " MB），超过了 PHP post_max_size 限制（$postMaxSize）。" . $configInfo);
        }
        
        if (empty($jsonData)) {
            $configInfo = "\n\n当前 PHP 配置：\n";
            $configInfo .= "- post_max_size: $postMaxSize\n";
            $configInfo .= "- Content-Length: " . round($contentLength / 1024 / 1024, 2) . " MB\n";
            $configInfo .= "\n这通常意味着数据在传输过程中被截断了。";
            throw new Exception('没有接收到数据。可能是数据太大超过了 PHP post_max_size 限制（' . $postMaxSize . '）。' . $configInfo);
        }
        
        $data = json_decode($jsonData, true);
        
        if (!$data) {
            $jsonError = json_last_error_msg();
            // Check if JSON was truncated (incomplete JSON usually means data was cut off)
            if (json_last_error() === JSON_ERROR_SYNTAX && $contentLength > $inputSize) {
                $configInfo = "\n\n当前 PHP 配置：\n";
                $configInfo .= "- post_max_size: $postMaxSize (" . round($postMaxSizeBytes / 1024 / 1024, 2) . " MB)\n";
                $configInfo .= "- Content-Length: " . round($contentLength / 1024 / 1024, 2) . " MB\n";
                $configInfo .= "- 实际接收: " . round($inputSize / 1024 / 1024, 2) . " MB\n";
                $configInfo .= "\n数据被截断，说明超过了 post_max_size 限制。";
                throw new Exception("数据太大，超过了 PHP post_max_size 限制（$postMaxSize）。数据被截断导致 JSON 解析失败。" . $configInfo);
            }
            $configInfo = "\n\n当前 PHP 配置：\n";
            $configInfo .= "- post_max_size: $postMaxSize\n";
            $configInfo .= "- Content-Length: " . round($contentLength / 1024 / 1024, 2) . " MB\n";
            throw new Exception('无效的 JSON 数据: ' . $jsonError . '。可能是数据太大导致数据被截断。' . $configInfo);
        }
        
        // Validate required fields
        if (!isset($data['captureDate']) || !isset($data['processId']) || !isset($data['currencyId'])) {
            throw new Exception('Missing required fields: captureDate, processId, or currencyId');
        }
        
        if (!isset($data['summaryRows']) || !is_array($data['summaryRows']) || count($data['summaryRows']) === 0) {
            throw new Exception('No summary rows to submit');
        }

        $groupCodeSubmit = dcNormalizeGroupId(
            $scopeParams['view_group'] ?? $scopeParams['group_id'] ?? ($groupIdForAccess ?? '')
        );
        if (!empty($data['groupOnlyCapture']) && !empty($data['captureSelectedGroup'])) {
            $capture_scope_group = true;
            $groupCodeSubmit = dcNormalizeGroupId((string) $data['captureSelectedGroup']);
        }

        if ($capture_scope_group) {
            if ($groupCodeSubmit === '') {
                $groupCodeSubmit = dcNormalizeGroupId(
                    $scopeParams['view_group'] ?? $scopeParams['group_id'] ?? ($groupIdForAccess ?? '')
                );
            }
            if ($groupCodeSubmit !== '') {
                $resolvedGroupCompanyId = dcResolveGroupCaptureCompanyId($pdo, $groupCodeSubmit);
                if ($resolvedGroupCompanyId > 0) {
                    $company_id = $resolvedGroupCompanyId;
                }
            }
        }

        $companyId = (int) $company_id;
        $processCompanyId = !empty($capture_scope_ctx)
            ? dcCaptureProcessCompanyId($capture_scope_ctx)
            : $companyId;
        $scopeInsert = !empty($capture_scope_ctx)
            ? dcCaptureScopeInsertValues($capture_scope_ctx)
            : ['company_id' => $companyId, 'scope_type' => null, 'scope_id' => null];
        $useCaptureScopeColumns = !empty($capture_scope_ctx['dual_tenant']);

        dcAssertProcessIdInCaptureScope(
            $pdo,
            (int) $data['processId'],
            (int) $processCompanyId,
            (bool) $capture_scope_group
        );

        // 可选：前端要求“立即回成功”，后端继续处理
        $immediateAckMode = !empty($data['immediateAck']);
        if ($immediateAckMode) {
            ensureSummarySubmitQueueTable($pdo);
            $queueStmt = $pdo->prepare("
                INSERT INTO data_capture_submit_queue (company_id, user_id, status, request_json, rows_count)
                VALUES (:company_id, :user_id, 'processing', :request_json, :rows_count)
            ");
            $queueStmt->execute([
                ':company_id' => $companyId,
                ':user_id' => (isset($_SESSION['user_id']) ? (int)$_SESSION['user_id'] : null),
                ':request_json' => $jsonData,
                ':rows_count' => count($data['summaryRows'])
            ]);
            $queueJobId = (int)$pdo->lastInsertId();

            echo json_encode([
                'success' => true,
                'queued' => true,
                'jobId' => $queueJobId,
                'message' => 'Data received. Processing in background.'
            ]);

            if (function_exists('fastcgi_finish_request')) {
                fastcgi_finish_request();
            } else {
                @ob_end_flush();
                @flush();
            }
        }
        
        $resolvedCurrencyId = dcResolveCaptureCurrencyId(
            $pdo,
            (bool) $capture_scope_group,
            (int) $company_id,
            $groupCodeSubmit,
            $data['currencyId'] ?? null,
            $data['currencyCode'] ?? ($data['currencyName'] ?? null)
        );
        if ($resolvedCurrencyId === null) {
            throw new Exception(
                !empty($capture_scope_group)
                    ? '所选币别不属于当前集团范围，请重新选择后再提交'
                    : '所选币别不属于当前公司，请重新选择正确的币别后再提交'
            );
        }
        $data['currencyId'] = $resolvedCurrencyId;
        
        // Get user ID from session (if available)
        $userId = isset($_SESSION['user_id']) ? $_SESSION['user_id'] : null;
        
        // 检查当前用户是 owner 还是 user
        $user_type = isset($_SESSION['user_type']) && $_SESSION['user_type'] === 'owner' ? 'owner' : 'user';
        
        // Check if this is a batch append (has captureId)
        $captureId = isset($data['captureId']) && !empty($data['captureId']) ? (int)$data['captureId'] : null;
        $isBatchAppend = $captureId !== null;
        
        // Start transaction
        $pdo->beginTransaction();
        
        try {
            if (!$isBatchAppend) {
                // Insert main capture record (first batch)
                if ($useCaptureScopeColumns) {
                    $stmt = $pdo->prepare("
                        INSERT INTO data_captures (company_id, scope_type, scope_id, capture_date, process_id, currency_id, created_by, user_type, remark) 
                        VALUES (:company_id, :scope_type, :scope_id, :capture_date, :process_id, :currency_id, :created_by, :user_type, :remark)
                    ");
                    $stmt->execute([
                        ':company_id' => (int) ($scopeInsert['company_id'] ?? $companyId),
                        ':scope_type' => $scopeInsert['scope_type'],
                        ':scope_id' => $scopeInsert['scope_id'],
                        ':capture_date' => $data['captureDate'],
                        ':process_id' => $data['processId'],
                        ':currency_id' => $data['currencyId'],
                        ':created_by' => $userId,
                        ':user_type' => $user_type,
                        ':remark' => isset($data['remark']) && !empty($data['remark']) ? $data['remark'] : null,
                    ]);
                } else {
                    $stmt = $pdo->prepare("
                        INSERT INTO data_captures (company_id, capture_date, process_id, currency_id, created_by, user_type, remark) 
                        VALUES (:company_id, :capture_date, :process_id, :currency_id, :created_by, :user_type, :remark)
                    ");
                    $stmt->execute([
                        ':company_id' => $companyId,
                        ':capture_date' => $data['captureDate'],
                        ':process_id' => $data['processId'],
                        ':currency_id' => $data['currencyId'],
                        ':created_by' => $userId,
                        ':user_type' => $user_type,
                        ':remark' => isset($data['remark']) && !empty($data['remark']) ? $data['remark'] : null,
                    ]);
                }
                
                // Get the inserted capture ID
                $captureId = $pdo->lastInsertId();
            } else {
                // Verify capture exists and belongs to same process/date/currency/company
                if ($useCaptureScopeColumns) {
                    $stmt = $pdo->prepare("
                        SELECT id FROM data_captures 
                        WHERE id = :capture_id 
                          AND scope_type = :scope_type
                          AND scope_id = :scope_id
                          AND capture_date = :capture_date 
                          AND process_id = :process_id 
                          AND currency_id = :currency_id
                    ");
                    $stmt->execute([
                        ':capture_id' => $captureId,
                        ':scope_type' => $scopeInsert['scope_type'],
                        ':scope_id' => $scopeInsert['scope_id'],
                        ':capture_date' => $data['captureDate'],
                        ':process_id' => $data['processId'],
                        ':currency_id' => $data['currencyId'],
                    ]);
                } else {
                    $stmt = $pdo->prepare("
                        SELECT id FROM data_captures 
                        WHERE id = :capture_id 
                          AND company_id = :company_id
                          AND capture_date = :capture_date 
                          AND process_id = :process_id 
                          AND currency_id = :currency_id
                    ");
                    $stmt->execute([
                        ':capture_id' => $captureId,
                        ':company_id' => $companyId,
                        ':capture_date' => $data['captureDate'],
                        ':process_id' => $data['processId'],
                        ':currency_id' => $data['currencyId'],
                    ]);
                }
                
                if (!$stmt->fetch()) {
                    throw new Exception('Invalid capture ID for batch append');
                }
            }
            
            // Insert detail records
            // Check for duplicates before inserting to prevent duplicate data
            // For 'main' type: check id_product_main, account_id, currency_id, formula_variant (id_product_sub should be NULL or empty)
            // For 'sub' type: check id_product_sub, id_product_main (as parent), account_id, currency_id, formula_variant
            // Use COALESCE to handle NULL values properly in comparison
            $checkStmtMain = $pdo->prepare("
                SELECT id FROM data_capture_details 
                WHERE company_id = :company_id
                  AND capture_id = :capture_id 
                  AND product_type = 'main'
                  AND COALESCE(id_product_main, '') = COALESCE(:id_product_main, '')
                  AND COALESCE(id_product_sub, '') = ''
                  AND account_id = :account_id
                  AND currency_id = :currency_id
                  AND formula_variant = :formula_variant
                LIMIT 1
            ");
            
            $checkStmtSub = $pdo->prepare("
                SELECT id FROM data_capture_details 
                WHERE company_id = :company_id
                  AND capture_id = :capture_id 
                  AND product_type = 'sub'
                  AND COALESCE(id_product_sub, '') = COALESCE(:id_product_sub, '')
                  AND COALESCE(id_product_main, '') = COALESCE(:id_product_main, '')
                  AND account_id = :account_id
                  AND currency_id = :currency_id
                  AND formula_variant = :formula_variant
                LIMIT 1
            ");
            
            // ⚠️ 重要说明（避免误会「数据乱了」）：
            // data_capture_details 表里有一个自增主键列 id_product（AUTO_INCREMENT），
            // 它只是「这一条明细记录本身」的 ID，不是产品编号。
            //
            // 真正的产品相关字段是：
            // - 主产品编号：id_product_main
            // - 主产品描述：description_main
            // - 子产品编号：id_product_sub
            // - 子产品描述：description_sub
            // - 产品类型：product_type（'main' / 'sub'）
            //
            // 也就是说：
            // - 你在界面上看到的「产品代码」会存到 id_product_main / id_product_sub
            // - 数据库里中间那一列递增的 172 / 173 等，是这张表自己的主键，不要拿来当产品号看
            //
            // 如果以后真的需要一个「业务上的产品 ID」列，可以另外加字段，例如：
            //   ALTER TABLE data_capture_details ADD COLUMN business_product_id VARCHAR(255) NULL AFTER product_type;
            // 然后在下面的 INSERT 里一并写入。
            
            // Ensure display_order column exists to preserve row ordering
            if (!summaryApiHasDisplayOrder($pdo)) { // static 缓存，不重复 SHOW
                try {
                    $pdo->exec("ALTER TABLE data_capture_details ADD COLUMN display_order INT NULL AFTER rate");
                    error_log('Added display_order column to data_capture_details');
                } catch (Exception $columnException) {
                    error_log('display_order column check warning: ' . $columnException->getMessage());
                }
            }
            
            $detailCompanyId = (int) ($scopeInsert['company_id'] ?? $companyId);
            $useDetailScopeColumns = $useCaptureScopeColumns
                && tenant_table_has_scope_columns($pdo, 'data_capture_details');
            if ($useDetailScopeColumns) {
                $stmt = $pdo->prepare("
                    INSERT INTO data_capture_details 
                    (company_id, scope_type, scope_id, capture_id, id_product_main, description_main, id_product_sub, description_sub, product_type, formula_variant, id_product, account_id, currency_id, columns_value, source_value, source_percent, enable_source_percent, formula, processed_amount, rate, display_order) 
                    VALUES 
                    (:company_id, :scope_type, :scope_id, :capture_id, :id_product_main, :description_main, :id_product_sub, :description_sub, :product_type, :formula_variant, :id_product, :account_id, :currency_id, :columns_value, :source_value, :source_percent, :enable_source_percent, :formula, :processed_amount, :rate, :display_order)
                ");
            } else {
                $stmt = $pdo->prepare("
                    INSERT INTO data_capture_details 
                    (company_id, capture_id, id_product_main, description_main, id_product_sub, description_sub, product_type, formula_variant, id_product, account_id, currency_id, columns_value, source_value, source_percent, enable_source_percent, formula, processed_amount, rate, display_order) 
                    VALUES 
                    (:company_id, :capture_id, :id_product_main, :description_main, :id_product_sub, :description_sub, :product_type, :formula_variant, :id_product, :account_id, :currency_id, :columns_value, :source_value, :source_percent, :enable_source_percent, :formula, :processed_amount, :rate, :display_order)
                ");
            }
            
            // 同一 capture 下相同 id_product_main 按顺序：第一条为 main，后续均为 sub
            $mainSeenForIdProductMain = [];
            if ($isBatchAppend) {
                $existMainStmt = $pdo->prepare("
                    SELECT DISTINCT COALESCE(TRIM(id_product_main), '') AS id_product_main
                    FROM data_capture_details
                    WHERE capture_id = ? AND company_id = ? AND product_type = 'main' AND COALESCE(id_product_main, '') != ''
                ");
                $existMainStmt->execute([$captureId, $companyId]);
                while ($r = $existMainStmt->fetch(PDO::FETCH_ASSOC)) {
                    $mainSeenForIdProductMain[$r['id_product_main']] = true;
                }
            }
            
            // Track display_order to preserve row order from frontend
            $displayOrder = 0;
            // Performance optimization:
            // Build in-memory formula_variant maps to avoid per-row SQL lookups.
            // Key format:
            // - main formula key: "<id_product_main>|<account_id>|<formula>"
            // - main max key: "<id_product_main>|<account_id>"
            // - sub formula key: "<id_product_sub>|<id_product_main>|<account_id>|<formula>"
            // - sub max key: "<id_product_sub>|<id_product_main>|<account_id>"
            $variantByFormulaMain = [];
            $variantMaxMain = [];
            $variantByFormulaSub = [];
            $variantMaxSub = [];

            if ($isBatchAppend) {
                $variantSeedStmt = $pdo->prepare("
                    SELECT
                        product_type,
                        COALESCE(id_product_main, '') AS id_product_main,
                        COALESCE(id_product_sub, '') AS id_product_sub,
                        account_id,
                        COALESCE(formula, '') AS formula,
                        COALESCE(formula_variant, 0) AS formula_variant
                    FROM data_capture_details
                    WHERE company_id = ? AND capture_id = ?
                ");
                $variantSeedStmt->execute([$companyId, $captureId]);
                while ($seed = $variantSeedStmt->fetch(PDO::FETCH_ASSOC)) {
                    $seedType = trim((string)($seed['product_type'] ?? 'main'));
                    $seedMain = trim((string)($seed['id_product_main'] ?? ''));
                    $seedSub = trim((string)($seed['id_product_sub'] ?? ''));
                    $seedAccountId = (int)($seed['account_id'] ?? 0);
                    $seedFormula = (string)($seed['formula'] ?? '');
                    $seedVariant = (int)($seed['formula_variant'] ?? 0);

                    if ($seedType === 'sub') {
                        $formulaKey = $seedSub . '|' . $seedMain . '|' . $seedAccountId . '|' . $seedFormula;
                        $maxKey = $seedSub . '|' . $seedMain . '|' . $seedAccountId;
                        if (!isset($variantByFormulaSub[$formulaKey])) {
                            $variantByFormulaSub[$formulaKey] = $seedVariant;
                        }
                        if (!isset($variantMaxSub[$maxKey]) || $seedVariant > $variantMaxSub[$maxKey]) {
                            $variantMaxSub[$maxKey] = $seedVariant;
                        }
                    } else {
                        $formulaKey = $seedMain . '|' . $seedAccountId . '|' . $seedFormula;
                        $maxKey = $seedMain . '|' . $seedAccountId;
                        if (!isset($variantByFormulaMain[$formulaKey])) {
                            $variantByFormulaMain[$formulaKey] = $seedVariant;
                        }
                        if (!isset($variantMaxMain[$maxKey]) || $seedVariant > $variantMaxMain[$maxKey]) {
                            $variantMaxMain[$maxKey] = $seedVariant;
                        }
                    }
                }
            }

            foreach ($data['summaryRows'] as $row) {
                // Validate row data
                if (!isset($row['accountId'])) {
                    throw new Exception('Missing required row data: accountId');
                }

                dcAssertAccountIdInCaptureScope(
                    $pdo,
                    (int) $row['accountId'],
                    (bool) $capture_scope_group,
                    (int) $company_id,
                    $groupCodeSubmit
                );
                
                // Validate that at least one of main or sub is provided
                if (empty($row['idProductMain']) && empty($row['idProductSub'])) {
                    throw new Exception('Missing required row data: idProductMain or idProductSub');
                }
                
                // Get display_order from row data, or use auto-incrementing counter
                // This preserves the exact order from the frontend summary table
                $rowDisplayOrder = isset($row['displayOrder']) && $row['displayOrder'] !== null ? (int)$row['displayOrder'] : $displayOrder;
                $displayOrder++;
                
                // Determine product_type: 同一 id_product_main 下第一条为 main，其余为 sub；仅 id_product_sub 有值且 main 空时为 sub
                $productType = 'main';
                if (empty($row['idProductMain']) && !empty($row['idProductSub'])) {
                    $productType = 'sub';
                } elseif (!empty($row['idProductMain'])) {
                    $key = trim((string)$row['idProductMain']);
                    if (isset($mainSeenForIdProductMain[$key])) {
                        $productType = 'sub';
                    } else {
                        $productType = 'main';
                        $mainSeenForIdProductMain[$key] = true;
                    }
                } else {
                    $productType = $row['productType'] ?? 'main';
                }

                $normalizedIdProductMain = trim((string)($row['idProductMain'] ?? ''));
                $normalizedIdProductSub = trim((string)($row['idProductSub'] ?? ''));
                $normalizedIdProduct = $productType === 'sub'
                    ? ($normalizedIdProductSub !== '' ? $normalizedIdProductSub : $normalizedIdProductMain)
                    : ($normalizedIdProductMain !== '' ? $normalizedIdProductMain : $normalizedIdProductSub);
                if ($normalizedIdProduct === '') {
                    throw new Exception('Missing required row data: id_product');
                }
                
                // Check for duplicate before inserting
                // 注意：
                // - 首次提交（$isBatchAppend === false）时，同一个 capture 还没有明细记录，
                //   此时不需要做「重复检查」，前端 Summary 中的每一行都应当各自插入一条记录。
                // - 只有在追加批次（$isBatchAppend === true，带 captureId 再次提交）时，
                //   才根据 product/account/currency/formula_variant 判断是否更新已有记录，避免重复。
                $existingRecord = false;
                $rowCurrencyId = dcResolveCaptureCurrencyId(
                    $pdo,
                    (bool) $capture_scope_group,
                    (int) $company_id,
                    $groupCodeSubmit,
                    $row['currencyId'] ?? null,
                    $row['currencyCode'] ?? null
                );
                if ($rowCurrencyId === null) {
                    $rowCurrencyId = $data['currencyId'];
                    error_log(
                        'Row currency_id fallback to capture currency. account_id='
                        . ($row['accountId'] ?? '')
                        . ' scope=' . (!empty($capture_scope_group) ? 'group' : 'company')
                    );
                }

                // Get formula_variant from row data
                // If formulaVariant is provided and not null, use it; otherwise generate a new one
                $formulaVariant = null;
                if (isset($row['formulaVariant']) && $row['formulaVariant'] !== null && $row['formulaVariant'] !== '') {
                    $formulaVariant = (int)$row['formulaVariant'];
                }
                
                // If formula_variant not provided or is null, find the next available variant for this id_product and account_id
                if ($formulaVariant === null) {
                    $formula = (string)($row['formula'] ?? '');
                    if ($productType === 'main') {
                        $keyMain = trim((string)($row['idProductMain'] ?? ''));
                        $keyAccountId = (int)($row['accountId'] ?? 0);
                        $formulaKey = $keyMain . '|' . $keyAccountId . '|' . $formula;
                        $maxKey = $keyMain . '|' . $keyAccountId;

                        if (isset($variantByFormulaMain[$formulaKey])) {
                            $formulaVariant = (int)$variantByFormulaMain[$formulaKey];
                        } else {
                            $next = (isset($variantMaxMain[$maxKey]) ? (int)$variantMaxMain[$maxKey] : 0) + 1;
                            $formulaVariant = $next;
                            $variantByFormulaMain[$formulaKey] = $formulaVariant;
                            $variantMaxMain[$maxKey] = $formulaVariant;
                        }
                    } else {
                        $keySub = trim((string)($row['idProductSub'] ?? ''));
                        $keyMain = trim((string)($row['parentIdProduct'] ?? $row['idProductMain'] ?? ''));
                        $keyAccountId = (int)($row['accountId'] ?? 0);
                        $formulaKey = $keySub . '|' . $keyMain . '|' . $keyAccountId . '|' . $formula;
                        $maxKey = $keySub . '|' . $keyMain . '|' . $keyAccountId;

                        if (isset($variantByFormulaSub[$formulaKey])) {
                            $formulaVariant = (int)$variantByFormulaSub[$formulaKey];
                        } else {
                            $next = (isset($variantMaxSub[$maxKey]) ? (int)$variantMaxSub[$maxKey] : 0) + 1;
                            $formulaVariant = $next;
                            $variantByFormulaSub[$formulaKey] = $formulaVariant;
                            $variantMaxSub[$maxKey] = $formulaVariant;
                        }
                    }
                }

                // 只有在 batch append 模式下才检查并更新已有记录；
                // 首次提交时，一律走 INSERT，让 Summary 里的所有行都各自落一条明细。
                if ($isBatchAppend) {
                    if ($productType === 'main') {
                        $idProductMain = $row['idProductMain'] ?? null;
                        $checkStmtMain->execute([
                            ':company_id' => $companyId,
                            ':capture_id' => $captureId,
                            ':id_product_main' => $idProductMain,
                            ':account_id' => $row['accountId'],
                            ':currency_id' => $rowCurrencyId,
                            ':formula_variant' => $formulaVariant,
                        ]);
                        $existingRecord = $checkStmtMain->fetch();
                    } else {
                        // sub type - use parentIdProduct as id_product_main for checking
                        $idProductSub = $row['idProductSub'] ?? null;
                        $parentIdProduct = $row['parentIdProduct'] ?? $row['idProductMain'] ?? null;
                        
                        // Debug log for sub type duplicate check
                        error_log("Checking duplicate sub: capture_id=$captureId, id_product_sub=" . ($idProductSub ?? 'NULL') . ", parent_id_product=" . ($parentIdProduct ?? 'NULL') . ", account_id=" . $row['accountId'] . ", formula_variant=$formulaVariant");
                        
                        $checkStmtSub->execute([
                            ':company_id' => $companyId,
                            ':capture_id' => $captureId,
                            ':id_product_sub' => $idProductSub,
                            ':id_product_main' => $parentIdProduct,
                            ':account_id' => $row['accountId'],
                            ':currency_id' => $rowCurrencyId,
                            ':formula_variant' => $formulaVariant,
                        ]);
                        $existingRecord = $checkStmtSub->fetch();
                    }
                }
                
                if ($isBatchAppend && $existingRecord) {
                    // Skip duplicate record - update existing record instead of inserting
                    $existingId = $existingRecord['id'];
                    error_log("Found duplicate data_capture_details record (ID: $existingId): capture_id=$captureId, product_type=$productType, id_product_main=" . ($row['idProductMain'] ?? 'NULL') . ", id_product_sub=" . ($row['idProductSub'] ?? 'NULL') . ", account_id=" . $row['accountId'] . " - Updating existing record instead of inserting");
                    
                    // Get rate value: use rateValue if it exists (from Rate Value column or global rateInput)
                    // Priority: Rate Value column > Global rateInput (if checkbox checked)
                    $rateValue = null;
                    if (isset($row['rateValue']) && $row['rateValue'] !== '' && $row['rateValue'] !== null) {
                        // Rate Value column has value, use it
                        $rateValueStr = (string)$row['rateValue'];
                        // Handle formats like "*3", "/2", or plain numbers
                        if (strpos($rateValueStr, '*') === 0) {
                            $rateValue = (float)substr($rateValueStr, 1);
                        } else if (strpos($rateValueStr, '/') === 0) {
                            $rateValue = (float)substr($rateValueStr, 1);
                        } else {
                            $rateValue = (float)$rateValueStr;
                        }
                    } else if (isset($row['rateChecked']) && $row['rateChecked']) {
                        // Fallback: if checkbox checked but no Rate Value, use global rateInput (backward compatibility)
                        $rateValue = isset($row['rateValue']) && $row['rateValue'] !== '' && $row['rateValue'] !== null ? (float)$row['rateValue'] : null;
                    }
                    
                    // Get display_order for update
                    $rowDisplayOrderForUpdate = isset($row['displayOrder']) && $row['displayOrder'] !== null ? (int)$row['displayOrder'] : null;
                    
                    // Update existing record instead of skipping
                    $updateStmt = $pdo->prepare("
                        UPDATE data_capture_details SET
                            description_main = :description_main,
                            description_sub = :description_sub,
                            id_product = :id_product,
                            columns_value = :columns_value,
                            source_value = :source_value,
                            source_percent = :source_percent,
                            enable_source_percent = :enable_source_percent,
                            formula = :formula,
                            processed_amount = :processed_amount,
                            rate = :rate,
                            display_order = :display_order
                        WHERE id = :id
                    ");
                    
                    $updateStmt->execute([
                        ':id' => $existingId,
                        ':description_main' => $row['descriptionMain'] ?? null,
                        ':description_sub' => $row['descriptionSub'] ?? null,
                        ':id_product' => $normalizedIdProduct,
                        ':columns_value' => $row['columns'] ?? '',
                        ':source_value' => $row['source'] ?? '',
                        // source_percent: default to '1' (multiplier, 1 = multiply by 1), auto-enable if has value
                        ':source_percent' => isset($row['sourcePercent']) && $row['sourcePercent'] !== '' ? (string)$row['sourcePercent'] : '1',
                        ':enable_source_percent' => (isset($row['sourcePercent']) && $row['sourcePercent'] !== '' && $row['sourcePercent'] !== '0') ? 1 : 0,
                        ':formula' => $row['formula'] ?? '',
                        ':processed_amount' => $row['processedAmount'] ?? 0,
                        ':rate' => $rateValue,
                        ':display_order' => $rowDisplayOrderForUpdate
                    ]);
                    
                    continue; // Skip insert, already updated
                }
                
                // Get rate value: use rateValue if it exists (from Rate Value column or global rateInput)
                // Priority: Rate Value column > Global rateInput (if checkbox checked)
                $rateValue = null;
                if (isset($row['rateValue']) && $row['rateValue'] !== '' && $row['rateValue'] !== null) {
                    // Rate Value column has value, use it
                    $rateValueStr = (string)$row['rateValue'];
                    // Handle formats like "*3", "/2", or plain numbers
                    if (strpos($rateValueStr, '*') === 0) {
                        $rateValue = (float)substr($rateValueStr, 1);
                    } else if (strpos($rateValueStr, '/') === 0) {
                        $rateValue = (float)substr($rateValueStr, 1);
                    } else {
                        $rateValue = (float)$rateValueStr;
                    }
                } else if (isset($row['rateChecked']) && $row['rateChecked']) {
                    // Fallback: if checkbox checked but no Rate Value, use global rateInput (backward compatibility)
                    $rateValue = isset($row['rateValue']) && $row['rateValue'] !== '' && $row['rateValue'] !== null ? (float)$row['rateValue'] : null;
                }
                
                $detailParams = [
                    ':company_id' => $detailCompanyId,
                    ':capture_id' => $captureId,
                    ':id_product_main' => $normalizedIdProductMain !== '' ? $normalizedIdProductMain : null,
                    ':description_main' => $row['descriptionMain'] ?? null,
                    ':id_product_sub' => $normalizedIdProductSub !== '' ? $normalizedIdProductSub : null,
                    ':description_sub' => $row['descriptionSub'] ?? null,
                    ':product_type' => $productType,
                    ':formula_variant' => $formulaVariant,
                    ':id_product' => $normalizedIdProduct,
                    ':account_id' => $row['accountId'],
                    ':currency_id' => $rowCurrencyId,
                    ':columns_value' => $row['columns'] ?? '',
                    ':source_value' => $row['source'] ?? '',
                    ':source_percent' => isset($row['sourcePercent']) && $row['sourcePercent'] !== '' ? (string)$row['sourcePercent'] : '1',
                    ':enable_source_percent' => (isset($row['sourcePercent']) && $row['sourcePercent'] !== '' && $row['sourcePercent'] !== '0') ? 1 : 0,
                    ':formula' => $row['formula'] ?? '',
                    ':processed_amount' => $row['processedAmount'] ?? 0,
                    ':rate' => $rateValue,
                    ':display_order' => $rowDisplayOrder,
                ];
                if ($useDetailScopeColumns) {
                    $detailParams[':scope_type'] = $scopeInsert['scope_type'];
                    $detailParams[':scope_id'] = $scopeInsert['scope_id'];
                }
                $stmt->execute($detailParams);
            }

            // ⚠ 这里开始不再在 Submit 时写入 / 更新 data_capture_templates，
            // Maintenance - Formula 的模板完全由 Edit Formula 弹窗里的 Save（action=save_template）维护。
            
            // Commit transaction
            $pdo->commit();
            
            // Log success
            error_log("Data capture submitted successfully - Capture ID: $captureId, Rows: " . count($data['summaryRows']));
            
            if ($queueJobId) {
                $qDoneStmt = $pdo->prepare("
                    UPDATE data_capture_submit_queue
                    SET status = 'success', capture_id = :capture_id, finished_at = NOW(), error_message = NULL
                    WHERE id = :id
                ");
                $qDoneStmt->execute([
                    ':capture_id' => $captureId,
                    ':id' => $queueJobId
                ]);
            } else {
                echo json_encode([
                    'success' => true,
                    'captureId' => $captureId,
                    'message' => 'Data submitted successfully',
                    'rowsInserted' => count($data['summaryRows'])
                ]);
            }
            
        } catch (Exception $e) {
            // Rollback transaction on error
            $pdo->rollBack();
            throw $e;
        }
        
    } catch (Exception $e) {
        error_log("Submit Error: " . $e->getMessage());
        if ($queueJobId) {
            try {
                ensureSummarySubmitQueueTable($pdo);
                $qFailStmt = $pdo->prepare("
                    UPDATE data_capture_submit_queue
                    SET status = 'failed', finished_at = NOW(), error_message = :error_message
                    WHERE id = :id
                ");
                $qFailStmt->execute([
                    ':error_message' => mb_substr($e->getMessage(), 0, 1000),
                    ':id' => $queueJobId
                ]);
            } catch (Exception $qe) {
                error_log("Submit queue update failed: " . $qe->getMessage());
            }
            // 已经提前响应给前端，这里不再二次输出
        } else {
            echo json_encode([
                'success' => false,
                'message' => $e->getMessage(),
                'data' => null
            ]);
        }
    }
    
} else {
    // Default action: Load currencies and accounts (group ledger vs subsidiary company)
    try {
        $groupCodeForCatalog = dcNormalizeGroupId(
            $scopeParams['view_group'] ?? $scopeParams['group_id'] ?? ($groupIdForAccess ?? '')
        );
        $isGroupCatalog = !empty($capture_scope_group);
        $currencies = dcSummaryLoadFormCurrencies($pdo, $isGroupCatalog, (int) $company_id, $groupCodeForCatalog);
        $accounts = dcSummaryLoadFormAccounts($pdo, $isGroupCatalog, (int) $company_id, $groupCodeForCatalog);

        error_log(
            'Summary form catalog - scope='
            . ($isGroupCatalog ? 'group' : 'company')
            . ' group=' . $groupCodeForCatalog
            . ' accounts=' . count($accounts)
            . ' currencies=' . count($currencies)
            . ' company_id=' . (int) $company_id
        );

        echo json_encode([
            'success' => true,
            'currencies' => $currencies,
            'accounts' => $accounts,
            'scope' => $isGroupCatalog ? 'group' : 'company',
            'debug' => [
                'accounts_count' => count($accounts),
                'currencies_count' => count($currencies),
                'company_id' => $company_id,
                'capture_scope_group' => $isGroupCatalog,
                'group_code' => $groupCodeForCatalog,
            ]
        ]);
        
    } catch (Exception $e) {
        error_log("API Error: " . $e->getMessage());
        echo json_encode([
            'success' => false,
            'message' => $e->getMessage(),
            'data' => null
        ]);
    }
}
?>