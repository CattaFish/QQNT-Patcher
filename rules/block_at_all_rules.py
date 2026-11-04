# -*- coding: utf-8 -*-
RULE_ID = "block_at_all"
RULE_NAME = "屏蔽群聊 @全体成员"
RULE_DESC = "过滤群聊中 @全体成员 的强提醒与横幅通知"
RULE_ALIASES = ["at_all", "全体", "@全体"]
RULE_ENABLED = True

def resolve_rules(dex_data_dict, meta=None):
    return []