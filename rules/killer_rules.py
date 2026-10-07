# -*- coding: utf-8 -*-
"""
去签核心注入规则
严格限定【仅真·主进程】且【进程名严格匹配】才允许初始化，杜绝多进程并发与 signed.apk.tmp 残留
"""

RULE_ID = "killer"
RULE_NAME = "去签入口注入"
RULE_ENABLED = True

def build_killer_rules(dex_data_dict):
    qfix_app = "Lcom/tencent/common/app/QFixApplicationImplProxy;"
    
    # 严格的 Smali 判定逻辑：
    # 1. 获取进程名，若为 null 立即跳过 (:cond_killer_skip)
    # 2. 比对是否严格等于主包名，不等于立即跳过 (:cond_killer_skip)
    # 3. 只有真·主进程才执行 onLoaded
    strict_main_process_smali = """
    invoke-static {}, Landroid/app/ActivityThread;->currentProcessName()Ljava/lang/String;
    move-result-object v0
    if-nez v0, :cond_killer_check
    goto :cond_killer_skip
    :cond_killer_check
    invoke-virtual {p1}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_killer_skip
    invoke-static {p1}, Lr/s/sign/KillerApplication;->onLoaded(Landroid/content/Context;)V
    :cond_killer_skip
"""

    for dex_bytes in dex_data_dict.values():
        if b"Lcom/tencent/common/app/QFixApplicationImplProxy;" in dex_bytes:
            return [{
                "name": "去签入口注入 (QFixApplicationImplProxy.attachBaseContext -> 真·主进程独占 onLoaded)",
                "target_class": qfix_app,
                "target_method": "attachBaseContext(Landroid/content/Context;)V",
                "type": "INSERT_BEFORE",
                "smali": strict_main_process_smali
            }]

    # 兜底 SplashActivity
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
