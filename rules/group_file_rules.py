# -*- coding: utf-8 -*-
"""群文件下载次数动态规则引擎 (自适应 9.2.90 ~ 9.3.60+ 全版本)"""

import struct
from .parser import FastDexParser

def _find_method_strictly_referencing_string(p, class_idx, target_string):
    """机器指令检查：方法是否真正执行了 const-string 加载指定字符串 (0x1A/0x1B)"""
    s_id = p.find_string_id(target_string)
    if s_id == -1:
        return None
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
                    return f"{m_name}{proto_desc}"
                k += 4
                continue
            elif op == 0x1B and k + 6 <= len_insns:
                if insns[k + 2 : k + 6] == s_32:
                    return f"{m_name}{proto_desc}"
                k += 6
                continue
            k += 2
    return None

def _find_methods_calling_named_method(p, class_idx, target_method_name):
    """机器指令检查：从 Dalvik 机器码查找真正调用了 target_method_name 的方法"""
    s_id = p.find_string_id(target_method_name)
    if s_id == -1: return []
    
    target_m_indices = set()
    for m_idx in range(p.method_ids_size):
        name_idx = struct.unpack_from('<I', p.data, p.method_ids_off + m_idx * 8 + 4)[0]
        if name_idx == s_id:
            target_m_indices.add(m_idx)
            
    if not target_m_indices: return []
    
    matched = []
    for m_name, proto_desc, _, code_off, _ in p.get_class_methods(class_idx):
        if code_off == 0 or code_off + 16 >= len(p.data): continue
        insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
        insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]
        
        k = 0
        len_insns = len(insns)
        while k < len_insns - 3:
            op = insns[k]
            if op in (0x71, 0x77):  # invoke-static 或 invoke-static/range
                m_ref = struct.unpack_from('<H', insns, k + 2)[0]
                if m_ref in target_m_indices:
                    matched.append((m_name, proto_desc, code_off))
                    break
            k += 2
    return matched

def build_group_file_rules(dex_data_dict):
    """全自动动态群文件下载次数规则构建器"""
    rules_list = []
    
    repo_cls = "Lgroup_file/group_file_common/repo/GroupFileListRepo;"
    old_f_cls = "Lqqfile_common/components/f;"
    slot_cls = "Lgroup_file/group_file_common/abstraction/slot/GroupFileCellSlotProvider;"

    # 1. 网络回包拦截 (GroupFileListRepo -> 查找包含 [syncExtraInfo] 的挂起函数)
    for _, dex_bytes in dex_data_dict.items():
        if b"GroupFileListRepo" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue
            c_idx = p.find_class_index(repo_cls)
            if c_idx != -1:
                m1 = _find_method_strictly_referencing_string(p, c_idx, "[syncExtraInfo]: start")
                m2 = _find_method_strictly_referencing_string(p, c_idx, "[syncExtraInfoV2]: start")
                for m in [m1, m2]:
                    if m:
                        rules_list.append({
                            "name": f"群文件回包拦截 (GroupFileListRepo->{m.split('(')[0]})",
                            "target_class": repo_cls,
                            "target_method": m,
                            "type": "INSERT_BEFORE",
                            "smali": """
    move-object/16 v0, p2
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->handleGroupFileListResponse(Ljava/lang/Object;)V
"""
                        })

    # 2. UI 渲染点 A: 检查 qqfile_common/components/f (9.2.90 主渲染通道)
    for _, dex_bytes in dex_data_dict.items():
        if b"qqfile_common/components/f" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue
            c_idx = p.find_class_index(old_f_cls)
            if c_idx != -1:
                calling_methods = _find_methods_calling_named_method(p, c_idx, "joinToString$default")
                for m_name, proto_desc, _ in calling_methods:
                    if proto_desc.startswith("(Llr5/e;") or "ZLqqfile_common/data/a;)" in proto_desc:
                        rules_list.append({
                            "name": f"群文件状态文字追加 (components.f->{m_name})",
                            "target_class": old_f_cls,
                            "target_method": f"{m_name}{proto_desc}",
                            "type": "REGEX_REPLACE",
                            "regex": r"(invoke-static/range\s+\{[^}]+\},\s+Lkotlin/collections/CollectionsKt;->joinToString\$default\([^)]+\)Ljava/lang/String;\s+move-result-object\s+([vp]\d+))",
                            "smali": r"""\1
    move-object/16 v8, p0
    invoke-static {\2, v8}, Lcom/tencent/qqnt/patch/PatchBridge;->appendDownloadCountToStatusText(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
    move-result-object \2"""
                        })
                        break

    # 3. UI 渲染点 B: 检查 GroupFileCellSlotProvider (9.3.60+ 主渲染通道)
    for _, dex_bytes in dex_data_dict.items():
        if b"GroupFileCellSlotProvider" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue
            c_idx = p.find_class_index(slot_cls)
            if c_idx != -1:
                calling_methods = _find_methods_calling_named_method(p, c_idx, "joinToString$default")
                for m_name, proto_desc, _ in calling_methods:
                    if "Lqqfile_common/components/e;" in proto_desc:
                        rules_list.append({
                            "name": f"群文件状态文字追加 (GroupFileCellSlotProvider->{m_name})",
                            "target_class": slot_cls,
                            "target_method": f"{m_name}{proto_desc}",
                            "type": "REGEX_REPLACE",
                            "regex": r"(invoke-static/range\s+\{[^}]+\},\s+Lkotlin/collections/CollectionsKt;->joinToString\$default\([^)]+\)Ljava/lang/String;\s+move-result-object\s+([vp]\d+))",
                            "smali": r"""\1
    move-object/16 v8, p1
    invoke-static {\2, v8}, Lcom/tencent/qqnt/patch/PatchBridge;->appendDownloadCountToStatusText(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
    move-result-object \2"""
                        })
                        break

    return rules_list
# === 规则插件契约 ===
RULE_ID = "group_file"
RULE_NAME = "群文件下载次数"
RULE_ENABLED = True

def resolve_rules(dex_data_dict, meta=None):
    return build_group_file_rules(dex_data_dict)
