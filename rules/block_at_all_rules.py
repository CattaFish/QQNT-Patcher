# -*- coding: utf-8 -*-
"""屏蔽群聊 @全体成员 通知动态规则插件"""

from .parser import FastDexParser

RULE_ID = "block_at_all"
RULE_NAME = "屏蔽群聊 @全体成员"
RULE_DESC = "过滤群聊中 @全体成员 的强提醒与横幅通知"
RULE_ALIASES = ["at_all", "全体", "@全体", "屏蔽全体"]
RULE_ENABLED = True

def build_block_at_all_rules(dex_data_dict):
    rules = []
    target_class = "Lcom/tencent/qqnt/notification/NotificationFacade;"
    target_method = None

    # 1. 动态嗅探 NotificationFacade 中处理新消息通知展示的核心方法
    for _, dex_bytes in dex_data_dict.items():
        if b"com/tencent/qqnt/notification/NotificationFacade" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            c_idx = p.find_class_index(target_class)
            if c_idx != -1:
                methods = p.get_class_methods(c_idx)
                for m_name, proto_desc, is_virt, code_off, access_flags in methods:
                    # 匹配参数特征：(AppRuntime, RecentContactInfo, ..., boolean)V
                    if proto_desc.startswith("(Lmqq/app/AppRuntime;Lcom/tencent/qqnt/kernel/nativeinterface/RecentContactInfo;") and proto_desc.endswith(")V"):
                        target_method = f"{m_name}{proto_desc}"
                        break
            if target_method:
                break

    # 2. 兜底保障（基于已知版本的混淆方法签名）
    if not target_method:
        target_method = "H(Lmqq/app/AppRuntime;Lcom/tencent/qqnt/kernel/nativeinterface/RecentContactInfo;Lcom/tencent/qqnt/kernel/nativeinterface/NotificationCommonInfo;Z)V"

    # 3. 构造静态插桩规则
    rules.append({
        "name": f"屏蔽群聊 @全体成员 通知 ({target_class}->{target_method.split('(')[0]})",
        "target_class": target_class,
        "target_method": target_method,
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "block_at_all"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_at_all
    if-eqz p2, :cond_pass_at_all
    iget v0, p2, Lcom/tencent/qqnt/kernel/nativeinterface/RecentContactInfo;->atType:I
    const/4 v1, 0x1
    if-ne v0, v1, :cond_pass_at_all
    return-void
    :cond_pass_at_all
"""
    })

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_block_at_all_rules(dex_data_dict)