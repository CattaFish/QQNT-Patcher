# -*- coding: utf-8 -*-
"""群待办 (0x135 强通知) 动态规则引擎 (自适应全版本混淆)"""

import struct
from .parser import FastDexParser

def _find_method_referencing_string(p, class_idx, target_string):
    s_id = p.find_string_id(target_string)
    if s_id == -1:
        return None, None
    s_16 = struct.pack('<H', s_id) if s_id <= 65535 else None
    s_32 = struct.pack('<I', s_id)

    methods = p.get_class_methods(class_idx)
    for m_name, proto_desc, _, code_off, _ in methods:
        if code_off == 0 or code_off + 16 >= len(p.data):
            continue
        insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
        insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]
        k = 0
        len_insns = len(insns)
        while k < len_insns - 1:
            op = insns[k]
            if op == 0x1A and k + 4 <= len_insns:
                if s_16 and insns[k + 2 : k + 4] == s_16:
                    return m_name, proto_desc
                k += 4
                continue
            elif op == 0x1B and k + 6 <= len_insns:
                if insns[k + 2 : k + 6] == s_32:
                    return m_name, proto_desc
                k += 6
                continue
            k += 2
    return None, None

def build_troop_todo_rule(dex_data_dict):
    """动态嗅探处理 0x135 群待办/天枢强通知的关键分发函数"""
    target_strings = ["deal0x135Msg online:", "TianShuOfflineMsgCenter"]

    for _, dex_bytes in dex_data_dict.items():
        if b"deal0x135Msg online:" in dex_bytes or b"TianShuOfflineMsgCenter" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            for target_str in target_strings:
                candidates = p.find_classes_referencing_string_strictly(target_str)
                for cls_name, cls_idx in candidates:
                    if "notification" in cls_name:
                        m_name, proto_desc = _find_method_referencing_string(p, cls_idx, target_str)
                        if m_name and proto_desc:
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

# === 规则插件契约 ===
RULE_ID = "troop_todo"
RULE_NAME = "静默群待办强提醒"
RULE_ENABLED = True

def resolve_rules(dex_data_dict, meta=None):
    r = build_troop_todo_rule(dex_data_dict)
    return [r] if r else []
