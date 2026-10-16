# -*- coding: utf-8 -*-
"""
qqPatch 构建期 SubID 增量合并器
以本地已有的 assets/subid.json 为绝对权威基准，从云端仅做增量追加与老词条高精富化，永不删除老数据。
"""
import os
import json
import urllib.request

REMOTE_SUBID_URLS = [
    "https://ghfast.top/https://raw.githubusercontent.com/Cattafish/qqDecoder/main/data/subid.json",
    "https://cdn.jsdelivr.net/gh/Cattafish/qqDecoder@main/data/subid.json",
    "https://raw.githubusercontent.com/Cattafish/qqDecoder/main/data/subid.json"
]

def sync_subid_incremental(ctx):
    assets_subid_path = os.path.join(ctx.assets_src_dir, "subid.json")
    os.makedirs(ctx.assets_src_dir, exist_ok=True)

    # 1. 载入本地最全权威底表
    local_dict = {}
    if os.path.isfile(assets_subid_path):
        try:
            with open(assets_subid_path, "r", encoding="utf-8") as f:
                d = json.load(f)
                if isinstance(d, dict):
                    local_dict = d
        except Exception as e:
            ctx.log("WARN", f"读取本地 assets/subid.json 异常: {e}")

    # 2. 从云端拉取增量字典 (3.5 秒熔断，不拖慢打包)
    remote_dict = None
    headers = {"User-Agent": "QQNT-Patcher-Build/1.0"}

    for url in REMOTE_SUBID_URLS:
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=3.5) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode('utf-8'))
                    if isinstance(data, dict) and len(data) > 0:
                        remote_dict = data
                        break
        except Exception:
            continue

    if not remote_dict:
        ctx.log("INFO", f"-> 未连接到云端字典或已处于离线环境，继续使用本地全量字典 ({len(local_dict)} 条)")
        return

    # 3. 严格执行“只增不减、智能升级”的合并规则
    added_count = 0
    upgraded_count = 0

    for subid, r_val in remote_dict.items():
        subid_str = str(subid).strip()
        if not subid_str:
            continue

        if subid_str not in local_dict:
            # 规则 1: 发现新 SubID，增量追加
            local_dict[subid_str] = r_val
            added_count += 1
        else:
            # 规则 2: 已存在词条，仅在远程提供结构化富化信息时升级
            l_val = local_dict[subid_str]
            if isinstance(l_val, str) and isinstance(r_val, dict):
                local_dict[subid_str] = r_val
                upgraded_count += 1
            elif isinstance(l_val, dict) and isinstance(r_val, dict):
                # 联合保留多构建号
                l_ver = l_val.get("version", "")
                r_ver = r_val.get("version", "")
                if l_ver and r_ver and l_ver != r_ver and r_ver not in l_ver:
                    combined_ver = f"{l_ver} / {r_ver}"
                    l_val["version"] = combined_ver
                    l_val["summary"] = l_val.get("summary", "").replace(l_ver, combined_ver)
                    upgraded_count += 1

    # 4. 回写保存 assets/subid.json
    sorted_dict = dict(sorted(local_dict.items(), key=lambda x: int(x[0]) if x[0].isdigit() else 0, reverse=True))

    try:
        with open(assets_subid_path, "w", encoding="utf-8") as f:
            json.dump(sorted_dict, f, ensure_ascii=False, indent=2)
        if added_count > 0 or upgraded_count > 0:
            ctx.log("OK", f"-> SubID 字典已自动增量同步 (新增 {added_count} 条，升级 {upgraded_count} 条，总计 {len(sorted_dict)} 条)")
        else:
            ctx.log("OK", f"-> SubID 字典已与云端对齐 (当前收录 {len(sorted_dict)} 条)")
    except Exception as e:
        ctx.log("WARN", f"写入 assets/subid.json 失败: {e}")
