# -*- coding: utf-8 -*-
import os
import shlex
import shutil
import hashlib
import json
from .context import Colors

def compute_dex_patch_key(raw_dex_bytes, rules_list):
    h = hashlib.sha256()
    h.update(hashlib.md5(raw_dex_bytes).hexdigest().encode('utf-8'))
    h.update(json.dumps(rules_list, sort_keys=True, ensure_ascii=False).encode('utf-8'))
    return h.hexdigest()[:16]

def dump_batch_tasks(dex_tasks, batch_file):
    with open(batch_file, "w", encoding="utf-8") as f:
        for dex_in, dex_out, dex_warn, r_list in dex_tasks:
            f.write("===DEX_TASK_SPLIT===\n")
            f.write(f"DEX_IN={dex_in}\n")
            f.write(f"DEX_OUT={dex_out}\n")
            f.write(f"DEX_WARN={dex_warn}\n")
            for r in r_list:
                f.write("===RULE_SPLIT===\n")
                f.write(f"NAME={r.get('name', '未命名规则')}\n")
                f.write(f"TARGET_CLASS={r['target_class']}\n")
                f.write(f"TARGET_METHOD={r['target_method']}\n")
                f.write(f"TYPE={r['type']}\n")
                if "regex" in r: f.write(f"REGEX={r['regex']}\n")
                f.write("---SMALI_START---\n")
                f.write(r['smali'].strip() + "\n")
                f.write("---SMALI_END---\n")

def _print_warnings(header_tag, dex_name, warn_file_path):
    """漂亮优雅地树状格式化回显警告列表"""
    if not os.path.isfile(warn_file_path) or os.path.getsize(warn_file_path) == 0:
        return
    try:
        with open(warn_file_path, "r", encoding="utf-8") as f:
            lines = [l.strip() for l in f if l.strip()]
        if not lines:
            return

        tag_color = Colors.CYAN if "CACHE" in header_tag else Colors.YELLOW
        print(f"  {tag_color}┌{Colors.RESET} [{header_tag}] {Colors.BOLD}{dex_name}{Colors.RESET} ({len(lines)} 条警告)")
        for i, line in enumerate(lines):
            is_last = (i == len(lines) - 1)
            branch = "└─" if is_last else "├─"
            print(f"  {tag_color}│{Colors.RESET}  {branch} {Colors.YELLOW}{line}{Colors.RESET}")
    except Exception:
        pass

def run_stage3(ctx):
    ctx.print_stage_header(3, 5, "Bytecode Memory AST Transform")

    dex_cache_dir = os.path.join(ctx.work_dir, "dex_cache")
    os.makedirs(dex_cache_dir, exist_ok=True)

    dex_tasks = []
    ctx.modified_dex_files = []
    cached_hit_count = 0
    total_target_dexes = len(ctx.dex_to_rules)

    if ctx.skip_dex_patch:
        ctx.log("WARN", "已指定 --skip-dex-patch: 跳过宿主 DEX 字节码重构")
        return

    ctx.log("INFO", f"待校验/重构分包: {total_target_dexes} 个 DEX")

    for dex_name, r_list in ctx.dex_to_rules.items():
        raw_bytes = ctx.dex_data_dict[dex_name]
        cache_key = compute_dex_patch_key(raw_bytes, r_list)
        cached_dex_file = os.path.join(dex_cache_dir, f"{dex_name}_{cache_key}.dex")
        cached_warn_file = os.path.join(dex_cache_dir, f"{dex_name}_{cache_key}.warn")

        target_out_dex = os.path.join(ctx.work_dir, f"patched_{dex_name}")
        target_out_warn = os.path.join(ctx.work_dir, f"patched_{dex_name}.warn")

        # 1. 命中缓存分支
        if os.path.exists(cached_dex_file) and os.path.getsize(cached_dex_file) > 0:
            shutil.copyfile(cached_dex_file, target_out_dex)
            ctx.modified_dex_files.append((target_out_dex, dex_name))
            cached_hit_count += 1

            # ★ 核心改动：命中缓存时，同步回放历史保存的 .warn
            if os.path.exists(cached_warn_file):
                _print_warnings("CACHE-WARN", dex_name, cached_warn_file)
            continue

        # 2. 未命中缓存，准备任务
        dex_raw_path = os.path.join(ctx.work_dir, dex_name)
        with open(dex_raw_path, "wb") as f:
            f.write(raw_bytes)

        dex_tasks.append((dex_raw_path, target_out_dex, target_out_warn, r_list, cached_dex_file, cached_warn_file, dex_name))
        ctx.modified_dex_files.append((target_out_dex, dex_name))

    if cached_hit_count > 0:
        ctx.log("CACHE", f"分包缓存命中: {cached_hit_count} / {total_target_dexes} 个未改动，直接复用")

    # 3. 执行未命中缓存的任务（带平滑进度条驱动）
    if dex_tasks:
        batch_cfg_path = os.path.join(ctx.work_dir, "batch_tasks.txt")
        dump_batch_tasks([(t[0], t[1], t[2], t[3]) for t in dex_tasks], batch_cfg_path)

        progress = ctx.create_progress(total=len(dex_tasks), title="DexPatcher")

        cp = f"{shlex.quote(ctx.engine_bin)}:{shlex.quote(ctx.guava_jar)}:{shlex.quote(ctx.dexlib2_jar)}:{shlex.quote(ctx.smali_jar)}:{shlex.quote(ctx.baksmali_jar)}"
        cmd = f"java -Xms256m -Xmx768m -XX:+UseParallelGC -cp {cp} com.tencent.qqnt.patcher.DexPatcher {shlex.quote(batch_cfg_path)}"

        def handle_patcher_output(line):
            if line.startswith("[DexPatcher:TASK_DONE]"):
                # 解析格式: [DexPatcher:TASK_DONE] NAME=classes2.dex,COST=180,WARNS=0
                props = dict(part.split("=", 1) for part in line[22:].split(",") if "=" in part)
                name = props.get("NAME", "")
                progress.update(1, name)
            elif line.startswith("[DexPatcher:TASK_FAIL]"):
                props = dict(part.split("=", 1) for part in line[22:].split(",") if "=" in part)
                ctx.log("ERR", f"分包重构失败: {props.get('NAME')} -> {props.get('MSG')}")
            elif line.startswith("[DexPatcher:ERROR]"):
                ctx.log("ERR", line)

        ret = ctx.run_cmd_stream(cmd, on_line=handle_patcher_output)
        progress.finish(f"已重构 {len(dex_tasks)} 个变动分包")

        if ret != 0:
            ctx.log("ERR", "DexPatcher 编译引擎异常退出，终止打包以防污染产物")
            raise SystemExit(1)

        # 4. 归档新生成的产物与 .warn 到缓存，并即时打印告警
        for _, target_out_dex, target_out_warn, _, cached_dex_file, cached_warn_file, dex_name in dex_tasks:
            if os.path.exists(target_out_dex) and os.path.getsize(target_out_dex) > 0:
                shutil.copyfile(target_out_dex, cached_dex_file)

            if os.path.exists(target_out_warn) and os.path.getsize(target_out_warn) > 0:
                shutil.copyfile(target_out_warn, cached_warn_file)
                _print_warnings("TRANSFORM-WARN", dex_name, target_out_warn)
            else:
                if os.path.exists(cached_warn_file):
                    os.remove(cached_warn_file)
    else:
        ctx.log("OK", "全量分包命中缓存，无需重新汇编")