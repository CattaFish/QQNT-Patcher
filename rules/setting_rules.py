# -*- coding: utf-8 -*-
"""设置中心动态挂载与原生搜索索引规则引擎 (全版本自适应混淆)"""

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

def _find_search_method_dynamically(p, class_idx):
    """动态多重防线嗅探 FunctionSearchFragment 中的真正搜索执行函数"""
    # 防线 1: 查找引用了 "webNodes" 的方法
    m = _find_method_strictly_referencing_string(p, class_idx, "webNodes")
    if m:
        return m

    # 防线 2: 查找引用了统一配置 ID "105693" 的方法
    m = _find_method_strictly_referencing_string(p, class_idx, "105693")
    if m:
        return m

    # 防线 3: 查找 new-instance Lcom/tencent/mobileqq/setting/search/node/b; 的方法
    t_id = p.find_type_id("Lcom/tencent/mobileqq/setting/search/node/b;")
    if t_id != -1:
        t_pat = struct.pack('<H', t_id)
        for m_name, proto_desc, _, code_off, _ in p.get_class_methods(class_idx):
            if code_off == 0 or code_off + 16 >= len(p.data):
                continue
            insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
            insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]
            k = 0
            while k < len(insns) - 3:
                if insns[k] == 0x22 and insns[k + 2 : k + 4] == t_pat:
                    return f"{m_name}{proto_desc}"
                k += 2
    return None

def build_setting_rules(dex_data_dict):
    rules = []

    # 1. 设置中心主列表连体卡片动态注入
    config_class, target_method, item_class = None, None, None
    for _, dex_bytes in dex_data_dict.items():
        if not (config_class and target_method) and b'SettingConfigProvider' in dex_bytes:
            p = FastDexParser(dex_bytes)
            if p.valid:
                cls, m = p.find_setting_config_info()
                if cls and m: config_class, target_method = cls, m

        if not item_class and b'SimpleItemProcessor' in dex_bytes:
            p = FastDexParser(dex_bytes)
            if p.valid:
                item = p.find_simple_item_class()
                if item: item_class = item

        if config_class and target_method and item_class: break

    if config_class and target_method and item_class:
        rules.append({
            "name": f"设置中心动态挂载 ({config_class})",
            "target_class": config_class,
            "target_method": target_method,
            "type": "REGEX_REPLACE",
            "regex": r"return-object\s+([vp]\d+)(?=\s*(?:\.end\s+method|$))",
            "smali": f"""
    move-object/16 v0, \\1
    move-object/16 v1, p1
    const-string v2, "{item_class}"
    invoke-static {{v1, v0, v2}}, Lcom/tencent/qqnt/patch/SettingInjector;->inject(Landroid/content/Context;Ljava/util/List;Ljava/lang/String;)V
    return-object v0"""
        })

    # 2. 前台搜索 UI 树动态嗅探与挂载 (自适应任意混淆方法名与字段名)
    search_frag_cls = "Lcom/tencent/mobileqq/setting/search/FunctionSearchFragment;"
    for _, dex_bytes in dex_data_dict.items():
        if b"FunctionSearchFragment" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if p.valid:
                c_idx = p.find_class_index(search_frag_cls)
                if c_idx != -1:
                    target_m = _find_search_method_dynamically(p, c_idx)
                    if target_m:
                        rules.append({
                            "name": f"设置搜索前台动态挂载 (FunctionSearchFragment->{target_m})",
                            "target_class": search_frag_cls,
                            "target_method": target_m,
                            "type": "REGEX_REPLACE",
                            "regex": r"(invoke-direct\s+\{([vp]\d+)\},\s+Lcom/tencent/mobileqq/setting/search/node/b;-><init>\(\)V)",
                            "smali": r"""\1
    invoke-static {\2}, Lcom/tencent/qqnt/patch/SettingSearchInjector;->inject(Ljava/lang/Object;)V"""
                        })
                        break

    # 3. 后台搜索接口双保险 (FunctionSearchManagerImpl->initSearchNode)
    search_mgr_cls = "Lcom/tencent/mobileqq/setting/api/impl/FunctionSearchManagerImpl;"
    for _, dex_bytes in dex_data_dict.items():
        if b"FunctionSearchManagerImpl" in dex_bytes:
            rules.append({
                "name": "设置搜索后台动态挂载 (FunctionSearchManagerImpl->initSearchNode)",
                "target_class": search_mgr_cls,
                "target_method": "initSearchNode()V",
                "type": "REGEX_REPLACE",
                "regex": r"(invoke-direct\s+\{([vp]\d+)\},\s+Lcom/tencent/mobileqq/setting/search/node/b;-><init>\(\)V)",
                "smali": r"""\1
    invoke-static {\2}, Lcom/tencent/qqnt/patch/SettingSearchInjector;->inject(Ljava/lang/Object;)V"""
            })
            break

    return rules

# === 规则插件契约 ===
RULE_ID = "setting"
RULE_NAME = "设置中心与搜索动态挂载"
RULE_ENABLED = True

def resolve_rules(dex_data_dict, meta=None):
    return build_setting_rules(dex_data_dict)