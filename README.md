# Settlement Monitoring API

Tracks cross-border payments from **capture → settlement**, classifies each payment's settlement
status, surfaces aggregate analytics, and detects settlement anomalies. Built for OceanTrade's
finance team to investigate the **$23M "stuck in limbo" gap** described in the Yuno challenge.

> **TL;DR** — Submit payments, get back `PENDING_SETTLEMENT | SETTLED | DELAYED | AT_RISK | FAILED`
> per payment, computed against **configurable settlement windows** per *(payment method × country)*
> with cross-border surcharges and a 2× AT_RISK threshold. Then call the analytics endpoint to see
> how much money is fine, delayed, or at risk — and **which method+country combos are the problem**.

- Java 21 · Spring Boot 3.3 · H2 (dev) / PostgreSQL (prod) · OpenAPI/Swagger · Actuator/Prometheus
- Full engineering deep-dive (architecture, LLD, UML, DB, concurrency, security, trade-offs):
  see **[DESIGN.md](DESIGN.md)**
- How finance should use this to chase the $23M: see **[docs/the-23m-gap.md](docs/the-23m-gap.md)**

---

## 1. Run it

Requires **JDK 21**. From the repo root:

```bash
./gradlew bootRun
# API:      http://localhost:8080
# Swagger:  http://localhost:8080/swagger-ui.html
# Health:   http://localhost:8080/actuator/health
# H2 console: http://localhost:8080/h2-console  (jdbc:h2:mem:testdb, user: sa)
```

On startup the app **seeds 360 demo payments** (`src/main/resources/test-data/payments.json`) so the
analytics endpoints have data immediately.

Run the tests:

```bash
./gradlew test
```

Run on PostgreSQL via Docker:

```bash
docker compose up --build      # API on :8080, Postgres on :5432
```

---

## 2. API

Base path: `/api/v1`. Full request/response contracts are in Swagger and
[DESIGN.md §7](DESIGN.md#7-api-specifications).

| Method & path | Purpose |
|---|---|
| `POST /settlement/classify` | **Stateless** — classify a batch of payments, return enriched status. No DB write. |
| `POST /settlement/payments` | Ingest & persist payments (idempotent on `paymentId`). |
| `GET  /settlement/payments` | Query stored payments; filters: `status,method,country,processor`. |
| `GET  /settlement/payments/{id}` | One enriched payment. |
| `GET  /analytics/settlement` | Aggregate analytics (value by status, avg times, efficiency, worst combos). |
| `GET  /analytics/anomalies` | Detected settlement anomalies with severity + confidence. |
| `GET  /settlement/health` | Liveness. |

### 2.1 Classify payments (Requirement 1)

```bash
curl -s -X POST http://localhost:8080/api/v1/settlement/classify \
  -H 'Content-Type: application/json' -d '{
    "payments": [
      {"paymentId":"p-card-mx","paymentMethod":"credit_card","country":"MX","processor":"dlocal",
       "currency":"MXN","amount":26543.45,"capturedAt":"2026-05-24T23:27:58Z",
       "settledAt":null,"crossBorder":true},
      {"paymentId":"p-pix-br","paymentMethod":"pix","country":"BR","processor":"ebanx",
       "currency":"BRL","amount":5000,"capturedAt":"2026-06-07T09:00:00Z","settledAt":null}
    ]
  }'
```

Response (abridged):

```json
[
  {
    "paymentId": "p-card-mx",
    "paymentMethod": "credit_card",
    "country": "MX",
    "amountUsd": 1539.52,
    "settlementStatus": "DELAYED",
    "expectedWindow": "3 BUSINESS_DAYS (+3 cross-border)",
    "expectedSettlementDeadline": "2026-06-01T23:27:58Z",
    "timeInTransitDays": 13.6,
    "needsAttention": true,
    "reason": "Past the expected window but under the at-risk threshold."
  },
  {
    "paymentId": "p-pix-br",
    "settlementStatus": "PENDING_SETTLEMENT",
    "expectedWindow": "1 HOURS",
    "needsAttention": false
  }
]
```

### 2.2 Analytics (Requirement 2)

```bash
curl -s http://localhost:8080/api/v1/analytics/settlement | jq
```

Representative output over the seeded dataset (numbers drift as the clock advances, since status is
computed live):

```text
STATUS              count        USD
SETTLED               216   $4,281,803
PENDING_SETTLEMENT     84   $1,603,061
DELAYED                42   $  597,120
AT_RISK                14   $  378,240
FAILED                  4   $   69,776
------------------------------------------
IN-TRANSIT (P+D+R)          $2,578,421
NEEDS ATTENTION (D+R)       $  975,360

Worst (method, country) by delayed+at-risk USD:
  oxxo        MX   $398,866   (delayed=6, atRisk=8)
  pix         BR   $242,254   (delayed=16, atRisk=0)
  credit_card BR   $ 66,005   (delayed=1,  atRisk=1)
```

The response also includes `settlementEfficiencyRate` (on-time settlements ÷ total settlements),
`avgSettlementHoursByMethod`, `avgSettlementHoursByCountry`, and a per-combo `efficiencyRate`.

### 2.3 Anomalies (Stretch)

```bash
curl -s http://localhost:8080/api/v1/analytics/anomalies | jq
```

```json
[
  {
    "paymentMethod": "bank_transfer", "country": "BR",
    "type": "SLOWDOWN", "severity": "HIGH", "confidence": 0.8,
    "baselineAvgHours": 72.0, "recentAvgHours": 180.0, "degradationRatio": 2.5,
    "recentDelayedShare": 0.4, "recentSampleSize": 9, "baselineSampleSize": 30,
    "description": "SLOWDOWN on bank_transfer/BR: recent avg settlement 180.0h vs baseline 72.0h (2.50x)..."
  }
]
```

---

## 3. Configuration — settlement windows are data, not code

All thresholds live in [`application.yml`](src/main/resources/application.yml) under `settlement.*`
and bind to `SettlementProperties`. **Change windows without recompiling.**

```yaml
settlement:
  cross-border-surcharge-days: 3      # extra business days for cross-border
  risk-multiplier: 2.0                # AT_RISK once age > 2× window
  default-window: { value: 5, unit: BUSINESS_DAYS }
  windows:
    - { method: pix,           country: BR, value: 1, unit: HOURS }
    - { method: credit_card,   country: MX, value: 3, unit: BUSINESS_DAYS }
    - { method: bank_transfer, country: BR, value: 7, unit: BUSINESS_DAYS }
    - { method: bank_transfer, country: ID, value: 5, unit: BUSINESS_DAYS }
    - { method: credit_card,   country: "*", value: 5, unit: BUSINESS_DAYS }   # wildcard fallback
  fx-rates-to-usd: { USD: 1.0, MXN: 0.058, BRL: 0.18, COP: 0.00025, IDR: 0.000062, THB: 0.028, VND: 0.000039 }
```

Resolution precedence: **exact `(method, country)` → `(method, "*")` → `default-window`**.
`unit` is `HOURS`, `CALENDAR_DAYS`, or `BUSINESS_DAYS` (weekends skipped).

---

## 4. Classification logic (the core)

For each payment, evaluated top-down:

```
failed flag set                  -> FAILED
settledAt present                -> SETTLED        (+ withinExpectedWindow flag for efficiency)
age <= expected window           -> PENDING_SETTLEMENT
age <= riskMultiplier × window   -> DELAYED
otherwise                        -> AT_RISK
```

`age` runs from `capturedAt` to `settledAt` (if set) or **now**. The expected window =
`base window (method × country) + cross-border surcharge`, computed in business days where
applicable. See `SettlementWindowResolver` and `SettlementClassificationService`.

---

## 5. Test data

`test-data/payments.json` — 360 transactions over the last 30 days, regenerated by
[`tools/generate_test_data.py`](tools/generate_test_data.py). The generator mirrors the service's
window math, so it doubles as an executable reference implementation. Distribution matches the brief:

```
python3 tools/generate_test_data.py 360
# SETTLED ~60% · PENDING ~25% · DELAYED ~10% · AT_RISK ~4% · FAILED ~1%
# currencies: USD, MXN, BRL, COP, IDR, THB, VND
# methods: credit_card, bank_transfer, pix, oxxo, e_wallet
# countries: MX, BR, CO, CL, VN, ID, TH
```

> The brief's $23M is the real-world business figure. The demo dataset honours the explicit
> per-transaction range ($500–$150k) and 360-row count, so its in-transit total is proportionally
> smaller (~$2.6M). Scale `random_usd()` or row count in the generator to model the full $23M.

---

## 6. Design decisions (short version)

- **Status is derived, never stored.** A payment that is PENDING today is AT_RISK tomorrow, so
  persisting status would immediately go stale. We store raw facts and classify on read.
- **Windows are externalized config**, bound via `@ConfigurationProperties` — the configurability
  requirement is met by *data*, with a clear specificity-based resolution order.
- **Stateless `/classify` + stateful ingest/query** separate the pure decision engine from
  persistence, keeping the engine trivially testable.
- **`Clock` is injected** so time-sensitive logic is deterministic under test.
- **Analytics is a pure function** over enriched payments — reusable and unit-tested in isolation.

The full rationale, alternatives, UML, ER diagram, concurrency/locking, security, observability,
resilience, testing strategy, and staff-level review notes are in **[DESIGN.md](DESIGN.md)**.

---

## 7. Project layout

```
src/main/java/com/yuno/settlement
├── config/        SettlementProperties, SecurityConfig, AppConfig (Clock, OpenAPI)
├── model/         Payment entity + enums (SettlementStatus, WindowUnit)
├── dto/           PaymentRequest, EnrichedPayment, AnalyticsResponse, Anomaly, ...
├── repository/    PaymentRepository (Spring Data JPA)
├── service/       SettlementWindowResolver, SettlementClassificationService,
│                  AnalyticsService, AnomalyDetectionService, PaymentService, CurrencyConverter
├── controller/    SettlementController, AnalyticsController
├── exception/     GlobalExceptionHandler, ApiError, PaymentNotFoundException
└── bootstrap/     SeedDataLoader
```
