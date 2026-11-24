# -*- coding: utf-8 -*-
"""群待办 (0x135 强通知) 动态规则引擎"""

from .parser import FastDexParser

RULE_ID = "troop_todo"
RULE_NAME = "静默群待办强提醒"
RULE_DESC = "静默群待办强提醒"
RULE_ALIASES = ["todo", "群待办"]
RULE_ENABLED = True


def build_troop_todo_rule(dex_data_dict):
    target_strings = ["deal0x135Msg online:", "TianShuOfflineMsgCenter"]
    for _, dex_bytes in dex_data_dict.items():
        if b"deal0x135Msg online:" in dex_bytes or b"TianShuOfflineMsgCenter" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue

            for target_str in target_strings:
                candidates = p.find_classes_referencing_string_strictly(target_str)
                for cls_name, cls_idx in candidates:
                    if "notification" in cls_name:
                        info = p.find_method_info_referencing_string(cls_idx, target_str)
                        if info:
                            m_name, proto_desc = info[0], info[1]
                            is_void = proto_desc.endswith(")V")
                            return_smali = "return-void" if is_void else "const/4 v0, 0x0\n    return v0"

                            return {
                                "name": f"静默群待办强提醒 ({cls_name}->{m_name})",
                                "target_class": cls_name,
                                "target_method": f"{m_name}{proto_desc}",
                                "type": "INSERT_BEFORE",
                                "smali": f"""
    invoke-static {{}}, Lcom/tencent/qqnt/patch/PatchBridge;->shouldDropTroopToDo()Z
    move-result v0
    if-eqz v0, :cond_todo_pass
    {return_smali}
    :cond_todo_pass
"""
                            }
    return None


def resolve_rules(dex_data_dict, meta=None):
    r = build_troop_todo_rule(dex_data_dict)
    return [r] if r else []