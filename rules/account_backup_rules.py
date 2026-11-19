# -*- coding: utf-8 -*-
"""登录凭证备份与恢复自描述规则插件"""

RULE_ID = "account_backup"
RULE_NAME = "登录凭证备份与恢复"
RULE_DESC = "备份与切换本地免登录凭证，支持多账号快捷恢复"
RULE_ALIASES = ["account_backup", "login_backup", "账号备份", "登录备份", "账号切换", "切号"]
RULE_ENABLED = True


def build_account_backup_rules(dex_data_dict):
    # 纯 Java 层模块，通过 ModuleManager 与设置中心自动装配，无需 Smali 字节码插桩
    return []


def resolve_rules(dex_data_dict, meta=None):
    return build_account_backup_rules(dex_data_dict)