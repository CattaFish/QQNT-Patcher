# -*- coding: utf-8 -*-
"""
安全穿透规则 (完全对齐 all_modified.diff 策略)
证书及整包校验交由底层的 Killer 处理，Java 层仅保留 diff 中实际修改的 2 项风控与硬件隐私上报拦截。
"""

import struct
from .stubs import stub_void
from .parser import FastDexParser

def build_security_rules(dex_data_dict, orig_apk_md5="", orig_sig_md5=""):
    sec_rules = []
    parsers = [FastDexParser(v) for v in dex_data_dict.values() if len(v) >= 0x70 and v[:4] == b'dex\n']

    # =========================================================================
    # 【对齐 diff 第 1 处】ChannelReport: 阻断 trpc.o3.report.Report.SsoEventReport 通道风控上报
    # diff 对应: com/tencent/mobileqq/channel/ChannelReport.smali
    #            删除了 ChannelManager->sendMessage(trpc.o3.report.Report.SsoEventReport, ...)
    # =========================================================================
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

    # =========================================================================
    # 【对齐 diff 第 2 处】QQBeaconReport: 阻断灯塔 Beacon 硬件与设备指纹收集
    # diff 对应: com/tencent/mobileqq/statistics/QQBeaconReport.smali
    #            清空 setBeaconPrivacyInfo()V 方法体，直接 return-void
    # =========================================================================
    sec_rules.append(
        stub_void(
            "Lcom/tencent/mobileqq/statistics/QQBeaconReport;",
            "setBeaconPrivacyInfo()V",
            is_static=True,
            regs=1,
            name="阻断灯塔 Beacon 硬件指纹收集"
        )
    )

    # =========================================================================
    # 以下规则在 all_modified.diff 中均未被修改，全部注释停用：
    # 依靠 Killer 运行时欺骗，避免激进掏空触发腾讯云端特征风控
    # =========================================================================
    """
    # 1. 动态嗅探主查签 A (SignatureReport)
    # 2. 动态嗅探主查签 B (SecMd5Entry)
    # 3. 完整性打击器 (MSFIntChkStrike)
    # 4. 云控账号参数 (UnitedConfigManagerImpl$a)
    # 5. QSecConfig 注入假 UIN
    # 6. FEKit 探针致盲 (Err Code: 101)
    # 7. 涉诈敏感消息本地扫描 (mqp/app/sec)
    # 8. 官方 MD5 替换 (mdm)
    # 9. AntEst 定时器极化
    # 10. 启动自检 (SignatureScan / CheckSafeCenterConfig)
    # 11. 风控信道 (QSecChannelImpl.reportEnable)
    # 12. 本地安全扫描 (QQAppInterface.isNeedSecurityScan)
    # 13. 内存转储 (MemoryFile.b)
    # 14. 通道风控周期与单条上报 (ChannelReport.isReportOnceOfDay/commonReport/batchCommonReport)
    # 15. 安装包物理路径脱敏 (ApplicationDelegate.getPackageCodePath)
    # 16. 云控拉取间隔 (UnitedConfigManagerImpl.getUpdateInterval)
    # 17. 安全中心密码配置查询 (SafeApiImpl.getUpdatePwdUrl)
    # 18. 防截屏/投屏探测 (Ganliang.h)
    # 19. SecUtil 签名 Hash 写死 (SecUtil.getSignatureHash)
    """

    return sec_rules

# === 规则插件契约 ===
RULE_ID = "security"
RULE_NAME = "安全穿透规则"
RULE_ENABLED = True

def resolve_rules(dex_data_dict, meta=None):
    meta = meta or {}
    return build_security_rules(dex_data_dict, meta.get("orig_apk_md5", ""), meta.get("orig_sig_md5", ""))