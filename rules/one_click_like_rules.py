# -*- coding: utf-8 -*-
"""
一键点赞动态规则插件
覆盖两个核心场景：
1. 他人资料卡点赞 (AbsProfileHeaderComponent->handleVoteBtnClickForGuestProfile)
2. 访客 / 谁赞过我 / 我赞过谁回赞页 (VoteHelper->onClickVote)
"""

import struct
from .parser import FastDexParser

RULE_ID = "one_click_like"
RULE_NAME = "一键点赞"
RULE_DESC = "在资料卡页面点赞时自动点满当日可赞次数"
RULE_ALIASES = ["one_click_like", "like", "vote", "zan", "一键点赞", "一键20赞", "一键赞", "自动点赞", "回赞"]
RULE_ENABLED = True


def _find_method_strictly_referencing_string(p, class_idx, target_string, expected_proto=None):
    """通过方法体中严格引用的特征字符串定位目标方法，兼容混淆方法名"""
    s_id = p.find_string_id(target_string)
    if s_id == -1:
        return None
    s_16 = struct.pack('<H', s_id) if s_id <= 65535 else None
    s_32 = struct.pack('<I', s_id)

    methods = p.get_class_methods(class_idx)
    for m_name, proto_desc, _, code_off, _ in methods:
        if expected_proto and proto_desc != expected_proto:
            continue
        if code_off == 0 or code_off + 16 >= len(p.data):
            continue
        insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
        insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]

        if (s_16 and s_16 not in insns) and (s_32 not in insns):
            continue

        k = 0
        len_insns = len(insns)
        while k < len_insns - 1:
            op = insns[k]
            if op == 0x1A and k + 4 <= len_insns:  # const-string
                if s_16 and insns[k + 2 : k + 4] == s_16:
                    return m_name, proto_desc
                k += 4
                continue
            elif op == 0x1B and k + 6 <= len_insns:  # const-string/jumbo
                if insns[k + 2 : k + 6] == s_32:
                    return m_name, proto_desc
                k += 6
                continue
            k += 2
    return None


def _locate_class_and_method(dex_data_dict, default_class, marker_str, expected_proto):
    """优先按默认类名嗅探，失败后按特征字符串全 DEX 反向定位"""
    marker_bytes = marker_str.encode("utf-8")

    for _, dex_bytes in dex_data_dict.items():
        if marker_bytes not in dex_bytes:
            continue
        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        c_idx = p.find_class_index(default_class)
        if c_idx != -1:
            found = _find_method_strictly_referencing_string(p, c_idx, marker_str, expected_proto)
            if found:
                return default_class, found[0], found[1]

        candidates = p.find_classes_referencing_string_strictly(marker_str)
        for cls_name, cls_idx in candidates:
            if "$" in cls_name:
                continue
            found = _find_method_strictly_referencing_string(p, cls_idx, marker_str, expected_proto)
            if found:
                return cls_name, found[0], found[1]

    return None, None, None


def build_one_click_like_rules(dex_data_dict):
    rules = []

    # =========================================================================
    # 入口 1：他人资料卡点赞 (AbsProfileHeaderComponent)
    # =========================================================================
    profile_default_cls = "Lcom/tencent/mobileqq/profilecard/base/component/AbsProfileHeaderComponent;"
    profile_proto = "(Lcom/tencent/mobileqq/data/Card;)V"
    profile_marker = "handleVoteBtnClickForGuestProfile availVoteCnt="

    p_cls, p_name, p_proto = _locate_class_and_method(
        dex_data_dict, profile_default_cls, profile_marker, profile_proto
    )
    if not p_cls:
        p_cls = profile_default_cls
        p_name = "handleVoteBtnClickForGuestProfile"
        p_proto = profile_proto

    rules.append({
        "name": f"一键点赞 - 资料卡入口 ({p_cls}->{p_name})",
        "target_class": p_cls,
        "target_method": f"{p_name}{p_proto}",
        "type": "INSERT_BEFORE",
        "smali": f"""
    move-object/16 v0, p0
    move-object/16 v1, p1
    const-string v2, "{p_name}"
    invoke-static {{v0, v1, v2}}, Lcom/tencent/qqnt/patch/modules/misc/OneClickLikeModule;->onProfileCardVote(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_profile_like
    return-void
    :cond_pass_profile_like
"""
    })

    # =========================================================================
    # 入口 2：访客 / 谁赞过我 / 我赞过谁回赞页 (VoteHelper)
    # =========================================================================
    visitor_default_cls = "Lcom/tencent/mobileqq/profile/vote/VoteHelper;"
    visitor_proto = "(Lcom/tencent/mobileqq/data/CardProfile;Landroid/widget/ImageView;)Z"
    visitor_marker = "VisitorActivity->onClickVote, uin="

    v_cls, v_name, v_proto = _locate_class_and_method(
        dex_data_dict, visitor_default_cls, visitor_marker, visitor_proto
    )
    if not v_cls:
        v_cls = visitor_default_cls
        v_name = "f"
        v_proto = visitor_proto

    rules.append({
        "name": f"一键点赞 - 访客回赞入口 ({v_cls}->{v_name})",
        "target_class": v_cls,
        "target_method": f"{v_name}{v_proto}",
        "type": "INSERT_BEFORE",
        "smali": f"""
    move-object/16 v0, p0
    move-object/16 v1, p1
    move-object/16 v2, p2
    const-string v3, "{v_name}"
    invoke-static {{v0, v1, v2, v3}}, Lcom/tencent/qqnt/patch/modules/misc/OneClickLikeModule;->onVisitorVote(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)I
    move-result v0
    if-ltz v0, :cond_pass_visitor_like
    if-eqz v0, :cond_ret_false_visitor_like
    const/4 v0, 0x1
    return v0
    :cond_ret_false_visitor_like
    const/4 v0, 0x0
    return v0
    :cond_pass_visitor_like
"""
    })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_one_click_like_rules(dex_data_dict)