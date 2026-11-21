# -*- coding: utf-8 -*-
"""
突破点赞次数上限动态规则插件
采用 数据解锁 + 发包自动切片 双层架构：
1. 资料卡下发解析 (TempProfileBusinessProcessor->handleGetSummaryCardForVote)：提升 RespSummaryCard.bAvailVoteCnt 至 50 - 已赞数
2. 访客列表解析 (CardProfile->readFrom)：提升 UserProfile.bAvailableCnt 至 50 - 今日已赞数
3. 资料卡/好友发包 (CardHandler->X3)：当 iCount > 10 时按每包最多 10 赞自动拆包发送
4. 访客非好友发包 (NearbyCardHandler->H2)：当 iCount > 10 时按每包最多 10 赞自动拆包发送
"""

import struct
from .parser import FastDexParser

RULE_ID = "unlock_like_limit"
RULE_NAME = "突破非好友点赞上限"
RULE_DESC = "将非好友每日可点赞次数上限提升至 50 次"
RULE_ALIASES = ["unlock_like_limit", "like_50", "zan_50", "50赞", "突破点赞", "点赞上限", "突破50赞", "非好友50赞"]
RULE_ENABLED = True


def _find_method_strictly_referencing_string(p, class_idx, target_string, expected_proto=None):
    """通过方法体中严格引用的特征字符串定位目标方法"""
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


def _locate_class_and_method(dex_data_dict, default_class, marker_str, expected_proto, fallback_scan=True):
    """优先按默认类名嗅探，可选回退到全 DEX 字符串反查"""
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

        if fallback_scan:
            candidates = p.find_classes_referencing_string_strictly(marker_str)
            for cls_name, cls_idx in candidates:
                if "$" in cls_name:
                    continue
                found = _find_method_strictly_referencing_string(p, cls_idx, marker_str, expected_proto)
                if found:
                    return cls_name, found[0], found[1]

    return None, None, None


def build_unlock_like_limit_rules(dex_data_dict):
    rules = []

    # =========================================================================
    # 规则 1：资料卡回包解析入口 (TempProfileBusinessProcessor)
    # p0=this, p1=RespHead, p2=RespSummaryCard, p3=SparseArray, p4=targetUin, p5=Card
    # =========================================================================
    proc_default_cls = "Lcom/tencent/mobileqq/profilecard/processor/TempProfileBusinessProcessor;"
    proc_proto = "(LSummaryCard/RespHead;LSummaryCard/RespSummaryCard;Landroid/util/SparseArray;Ljava/lang/String;Lcom/tencent/mobileqq/data/Card;)V"
    proc_marker = "handleGetSummaryCardForVote 0x6d4 fail. resultCode=%d"

    proc_cls, proc_name, proc_m_proto = _locate_class_and_method(
        dex_data_dict, proc_default_cls, proc_marker, proc_proto, fallback_scan=True
    )
    if not proc_cls:
        proc_cls = proc_default_cls
        proc_name = "handleGetSummaryCardForVote"
        proc_m_proto = proc_proto

    rules.append({
        "name": f"突破点赞上限 - 资料卡配额解锁 ({proc_cls}->{proc_name})",
        "target_class": proc_cls,
        "target_method": f"{proc_name}{proc_m_proto}",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p2
    move-object/16 v1, p4
    invoke-static {v0, v1}, Lcom/tencent/qqnt/patch/modules/misc/UnlockLikeLimitModule;->onSummaryCardResp(Ljava/lang/Object;Ljava/lang/String;)V
"""
    })

    # =========================================================================
    # 规则 2：访客 / 谁赞过我列表解析入口 (CardProfile->readFrom)
    # p0=this, p1=UserProfile
    # =========================================================================
    card_profile_cls = "Lcom/tencent/mobileqq/data/CardProfile;"
    card_profile_method = "readFrom(LQQService/UserProfile;)V"

    rules.append({
        "name": "突破点赞上限 - 访客列表配额解锁 (CardProfile->readFrom)",
        "target_class": card_profile_cls,
        "target_method": card_profile_method,
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p1
    invoke-static {v0}, Lcom/tencent/qqnt/patch/modules/misc/UnlockLikeLimitModule;->onUserProfileRead(Ljava/lang/Object;)V
"""
    })

    # =========================================================================
    # 规则 3：发包层切片 - CardHandler (资料卡 & 访客好友)
    # 签名: (JJ[BIII)V -> p0=this, p1..p2=selfUin, p3..p4=targetUin, p5=cookies, p6=src, p7=iCount, p8=from
    # =========================================================================
    req_fav_proto = "(JJ[BIII)V"
    req_fav_marker = "VisitorSvc.ReqFavorite"

    card_handler_cls = "Lcom/tencent/mobileqq/app/CardHandler;"
    ch_cls, ch_name, ch_proto = _locate_class_and_method(
        dex_data_dict, card_handler_cls, req_fav_marker, req_fav_proto, fallback_scan=False
    )
    if not ch_cls:
        ch_cls = card_handler_cls
        ch_name = "X3"
        ch_proto = req_fav_proto

    rules.append({
        "name": f"突破点赞上限 - CardHandler 自动分包 ({ch_cls}->{ch_name})",
        "target_class": ch_cls,
        "target_method": f"{ch_name}{ch_proto}",
        "type": "INSERT_BEFORE",
        "smali": f"""
    move-object/16 v0, p0
    move-wide/16 v1, p1
    move-wide/16 v3, p3
    move-object/16 v5, p5
    move/16 v6, p6
    move/16 v7, p7
    move/16 v8, p8
    const-string v9, "{ch_name}"
    invoke-static/range {{v0 .. v9}}, Lcom/tencent/qqnt/patch/modules/misc/UnlockLikeLimitModule;->onSendFavorite(Ljava/lang/Object;JJ[BIIILjava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_card_split_like
    return-void
    :cond_pass_card_split_like
"""
    })

    # =========================================================================
    # 规则 4：发包层切片 - NearbyCardHandler (访客非好友)
    # 签名同为 (JJ[BIII)V
    # =========================================================================
    nearby_handler_cls = "Lcom/tencent/mobileqq/nearby/business/NearbyCardHandler;"
    nh_cls, nh_name, nh_proto = _locate_class_and_method(
        dex_data_dict, nearby_handler_cls, req_fav_marker, req_fav_proto, fallback_scan=False
    )
    if not nh_cls:
        nh_cls = nearby_handler_cls
        nh_name = "H2"
        nh_proto = req_fav_proto

    rules.append({
        "name": f"突破点赞上限 - NearbyCardHandler 自动分包 ({nh_cls}->{nh_name})",
        "target_class": nh_cls,
        "target_method": f"{nh_name}{nh_proto}",
        "type": "INSERT_BEFORE",
        "smali": f"""
    move-object/16 v0, p0
    move-wide/16 v1, p1
    move-wide/16 v3, p3
    move-object/16 v5, p5
    move/16 v6, p6
    move/16 v7, p7
    move/16 v8, p8
    const-string v9, "{nh_name}"
    invoke-static/range {{v0 .. v9}}, Lcom/tencent/qqnt/patch/modules/misc/UnlockLikeLimitModule;->onSendFavorite(Ljava/lang/Object;JJ[BIIILjava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_nearby_split_like
    return-void
    :cond_pass_nearby_split_like
"""
    })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_unlock_like_limit_rules(dex_data_dict)