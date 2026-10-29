# -*- coding: utf-8 -*-
import os
import sys
import importlib.util

class RulePlugin:
    def __init__(self, plugin_id, name, resolver_func, file_path, enabled=True, desc="", aliases=None):
        self.plugin_id = plugin_id
        self.name = name
        self.resolver_func = resolver_func
        self.file_path = file_path
        self.enabled = enabled
        self.desc = desc or name
        self.aliases = list(aliases) if aliases else []

    def resolve(self, dex_data_dict, meta):
        return self.resolver_func(dex_data_dict, meta)

_CACHED_PLUGINS = None

def discover_rule_plugins(rules_dir=None, reload=False):
    """自动扫描 rules/*.py 并注册为自描述标准插件"""
    global _CACHED_PLUGINS
    if _CACHED_PLUGINS is not None and not reload:
        return _CACHED_PLUGINS

    if rules_dir is None:
        rules_dir = os.path.dirname(os.path.abspath(__file__))

    plugins = []
    ignored = {"__init__.py", "engine.py", "parser.py", "stubs.py"}
    
    if not os.path.isdir(rules_dir):
        return plugins

    for f in sorted(os.listdir(rules_dir)):
        if f.endswith(".py") and f not in ignored and not f.startswith("."):
            mod_name = f[:-3]
            file_path = os.path.join(rules_dir, f)
            
            try:
                spec = importlib.util.spec_from_file_location(f"rules.{mod_name}", file_path)
                mod = importlib.util.module_from_spec(spec)
                sys.modules[f"rules.{mod_name}"] = mod
                spec.loader.exec_module(mod)
                
                rule_id = getattr(mod, "RULE_ID", mod_name)
                rule_name = getattr(mod, "RULE_NAME", mod_name)
                rule_desc = getattr(mod, "RULE_DESC", rule_name)
                rule_aliases = getattr(mod, "RULE_ALIASES", getattr(mod, "ALIASES", []))
                resolver = getattr(mod, "resolve_rules", None)
                enabled = getattr(mod, "RULE_ENABLED", True)
                
                if resolver and callable(resolver):
                    plugins.append(RulePlugin(
                        plugin_id=rule_id,
                        name=rule_name,
                        resolver_func=resolver,
                        file_path=file_path,
                        enabled=enabled,
                        desc=rule_desc,
                        aliases=rule_aliases
                    ))
            except Exception as e:
                print(f" \033[1;31m[ERROR]\033[0m 加载规则插件失败 [{f}]: {e}")
                
    _CACHED_PLUGINS = plugins
    return plugins
