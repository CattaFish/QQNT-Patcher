# -*- coding: utf-8 -*-
"""
突破点赞次数上限动态规则插件
采用 数据解锁 + 发包自动切片 双层架构：
1. 资料卡下发解析 (TempProfileBusinessProcessor->handleGetSummaryCardForVote)：提升 RespSummaryCard.bAvailVoteCnt 至 50 - 已赞数
2. 访客列表解析 (CardProfile->readFrom)：提升 UserProfile.bAvailableCnt 至 50 - 今日已赞数
3. 资料卡/好友发包 (CardHandler->X3)：当 iCount > 10 时按每包最多 10 赞自动拆包发送
4. 访客非好友发包 (NearbyCardHandler->H2)：当 iCount > 10 时按每包最多 10 赞自动拆包发送
"""

from .parser import locate_class_and_method

RULE_ID = "unlock_like_limit"
RULE_NAME = "突破非好友点赞上限"
RULE_DESC = "将非好友每日可点赞次数上限提升至 50 次"
RULE_ALIASES = ["unlock_like_limit", "like_50", "zan_50", "50赞", "突破点赞", "点赞上限", "突破50赞", "非好友50赞"]
RULE_ENABLED = True


def build_unlock_like_limit_rules(dex_data_dict):
    rules = []

    # =========================================================================
    # 规则 1：资料卡回包解析入口 (TempProfileBusinessProcessor)
    # p0=this, p1=RespHead, p2=RespSummaryCard, p3=SparseArray, p4=targetUin, p5=Card
    # =========================================================================
    proc_default_cls = "Lcom/tencent/mobileqq/profilecard/processor/TempProfileBusinessProcessor;"
    proc_proto = "(LSummaryCard/RespHead;LSummaryCard/RespSummaryCard;Landroid/util/SparseArray;Ljava/lang/String;Lcom/tencent/mobileqq/data/Card;)V"
    proc_marker = "handleGetSummaryCardForVote 0x6d4 fail. resultCode=%d"

    proc_cls, proc_name, proc_m_proto = locate_class_and_method(
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
    ch_cls, ch_name, ch_proto = locate_class_and_method(
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
        "smali": """
    invoke-static/range {p0 .. p8}, Lcom/tencent/qqnt/patch/modules/misc/UnlockLikeLimitModule;->onSendFavorite(Ljava/lang/Object;JJ[BIII)Z
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
    nh_cls, nh_name, nh_proto = locate_class_and_method(
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
        "smali": """
    invoke-static/range {p0 .. p8}, Lcom/tencent/qqnt/patch/modules/misc/UnlockLikeLimitModule;->onSendFavorite(Ljava/lang/Object;JJ[BIII)Z
    move-result v0
    if-eqz v0, :cond_pass_nearby_split_like
    return-void
    :cond_pass_nearby_split_like
"""
    })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_unlock_like_limit_rules(dex_data_dict)