# -*- coding: utf-8 -*-
"""查看聊天记录回复消息动态规则引擎"""

from .parser import FastDexParser

RULE_ID = "records_reply_check"
RULE_NAME = "查看聊天记录回复图片"
RULE_DESC = "查看聊天记录回复图片"
RULE_ALIASES = ["records_reply", "聊天记录回复", "回复图片", "合并转发回复"]
RULE_ENABLED = True

def build_records_reply_rules(dex_data_dict):
    rules = []
    target_class = None
    target_method = None

    for _, dex_bytes in dex_data_dict.items():
        if b"AIOMsgRepo MsgReplyAbility" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue

            candidates = p.find_classes_referencing_string_strictly("AIOMsgRepo MsgReplyAbility")
            for cls_name, cls_idx in candidates:
                if "aio/msglist/msgrepo" in cls_name and "$" not in cls_name:
                    methods = p.get_class_methods(cls_idx)
                    for m_name, proto_desc, is_virt, code_off, access_flags in methods:
                        if proto_desc.endswith(")J") and "MsgRecord;" in proto_desc:
                            target_class = cls_name
                            target_method = f"{m_name}{proto_desc}"
                            break
                if target_class: break
        if target_class: break

    if not target_class: target_class = "Lcom/tencent/mobileqq/aio/msglist/msgrepo/s;"
    if not target_method: target_method = "o(Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;Lcom/tencent/aio/data/msglist/a;)J"

    rules.append({
        "name": f"聊天记录回复图片放行 ({target_class}->{target_method.split('(')[0]})",
        "target_class": target_class,
        "target_method": target_method,
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "records_reply_check"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_reply_check
    const-wide/16 v0, 0x1
    return-wide v0
    :cond_pass_reply_check
"""
    })

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_records_reply_rules(dex_data_dict)
