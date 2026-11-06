# -*- coding: utf-8 -*-
"""内置浏览器网页拦截解除动态规则引擎 (基于 CustomWebView.loadUrl 终端阻断)"""

from .parser import FastDexParser

RULE_ID = "browser"
RULE_NAME = "禁用内置浏览器网页拦截"
RULE_DESC = "允许在内置浏览器直接访问非官方/第三方网页"
RULE_ALIASES = ["browser_mitigation", "网页拦截", "浏览器"]
RULE_ENABLED = True

def build_browser_rules(dex_data_dict):
    rules = []
    target_class = "Lcom/tencent/biz/pubaccount/CustomWebView;"

    # 1. 动态嗅探 CustomWebView 所在的分包与方法存在性
    target_dex = None
    has_load_url = False
    has_load_url_original = False

    for dex_name, dex_bytes in dex_data_dict.items():
        if b"Lcom/tencent/biz/pubaccount/CustomWebView;" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            c_idx = p.find_class_index(target_class)
            if c_idx != -1:
                target_dex = dex_name
                methods = p.get_class_methods(c_idx)
                for m_name, proto_desc, _, _, _ in methods:
                    if m_name == "loadUrl" and proto_desc == "(Ljava/lang/String;)V":
                        has_load_url = True
                    elif m_name == "loadUrlOriginal" and proto_desc == "(Ljava/lang/String;)V":
                        has_load_url_original = True
                break

    # 2. 对 loadUrl(String) 进行就地清洗
    if has_load_url or target_dex:
        rules.append({
            "name": "内置浏览器终端加载拦截 (CustomWebView->loadUrl)",
            "target_class": target_class,
            "target_method": "loadUrl(Ljava/lang/String;)V",
            "type": "INSERT_BEFORE",
            "smali": """
    move-object/16 v0, p0
    move-object/16 v1, p1
    invoke-static {v0, v1}, Lcom/tencent/qqnt/patch/modules/purify/BrowserMitigationModule;->filterLoadUrl(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
    move-result-object p1
"""
        })

    # 3. 对 loadUrlOriginal(String) 进行并网防护 (双保险)
    if has_load_url_original or target_dex:
        rules.append({
            "name": "内置浏览器终端原生加载拦截 (CustomWebView->loadUrlOriginal)",
            "target_class": target_class,
            "target_method": "loadUrlOriginal(Ljava/lang/String;)V",
            "type": "INSERT_BEFORE",
            "smali": """
    move-object/16 v0, p0
    move-object/16 v1, p1
    invoke-static {v0, v1}, Lcom/tencent/qqnt/patch/modules/purify/BrowserMitigationModule;->filterLoadUrl(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
    move-result-object p1
"""
        })

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_browser_rules(dex_data_dict)