# -*- coding: utf-8 -*-
"""
发送者 QQ 客户端版本显示动态规则引擎
基于 FastDexParser 语义特征全动态嗅探，彻底杜绝混淆类名与方法名硬编码！
自适应 9.2.90 ~ 9.3.70+ 全版本。
"""

from .parser import FastDexParser

RULE_ID = "qq_version"
RULE_NAME = "发送者 QQ 版本显示"
RULE_ENABLED = True

def build_qq_version_rules(dex_data_dict):
    rules = []
    more_activity_cls = None
    member_setting_frag_cls = None

    # 1. 动态嗅探 ProfileCardMoreActivity (排除内部类$，必须是 Activity 且拥有 doOnResume 方法)
    for _, dex_bytes in dex_data_dict.items():
        if b"pg_zplan_qqusercard_setting" in dex_bytes or b"troopMembercard" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue

            candidates = p.find_classes_referencing_string_strictly("pg_zplan_qqusercard_setting")
            if not candidates:
                candidates = p.find_classes_referencing_string_strictly("troopMembercard")

            for cls_name, cls_idx in candidates:
                # 排除内部类，必须以 Activity 结尾
                if "$" in cls_name or not cls_name.endswith("Activity;"):
                    continue

                if "Profile" in cls_name or "profile" in cls_name:
                    methods = p.get_class_methods(cls_idx)
                    has_resume = any(m_name == "doOnResume" and m_proto == "()V" for m_name, m_proto, _, _, _ in methods)
                    if has_resume:
                        more_activity_cls = cls_name
                        break
        if more_activity_cls: break

    # 兜底已知类名
    if not more_activity_cls:
        more_activity_cls = "Lcom/tencent/mobileqq/profilesetting/ProfileCardMoreActivity;"

    rules.append({
        "name": f"好友与群成员更多设置顶置挂载 ({more_activity_cls}->doOnResume)",
        "target_class": more_activity_cls,
        "target_method": "doOnResume()V",
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {p0}, Lcom/tencent/qqnt/patch/PatchBridge;->onProfileCardMoreResume(Ljava/lang/Object;)V
"""
    })

    # 2. 动态嗅探 MemberSettingFragment (特征: 引用 member_setting_new 或 QUISettingsRecyclerView)
    for _, dex_bytes in dex_data_dict.items():
        if b"member_setting_new" in dex_bytes or b"QUISettingsRecyclerView" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue

            candidates = p.find_classes_referencing_string_strictly("member_setting_new")
            for cls_name, cls_idx in candidates:
                if "MemberSetting" in cls_name or "membersetting" in cls_name:
                    member_setting_frag_cls = cls_name
                    break
        if member_setting_frag_cls: break

    # 兜底已知类名
    if not member_setting_frag_cls:
        member_setting_frag_cls = "Lcom/tencent/mobileqq/troop/membersetting/fragment/MemberSettingFragment;"

    rules.append({
        "name": f"群成员高级管理页顶置挂载 ({member_setting_frag_cls}->onViewCreatedAfterPartInit)",
        "target_class": member_setting_frag_cls,
        "target_method": "onViewCreatedAfterPartInit(Landroid/view/View;Landroid/os/Bundle;)V",
        "type": "REGEX_REPLACE",
        "regex": r"(invoke-virtual\s+\{[vp]\d+,\s*[vp]\d+\},\s*Ljava/util/ArrayList;->toArray.*)",
        "smali": r"""
    invoke-static {p0, p1}, Lcom/tencent/qqnt/patch/PatchBridge;->onMemberSettingGroups(Ljava/lang/Object;Ljava/lang/Object;)V
    \1"""
    })

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_qq_version_rules(dex_data_dict)
