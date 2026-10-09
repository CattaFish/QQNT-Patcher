# -*- coding: utf-8 -*-
import os
import shutil
import time
from .context import PipelineContext
from .stage1_prepare import run_stage1
from .stage2_rules import run_stage2
from .stage3_transform import run_stage3
from .stage4_pack import run_stage4
from .stage5_sign import run_stage5

def run_pipeline(ctx: PipelineContext):
    t_start = time.time()
    
    t0 = time.time()
    run_stage1(ctx)
    ctx.log("TIME", f"  -> 阶段 1 耗时: {round(time.time() - t0, 2)}s")

    t0 = time.time()
    run_stage2(ctx)
    ctx.log("TIME", f"  -> 阶段 2 耗时: {round(time.time() - t0, 2)}s")

    t0 = time.time()
    run_stage3(ctx)
    ctx.log("TIME", f"  -> 阶段 3 耗时: {round(time.time() - t0, 2)}s")

    t0 = time.time()
    run_stage4(ctx)
    ctx.log("TIME", f"  -> 阶段 4 耗时: {round(time.time() - t0, 2)}s")

    t0 = time.time()
    run_stage5(ctx)
    ctx.log("TIME", f"  -> 阶段 5 耗时: {round(time.time() - t0, 2)}s")

    # 工作空间清理
    keep_list = {
        "patcher_bin", "dex_out", "bin", "bsh.dex", "bsh_dex",
        "libs_cached.dex", "preset_plugins.zip", "apk_meta_cache.json",
        "dex_cache", "rule_discovery_cache.json", "dex_classes_map.json"
    }
    for f in os.listdir(ctx.work_dir):
        if f not in keep_list:
            p = os.path.join(ctx.work_dir, f)
            if os.path.isdir(p): shutil.rmtree(p, ignore_errors=True)
            else: os.remove(p)

    ctx.log("OK", f"全流程构建完成，总耗时: {round(time.time() - t_start, 2)}s, 输出: {ctx.output_apk}")