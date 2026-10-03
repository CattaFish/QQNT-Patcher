# -*- coding: utf-8 -*-
import os
import shlex
from .base import BaseProvider

class NoneSignerProvider(BaseProvider):
    name = "none"

    def sign(self, ctx, in_apk, out_apk):
        ctx.log("INFO", "5. 正在执行 NeoPacker 免签打包 (绝无 input.apk，绝不膨胀)...")
        neoapk_jar = os.path.join(ctx.tools_dir, "neoapk.jar")
        inject_dir = os.path.join(ctx.work_dir, "inject")
        cp = f"{shlex.quote(neoapk_jar)}:{shlex.quote(ctx.engine_bin)}"
        
        cmd = f"java -cp {cp} com.tencent.qqnt.patcher.NeoPacker {shlex.quote(ctx.input_apk)} {shlex.quote(out_apk)} {shlex.quote(inject_dir)} --no-killer --no-sign"
        ret = ctx.run_cmd_stream(cmd)
        if ret != 0 or not os.path.isfile(out_apk):
            ctx.log("ERR", "NeoPacker 免签打包失败！")
            sys.exit(1)
