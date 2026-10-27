# -*- coding: utf-8 -*-
"""
解除左滑回复限制动态规则引擎
基于 FastDexParser 语义特征全动态嗅探 AIOContentLeftSwipeHelper 的启用方法 (Z)V，
开启后强制所有消息类型均允许左滑触发官方回复。
"""

from .parser import FastDexParser

RULE_ID = "unlock_swipe_reply"
RULE_NAME = "解除左滑回复限制"
RULE_ENABLED = True


def build_unlock_swipe_reply_rules(dex_data_dict):
    rules = []
    helper_cls = "Lcom/tencent/mobileqq/aio/msglist/holder/component/leftswipearea/AIOContentLeftSwipeHelper;"
    target_method = None

    # 动态嗅探 (Z)V 方法名，适应不同版本的混淆变动
    for _, dex_bytes in dex_data_dict.items():
        if b"leftswipearea/AIOContentLeftSwipeHelper" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue
            c_idx = p.find_class_index(helper_cls)
            if c_idx != -1:
                methods = p.get_class_methods(c_idx)
                for m_name, proto_desc, is_virt, code_off, access_flags in methods:
                    if proto_desc == "(Z)V" and m_name != "<init>":
                        target_method = f"{m_name}{proto_desc}"
                        break
        if target_method:
            break

    # 兜底已知混淆方法名
    if not target_method:
        target_method = "j(Z)V"

    rules.append({
        "name": f"解除左滑回复限制 ({helper_cls}->{target_method.split('(')[0]})",
        "target_class": helper_cls,
        "target_method": target_method,
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/PatchBridge;->isUnlockSwipeReplyEnabled()Z
    move-result v0
    if-eqz v0, :cond_pass_unlock_swipe
    const/4 p1, 0x1
    :cond_pass_unlock_swipe
"""
    })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_unlock_swipe_reply_rules(dex_data_dict)