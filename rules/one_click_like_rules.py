# -*- coding: utf-8 -*-
"""
一键点赞动态规则插件
覆盖两个核心场景：
1. 他人资料卡点赞 (AbsProfileHeaderComponent->handleVoteBtnClickForGuestProfile)
2. 访客 / 谁赞过我 / 我赞过谁回赞页 (VoteHelper->onClickVote)
"""

from .parser import locate_class_and_method

RULE_ID = "one_click_like"
RULE_NAME = "一键点赞"
RULE_DESC = "在资料卡页面点赞时自动点满当日可赞次数"
RULE_ALIASES = ["one_click_like", "like", "vote", "zan", "一键点赞", "一键20赞", "一键赞", "自动点赞", "回赞"]
RULE_ENABLED = True


def build_one_click_like_rules(dex_data_dict):
    rules = []

    # =========================================================================
    # 入口 1：他人资料卡点赞 (AbsProfileHeaderComponent)
    # =========================================================================
    profile_default_cls = "Lcom/tencent/mobileqq/profilecard/base/component/AbsProfileHeaderComponent;"
    profile_proto = "(Lcom/tencent/mobileqq/data/Card;)V"
    profile_marker = "handleVoteBtnClickForGuestProfile availVoteCnt="

    p_cls, p_name, p_proto = locate_class_and_method(
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

    v_cls, v_name, v_proto = locate_class_and_method(
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