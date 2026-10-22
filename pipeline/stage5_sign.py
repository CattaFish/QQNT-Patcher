# -*- coding: utf-8 -*-

def run_stage5(ctx):
    ctx.print_stage_header(5, 5, "Sign & Package APK")
    ctx.provider.sign(ctx, in_apk=ctx.output_apk, out_apk=ctx.output_apk)