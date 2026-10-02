# -*- coding: utf-8 -*-
"""全套反外挂、防篡改、风控对齐规则"""

import struct
from .stubs import stub_void, stub_ret
from .parser import FastDexParser

def build_security_rules(dex_data_dict, orig_apk_md5="", orig_sig_md5=""):
    sec_rules = []
    parsers = [FastDexParser(v) for v in dex_data_dict.values() if len(v) >= 0x70 and v[:4] == b'dex\n']

    # 1. 动态嗅探主查签 A (ctsz.m)
    for p in parsers:
        for cls_name, cls_idx in p.find_classes_referencing_type_strictly("Lcom/tencent/ims/signature$SignatureReport;"):
            for m_name, proto, _, _, _ in p.get_class_methods(cls_idx):
                if proto == "()V" and m_name not in ("<init>", "<clinit>"):
                    sec_rules.append(stub_void(cls_name, f"{m_name}()V", name=f"致盲主查签 A ({cls_name}->{m_name})"))
                elif proto == "(Ljava/lang/Object;Ljava/lang/Object;)V":
                    sec_rules.append(stub_void(cls_name, f"{m_name}(Ljava/lang/Object;Ljava/lang/Object;)V", regs=3, name=f"阻断查签回调 A ({cls_name}->{m_name})"))

    # 2. 动态嗅探主查签 B (mezs.a)
    for p in parsers:
        for cls_name, cls_idx in p.find_classes_referencing_string_strictly("SecMd5Entry"):
            for m_name, proto, _, _, _ in p.get_class_methods(cls_idx):
                if proto == "(Z)V":
                    sec_rules.append(stub_void(cls_name, f"{m_name}(Z)V", regs=2, name=f"致盲主查签 B ({cls_name}->{m_name})"))
                elif proto == "(Ljava/lang/String;)V":
                    sec_rules.append(stub_void(cls_name, f"{m_name}(Ljava/lang/String;)V", regs=2, name=f"阻断主查签 B 回调 ({cls_name}->{m_name})"))

    # 3. 动态嗅探完整性打击器 (MSFIntChkStrike)
    for p in parsers:
        for cls_name, cls_idx in p.find_classes_referencing_string_strictly("strike_result"):
            for m_name, proto, _, _, _ in p.get_class_methods(cls_idx):
                if "secsrv" in proto:
                    sec_rules.append(stub_void(cls_name, f"{m_name}{proto}", regs=3, name=f"阻断完整性打击惩罚 ({cls_name}->{m_name})"))

    # 4. 动态嗅探云控账号获取方法
    united_a_cls = "Lcom/tencent/mobileqq/unitedconfig_android/api/impl/UnitedConfigManagerImpl$a;"
    for p in parsers:
        c_idx = p.find_class_index(united_a_cls)
        if c_idx != -1:
            for m_name, proto, _, _, _ in p.get_class_methods(c_idx):
                if proto == "()Ljava/lang/String;" and m_name not in ("<init>", "<clinit>"):
                    sec_rules.append(stub_ret(united_a_cls, f"{m_name}()Ljava/lang/String;", 'const-string v0, ""\n    return-object v0', name=f"脱敏云控账号参数 ({m_name})"))
            break

    # 5. 动态对齐 QSecConfig 设备与账号指纹脱敏
    qsec_cfg_cls = "Lcom/tencent/mobileqq/qsec/qsecurity/QSecConfig;"
    qsec_proto = "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
    for p in parsers:
        c_idx = p.find_class_index(qsec_cfg_cls)
        if c_idx != -1:
            for m_name, proto, _, _, _ in p.get_class_methods(c_idx):
                if proto == qsec_proto:
                    sec_rules.append({
                        "name": "脱敏 QSec 设备指纹并注入假 UIN",
                        "target_class": qsec_cfg_cls,
                        "target_method": f"{m_name}{proto}",
                        "type": "REPLACE",
                        "smali": f""".method public static {m_name}{proto}
    .registers 7
    sput-object p0, Lcom/tencent/mobileqq/qsec/qsecurity/QSecConfig;->sContext:Landroid/content/Context;
    const-string p1, "12345678901"
    sput-object p1, Lcom/tencent/mobileqq/qsec/qsecurity/QSecConfig;->business_uin:Ljava/lang/String;
    return-void
.end method"""
                    })
            break

    # 6. 动态嗅探 ChannelReport 中上报 SsoEventReport 的真实方法
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

    # 7. 动态嗅探 FEKit 外挂、ClassLoader 与内存 Dex 探针
    for p in parsers:
        for cls_name, cls_idx in p.find_classes_referencing_string_strictly("Err Code: 101"):
            for m_name, proto, _, _, access_flags in p.get_class_methods(cls_idx):
                if proto == "(Landroid/content/Context;)Z" and (access_flags & 0x0008):
                    sec_rules.append(stub_ret(
                        cls_name,
                        f"{m_name}(Landroid/content/Context;)Z",
                        "const/4 v0, 0\n    return v0",
                        is_static=True,
                        name=f"动态致盲 FEKit 环境与外挂检测 ({cls_name}->{m_name})"
                    ))

    # 8. 动态嗅探涉诈敏感消息本地扫描
    for p in parsers:
        for i in range(p.class_defs_size):
            c_idx = struct.unpack_from('<I', p.data, p.class_defs_off + i * 32)[0]
            cls_name = p.get_type_str(c_idx)
            if cls_name.startswith("Lcom/tencent/mqp/app/sec/"):
                for m_name, proto, _, _, access_flags in p.get_class_methods(i):
                    if proto == "(Lcom/tencent/mobileqq/data/MessageRecord;Ljava/util/List;Z[B)V" and (access_flags & 0x0008):
                        sec_rules.append(stub_void(
                            cls_name,
                            f"{m_name}{proto}",
                            is_static=True,
                            regs=4,
                            name=f"动态静默敏感消息涉诈扫描 ({cls_name}->{m_name})"
                        ))

    # 9. 动态嗅探官方原版 APK MD5 计算器
    if orig_apk_md5:
        for p in parsers:
            for i in range(p.class_defs_size):
                c_idx = struct.unpack_from('<I', p.data, p.class_defs_off + i * 32)[0]
                cls_name = p.get_type_str(c_idx)
                if cls_name.startswith("Lcom/tencent/mdm/"):
                    for m_name, proto, _, _, access_flags in p.get_class_methods(i):
                        if proto == "(Ljava/lang/String;)Ljava/lang/String;" and (access_flags & 0x0008):
                            sec_rules.append(stub_ret(
                                cls_name,
                                f"{m_name}(Ljava/lang/String;)Ljava/lang/String;",
                                f'const-string v0, "{orig_apk_md5}"\n    return-object v0',
                                is_static=True,
                                name=f"动态固化官方原版 APK MD5 ({cls_name}->{m_name})"
                            ))

    # 10. ★ 全动态定位 AntEst 定时器方法 (包含 0x05265c00 即 \x00\x5c\x26\x05 的方法)
    antest_cls = "Lcom/tencent/qqprotect/xps/core/AntEst;"
    for p in parsers:
        c_idx = p.find_class_index(antest_cls)
        if c_idx != -1:
            for m_name, proto_desc, is_virt, code_off, access_flags in p.get_class_methods(c_idx):
                if code_off != 0 and code_off + 16 < len(p.data):
                    insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
                    insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]
                    if b"\x00\x5c\x26\x05" in insns:
                        target_m = f"{m_name}{proto_desc}"
                        sec_rules.append({
                            "name": f"极化 AntEst 定时器 (动态定位: {m_name})",
                            "target_class": antest_cls,
                            "target_method": target_m,
                            "type": "REGEX_REPLACE",
                            "regex": r"const-wide/32\s+([vp]\d+),\s+0x5265c00",
                            "smali": "const-wide \\1, 0x7dba8218000L"
                        })
                        break
            break

    # 11. 纯正官方明确公开类与常量规则
    static_rules = [
        stub_ret("Lcom/tencent/mobileqq/app/automator/step/SignatureScan;", "doStep()I", "const/4 v0, 0x7\n    return v0", name="旁路启动自检 SignatureScan"),
        stub_ret("Lcom/tencent/mobileqq/app/automator/step/CheckSafeCenterConfig;", "doStep()I", "const/4 v0, 0x7\n    return v0", name="旁路启动自检 CheckSafeCenterConfig"),
        stub_ret("Lcom/tencent/mobileqq/dt/api/impl/QSecChannelImpl;", "reportEnable()Z", "const/4 v0, 0\n    return v0", name="强制关闭风控信道"),
        stub_ret("Lcom/tencent/mobileqq/app/QQAppInterface;", "isNeedSecurityScan()Z", "const/4 v0, 0\n    return v0", name="写死关闭本地安全扫描"),
        stub_void("Lcom/tencent/mobileqq/perf/memory/dump/MemoryFile;", "b()V", is_private=True, regs=1, name="阻断内存篡改转储"),
        stub_void("Lcom/tencent/mobileqq/statistics/QQBeaconReport;", "setBeaconPrivacyInfo()V", is_static=True, regs=1, name="阻断灯塔 Beacon 硬件指纹收集"),
        stub_ret("Lcom/tencent/mobileqq/channel/ChannelReport;", "isReportOnceOfDay(Ljava/lang/String;)Z", "const/4 v0, 0\n    return v0", is_static=True, name="禁用通道风控周期判定"),
        stub_void("Lcom/tencent/mobileqq/channel/ChannelReport;", "commonReport(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V", is_static=True, regs=4, name="阻断通道单条风控上报"),
        stub_void("Lcom/tencent/mobileqq/channel/ChannelReport;", "batchCommonReport(Ljava/lang/String;[Ljava/lang/String;[[Ljava/lang/String;)V", is_static=True, regs=4, name="阻断通道批量风控上报"),
        stub_ret("Lcom/tencent/mobileqq/app/qfix/ApplicationDelegate;", "getPackageCodePath()Ljava/lang/String;", "const/4 v0, 0\n    return-object v0", name="脱敏安装包物理路径"),
        stub_ret("Lcom/tencent/mobileqq/unitedconfig_android/api/impl/UnitedConfigManagerImpl;", "getUpdateInterval()J", "const-wide v0, 0xc92a69c000L\n    return-wide v0", is_private=True, regs=3, name="极化统一云控拉取间隔"),
        stub_ret("Lcom/tencent/mobileqq/app/identity/impl/SafeApiImpl;", "getUpdatePwdUrl(Ljava/lang/String;)Ljava/lang/String;", "const/4 v0, 0\n    return-object v0", regs=3, name="阻断安全中心密码配置查询"),
        stub_void("Lcom/tencent/qqprotect/ganliang/Ganliang;", "h()V", regs=1, name="阻断 Ganliang 投屏探测与防截屏黑屏")
    ]

    if orig_sig_md5:
        static_rules.append(
            stub_ret(
                "Lcom/tencent/mobileqq/utils/SecUtil;", 
                "getSignatureHash([B)Ljava/lang/String;", 
                f'const-string v0, "{orig_sig_md5}"\n    return-object v0', 
                is_static=True, 
                name="固化返回官方原版签名 Hash"
            )
        )

    sec_rules.extend(static_rules)
    return sec_rules
# === 规则插件契约 ===
RULE_ID = "security"
RULE_NAME = "安全穿透规则"
RULE_ENABLED = True

def resolve_rules(dex_data_dict, meta=None):
    meta = meta or {}
    return build_security_rules(dex_data_dict, meta.get("orig_apk_md5", ""), meta.get("orig_sig_md5", ""))
