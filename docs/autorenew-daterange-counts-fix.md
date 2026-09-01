# Auto Renew — Date-range counts/list mismatch fix

> **最后更新**：2026-09-01

## Symptom
On the Auto Renew page, picking a date range in the picker did not change the Pending /
Approved / Rejected / Show All badge numbers next to the filter chips — they always showed
the same totals regardless of the selected range. Separately, once counts were made
date-range aware, `Show All` started returning an **empty list** on days with pending
requests but no approved/rejected activity, even though its badge correctly showed a
non-zero count.

## Root cause

### 1. Counts ignored the date range entirely
`AutoRenewDao.countRequestsByStatus(status, tenantType, windowDays)` had no `dateFrom`/
`dateTo` parameters at all. Pending count used the existing expiration-window logic
(`DATEDIFF(t.expiration_date, CURDATE()) <= windowDays`, correct/unaffected), but
approved/rejected counted **every matching row that ever existed**, with no `processed_at`
filter — while `selectAutoRenewList` (the row list backing the same tabs) already filtered
approved/rejected rows by `DATE(r.processed_at)` within the selected range. Badge vs. row
count could diverge any time a date range narrower than "all time" was picked.

### 2. Show All silently dropped every pending row
`selectAutoRenewList`'s `<where>` block had:
```xml
<if test="status != 'pending'">
    <if test="dateFrom != null">AND DATE(r.processed_at) &gt;= #{dateFrom}</if>
    <if test="dateTo != null">AND DATE(r.processed_at) &lt;= #{dateTo}</if>
</if>
```
For Show All, `status` is `null` (the service maps `"all"` → `null`). In MyBatis OGNL,
`null != 'pending'` evaluates to `true`, so this branch fired **for every row regardless of
its actual status**, including pending rows. Pending rows have `processed_at = NULL`, and
`NULL >= '2026-09-01'` is never true in SQL — so every pending row was silently excluded
from the Show All list whenever any date range was set (which is always, since the frontend
sends a default range on load).

## Fix

**`backend/src/main/resources/mybatis/AutoRenewMapper.xml`**
- `countRequestsByStatus`: added `dateFrom`/`dateTo` params. Pending keeps the window-based
  filter untouched; approved/rejected now filter on `DATE(r.processed_at)` within the
  selected range, matching `selectAutoRenewList`'s existing per-row logic.
- `selectAutoRenewList`: restructured the `<where>` so a specific status keeps its existing
  branch (pending → window, approved/rejected → date range), and the Show All (`status ==
  null`) case now uses an explicit `OR` group instead of one blanket condition:
  ```sql
  (r.status = 'pending' AND DATEDIFF(t.expiration_date, CURDATE()) <= windowDays)
  OR (r.status IN ('approved','rejected') AND DATE(r.processed_at) BETWEEN dateFrom AND dateTo)
  ```

**`backend/src/main/java/com/eazycount/dao/AutoRenewDao.java`**
- `countRequestsByStatus` signature gains `LocalDate dateFrom, LocalDate dateTo`.

**`backend/src/main/java/com/eazycount/service/AutoRenewService.java` /
`service/impl/AutoRenewServiceImpl.java`**
- `getAutoRenewCounts(tenantType, windowDays)` → `getAutoRenewCounts(tenantType, windowDays,
  dateFrom, dateTo)`. Pending's own `countRequestsByStatus` call always passes
  `(null, null)` (window-based, unaffected by range); approved/rejected pass through the
  real range. `total = pending + approved + rejected` is unchanged and now stays consistent
  automatically.
- `getAutoRenewList(...)` forwards its already-parsed `dateFrom`/`dateTo` into the
  `getAutoRenewCounts` call it makes to populate the response's `counts` field.

**`backend/src/main/java/com/eazycount/controller/AutoRenewController.java`**
- The `pending_count` short-circuit branch (used by the sidebar pending badge, unrelated to
  the date picker) updated to the new signature, passing `(null, null)`.

## Why this shouldn't recur
Every place that counts or lists auto-renew requests now applies exactly one rule per
status: pending is always window-based (never date-range-filtered), approved/rejected are
always date-range-based (never window-filtered), and Show All is an explicit `OR` of both
rules rather than one condition applied indiscriminately to every row. There's no longer a
code path where a status-agnostic filter (like the old blanket `processed_at` check) can
accidentally exclude rows whose status it was never meant to touch.

## Verification
Confirmed directly against `count_real` data for a 2026-09-01 → 2026-09-01 range: 2 pending
requests (tenants `BK1`, `M2`), 0 approved/rejected in that window. Badge counts
(`Pending 2 / Approved 0 / Rejected 0 / Show All 2`) now match the row lists exactly,
including Show All returning both pending rows instead of an empty table.

## Files changed
- `backend/src/main/resources/mybatis/AutoRenewMapper.xml`
- `backend/src/main/java/com/eazycount/dao/AutoRenewDao.java`
- `backend/src/main/java/com/eazycount/service/AutoRenewService.java`
- `backend/src/main/java/com/eazycount/service/impl/AutoRenewServiceImpl.java`
- `backend/src/main/java/com/eazycount/controller/AutoRenewController.java`

## Frontend
`Count-frontend/src/pages/autorenew/AutoRenewPage.jsx` — filter chips reordered to
`Show All → Pending → Approved → Rejected` (default selected filter stays Pending); no
change needed to how it calls `/api/auto-renew/list` (`dateFrom`/`dateTo` were already
being sent). See `Count-frontend/docs/autorenew-daterange-counts-fix.md`.
