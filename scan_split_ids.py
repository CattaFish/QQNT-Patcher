# scan_split_ids.py
import zipfile
import re
import struct
from rules.parser import FastDexParser

target_ids = [
    0x7f12021d, 0x7f122d05, 0x7f125105, 0x7f125165,
    0x7f125177, 0x7f125eeb, 0x7f126226, 0x7f126228,
    0x7f126641, 0x7f126701, 0x7f126703, 0x7f126707
]

id_patterns = {struct.pack('<I', i): f"0x{i:08x}" for i in target_ids}
apk_path = "QQ.apk"

print(f"正在全包检索引用了这 12 个分屏提示 ID 的代码...")

with zipfile.ZipFile(apk_path, 'r') as zf:
    for name in zf.namelist():
        if re.match(r'^classes\d*\.dex$', name):
            dex_bytes = zf.read(name)
            # 快速预检当前 dex 是否包含任何目标 ID 字节
            if not any(pat in dex_bytes for pat in id_patterns.keys()):
                continue

            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            for c_idx in range(p.class_defs_size):
                class_idx = struct.unpack_from('<I', p.data, p.class_defs_off + c_idx * 32)[0]
                cls_name = p.get_type_str(class_idx)

                methods = p.get_class_methods(c_idx)
                for m_name, proto_desc, _, code_off, _ in methods:
                    if code_off == 0 or code_off + 16 >= len(p.data):
                        continue
                    insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
                    insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]

                    for pat, hex_id in id_patterns.items():
                        if pat in insns:
                            print(f"[精准命中] {name} | 资源: {hex_id}")
                            print(f"  ├─ 类名: {cls_name}")
                            print(f"  └─ 方法: {m_name}{proto_desc}")

print("扫描完毕！")