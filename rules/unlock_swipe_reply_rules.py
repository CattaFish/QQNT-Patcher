# -*- coding: utf-8 -*-
"""解除左滑回复限制动态规则引擎"""

from .parser import FastDexParser

RULE_ID = "unlock_swipe_reply"
RULE_NAME = "解除左滑回复限制"
RULE_DESC = "解除左滑回复限制"
RULE_ALIASES = ["unlock_swipe", "左滑回复", "解除左滑回复", "回复限制", "swipe_reply"]
RULE_ENABLED = True

def build_unlock_swipe_reply_rules(dex_data_dict):
    rules = []
    helper_cls = "Lcom/tencent/mobileqq/aio/msglist/holder/component/leftswipearea/AIOContentLeftSwipeHelper;"
    target_method = None

    for _, dex_bytes in dex_data_dict.items():
        if b"leftswipearea/AIOContentLeftSwipeHelper" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue
            c_idx = p.find_class_index(helper_cls)
            if c_idx != -1:
                methods = p.get_class_methods(c_idx)
                for m_name, proto_desc, is_virt, code_off, access_flags in methods:
                    if proto_desc == "(Z)V" and m_name != "<init>":
                        target_method = f"{m_name}{proto_desc}"
                        break
        if target_method: break

    if not target_method: target_method = "j(Z)V"

    rules.append({
        "name": f"解除左滑回复限制 ({helper_cls}->{target_method.split('(')[0]})",
        "target_class": helper_cls,
        "target_method": target_method,
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "unlock_swipe_reply"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_unlock_swipe
    const/4 p1, 0x1
    :cond_pass_unlock_swipe
"""
    })

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_unlock_swipe_reply_rules(dex_data_dict)
