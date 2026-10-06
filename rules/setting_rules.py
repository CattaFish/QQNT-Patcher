# -*- coding: utf-8 -*-
"""设置中心动态挂载与原生搜索索引规则引擎"""

from .parser import FastDexParser

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

    # 2. ★ 真正的前台搜索 UI 树动态注入 (FunctionSearchFragment->cd)
    search_frag_cls = "Lcom/tencent/mobileqq/setting/search/FunctionSearchFragment;"
    for _, dex_bytes in dex_data_dict.items():
        if b"FunctionSearchFragment" in dex_bytes:
            rules.append({
                "name": "设置搜索前台索引动态挂载 (FunctionSearchFragment->cd)",
                "target_class": search_frag_cls,
                "target_method": "cd(Ljava/lang/CharSequence;)V",
                "type": "REGEX_REPLACE",
                "regex": r"(iput-object\s+([vp]\d+),\s+[vp]\d+,\s+Lcom/tencent/mobileqq/setting/search/FunctionSearchFragment;->H:Lcom/tencent/mobileqq/setting/search/node/c;)",
                "smali": r"""\1
    invoke-static {\2}, Lcom/tencent/qqnt/patch/SettingSearchInjector;->inject(Ljava/lang/Object;)V"""
            })
            break

    # 3. 后台接口双保险 (FunctionSearchManagerImpl->initSearchNode)
    search_mgr_cls = "Lcom/tencent/mobileqq/setting/api/impl/FunctionSearchManagerImpl;"
    for _, dex_bytes in dex_data_dict.items():
        if b"FunctionSearchManagerImpl" in dex_bytes:
            rules.append({
                "name": "设置搜索后台索引动态挂载 (FunctionSearchManagerImpl->initSearchNode)",
                "target_class": search_mgr_cls,
                "target_method": "initSearchNode()V",
                "type": "REGEX_REPLACE",
                "regex": r"(iput-object\s+([vp]\d+),\s+[vp]\d+,\s+Lcom/tencent/mobileqq/setting/api/impl/FunctionSearchManagerImpl;->searchRootNode:Lcom/tencent/mobileqq/setting/search/node/c;)",
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