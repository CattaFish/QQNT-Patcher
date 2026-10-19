# -*- coding: utf-8 -*-
from .base import BaseProvider
from .killer import KillerProvider
from .debug import DebugSignerProvider
from .none import NoneSignerProvider

def get_provider(ctx, provider_name="killer"):
    if ctx.no_sign or provider_name == "none":
        return NoneSignerProvider()

    if not ctx.no_killer and provider_name == "killer":
        return KillerProvider()

    return DebugSignerProvider()

__all__ = ["BaseProvider", "KillerProvider", "DebugSignerProvider", "NoneSignerProvider", "get_provider"]
