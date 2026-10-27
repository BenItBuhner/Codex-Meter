#!/usr/bin/env python3
"""Prints demo Wear app preferences (codex_meter_wear_v1.xml) with five providers, as if
the phone had just synced. Pass a provider id to make the watch follow that provider."""
import json, time, sys
from xml.sax.saxutils import escape
now = int(time.time()*1000)
choice = sys.argv[1] if len(sys.argv) > 1 else ""
def win(used, seconds, reset_min):
    return {"used_percent": used, "limit_window_seconds": seconds,
            "reset_after_seconds": int(reset_min*60), "reset_at": int(now/1000 + reset_min*60)}
codex = {"plan_type": "pro", "allowed": True, "limit_reached": False,
         "five_hour": win(38, 18000, 134), "weekly": win(59, 604800, 3*1440+260),
         "reset_credits_available": 2, "fetched_at": now - 240000}
claude = {"plan_type": "max", "allowed": True, "limit_reached": False,
          "five_hour": win(71, 18000, 52), "weekly": win(34, 604800, 5*1440),
          "additional_limits": [{"id": "opus", "name": "Opus", "metered_feature": "", "allowed": True,
                                 "limit_reached": False, "primary": win(46, 604800, 5*1440)}],
          "fetched_at": now - 600000}
cursor = {"plan_type": "pro", "allowed": True, "limit_reached": False,
          "monthly": win(27, 2592000, 11*1440), "fetched_at": now - 900000}
copilot = {"plan_type": "", "allowed": True, "limit_reached": False,
           "additional_limits": [{"id": "premium", "name": "Premium requests", "metered_feature": "",
                                  "allowed": True, "limit_reached": False,
                                  "primary": win(64, 2592000, 9*1440)}],
           "fetched_at": now - 1200000}
providers = {"providers": [
    {"id": "codex", "name": "Codex", "signed_in": True, "usage": codex},
    {"id": "claude_code", "name": "Claude Code", "signed_in": True, "usage": claude},
    {"id": "cursor", "name": "Cursor", "signed_in": True, "usage": cursor},
    {"id": "copilot", "name": "Copilot", "signed_in": True, "usage": copilot},
    {"id": "kimi", "name": "Kimi Code", "signed_in": False}],
    "phone_selected": "codex", "updated_at_millis": now}
def s(k, v): return f'    <string name="{k}">{escape(v)}</string>'
def b(k, v): return f'    <boolean name="{k}" value="{"true" if v else "false"}" />'
def l(k, v): return f'    <long name="{k}" value="{v}" />'
lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>",
    s("snapshot_json", json.dumps(codex)), s("providers_json", json.dumps(providers)),
    b("synced", True), b("connected", True), b("signed_in", True),
    l("last_applied_usage_at", now - 240000), l("last_applied_status_at", now),
    l("last_applied_providers_at", now), l("status_last_success", now - 240000),
    s("status_error", ""), s("phone_version", "demo"), b("usage_pace_enabled", True)]
if choice: lines.append(s("watch_provider", choice))
lines.append("</map>")
print("\n".join(lines))
