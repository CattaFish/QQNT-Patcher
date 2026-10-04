# -*- coding: utf-8 -*-
"""去签核心注入规则 (精准限定仅主进程初始化，杜绝多进程并发写入产生 signed.apk.tmp 垃圾)"""

RULE_ID = "killer"
RULE_NAME = "去签入口注入"
RULE_ENABLED = True

def build_killer_rules(dex_data_dict):
    qfix_app = "Lcom/tencent/common/app/QFixApplicationImplProxy;"
    
    # 仅允许主进程初始化去签，彻底杜绝多进程并发写入产生 signed.apk.tmp.<PID> 垃圾文件
    main_process_killer_smali = """
    invoke-static {}, Landroid/app/ActivityThread;->currentProcessName()Ljava/lang/String;
    move-result-object v0
    if-eqz v0, :cond_killer_main_proc
    invoke-virtual {p1}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_killer_skip
    :cond_killer_main_proc
    invoke-static {p1}, Lr/s/sign/KillerApplication;->onLoaded(Landroid/content/Context;)V
    :cond_killer_skip
"""

    # 1. 优先定位 QQ 根 Application 代理 (真·最早时机)
    for dex_bytes in dex_data_dict.values():
        if b"Lcom/tencent/common/app/QFixApplicationImplProxy;" in dex_bytes:
            return [{
                "name": "去签入口注入 (QFixApplicationImplProxy.attachBaseContext -> 主进程独占 onLoaded)",
                "target_class": qfix_app,
                "target_method": "attachBaseContext(Landroid/content/Context;)V",
                "type": "INSERT_BEFORE",
                "smali": main_process_killer_smali
            }]

    # 2. 兜底回退到 SplashActivity (本身就是主进程 UI)
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
