# -*- coding: utf-8 -*-
"""
Feature 调度与依赖决议中心
已实现全动态自描述聚合：独立规则插件自声明元数据，无需在此手动硬编码！
"""

import os
import sys

_ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT_DIR not in sys.path:
    sys.path.insert(0, _ROOT_DIR)

BUILTIN_FEATURES = {
    "killer": {
        "desc": "去签核心入口注入",
        "aliases": ["killer", "去签", "签名", "sign_killer"],
    },
    "anti_revoke": {
        "desc": "消息防撤回",
        "aliases": ["anti_revoke", "revoke", "防撤回", "撤回"],
    },
    "flash_pic": {
        "desc": "闪照破解与画廊放行",
        "aliases": ["flash_pic", "flash", "闪照", "闪照破解"],
    },
    "script": {
        "desc": "动态脚本生态 (BeanShell 引擎与菜单)",
        "aliases": ["script", "scripts", "脚本", "动态脚本", "bsh", "qfun", "plugin", "plugins"],
    },
    "auto_remark_apk": {
        "desc": "发送 APK 自动重命名",
        "aliases": ["auto_remark_apk", "apk", "apk重命名"],
    },
    "modify_pic_summary": {
        "desc": "修改图片与表情外显",
        "aliases": ["modify_pic_summary", "pic_summary", "外显", "图片外显"],
    },
    "meow": {
        "desc": "喵喵助手",
        "aliases": ["meow", "喵喵", "喵喵助手"],
    },
    "floating_ball": {
        "desc": "会话悬浮球快捷入口",
        "aliases": ["floating_ball", "ball", "悬浮球"],
    },
    "chat_history": {
        "desc": "查看本地聊天记录 (私聊/群聊)",
        "aliases": ["chat_history", "history", "聊天记录", "历史记录"],
    },
}

BUS_DEPENDENCIES = {
    "bus_msf": {"anti_revoke", "auto_remark_apk", "script", "qq_version"},
    "bus_send_msg": {"meow", "auto_remark_apk", "modify_pic_summary", "script"},
    "bus_recv_msg": {"flash_pic", "anti_revoke", "script"},
    "bus_aio_msg": {"flash_pic", "floating_ball"},
    "bus_aio_lifecycle": {"floating_ball"},
    "bus_aio_menu": {"script"},
    "bus_setting": {"setting"},
    "bus_troop_member": {"script"},
}

def _collect_all_features():
    all_features = {}
    feature_aliases = {}

    for fid, meta in BUILTIN_FEATURES.items():
        all_features[fid] = meta.get("desc", fid)
        feature_aliases[fid.lower()] = fid
        for alias in meta.get("aliases", []):
            feature_aliases[alias.lower()] = fid

    try:
        from rules.engine import discover_rule_plugins
        plugins = discover_rule_plugins()
        for p in plugins:
            if not p.plugin_id or p.plugin_id == "base":
                continue
            fid = p.plugin_id
            all_features[fid] = p.desc or p.name
            feature_aliases[fid.lower()] = fid
            feature_aliases[p.name.lower()] = fid
            for alias in p.aliases:
                feature_aliases[alias.lower()] = fid
    except Exception as e:
        print(f" \033[1;33m[WARN]\033[0m 自动聚合规则特征失败: {e}")

    return all_features, feature_aliases

ALL_FEATURES, FEATURE_ALIASES = _collect_all_features()

def resolve_feature_id(name_or_alias: str):
    k = name_or_alias.strip().lower()
    return FEATURE_ALIASES.get(k, None)

def resolve_active_features(only_inputs, skip_inputs):
    if only_inputs:
        active = set()
        for item in only_inputs:
            for part in item.split(","):
                fid = resolve_feature_id(part)
                if fid: active.add(fid)
    else:
        active = set(ALL_FEATURES.keys())

    if skip_inputs:
        for item in skip_inputs:
            for part in item.split(","):
                fid = resolve_feature_id(part)
                if fid: active.discard(fid)

    return active
