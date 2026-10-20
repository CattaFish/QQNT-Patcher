# -*- coding: utf-8 -*-
"""FastDexParser: DEX 字节码流式解析引擎与语义扫描器 (高性能优化版)"""
import struct

class FastDexParser:
    def __init__(self, data: bytes):
        self.data = data
        self.valid = len(data) >= 0x70 and data[:4] == b'dex\n'
        if not self.valid: return
        self.string_ids_size = struct.unpack_from('<I', data, 0x38)[0]
        self.string_ids_off = struct.unpack_from('<I', data, 0x3C)[0]
        self.type_ids_size, self.type_ids_off = struct.unpack_from('<II', data, 0x40)
        self.proto_ids_size, self.proto_ids_off = struct.unpack_from('<II', data, 0x48)
        self.method_ids_size, self.method_ids_off = struct.unpack_from('<II', data, 0x58)
        self.class_defs_size, self.class_defs_off = struct.unpack_from('<II', data, 0x60)

        # 内存局部热点缓存，避免重复解码相同的类名、方法名与原型
        self._str_cache = {}
        self._type_cache = {}
        self._proto_cache = {}

    def get_string(self, str_idx: int) -> str:
        if str_idx >= self.string_ids_size: return ""
        cached = self._str_cache.get(str_idx)
        if cached is not None:
            return cached

        str_off = struct.unpack_from('<I', self.data, self.string_ids_off + str_idx * 4)[0]
        p = str_off
        while self.data[p] & 0x80: p += 1
        p += 1
        end = self.data.find(b'\x00', p)
        res = self.data[p:end].decode('utf-8', errors='ignore') if end != -1 else ""
        self._str_cache[str_idx] = res
        return res

    def get_type_str(self, type_idx: int) -> str:
        if type_idx >= self.type_ids_size: return ""
        cached = self._type_cache.get(type_idx)
        if cached is not None:
            return cached

        desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + type_idx * 4)[0]
        res = self.get_string(desc_idx)
        self._type_cache[type_idx] = res
        return res

    def get_proto_desc(self, proto_idx: int) -> str:
        if proto_idx >= self.proto_ids_size: return ""
        cached = self._proto_cache.get(proto_idx)
        if cached is not None:
            return cached

        _, return_type_idx, parameters_off = struct.unpack_from('<III', self.data, self.proto_ids_off + proto_idx * 12)
        ret_type = self.get_type_str(return_type_idx)
        param_types = []
        if parameters_off != 0:
            size = struct.unpack_from('<I', self.data, parameters_off)[0]
            for i in range(size):
                t_idx = struct.unpack_from('<H', self.data, parameters_off + 4 + i * 2)[0]
                param_types.append(self.get_type_str(t_idx))
        res = f"({''.join(param_types)}){ret_type}"
        self._proto_cache[proto_idx] = res
        return res

    def read_uleb128(self, pos):
        result, shift = 0, 0
        while True:
            b = self.data[pos]
            pos += 1
            result |= (b & 0x7F) << shift
            if (b & 0x80) == 0: break
            shift += 7
        return result, pos

    def find_string_id(self, target: str) -> int:
        """基于 DEX 规范强制 UTF-16/字典序升序特性的二分查找 (O(log N))"""
        if not self.valid or self.string_ids_size == 0:
            return -1
        low, high = 0, self.string_ids_size - 1
        while low <= high:
            mid = (low + high) >> 1
            mid_str = self.get_string(mid)
            if mid_str == target:
                return mid
            elif mid_str < target:
                low = mid + 1
            else:
                high = mid - 1
        return -1

    def find_type_id(self, target_type: str) -> int:
        """先二分查找 string_id，再二分查找 type_ids (底层 uint32 递增数组，0 字符串解码)"""
        if not self.valid or self.type_ids_size == 0:
            return -1
        s_id = self.find_string_id(target_type)
        if s_id == -1:
            return -1

        low, high = 0, self.type_ids_size - 1
        while low <= high:
            mid = (low + high) >> 1
            desc_idx = struct.unpack_from('<I', self.data, self.type_ids_off + mid * 4)[0]
            if desc_idx == s_id:
                return mid
            elif desc_idx < s_id:
                low = mid + 1
            else:
                high = mid - 1
        return -1

    def find_class_index(self, target_type_str: str) -> int:
        """先定位 type_id，直接比对 4 字节整数，消除几千个类的字符串解码损耗"""
        if not self.valid or self.class_defs_size == 0:
            return -1
        t_id = self.find_type_id(target_type_str)
        if t_id == -1:
            return -1

        for i in range(self.class_defs_size):
            c_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            if c_idx == t_id:
                return i
        return -1

    def get_class_methods(self, class_idx: int):
        methods = []
        c_off = struct.unpack_from('<I', self.data, self.class_defs_off + class_idx * 32 + 24)[0]
        if c_off == 0: return methods
        p = c_off
        s_f, p = self.read_uleb128(p); i_f, p = self.read_uleb128(p)
        d_m, p = self.read_uleb128(p); v_m, p = self.read_uleb128(p)
        for _ in range((s_f + i_f) * 2): _, p = self.read_uleb128(p)

        m_idx = 0
        for _ in range(d_m):
            diff, p = self.read_uleb128(p)
            m_idx += diff
            access_flags, p = self.read_uleb128(p)
            code_off, p = self.read_uleb128(p)
            _, proto_idx, name_idx = struct.unpack_from('<HHI', self.data, self.method_ids_off + m_idx * 8)
            methods.append((self.get_string(name_idx), self.get_proto_desc(proto_idx), False, code_off, access_flags))

        m_idx = 0
        for _ in range(v_m):
            diff, p = self.read_uleb128(p)
            m_idx += diff
            access_flags, p = self.read_uleb128(p)
            code_off, p = self.read_uleb128(p)
            _, proto_idx, name_idx = struct.unpack_from('<HHI', self.data, self.method_ids_off + m_idx * 8)
            methods.append((self.get_string(name_idx), self.get_proto_desc(proto_idx), True, code_off, access_flags))

        return methods

    def find_setting_config_info(self):
        target_super = "Lcom/tencent/mobileqq/setting/processor/SettingConfigProvider;"
        target_super_idx = self.find_type_id(target_super)
        if target_super_idx == -1:
            return None, None

        matching_proto_indices = {p for p in range(self.proto_ids_size) if self.get_proto_desc(p) == "(Landroid/content/Context;)Ljava/util/List;"}
        if not matching_proto_indices: return None, None

        for i in range(self.class_defs_size):
            super_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 8)[0]
            # 纯整数匹配目标父类
            if super_idx == target_super_idx:
                class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
                cls_name = self.get_type_str(class_idx)
                if cls_name.startswith("Lcom/tencent/mobileqq/setting/main/"):
                    for m_name, proto_desc, _, _, _ in self.get_class_methods(i):
                        if proto_desc == "(Landroid/content/Context;)Ljava/util/List;":
                            return cls_name, f"{m_name}(Landroid/content/Context;)Ljava/util/List;"
        return None, None

    def find_simple_item_class(self):
        if b"SimpleItemProcessor" not in self.data:
            return None

        target_str_ids = {s for s in range(self.string_ids_size) if "SimpleItemProcessor" in self.get_string(s)}
        if not target_str_ids: return None

        for i in range(self.class_defs_size):
            c_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            d_idx = struct.unpack_from('<I', self.data, self.type_ids_off + c_idx * 4)[0]
            if d_idx in target_str_ids: return self.get_string(d_idx)[1:-1].replace('/', '.')
            s_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 8)[0]
            if s_idx < self.type_ids_size:
                sd_idx = struct.unpack_from('<I', self.data, self.type_ids_off + s_idx * 4)[0]
                if sd_idx in target_str_ids: return self.get_string(d_idx)[1:-1].replace('/', '.')
            src_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 16)[0]
            if src_idx in target_str_ids: return self.get_string(d_idx)[1:-1].replace('/', '.')

        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            class_data_off = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 24)[0]
            if class_data_off == 0: continue
            p = class_data_off
            s_f, p = self.read_uleb128(p); i_f, p = self.read_uleb128(p)
            d_m, p = self.read_uleb128(p); v_m, p = self.read_uleb128(p)
            for _ in range((s_f + i_f) * 2): _, p = self.read_uleb128(p)
            for _ in range(d_m + v_m):
                _, p = self.read_uleb128(p); _, p = self.read_uleb128(p)
                code_off, p = self.read_uleb128(p)
                if code_off != 0 and code_off < len(self.data):
                    insns_size = struct.unpack_from('<I', self.data, code_off + 12)[0]
                    insns_start = code_off + 16
                    for k in range(insns_size):
                        if insns_start + k * 2 + 4 > len(self.data): break
                        opcode = self.data[insns_start + k * 2]
                        if opcode in (0x1A, 0x1B):
                            ref_idx = struct.unpack_from('<H', self.data, insns_start + k * 2 + 2)[0]
                            if ref_idx in target_str_ids:
                                d_idx = struct.unpack_from('<I', self.data, self.type_ids_off + class_idx * 4)[0]
                                return self.get_string(d_idx)[1:-1].replace('/', '.')
        return None

    def find_classes_referencing_string_strictly(self, target_str: str):
        if not self.valid: return []
        s_id = self.find_string_id(target_str)
        if s_id == -1: return []

        matched_classes = []
        EXCLUDE = ("/nearby/", "/fragment/", "/viewmodel/", "/ui/", "/widget/", "/adapter/", "/facetoface/", "/qcall/", "/relation/", "/share/")
        str_bytes_16 = struct.pack('<H', s_id) if s_id <= 65535 else None
        str_bytes_32 = struct.pack('<I', s_id)

        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            cls_name = self.get_type_str(class_idx)
            if any(tag in cls_name for tag in EXCLUDE): continue

            class_data_off = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 24)[0]
            if class_data_off == 0: continue

            methods = self.get_class_methods(i)
            found = False
            for _, _, _, code_off, _ in methods:
                if code_off != 0 and code_off + 16 < len(self.data):
                    insns_size = struct.unpack_from('<I', self.data, code_off + 12)[0]
                    insns = self.data[code_off + 16: code_off + 16 + insns_size * 2]

                    # ★ 优化点：利用 C 底层滑窗预检，绝大多数不包含该字符串 ID 的方法直接在纳秒内跳过
                    if (str_bytes_16 and str_bytes_16 not in insns) and (str_bytes_32 not in insns):
                        continue

                    k = 0
                    len_insns = len(insns)
                    while k < len_insns - 1:
                        op = insns[k]
                        if op == 0x1A and k + 4 <= len_insns:
                            if str_bytes_16 and insns[k + 2 : k + 4] == str_bytes_16:
                                found = True; break
                            k += 4; continue
                        elif op == 0x1B and k + 6 <= len_insns:
                            if insns[k + 2 : k + 6] == str_bytes_32:
                                found = True; break
                            k += 6; continue
                        k += 2
                if found: break
            if found: matched_classes.append((cls_name, i))
        return matched_classes

    def find_classes_referencing_type_strictly(self, target_type_str: str):
        if not self.valid: return []
        t_id = self.find_type_id(target_type_str)
        if t_id == -1: return []

        matched_classes = []
        t_pat = struct.pack('<H', t_id)

        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32)[0]
            cls_name = self.get_type_str(class_idx)
            if "com/tencent/ims" in cls_name: continue

            class_data_off = struct.unpack_from('<I', self.data, self.class_defs_off + i * 32 + 24)[0]
            if class_data_off == 0: continue

            methods = self.get_class_methods(i)
            found = False
            for _, _, _, code_off, _ in methods:
                if code_off != 0 and code_off + 16 < len(self.data):
                    insns_size = struct.unpack_from('<I', self.data, code_off + 12)[0]
                    insns = self.data[code_off + 16: code_off + 16 + insns_size * 2]

                    # ★ 优化点：预检当前方法指令是否包含目标类型的 2 字节 ID
                    if t_pat not in insns:
                        continue

                    k = 0
                    while k < len(insns) - 3:
                        op = insns[k]
                        if (op in (0x1F, 0x20, 0x22)) and insns[k + 2 : k + 4] == t_pat:
                            found = True
                            break
                        k += 2
                if found: break
            if found: matched_classes.append((cls_name, i))
        return matched_classes