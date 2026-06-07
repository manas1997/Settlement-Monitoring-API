#!/usr/bin/env python3
"""
Generate a realistic settlement test dataset for the Settlement Monitoring API.

The generator mirrors the service's window math (business days, cross-border
surcharge, 2x AT_RISK threshold) so that each synthesized payment actually
classifies into its intended status when fed through the Java engine. It thus
doubles as an executable reference implementation of the classification rules.

Output: a JSON array of payment requests written to:
  - src/main/resources/test-data/payments.json   (seeded on app startup)
  - test-data/payments.json                       (standalone deliverable)

Usage: python3 tools/generate_test_data.py [N]
"""
import json
import os
import random
import sys
from datetime import datetime, timedelta, timezone

random.seed(42)  # reproducible dataset

# --------------------------------------------------------------------------
# Configuration — kept in sync with src/main/resources/application.yml
# --------------------------------------------------------------------------
HOURS, CALENDAR_DAYS, BUSINESS_DAYS = "HOURS", "CALENDAR_DAYS", "BUSINESS_DAYS"

WINDOW_RULES = [
    ("pix", "BR", 1, HOURS),
    ("e_wallet", "*", 1, CALENDAR_DAYS),
    ("credit_card", "MX", 3, BUSINESS_DAYS),
    ("credit_card", "BR", 4, BUSINESS_DAYS),
    ("credit_card", "CL", 3, BUSINESS_DAYS),
    ("credit_card", "CO", 4, BUSINESS_DAYS),
    ("credit_card", "*", 5, BUSINESS_DAYS),
    ("bank_transfer", "BR", 7, BUSINESS_DAYS),
    ("bank_transfer", "MX", 2, BUSINESS_DAYS),
    ("bank_transfer", "CO", 4, BUSINESS_DAYS),
    ("bank_transfer", "CL", 3, BUSINESS_DAYS),
    ("bank_transfer", "ID", 5, BUSINESS_DAYS),
    ("bank_transfer", "TH", 3, BUSINESS_DAYS),
    ("bank_transfer", "VN", 4, BUSINESS_DAYS),
    ("bank_transfer", "*", 6, BUSINESS_DAYS),
    ("oxxo", "MX", 3, BUSINESS_DAYS),
]
DEFAULT_RULE = (None, "*", 5, BUSINESS_DAYS)
CROSS_BORDER_SURCHARGE_DAYS = 3
RISK_MULTIPLIER = 2.0
SKIP_WEEKENDS = True

FX_TO_USD = {
    "USD": 1.0, "MXN": 0.058, "BRL": 0.18, "COP": 0.00025,
    "IDR": 0.000062, "THB": 0.028, "VND": 0.000039,
}
COUNTRY_CURRENCY = {
    "MX": "MXN", "BR": "BRL", "CO": "COP", "CL": "USD",
    "VN": "VND", "ID": "IDR", "TH": "THB",
}
PROCESSORS = ["dlocal", "ebanx", "adyen", "stripe", "mercadopago", "yuno_direct"]
METHODS = ["credit_card", "bank_transfer", "pix", "oxxo", "e_wallet"]
COUNTRIES = ["MX", "BR", "CO", "CL", "VN", "ID", "TH"]

NOW = datetime.now(timezone.utc)

# Target status distribution (per the challenge brief).
TARGET_MIX = [
    ("SETTLED", 0.60),
    ("PENDING_SETTLEMENT", 0.25),
    ("DELAYED", 0.10),
    ("AT_RISK", 0.04),
    ("FAILED", 0.01),
]


# --------------------------------------------------------------------------
# Window math (mirrors SettlementWindowResolver.java)
# --------------------------------------------------------------------------
def resolve_rule(method, country):
    best = DEFAULT_RULE
    best_spec = -1
    for m, c, v, u in WINDOW_RULES:
        if m != method:
            continue
        if c == "*" or c == country:
            spec = 0 if c == "*" else 1
            if spec > best_spec:
                best, best_spec = (m, c, v, u), spec
    return best


def add_business_days(start, days):
    whole = int(days // 1)
    frac = days - whole
    cur = start
    added = 0
    while added < whole:
        cur = cur + timedelta(days=1)
        if SKIP_WEEKENDS and cur.weekday() >= 5:  # Sat=5, Sun=6
            continue
        added += 1
    if frac > 0:
        cur = cur + timedelta(seconds=frac * 86400)
    return cur


def expected_deadline(captured, method, country, cross_border):
    _, _, value, unit = resolve_rule(method, country)
    if unit == HOURS:
        deadline = captured + timedelta(hours=value)
    elif unit == CALENDAR_DAYS:
        deadline = captured + timedelta(days=value)
    else:
        deadline = add_business_days(captured, value)
    if cross_border and CROSS_BORDER_SURCHARGE_DAYS > 0:
        deadline = add_business_days(deadline, CROSS_BORDER_SURCHARGE_DAYS)
    return deadline


def risk_deadline(captured, method, country, cross_border):
    window = expected_deadline(captured, method, country, cross_border) - captured
    return captured + window * RISK_MULTIPLIER


def unsettled_status(captured, method, country, cross_border):
    d = expected_deadline(captured, method, country, cross_border)
    r = risk_deadline(captured, method, country, cross_border)
    if NOW <= d:
        return "PENDING_SETTLEMENT"
    if NOW <= r:
        return "DELAYED"
    return "AT_RISK"


# --------------------------------------------------------------------------
# Synthesis helpers
# --------------------------------------------------------------------------
def pick_combo():
    method = random.choice(METHODS)
    if method == "pix":
        country = "BR"
    elif method == "oxxo":
        country = "MX"
    else:
        country = random.choice(COUNTRIES)
    cross_border = random.random() < 0.6
    return method, country, cross_border


def find_captured_for_unsettled(target):
    """Search for a (combo, captured) that yields the desired unsettled status."""
    for _ in range(60):
        method, country, cross_border = pick_combo()
        matches = []
        step_h = 0.5
        age_h = step_h
        while age_h <= 30 * 24:
            captured = NOW - timedelta(hours=age_h)
            if unsettled_status(captured, method, country, cross_border) == target:
                matches.append(captured)
            age_h += step_h
        if matches:
            return method, country, cross_border, random.choice(matches)
    return None


def random_usd():
    # Many small, a few large; clamp to [500, 150000].
    val = 500 + random.expovariate(1 / 18000)
    return round(min(val, 150000.0), 2)


def to_local(usd, currency):
    rate = FX_TO_USD[currency]
    amt = usd / rate
    # Zero-decimal-ish currencies look cleaner rounded to whole units.
    return round(amt, 2) if currency in ("USD",) else round(amt, 2)


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def make_record(idx, status):
    method = country = cross_border = captured = None
    settled = None
    failed = False

    if status == "SETTLED":
        method, country, cross_border = pick_combo()
        captured = NOW - timedelta(days=random.uniform(1.5, 30))
        d = expected_deadline(captured, method, country, cross_border)
        r = risk_deadline(captured, method, country, cross_border)
        if random.random() < 0.83:  # on time
            settled = captured + (d - captured) * random.uniform(0.15, 0.98)
        else:  # settled late
            settled = d + (r - d) * random.uniform(0.05, 0.8)
        if settled > NOW:
            settled = NOW - timedelta(hours=random.uniform(1, 36))
        if settled <= captured:
            settled = captured + timedelta(hours=2)
    elif status == "FAILED":
        method, country, cross_border = pick_combo()
        captured = NOW - timedelta(days=random.uniform(1, 25))
        failed = True
    else:  # PENDING_SETTLEMENT / DELAYED / AT_RISK
        found = find_captured_for_unsettled(status)
        if found is None:
            return None
        method, country, cross_border, captured = found

    currency = COUNTRY_CURRENCY[country]
    if cross_border and random.random() < 0.35:
        currency = "USD"  # many cross-border legs are settled in USD
    usd = random_usd()
    amount = to_local(usd, currency)

    return {
        "paymentId": f"pay_{idx:05d}",
        "paymentMethod": method,
        "country": country,
        "processor": random.choice(PROCESSORS),
        "currency": currency,
        "amount": amount,
        "capturedAt": iso(captured),
        "settledAt": iso(settled) if settled else None,
        "crossBorder": cross_border,
        "failed": failed,
    }


def build_dataset(n):
    # Resolve integer counts that sum to n.
    counts = {s: int(round(p * n)) for s, p in TARGET_MIX}
    diff = n - sum(counts.values())
    counts["SETTLED"] += diff  # absorb rounding drift

    records = []
    idx = 1
    for status, c in counts.items():
        made = 0
        attempts = 0
        while made < c and attempts < c * 50:
            attempts += 1
            rec = make_record(idx, status)
            if rec is None:
                continue
            records.append((status, rec))
            idx += 1
            made += 1
    random.shuffle(records)
    # reassign ids after shuffle for stable ordering-independent ids
    out = []
    for i, (_, rec) in enumerate(records, start=1):
        rec["paymentId"] = f"pay_{i:05d}"
        out.append(rec)
    return [r for (_, r) in records], out, counts


def summarize(records):
    """Reclassify every record to confirm the intended distribution holds."""
    from collections import Counter
    status_counts = Counter()
    usd_by_status = Counter()
    for r in records:
        captured = datetime.fromisoformat(r["capturedAt"].replace("Z", "+00:00"))
        if r["failed"]:
            st = "FAILED"
        elif r["settledAt"]:
            st = "SETTLED"
        else:
            st = unsettled_status(captured, r["paymentMethod"], r["country"], r["crossBorder"])
        status_counts[st] += 1
        usd_by_status[st] += r["amount"] * FX_TO_USD[r["currency"]]
    return status_counts, usd_by_status


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 360
    _, records, counts = build_dataset(n)

    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    targets = [
        os.path.join(root, "src", "main", "resources", "test-data", "payments.json"),
        os.path.join(root, "test-data", "payments.json"),
    ]
    for path in targets:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump(records, f, indent=2)
        print(f"wrote {len(records)} payments -> {path}")

    status_counts, usd_by_status = summarize(records)
    total = len(records)
    print("\nClassified distribution (live, computed now):")
    for s in ["SETTLED", "PENDING_SETTLEMENT", "DELAYED", "AT_RISK", "FAILED"]:
        c = status_counts.get(s, 0)
        print(f"  {s:20s} {c:4d}  ({100*c/total:5.1f}%)  ${usd_by_status.get(s,0):>14,.0f}")
    in_transit = sum(usd_by_status.get(s, 0) for s in ["PENDING_SETTLEMENT", "DELAYED", "AT_RISK"])
    print(f"\n  Total value:        ${sum(usd_by_status.values()):>14,.0f}")
    print(f"  In transit (P+D+R): ${in_transit:>14,.0f}")
    print(f"  Needs attention (D+R): ${sum(usd_by_status.get(s,0) for s in ['DELAYED','AT_RISK']):>11,.0f}")


if __name__ == "__main__":
    main()
