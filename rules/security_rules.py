# -*- coding: utf-8 -*-
"""安全穿透规则"""

import struct
from .stubs import stub_void
from .parser import FastDexParser

RULE_ID = "security"
RULE_NAME = "安全风控与查签致盲"
RULE_DESC = "安全风控与查签致盲"
RULE_ALIASES = ["sec", "安全", "查签", "风控"]
RULE_ENABLED = True

def build_security_rules(dex_data_dict, orig_apk_md5="", orig_sig_md5=""):
    sec_rules = []
    parsers = [FastDexParser(v) for v in dex_data_dict.values() if len(v) >= 0x70 and v[:4] == b'dex\n']

    chan_rep_cls = "Lcom/tencent/mobileqq/channel/ChannelReport;"
    for p in parsers:
        c_idx = p.find_class_index(chan_rep_cls)
        if c_idx != -1:
            sso_event_id = p.find_string_id("trpc.o3.report.Report.SsoEventReport")
            if sso_event_id != -1:
                s_16 = struct.pack('<H', sso_event_id) if sso_event_id <= 65535 else None
                s_32 = struct.pack('<I', sso_event_id)
                for m_name, proto, is_virt, code_off, access_flags in p.get_class_methods(c_idx):
                    if code_off != 0 and code_off + 16 < len(p.data):
                        insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
                        insns = p.data[code_off + 16: code_off + 16 + insns_size * 2]
                        if (s_16 and s_16 in insns) or (s_32 in insns):
                            is_static = bool(access_flags & 0x0008)
                            is_priv = bool(access_flags & 0x0002)
                            sec_rules.append(stub_void(
                                chan_rep_cls,
                                f"{m_name}{proto}",
                                is_static=is_static,
                                is_private=is_priv,
                                regs=4,
                                name=f"动态阻断通道事件上报 ({m_name})"
                            ))
            break

    sec_rules.append(
        stub_void(
            "Lcom/tencent/mobileqq/statistics/QQBeaconReport;",
            "setBeaconPrivacyInfo()V",
            is_static=True,
            regs=1,
            name="阻断灯塔 Beacon 硬件指纹收集"
        )
    )

    return sec_rules

def resolve_rules(dex_data_dict, meta=None):
    meta = meta or {}
    return build_security_rules(dex_data_dict, meta.get("orig_apk_md5", ""), meta.get("orig_sig_md5", ""))
