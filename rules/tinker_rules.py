# -*- coding: utf-8 -*-
"""
禁用 Tinker 热修复动态规则插件
强制 TinkerApplication->getTinkerFlags() 返回 0 (TINKER_DISABLE)，彻底阻断官方热更新补丁合成与加载
"""

RULE_ID = "tinker"
RULE_NAME = "禁用 Tinker 热修复"
RULE_ENABLED = True  # 插件默认开启

def build_tinker_rules(dex_data_dict):
    tinker_app_cls = "Lcom/tencent/tinker/loader/app/TinkerApplication;"
    
    # Tinker 核心 loader 属于系统启动级组件，类名与方法名不参与混淆
    return [{
        "name": "强制禁用 Tinker 热修复 (getTinkerFlags -> 0)",
        "target_class": tinker_app_cls,
        "target_method": "getTinkerFlags()I",
        "type": "REPLACE",
        "smali": """
.method public getTinkerFlags()I
    .registers 1

    const/4 p0, 0x0

    return p0
.end method
"""
    }]

def resolve_rules(dex_data_dict, meta=None):
    return build_tinker_rules(dex_data_dict)