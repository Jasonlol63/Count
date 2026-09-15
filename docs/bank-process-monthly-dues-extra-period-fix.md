# Bank Process — Monthly frequency generates one extra due past dayEnd

> **最后更新**：2026-09-15

## Symptom
For a Bank Process with `Frequency = Monthly` whose contract runs an exact number of
months starting mid-month (e.g. `dayStart = 16/06/2026`, `Contract = 3 MONTHS`,
`dayEnd = 15/09/2026`), the accounting-due generator produced **one extra due dated
exactly on dayEnd** (here: `15/09/2026`, billing window `15/09 – 15/10`), on top of the
three legitimate monthly dues (16/06, 15/07, 15/08). This happened even when the process
was already in `BLOCK` status (contract expired / blocked), which is not supposed to keep
generating new periods.

Reported case: Bank Process for supplier `BS005` / OCBC / card owner `PIXEL FORGE
PTE.LTD.` (and the same pattern on `CATERING COLLECTIVE PTE.LTD.`), both 3-month
contracts starting on the 16th.

## Root cause
`resolveMonthlyDues()` in `BankAccountingDueServiceImpl.java` anchors each period on
`dayStart.getDayOfMonth() - 1` (see `monthlyAnchor()`), not on `dayStart`'s own
day-of-month. For a contract where `dayEnd`'s day-of-month equals that anchor day (i.e.
`dayEnd = dayStart + N months - 1 day`, the standard "N-month contract starting
mid-month" shape), the loop's stop check ran **after** adding the current period and
compared the *anchor's calendar month* to `endMonth = YearMonth.from(dayEnd)` — not
whether the period just added already covered through `dayEnd`.

Trace for the reported case (anchor day = 16 - 1 = 15):

| # | posted (anchor) | billing window | month vs endMonth check |
|---|---|---|---|
| 1 | 16/06 | 16/06 – 16/07 | 06 before 09 → continue |
| 2 | 15/07 | 15/07 – 15/08 | 07 before 09 → continue |
| 3 | 15/08 | 15/08 – 15/09 | 08 before 09 → continue (but this period already reaches dayEnd!) |
| 4 | 15/09 | 15/09 – 15/10 | 09 == endMonth → break **after** generating this extra due |

Period 3 already billed through `dayEnd` (15/09), but the stop condition only looked at
the anchor's calendar month, not the period's own coverage — so it let one more
iteration run and generated a 4th, entirely-past-contract-end due. This is independent of
`BLOCK`/`ACTIVE` status (the `ACTIVE`-only "keep rolling past dayEnd" branch was already
correctly gated off for `BLOCK`); it reproduces for **any** Monthly-frequency contract
shaped like `dayEnd = dayStart + N months - 1 day`, which is the common case for
"N MONTHS" contracts starting mid-month (several other rows in the same Bank Process
list — e.g. the `2 MONTHS`/`3 MONTHS` contracts also starting on the 16th — matched this
shape and were equally at risk, just not yet noticed because their last period hadn't
been reached).

## Fix
**`backend/src/main/java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java`**,
`resolveMonthlyDues()`:
- Removed the `endMonth` calendar-month comparison and the mid-loop clamp that forced the
  overshooting anchor date down to `dayEnd` (`periodPosted = ... ? dayEnd : posted`) —
  that clamp is what mislabeled the extra due's `postedDate` as `dayEnd` itself.
- The loop now stops right after adding a period whose own billing window
  (`posted.plusMonths(1)`) already reaches or passes `dayEnd`, instead of waiting for the
  *next* iteration's anchor to land in the same calendar month as `dayEnd`:
  ```java
  if (!extendPastDayEnd && !posted.plusMonths(1).isBefore(dayEnd)) {
      break;
  }
  ```
- `ACTIVE`-status behavior (keep rolling anchors indefinitely past `dayEnd` until status
  changes) is unaffected — `extendPastDayEnd` still short-circuits this check.

## Why this shouldn't recur
The stop condition now asks "has the period I just billed already covered through
dayEnd?" instead of "did the *next* anchor's calendar month arrive?" — the two questions
happened to agree for contracts where `dayEnd`'s day-of-month differs from the anchor
day, which is why this went unnoticed until a contract landed exactly on that boundary.
Basing the check on the actual billing coverage instead of calendar-month equality makes
it correct for both cases.

## Verification
Recompiled (`mvnw -q -o compile`) with no errors. Re-traced the reported case
(`dayStart=16/06/2026`, `dayEnd=15/09/2026`, `Monthly`, `BLOCK`) by hand against the new
loop: produces exactly 3 dues (16/06, 15/07, 15/08) and stops — no more 15/09 entry.

## Files changed
- `backend/src/main/java/com/eazycount/service/impl/BankAccountingDueServiceImpl.java`
