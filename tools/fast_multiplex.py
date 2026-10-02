#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os, struct, sys, zlib, zipfile

INPUT_APK_ENTRY = b"assets/Zcraft/input.apk"

def find_eocd(data):
    for i in range(len(data) - 22, max(0, len(data) - 65557) - 1, -1):
        if data[i:i + 4] == b"PK\x05\x06": return i
    return -1

def parse_cd(data, cd_offset, cd_size):
    entries = []
    off = cd_offset
    end = cd_offset + cd_size
    while off + 46 <= end:
        if data[off:off + 4] != b"PK\x01\x02": break
        flags = struct.unpack_from("<H", data, off + 8)[0]
        method = struct.unpack_from("<H", data, off + 10)[0]
        crc = struct.unpack_from("<I", data, off + 16)[0]
        comp_size = struct.unpack_from("<I", data, off + 20)[0]
        uncomp_size = struct.unpack_from("<I", data, off + 24)[0]
        nl = struct.unpack_from("<H", data, off + 28)[0]
        el = struct.unpack_from("<H", data, off + 30)[0]
        cl = struct.unpack_from("<H", data, off + 32)[0]
        lho = struct.unpack_from("<I", data, off + 42)[0]
        name = data[off + 46:off + 46 + nl]
        entries.append({
            "name": name,
            "name_str": name.decode("utf-8", "replace"),
            "method": method, "flags": flags, "crc": crc,
            "comp_size": comp_size, "uncomp_size": uncomp_size,
            "local_offset": lho,
            "raw": data[off:off + 46 + nl + el + cl]
        })
        off += 46 + nl + el + cl
    return entries

def build_lh(name_bytes, method, crc, comp_size, uncomp_size, start_offset, align=True):
    name_len = len(name_bytes)
    extra_len = 0
    if align and method == 0:
        need = (start_offset + 30 + name_len) % 4
        if need: extra_len = 4 - need
    lh = bytearray(30 + name_len + extra_len)
    lh[0:4] = b"PK\x03\x04"
    struct.pack_into("<H", lh, 4, 20)
    struct.pack_into("<H", lh, 6, 0)
    struct.pack_into("<H", lh, 8, method)
    struct.pack_into("<I", lh, 14, crc)
    struct.pack_into("<I", lh, 18, comp_size)
    struct.pack_into("<I", lh, 22, uncomp_size)
    struct.pack_into("<H", lh, 26, name_len)
    struct.pack_into("<H", lh, 28, extra_len)
    lh[30:30 + name_len] = name_bytes
    return bytes(lh)

def build_ce(name_bytes, method, crc, comp_size, uncomp_size, offset):
    name_len = len(name_bytes)
    ce = bytearray(46 + name_len)
    ce[0:4] = b"PK\x01\x02"
    struct.pack_into("<H", ce, 4, 20)
    struct.pack_into("<H", ce, 6, 20)
    struct.pack_into("<H", ce, 8, 0)
    struct.pack_into("<H", ce, 10, method)
    struct.pack_into("<I", ce, 16, crc)
    struct.pack_into("<I", ce, 20, comp_size)
    struct.pack_into("<I", ce, 24, uncomp_size)
    struct.pack_into("<H", ce, 28, name_len)
    struct.pack_into("<I", ce, 42, offset)
    ce[46:46 + name_len] = name_bytes
    return bytes(ce)

def build_eocd(cd_offset, cd_size, total):
    e = bytearray(22)
    e[0:4] = b"PK\x05\x06"
    struct.pack_into("<H", e, 8, total)
    struct.pack_into("<H", e, 10, total)
    struct.pack_into("<I", e, 12, cd_size)
    struct.pack_into("<I", e, 16, cd_offset)
    return bytes(e)

def create_multiplexed_apk(orig_apk, out_apk, injected_files):
    orig_size = os.path.getsize(orig_apk)
    crc = 0
    with open(orig_apk, "rb") as f:
        while chunk := f.read(131072):
            crc = zlib.crc32(chunk, crc)
    orig_crc = crc & 0xffffffff

    with open(orig_apk, "rb") as f:
        f.seek(max(0, orig_size - 65557))
        tail = f.read()
    eocd_pos = max(0, orig_size - 65557) + find_eocd(tail)
    with open(orig_apk, "rb") as f:
        f.seek(eocd_pos + 12)
        cd_size, cd_offset = struct.unpack("<II", f.read(8))
        f.seek(cd_offset)
        cd_data = f.read(cd_size)

    orig_entries = parse_cd(cd_data, 0, cd_size)
    centrals = []
    injected_names = set(injected_files.keys())

    with open(out_apk, "wb") as out_f:
        # 1. 写入原包完整副本 (STORE 存储，4 字节对齐)
        inner_lh = build_lh(INPUT_APK_ENTRY, 0, orig_crc, orig_size, orig_size, 0, align=True)
        out_f.write(inner_lh)
        inner_data_start = len(inner_lh)
        with open(orig_apk, "rb") as orig_f:
            while chunk := orig_f.read(131072):
                out_f.write(chunk)
        centrals.append(build_ce(INPUT_APK_ENTRY, 0, orig_crc, orig_size, orig_size, 0))
        cur_pos = out_f.tell()

        # 2. 写入修改及新增文件 (Dex, SO, 原版V1三件套)
        for name, payload_or_path in injected_files.items():
            name_b = name.encode("utf-8") if isinstance(name, str) else name
            if isinstance(payload_or_path, bytes):
                payload = payload_or_path
            else:
                with open(payload_or_path, "rb") as ff:
                    payload = ff.read()
            p_len = len(payload)
            p_crc = zlib.crc32(payload) & 0xffffffff
            lh = build_lh(name_b, 0, p_crc, p_len, p_len, cur_pos, align=True)
            out_f.write(lh)
            out_f.write(payload)
            centrals.append(build_ce(name_b, 0, p_crc, p_len, p_len, cur_pos))
            cur_pos = out_f.tell()

        # 3. MT 式数据复用映射 (原包其余全部文件直接映射到 input.apk 内部偏移)
        reused = 0
        for e in orig_entries:
            ns = e["name_str"]
            if ns == "assets/Zcraft/input.apk" or ns in injected_names or ns.startswith("META-INF/"):
                continue
            target_offset = inner_data_start + e["local_offset"]
            ce = bytearray(e["raw"])
            struct.pack_into("<I", ce, 42, target_offset)
            flags = struct.unpack_from("<H", ce, 8)[0] & ~0x0008
            struct.pack_into("<H", ce, 8, flags)
            centrals.append(bytes(ce))
            reused += 1

        # 4. 写入中央目录与 EOCD
        new_cd_off = out_f.tell()
        cd_bytes = b"".join(centrals)
        out_f.write(cd_bytes)
        out_f.write(build_eocd(new_cd_off, len(cd_bytes), len(centrals)))

    print(f"[ok] 流式数据复用完成: {reused} 个文件直接复用原包偏移，产物仅 {os.path.getsize(out_apk) // (1024*1024)}MB")
