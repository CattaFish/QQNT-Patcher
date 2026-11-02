# -*- coding: utf-8 -*-
"""去签壳入口注入与热修复禁用规则插件 (自适应: 9.1.75- QFix 架构 vs 9.1.75+ Tinker 架构)"""

import re

RULE_ID = "tinker"
RULE_NAME = "去签壳挂载与热修复禁用"
RULE_DESC = "去签壳挂载与热修复禁用"
RULE_ALIASES = ["热修", "热修复", "tinker_disable", "tinker", "qfix"]
RULE_ENABLED = True

def _load_cfg():
    try:
        from pipeline import killer_config as KC
        return KC
    except Exception:
        return None

def parse_semver(ver_str):
    if not ver_str: return None
    m = re.search(r"(\d+)\.(\d+)\.(\d+)", ver_str)
    if m:
        return (int(m.group(1)), int(m.group(2)), int(m.group(3)))
    return None

def build_tinker_rules(dex_data_dict, meta):
    KC = _load_cfg()
    provider = (meta or {}).get("provider", "")
    killer_desc = (meta or {}).get("killer_app_desc", "") or ""
    ver_str = (meta or {}).get("version_name", "")
    semver = parse_semver(ver_str)

    # 1. 架构判定：Tinker (9.1.75+) 还是 QFix (< 9.1.75)
    is_tinker = True
    if semver is not None:
        is_tinker = (semver >= (9, 1, 75))
    else:
        has_tinker = any(b"Lcom/tencent/tinker/loader/app/TinkerApplication;" in b for b in dex_data_dict.values())
        has_qfix = any(b"Lcom/tencent/mobileqq/qfix/QFixApplication;" in b for b in dex_data_dict.values())
        if not has_tinker and has_qfix:
            is_tinker = False

    rules = []

    if is_tinker:
        tinker_cls = KC.TINKER_APP_CLASS if KC else "Lcom/tencent/tinker/loader/app/TinkerApplication;"
        orig_super = KC.TINKER_ORIG_SUPER if KC else "Landroid/app/Application;"
        flags_method = KC.TINKER_FLAGS_METHOD if KC else "getTinkerFlags()I"

        if provider == "killer" and killer_desc:
            rules.append({
                "name": "去签: TinkerApplication.super -> " + killer_desc,
                "feature": "killer",
                "target_class": tinker_cls,
                "target_method": "",
                "type": "SUPER_REPLACE",
                "regex": orig_super,
                "smali": killer_desc,
            })

        rules.append({
            "name": "禁用 Tinker 热修复 (getTinkerFlags -> 0)",
            "feature": "killer",
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
    else:
        qfix_cls = "Lcom/tencent/mobileqq/qfix/QFixApplication;"
        orig_super = "Landroid/app/Application;"

        if provider == "killer" and killer_desc:
            rules.append({
                "name": "去签: QFixApplication.super -> " + killer_desc,
                "feature": "killer",
                "target_class": qfix_cls,
                "target_method": "",
                "type": "SUPER_REPLACE",
                "regex": orig_super,
                "smali": killer_desc,
            })

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_tinker_rules(dex_data_dict, meta)