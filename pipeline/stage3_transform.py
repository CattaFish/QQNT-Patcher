# -*- coding: utf-8 -*-
import os
import shlex
import shutil
import hashlib
import json
import native_patcher

def compute_dex_patch_key(raw_dex_bytes, rules_list):
    h = hashlib.sha256()
    h.update(hashlib.md5(raw_dex_bytes).hexdigest().encode('utf-8'))
    h.update(json.dumps(rules_list, sort_keys=True, ensure_ascii=False).encode('utf-8'))
    return h.hexdigest()[:16]

def dump_batch_tasks(dex_tasks, batch_file):
    with open(batch_file, "w", encoding="utf-8") as f:
        for dex_in, dex_out, r_list in dex_tasks:
            f.write("===DEX_TASK_SPLIT===\n")
            f.write(f"DEX_IN={dex_in}\n")
            f.write(f"DEX_OUT={dex_out}\n")
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

def run_stage3(ctx):
    ctx.log("INFO", f"3. 正在执行 Dex 字节码内存 AST 重构 ({len(ctx.dex_to_rules)} 个分包)...")
    dex_cache_dir = os.path.join(ctx.work_dir, "dex_cache")
    so_cache_dir = os.path.join(ctx.work_dir, "so_cache")
    os.makedirs(dex_cache_dir, exist_ok=True)
    os.makedirs(so_cache_dir, exist_ok=True)

    dex_tasks = []
    ctx.modified_dex_files = []
    cached_hit_count = 0

    if ctx.skip_dex_patch:
        ctx.log("WARN", "已开启 --skip-dex-patch: 跳过所有宿主 Dex 重构！")
    else:
        for dex_name, r_list in ctx.dex_to_rules.items():
            raw_bytes = ctx.dex_data_dict[dex_name]
            cache_key = compute_dex_patch_key(raw_bytes, r_list)
            cached_dex_file = os.path.join(dex_cache_dir, f"{dex_name}_{cache_key}.dex")
            target_out_dex = os.path.join(ctx.work_dir, f"patched_{dex_name}")

            if os.path.exists(cached_dex_file) and os.path.getsize(cached_dex_file) > 0:
                shutil.copyfile(cached_dex_file, target_out_dex)
                ctx.modified_dex_files.append((target_out_dex, dex_name))
                cached_hit_count += 1
                continue

            dex_raw_path = os.path.join(ctx.work_dir, dex_name)
            with open(dex_raw_path, "wb") as f: f.write(raw_bytes)

            dex_tasks.append((dex_raw_path, target_out_dex, r_list, cached_dex_file))
            ctx.modified_dex_files.append((target_out_dex, dex_name))

        if cached_hit_count > 0:
            ctx.log("OK", f"分包缓存命中: {cached_hit_count} 个分包未变动，直接复用")

        if dex_tasks:
            batch_cfg_path = os.path.join(ctx.work_dir, "batch_tasks.txt")
            dump_batch_tasks([(t[0], t[1], t[2]) for t in dex_tasks], batch_cfg_path)
            
            cp = f"{shlex.quote(ctx.engine_bin)}:{shlex.quote(ctx.guava_jar)}:{shlex.quote(ctx.dexlib2_jar)}:{shlex.quote(ctx.smali_jar)}:{shlex.quote(ctx.baksmali_jar)}"
            cmd = f"java -Xms256m -Xmx768m -XX:+UseParallelGC -cp {cp} com.tencent.qqnt.patcher.DexPatcher {shlex.quote(batch_cfg_path)}"
            ctx.run_cmd_stream(cmd)

            for _, target_out_dex, _, cached_dex_file in dex_tasks:
                if os.path.exists(target_out_dex) and os.path.getsize(target_out_dex) > 0:
                    shutil.copyfile(target_out_dex, cached_dex_file)
        else:
            ctx.log("OK", "全部分包均命中缓存，跳过 Dex 编译流程")

    # Native SO 修补 (按 so_patch 特性精确控制)
    ctx.patched_so_files = []
    if "so_patch" not in ctx.active_features:
        ctx.log("WARN", "已跳过 [so_patch] 特性: 不对底层 libcodecwrapperV2.so 进行跳转修补")
    else:
        cached_so_file = os.path.join(so_cache_dir, f"libcodecwrapperV2_{ctx.orig_apk_md5}.so")
        if os.path.exists(cached_so_file) and os.path.getsize(cached_so_file) > 0:
            ctx.patched_so_files.append((cached_so_file, "lib/arm64-v8a/libcodecwrapperV2.so"))
            ctx.log("OK", "-> Native SO 命中缓存，秒级复用")
        else:
            ctx.log("INFO", "3.1 正在扫描底层 Native SO 安全探针...")
            new_so_files = native_patcher.patch_native_so(ctx.input_apk, ctx.work_dir, log_func=ctx.log)
            for local_so, in_zip_so in new_so_files:
                if "libcodecwrapperV2.so" in in_zip_so:
                    shutil.copyfile(local_so, cached_so_file)
                ctx.patched_so_files.append((local_so, in_zip_so))
