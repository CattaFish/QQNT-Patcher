# -*- coding: utf-8 -*-
"""rules 统一入口：纯路由导出与特性开关层"""

from .base_rules import BASE_RULES
from .security_rules import build_security_rules
from .setting_rules import build_setting_rule
from .tablet_rules import build_tablet_rule
from .group_file_rules import build_group_file_rules
from .troop_todo_rules import build_troop_todo_rule
from .multi_window_rules import build_multi_window_rules
from .parser import FastDexParser

FEATURES = {
    "BASE": True,            # 基础总线规则 (MSF、AIO长按菜单、收发消息)
    "SECURITY": True,        # 反风控、致盲查签
    "SETTINGS": True,        # QQ 设置中心挂载
    "GROUP_FILE": True,      # 群文件下载次数
    "TABLET_MODE": True,     # 平板模式
    "TROOP_TODO": True,      # 静默群待办
    "MULTI_WINDOW": True,    # 伪装处于非多窗口模式
}

def get_base_rules():
    return list(BASE_RULES) if FEATURES.get("BASE", True) else []

def get_dynamic_security_rules(dex_data_dict, orig_apk_md5="", orig_sig_md5=""):
    if not FEATURES.get("SECURITY", True): return []
    return build_security_rules(dex_data_dict, orig_apk_md5, orig_sig_md5)

def get_dynamic_setting_rule_fast(dex_data_dict):
    if not FEATURES.get("SETTINGS", True): return None
    return build_setting_rule(dex_data_dict)

def get_dynamic_tablet_rule_fast(dex_data_dict):
    if not FEATURES.get("TABLET_MODE", True): return None
    return build_tablet_rule(dex_data_dict)

def get_dynamic_group_file_rules(dex_data_dict):
    if not FEATURES.get("GROUP_FILE", True): return []
    return build_group_file_rules(dex_data_dict)

def get_dynamic_troop_todo_rule(dex_data_dict):
    if not FEATURES.get("TROOP_TODO", True): return None
    return build_troop_todo_rule(dex_data_dict)

def get_dynamic_multi_window_rules(dex_data_dict):
    if not FEATURES.get("MULTI_WINDOW", True): return []
    return build_multi_window_rules(dex_data_dict)

RULES = get_base_rules()