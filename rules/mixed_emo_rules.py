# -*- coding: utf-8 -*-
"""发送文字表情复合消息 (图文混排) 动态规则引擎"""

import struct
from .parser import FastDexParser

RULE_ID = "mixed_emo"
RULE_NAME = "文字表情混排"
RULE_DESC = "输入框已有文字或回复时，点击表情暂存至输入框混合发送"
RULE_ALIASES = ["mixed_emo", "文字表情混排", "复合消息", "表情暂存", "图文混排"]
RULE_ENABLED = True

def build_mixed_emo_rules(dex_data_dict):
    rules = []
    target_class = None
    target_method = "a"
    aio_delegate_cls = "Lcom/tencent/qqnt/aio/activity/AIODelegate;"

    # 1. 动态嗅探：扫描包含 "sampleSize" 的类，定位 aio/input/fullscreen 下的 ImageUtils
    for _, dex_bytes in dex_data_dict.items():
        if b"sampleSize" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            candidates = p.find_classes_referencing_string_strictly("sampleSize")
            for cls_name, cls_idx in candidates:
                if "aio/input/fullscreen/" in cls_name and "$" not in cls_name:
                    methods = p.get_class_methods(cls_idx)
                    for m_name, proto_desc, _, _, _ in methods:
                        if proto_desc.startswith("(Ljava/lang/String;Landroid/net/Uri;Z)"):
                            target_class = cls_name
                            target_method = m_name
                            break
                if target_class:
                    break
        if target_class:
            break

    # 兜底保障（基于已知 9.2.90 版本的类名）
    if not target_class:
        target_class = "Lcom/tencent/mobileqq/aio/input/fullscreen/v;"
        target_method = "a"

    java_class_name = target_class[1:-1].replace('/', '.')

    # 2. 注入规则：在 AIO 会话创建/打开时，把动态嗅探到的类名与方法名同步给 Java 模块
    rules.append({
        "name": f"动态挂载图文混排工厂 ({java_class_name}->{target_method})",
        "target_class": aio_delegate_cls,
        "target_method": "show()Landroid/view/View;",
        "type": "INSERT_BEFORE",
        "smali": f"""
    const-string v0, "{java_class_name}"
    const-string v1, "{target_method}"
    invoke-static {{v0, v1}}, Lcom/tencent/qqnt/patch/modules/chat/MixedEmoAndTextModule;->setupFactory(Ljava/lang/String;Ljava/lang/String;)V
"""
    })

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_mixed_emo_rules(dex_data_dict)