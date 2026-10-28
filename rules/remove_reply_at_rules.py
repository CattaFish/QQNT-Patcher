# -*- coding: utf-8 -*-
"""
去除回复自动艾特动态规则引擎
精准阻断 AIOInputReply 组件向总线发射 InsertAtMemberSpan 艾特意图。
"""

from .parser import FastDexParser

RULE_ID = "remove_reply_at"
RULE_NAME = "去除回复自动艾特"
RULE_ENABLED = True


def build_remove_reply_at_rules(dex_data_dict):
    rules = []
    target_class = None
    target_method = None
    event_str = "com.tencent.mobileqq.aio.event.AIOMsgSendEvent.MsgOnClickReplyEvent"

    for _, dex_bytes in dex_data_dict.items():
        if b"AIOMsgSendEvent.MsgOnClickReplyEvent" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            candidates = p.find_classes_referencing_string_strictly(event_str)
            for cls_name, cls_idx in candidates:
                if "aio/input/reply" in cls_name and "$" not in cls_name:
                    methods = p.get_class_methods(cls_idx)
                    for m_name, proto_desc, is_virt, code_off, access_flags in methods:
                        if proto_desc == "(Lcom/tencent/mvi/base/route/MsgIntent;)V":
                            target_class = cls_name
                            target_method = f"{m_name}{proto_desc}"
                            break
                if target_class:
                    break
        if target_class:
            break

    # 兜底已确定的类名与方法名
    if not target_class:
        target_class = "Lcom/tencent/mobileqq/aio/input/reply/i;"
    if not target_method:
        target_method = "f(Lcom/tencent/mvi/base/route/MsgIntent;)V"

    # 精准把调用 d->h(MsgIntent)V 替换为 RemoveReplyAtModule->dispatchRouteIntent
    rules.append({
        "name": f"阻断回复艾特意图分发 ({target_class}->{target_method.split('(')[0]})",
        "target_class": target_class,
        "target_method": target_method,
        "type": "REGEX_REPLACE",
        "regex": r"invoke-interface\s+\{([vp]\d+),\s*([vp]\d+)\},\s*Lcom/tencent/mvi/base/route/d;->h\(Lcom/tencent/mvi/base/route/MsgIntent;\)V",
        "smali": r"""    invoke-static {\1, \2}, Lcom/tencent/qqnt/patch/modules/chat/RemoveReplyAtModule;->dispatchRouteIntent(Ljava/lang/Object;Ljava/lang/Object;)V"""
    })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_remove_reply_at_rules(dex_data_dict)