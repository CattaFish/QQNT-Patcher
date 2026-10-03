# -*- coding: utf-8 -*-
"""去签核心注入规则 (针对 QQ Application 入口 QFixApplicationImplProxy.attachBaseContext)"""

RULE_ID = "killer"
RULE_NAME = "去签入口注入"
RULE_ENABLED = True

def build_killer_rules(dex_data_dict):
    qfix_app = "Lcom/tencent/common/app/QFixApplicationImplProxy;"
    
    # 1. 优先定位 QQ 根 Application 代理 (真·最早时机，覆盖所有子进程)
    for dex_bytes in dex_data_dict.values():
        if b"Lcom/tencent/common/app/QFixApplicationImplProxy;" in dex_bytes:
            return [{
                "name": "去签入口注入 (QFixApplicationImplProxy.attachBaseContext -> onLoaded(Context))",
                "target_class": qfix_app,
                "target_method": "attachBaseContext(Landroid/content/Context;)V",
                "type": "INSERT_BEFORE",
                "smali": "    invoke-static {p1}, Lr/s/sign/KillerApplication;->onLoaded(Landroid/content/Context;)V\n"
            }]

    # 2. 兜底回退到 SplashActivity
    splash_cls = "Lcom/tencent/mobileqq/activity/SplashActivity;"
    return [{
        "name": "去签入口注入 (SplashActivity.<clinit> -> onLoaded())",
        "target_class": splash_cls,
        "target_method": "<clinit>()V",
        "type": "INSERT_BEFORE",
        "smali": "    invoke-static {}, Lr/s/sign/KillerApplication;->onLoaded()V\n"
    }]

def resolve_rules(dex_data_dict, meta=None):
    meta = meta or {}
    if meta.get("provider") != "killer":
        return []
    return build_killer_rules(dex_data_dict)
