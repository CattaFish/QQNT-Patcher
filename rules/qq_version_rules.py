# -*- coding: utf-8 -*-
"""
发送者 QQ 客户端版本显示规则
挂载目标:
1. ProfileCardMoreActivity.doOnResume -> 顶置注入独立卡片
2. MemberSettingFragment.onViewCreatedAfterPartInit -> 顶置注入独立 Group
"""

RULE_ID = "qq_version"
RULE_NAME = "发送者 QQ 版本显示"
RULE_ENABLED = True

def build_qq_version_rules():
    return [
        {
            "name": "好友与群成员更多设置挂载 (ProfileCardMoreActivity.doOnResume)",
            "target_class": "Lcom/tencent/mobileqq/profilesetting/ProfileCardMoreActivity;",
            "target_method": "doOnResume()V",
            "type": "INSERT_BEFORE",
            "smali": """
    invoke-static {p0}, Lcom/tencent/qqnt/patch/PatchBridge;->onProfileCardMoreResume(Ljava/lang/Object;)V
"""
        },
        {
            "name": "群成员高级管理页顶置挂载 (MemberSettingFragment.onViewCreatedAfterPartInit)",
            "target_class": "Lcom/tencent/mobileqq/troop/membersetting/fragment/MemberSettingFragment;",
            "target_method": "onViewCreatedAfterPartInit(Landroid/view/View;Landroid/os/Bundle;)V",
            "type": "REGEX_REPLACE",
            # 宽容匹配整个 toArray 指令行，彻底杜绝类型签名细节差异导致的失配
            "regex": r"(invoke-virtual\s+\{[vp]\d+,\s*[vp]\d+\},\s*Ljava/util/ArrayList;->toArray[^\n]+)",
            "smali": r"""
    invoke-static {p0, p1}, Lcom/tencent/qqnt/patch/PatchBridge;->onMemberSettingGroups(Ljava/lang/Object;Ljava/lang/Object;)V
    \1"""
        }
    ]

def resolve_rules(dex_data_dict, meta=None):
    return build_qq_version_rules()