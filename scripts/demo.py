#!/usr/bin/env python3
"""Reproducible local demo. Uses only the Compose service at localhost."""
import base64
import datetime
import json
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
ALICE = "10000000-0000-0000-0000-000000000001"
BOB = "10000000-0000-0000-0000-000000000002"
REWARD = "20000000-0000-0000-0000-000000000001"
PROMOTION = "30000000-0000-0000-0000-000000000001"


def request(path, user="service", password="service-password", body=None, key=None):
    headers = {"Authorization": "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()}
    if key:
        headers["Idempotency-Key"] = key
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        result = json.load(error)
        print(path, error.code, json.dumps(result))
        if error.code not in (409,):
            raise
        return result
    print(path, json.dumps(result))
    return result


def money(amount, reference):
    return {"amount": amount, "reason": "Demonstration", "source": "demo", "reference": reference}


for name, player in [("alice", ALICE), ("bob", BOB)]:
    request("/v1/players", body={"playerId": player}, key=f"demo-provision-{name}")
request(f"/v1/wallets/{ALICE}/credits", body=money(100, "initial-credit"), key="demo-credit")
debit = request(f"/v1/wallets/{ALICE}/debits", body=money(30, "purchase"), key="demo-debit")
request("/v1/transfers", ALICE, "alice-password", {**money(20, "gift"), "recipientId": BOB}, "demo-transfer")
request("/v1/daily-login/claims", ALICE, "alice-password", {}, "demo-daily-" + datetime.datetime.now(datetime.timezone.utc).date().isoformat())
completion = request("/internal/v1/action-completions", body={"playerId": ALICE, "rewardId": REWARD, "source": "demo-server", "reference": "mission-1"}, key="demo-completion")
request(f"/v1/rewards/{REWARD}/claims", ALICE, "alice-password", {"completionReference": completion["completionReference"]}, "demo-reward")
request(f"/v1/promotions/{PROMOTION}/claims", ALICE, "alice-password", {}, "demo-promotion")
request(f"/v1/transactions/{debit['transactionId']}/refunds", body={"reason": "Cancelled purchase", "source": "demo", "reference": "cancel-purchase"}, key="demo-refund")
request(f"/v1/wallets/{ALICE}/balance", ALICE, "alice-password")
request(f"/v1/wallets/{ALICE}/transactions?limit=3", ALICE, "alice-password")
request("/v1/admin/reconciliation", "admin", "admin-password")
