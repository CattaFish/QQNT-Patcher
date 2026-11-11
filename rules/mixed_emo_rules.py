# -*- coding: utf-8 -*-
"""发送文字表情复合消息 (图文混排) 动态规则引擎"""

from .parser import FastDexParser

RULE_ID = "mixed_emo"
RULE_NAME = "文字表情混排"
RULE_DESC = "输入框已有文字或回复时，点击表情暂存至输入框混合发送"
RULE_ALIASES = ["mixed_emo", "文字表情混排", "复合消息", "表情暂存", "图文混排"]
RULE_ENABLED = True

def build_mixed_emo_rules(dex_data_dict):
    rules = []
    target_class = None
    target_method = None
    aio_delegate_cls = "Lcom/tencent/qqnt/aio/activity/AIODelegate;"

    # 1. 跨版本黄金特征字符串（定位 ImageUtils 工厂）
    FINGERPRINT = "aio_input_rotate_bitmap_147306215"

    for _, dex_bytes in dex_data_dict.items():
        if FINGERPRINT.encode("utf-8") in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            candidates = p.find_classes_referencing_string_strictly(FINGERPRINT)
            for cls_name, cls_idx in candidates:
                if "aio/input/fullscreen/" in cls_name and "$" not in cls_name:
                    methods = p.get_class_methods(cls_idx)
                    for m_name, proto_desc, _, _, _ in methods:
                        if proto_desc.startswith("(Ljava/lang/String;Landroid/net/Uri;Z)"):
                            target_class = cls_name
                            target_method = m_name
                            break
                if target_class and target_method:
                    break
        if target_class and target_method:
            break

    if not target_class:
        target_class = "Lcom/tencent/mobileqq/aio/input/fullscreen/InputImageUtils;"
        target_method = "b"

    java_class_name = target_class[1:-1].replace('/', '.')

    # 注入规则 A：同步 Span 工厂类与方法名
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

    # 注入规则 B (核心保回复条)：在 9.3.80+ 中，将 enablePicWithReplyOrInput 置为 false
    # 彻底阻止官方在点表情时提前抹掉输入框顶部的回复引用条！
    delegate_comp_cls = "Lcom/tencent/mobileqq/aio/input/sendmsg/AIOSendMsgVMDelegate$a;"
    for _, dex_bytes in dex_data_dict.items():
        if b"enablePicWithReplyOrInput" in dex_bytes:
            rules.append({
                "name": "禁用点表情自动清空回复条 (AIOSendMsgVMDelegate$a->a)",
                "target_class": delegate_comp_cls,
                "target_method": "a(Lcom/tencent/mobileqq/aio/input/sendmsg/AIOSendMsgVMDelegate$a;)Z",
                "type": "INSERT_BEFORE",
                "smali": """
    const-string v0, "mixed_emo"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_enable_pic
    const/4 v0, 0x0
    return v0
    :cond_pass_enable_pic
"""
            })
            break

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_mixed_emo_rules(dex_data_dict)