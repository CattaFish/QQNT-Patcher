# -*- coding: utf-8 -*-
"""
探测官方 AIOLeftSwipeAreaComponent 与 AIOContentLeftSwipeHelper
"""

import zipfile
import re
import struct
from rules.parser import FastDexParser

APK_PATH = "QQ.apk"
TARGETS = [
    ("classes6.dex", "leftswipearea/AIOLeftSwipeAreaComponent"),
    ("classes9.dex", "leftswipearea/AIOContentLeftSwipeHelper"),
]

def dump_methods(p, class_idx, cls_name):
    print(f"\n=======================================================")
    print(f"类名: {cls_name}")
    print(f"=======================================================")
    methods = p.get_class_methods(class_idx)
    for m_name, proto_desc, _, code_off, _ in methods:
        print(f"  ▶ 方法: {m_name}{proto_desc}")
        if code_off != 0 and code_off + 16 < len(p.data):
            insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
            insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]
            calls = set()
            k = 0
            len_insns = len(insns)
            while k < len_insns - 3:
                op = insns[k]
                if op in (0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
                    m_idx = struct.unpack_from('<H', insns, k + 2)[0]
                    if m_idx < p.method_ids_size:
                        c_idx_call, proto_idx_call, name_idx_call = struct.unpack_from('<HHI', p.data, p.method_ids_off + m_idx * 8)
                        called_cls = p.get_type_str(c_idx_call)
                        called_name = p.get_string(name_idx_call)
                        called_proto = p.get_proto_desc(proto_idx_call)
                        if any(tag in called_cls.lower() for tag in ["swipe", "reply", "intent", "msg", "aio"]):
                            calls.add(f"{called_cls}->{called_name}{called_proto}")
                k += 2
            for c in sorted(calls)[:6]:
                print(f"       ↳ 调用: {c}")

with zipfile.ZipFile(APK_PATH, 'r') as zf:
    for dex_name, keyword in TARGETS:
        dex_bytes = zf.read(dex_name)
        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue
        for c_idx in range(p.class_defs_size):
            class_idx = struct.unpack_from('<I', p.data, p.class_defs_off + c_idx * 32)[0]
            cls_name = p.get_type_str(class_idx)
            if keyword in cls_name and "$" not in cls_name:
                dump_methods(p, c_idx, cls_name)

print("\n[*] 探测完毕！")