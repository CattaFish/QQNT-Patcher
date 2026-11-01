# -*- coding: utf-8 -*-
"""QQ空间与好友动态广告消隐动态规则引擎"""

import struct
from .parser import FastDexParser

RULE_ID = "qzone_ad"
RULE_NAME = "隐藏空间广告"
RULE_DESC = "隐藏QQ空间说说流与详情页推广广告"
RULE_ALIASES = ["qzone_ad", "空间广告", "空间去广告", "隐藏空间广告"]
RULE_ENABLED = True

# 核心咽喉基类与组件列表
KNOWN_TARGET_CLASSES = [
    # 1. FeedPro 体系所有广告卡片 (普通卡片、多图、轮播、合约) 的公共基类
    "Lcom/qzone/reborn/feedpro/itemview/ad/QZoneAdBaseMediaFeedProItemView;",
    # 2. FeedX 体系所有广告 (视频、图文、激励、MDPA) 的公共基类
    "Lcom/qzone/reborn/feedx/itemview/ad/QZoneAdBaseFeedItemView;",
    # 3. 说说详情页评论底部广告组件
    "Lcom/qzone/reborn/feedpro/widget/comment/QZoneFeedProDetailBottomAdBlockView;",
]

TARGET_PACKAGE_PREFIXES = (
    "Lcom/qzone/reborn/feedpro/itemview/ad/",
    "Lcom/qzone/reborn/feedx/itemview/ad/",
    "Lcom/qzone/reborn/feedpro/widget/comment/QZoneFeedProDetailBottomAdBlockView",
)

def build_qzone_ad_rules(dex_data_dict):
    rules = []
    target_classes_to_patch = set(KNOWN_TARGET_CLASSES)

    # 1. 动态嗅探：扫描 DEX，寻找所有广告子包下实现了 bindData(Ljava/lang/Object;I)V 的类
    for _, dex_bytes in dex_data_dict.items():
        if b"itemview/ad" in dex_bytes or b"QZoneFeedProDetailBottomAdBlockView" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            for i in range(p.class_defs_size):
                class_idx = struct.unpack_from('<I', p.data, p.class_defs_off + i * 32)[0]
                cls_name = p.get_type_str(class_idx)

                # 匹配空间广告组件
                if any(cls_name.startswith(prefix) for prefix in TARGET_PACKAGE_PREFIXES):
                    if "$" not in cls_name and cls_name.endswith(";"):
                        # 检查当前类是否显式声明了 bindData(Ljava/lang/Object;I)V
                        for m_name, proto_desc, _, _, _ in p.get_class_methods(i):
                            if m_name == "bindData" and proto_desc == "(Ljava/lang/Object;I)V":
                                target_classes_to_patch.add(cls_name)
                                break

    # 2. 为命中的类构造插桩规则
    for cls_name in sorted(target_classes_to_patch):
        rules.append({
            "name": f"空间广告数据阻断与消隐 ({cls_name}->bindData)",
            "target_class": cls_name,
            "target_method": "bindData(Ljava/lang/Object;I)V",
            "type": "INSERT_BEFORE",
            "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/modules/purify/QZoneAdBlockModule;->suppressAdView(Landroid/view/View;)Z
    move-result v0
    if-eqz v0, :cond_pass_qzone_ad
    return-void
    :cond_pass_qzone_ad
"""
        })

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_qzone_ad_rules(dex_data_dict)