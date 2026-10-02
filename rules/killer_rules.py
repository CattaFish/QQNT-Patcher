# -*- coding: utf-8 -*-
"""去签核心注入规则 (条件启用: 仅当 Provider 为 Killer 时生效)"""

RULE_ID = "killer"
RULE_NAME = "去签入口注入"
RULE_ENABLED = True

def build_killer_rules(dex_data_dict):
    target_cls = "Lcom/tencent/mobileqq/activity/SplashActivity;"
    return [{
        "name": "去签入口注入 (SplashActivity.<clinit> -> KillerApplication.onLoaded)",
        "target_class": target_cls,
        "target_method": "<clinit>()V",
        "type": "INSERT_BEFORE",
        "smali": "    invoke-static {}, Lr/s/sign/KillerApplication;->onLoaded()V\n"
    }]

def resolve_rules(dex_data_dict, meta=None):
    meta = meta or {}
    # 只有当前 Provider 是 Killer 时才插入 onLoaded 字节码
    if meta.get("provider") != "killer":
        return []
    return build_killer_rules(dex_data_dict)
