# Investigating the $23M Settlement Gap — A Playbook for OceanTrade Finance

**The problem today.** OceanTrade knows $23M has left buyers' accounts but isn't confirmed in
sellers' accounts, and the team is tracking it in spreadsheets that can't tell "normal" apart from
"stuck." The root cause isn't fraud or loss — it's that settlement timing varies enormously by rail
and geography, and there has been no system that knows what "on time" *means* for each payment. This
API supplies exactly that missing definition and turns the $23M from one scary number into a sorted,
prioritized worklist.

**Step 1 — Size the gap and split it into "fine" vs. "act now."** Call `GET /api/v1/analytics/settlement`.
The `byStatus` block immediately partitions the in-transit money into `PENDING_SETTLEMENT` (captured
and still inside its expected window — leave it alone), `DELAYED` (past the window but under the
2× at-risk threshold — watch it), and `AT_RISK` (beyond 2× the expected window — investigate today).
`totalInTransitUsd` reconciles to the headline figure, while `needsAttentionUsd` (DELAYED + AT_RISK)
tells the CFO how much of the $23M is genuinely abnormal versus simply mid-flight. In most books the
majority of "stuck" money turns out to be `PENDING` and perfectly healthy — which is itself the most
calming thing finance can learn on day one.

**Step 2 — Localize the problem instead of boiling the ocean.** The same response returns
`worstCombinations`: every *(payment method × country)* pair ranked by the dollars currently delayed
or at risk, with per-combo counts and an `efficiencyRate`. This answers the VP's real question —
"Is it Brazil bank transfers, or everything?" — in one read. If, say, `bank_transfer / BR` and
`oxxo / MX` account for most of the at-risk value, the team stops chasing 300 individual payments and
instead opens two processor tickets. `avgSettlementHoursByMethod` and `avgSettlementHoursByCountry`
add the "how slow, exactly" context that makes those processor conversations concrete.

**Step 3 — Work the actual list and prove timing as you go.** Call
`GET /api/v1/settlement/payments?status=AT_RISK` (filterable by `country`, `method`, `processor`) to
pull the exact transactions behind the numbers. Each row carries `expectedSettlementDeadline`,
`timeInTransitDays`, and a plain-English `reason`, so when a retailer asks "where is my payout," the
team can say precisely how overdue it is and against what benchmark — replacing the spreadsheet with
an auditable answer. Reconciliation files from acquirers can be re-ingested via
`POST /api/v1/settlement/payments` (idempotent on `paymentId`); as `settledAt` arrives, those rows
flip to `SETTLED` and drop off the worklist automatically.

**Step 4 — Get ahead of the next gap.** `GET /api/v1/analytics/anomalies` flags *(method, country)*
segments whose recent settlement times have degraded against their own baseline, with a severity and
confidence score — e.g., "Brazil boleto usually settles in 3 days but 40% of this week is taking
8+ days." Wired to a daily alert, this converts settlement monitoring from a quarterly fire drill
into an early-warning system: OceanTrade learns a processor is slipping while the exposure is
thousands of dollars, not millions. The settlement windows that drive every classification live in
`application.yml` and are tunable without a deploy, so as OceanTrade negotiates faster rails or adds
corridors, the definition of "on time" — and therefore the entire dashboard — stays accurate.
