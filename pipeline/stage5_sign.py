# -*- coding: utf-8 -*-

def run_stage5(ctx):
    # 彻底交由当前激活的 Provider 执行具体的签名逻辑
    ctx.provider.sign(ctx, in_apk=ctx.output_apk, out_apk=ctx.output_apk)
