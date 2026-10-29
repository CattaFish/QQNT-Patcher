# -*- coding: utf-8 -*-
"""Tinker 去签 + 热修复禁用规则插件"""

RULE_ID = "tinker"
RULE_NAME = "禁用 Tinker 热修复载入"
RULE_DESC = "禁用 Tinker 热修复载入"
RULE_ALIASES = ["热修", "热修复", "tinker_disable"]
RULE_ENABLED = True

def _load_cfg():
    try:
        from pipeline import killer_config as KC
        return KC
    except Exception:
        return None

def build_tinker_rules(dex_data_dict, meta):
    KC = _load_cfg()
    tinker_cls = KC.TINKER_APP_CLASS if KC else "Lcom/tencent/tinker/loader/app/TinkerApplication;"
    orig_super = KC.TINKER_ORIG_SUPER if KC else "Landroid/app/Application;"
    flags_method = KC.TINKER_FLAGS_METHOD if KC else "getTinkerFlags()I"

    provider = (meta or {}).get("provider", "")
    killer_desc = (meta or {}).get("killer_app_desc", "") or ""

    rules = []

    if provider == "killer" and killer_desc:
        rules.append({
            "name": "去签: TinkerApplication.super -> " + killer_desc,
            "target_class": tinker_cls,
            "target_method": "",
            "type": "SUPER_REPLACE",
            "regex": orig_super,
            "smali": killer_desc,
        })

    rules.append({
        "name": "禁用 Tinker 热修复 (getTinkerFlags -> 0)",
        "target_class": tinker_cls,
        "target_method": flags_method,
        "type": "REPLACE",
        "smali": """
.method public getTinkerFlags()I
    .registers 1

    const/4 p0, 0x0

    return p0
.end method
"""
    })

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_tinker_rules(dex_data_dict, meta)
