# -*- coding: utf-8 -*-
"""
Feature 调度与依赖决议中心
支持细粒度控制任意功能的开启与跳过
"""

ALL_FEATURES = {
    # 基础设施与安全底座 (支持自由 skip/only 测试)
    "killer": "去签核心入口注入",
    "security": "安全风控与查签致盲",
    "tinker": "禁用 Tinker 热修复载入",

    # 业务扩展功能
    "anti_revoke": "消息防撤回",
    "flash_pic": "闪照破解与画廊放行",
    "script": "动态脚本生态 (BeanShell 引擎与菜单)",
    "setting": "原生设置中心与搜索索引",
    "tablet": "强制平板模式",
    "multi_window": "伪装非多窗口模式 (解除分屏限制)",
    "group_file": "群文件显示下载次数",
    "troop_todo": "静默群待办强提醒",
    "block_at_all": "静默 @全体 实时通知",
    "auto_remark_apk": "发送 APK 自动重命名",
    "modify_pic_summary": "修改图片与表情外显",
    "tg_stickers": "Telegram 外部表情包",
    "browser": "禁用内置浏览器网页拦截",
    "meow": "喵喵助手",
    "floating_ball": "会话悬浮球快捷入口",
    "chat_history": "查看本地聊天记录 (私聊/群聊)",
    "qq_version": "发送者 QQ 版本识别",
}

FEATURE_ALIASES = {
    # killer
    "killer": "killer", "去签": "killer", "签名": "killer", "sign_killer": "killer",
    # security
    "security": "security", "sec": "security", "安全": "security", "查签": "security", "风控": "security",
    # tinker
    "tinker": "tinker", "热修": "tinker", "热修复": "tinker", "tinker_disable": "tinker",
    # anti_revoke
    "anti_revoke": "anti_revoke", "revoke": "anti_revoke", "防撤回": "anti_revoke", "撤回": "anti_revoke",
    # flash_pic
    "flash_pic": "flash_pic", "flash": "flash_pic", "闪照": "flash_pic", "闪照破解": "flash_pic",
    # script
    "script": "script", "scripts": "script", "脚本": "script", "动态脚本": "script", "bsh": "script", "qfun": "script", "plugin": "script", "plugins": "script",
    # setting
    "setting": "setting", "settings": "setting", "设置": "setting", "设置中心": "setting", "zzz": "setting",
    # tablet
    "tablet": "tablet", "pad": "tablet", "平板": "tablet", "平板模式": "tablet",
    # multi_window
    "multi_window": "multi_window", "split": "multi_window", "分屏": "multi_window", "多窗口": "multi_window",
    # group_file
    "group_file": "group_file", "群文件": "group_file", "下载次数": "group_file",
    # troop_todo
    "troop_todo": "troop_todo", "todo": "troop_todo", "群待办": "troop_todo",
    # block_at_all
    "block_at_all": "block_at_all", "at_all": "block_at_all", "全体": "block_at_all", "@全体": "block_at_all",
    # auto_remark_apk
    "auto_remark_apk": "auto_remark_apk", "apk": "auto_remark_apk", "apk重命名": "auto_remark_apk",
    # modify_pic_summary
    "modify_pic_summary": "modify_pic_summary", "pic_summary": "modify_pic_summary", "外显": "modify_pic_summary", "图片外显": "modify_pic_summary",
    # tg_stickers
    "tg_stickers": "tg_stickers", "sticker": "tg_stickers", "stickers": "tg_stickers", "表情包": "tg_stickers", "tg表情": "tg_stickers",
    # browser
    "browser": "browser", "网页拦截": "browser", "浏览器": "browser",
    # meow
    "meow": "meow", "喵喵": "meow", "喵喵助手": "meow",
    # floating_ball
    "floating_ball": "floating_ball", "ball": "floating_ball", "悬浮球": "floating_ball",
    # chat_history
    "chat_history": "chat_history", "history": "chat_history", 
    "聊天记录": "chat_history", "历史记录": "chat_history",
    # qq_version
    "qq_version": "qq_version", "version": "qq_version", "版本": "qq_version", "qq版本": "qq_version",
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