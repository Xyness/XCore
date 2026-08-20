#!/usr/bin/env python3
"""
Static checks over XCore and its addons.

Every audit so far found a new class of problem because every audit brought a new pair of eyes and
then threw them away. These are those eyes, kept: run it before a deploy and it answers yes or no
instead of somebody having to go looking again.

Each check knows about its own false positives. A checker that reports things which are fine is a
checker nobody reads, so anything listed here is meant to be a real finding.

    python3 tools/check.py            all checks
    python3 tools/check.py lang sql   only those

Exit code is the number of findings, so it can gate a build.
"""

import os
import re
import sys

try:
    import yaml
except ImportError:
    print("PyYAML is required: pip install pyyaml")
    sys.exit(255)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PROJECTS = {
    'XCore':         '',
    'XBans':         'addons/XBans',
    'XAntiLag':      'addons/XAntiLag',
    'XAuctionHouse': 'addons/XAuctionHouse',
    'XLogin':        'addons/XLogin',
}

findings = []


def report(project, message):
    findings.append((project, message))
    print(f'  [{project}] {message}')


def java_files(base):
    src = os.path.join(ROOT, base, 'src/main/java')
    for dp, _, fs in os.walk(src):
        for fn in fs:
            if fn.endswith('.java'):
                yield os.path.join(dp, fn)


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'(?m)^\s*//.*$', '', text)


def rel(path):
    return path.split('/java/')[-1]


def load_yaml(path):
    if not os.path.isfile(path):
        return None
    with open(path, encoding='utf-8') as handle:
        return yaml.safe_load(handle) or {}


# ---------------------------------------------------------------------------
# Language keys
# ---------------------------------------------------------------------------

# What a shared XCore helper asks the addon's own namespace for when a menu item names no button.
BAR_FALLBACKS = ['gui-btn-click-access-on', 'gui-btn-click-access-off',
                 'gui-btn-no-perm-on', 'gui-btn-no-perm-off']
LANG_CALL = re.compile(r'\.(?:getComponent|getMessageString|getMessage|getRaw)\(\s*"([a-zA-Z0-9._-]+)"')
LANG_DYNAMIC = re.compile(r'\.(?:getComponent|getMessageString|getMessage|getRaw)\(\s*"([a-zA-Z0-9._-]*)"\s*\+')


def check_lang():
    print('== language keys')
    for project, base in PROJECTS.items():
        lang_dir = os.path.join(ROOT, base, 'src/main/resources/lang')
        gui_dir = os.path.join(ROOT, base, 'src/main/resources/guis')
        if not os.path.isdir(lang_dir):
            continue

        wanted, prefixes = set(), set()
        for path in java_files(base):
            text = strip_comments(open(path, encoding='utf-8', errors='replace').read())
            wanted |= set(LANG_CALL.findall(text))
            prefixes |= {p for p in LANG_DYNAMIC.findall(text) if p}

        # Keys named by the menu definitions.
        if os.path.isdir(gui_dir):
            for fn in os.listdir(gui_dir):
                if not fn.endswith('.yml'):
                    continue
                spec = load_yaml(os.path.join(gui_dir, fn)) or {}
                if spec.get('gui-title'):
                    wanted.add(spec['gui-title'])
                for item in (spec.get('items') or {}).values():
                    for field in ('target-title', 'target-lore', 'target-button-on', 'target-button-off'):
                        value = item.get(field)
                        if isinstance(value, str) and value.strip():
                            wanted.add(value.strip())
            # Only a project that draws its own menus is ever asked for the shared bar keys.
            wanted |= set(BAR_FALLBACKS)

        # A prefix is not itself a key: what must exist is something starting with it.
        wanted -= prefixes

        langs = {}
        for fn in sorted(os.listdir(lang_dir)):
            if fn.endswith('.yml') and not fn.startswith('web_'):
                langs[fn] = set(load_yaml(os.path.join(lang_dir, fn)))

        for fn, have in langs.items():
            for key in sorted(wanted - have):
                report(project, f'{fn}: key "{key}" is asked for and missing')
            for prefix in sorted(prefixes):
                if not any(k.startswith(prefix) for k in have):
                    report(project, f'{fn}: nothing matches the built key "{prefix}*"')

        # A key in one language and not the other renders as its own name for half the players.
        names = list(langs)
        for i, first in enumerate(names):
            for second in names[i + 1:]:
                for key in sorted(langs[first] - langs[second]):
                    report(project, f'"{key}" is in {first} but not in {second}')


# ---------------------------------------------------------------------------
# Configuration keys
# ---------------------------------------------------------------------------

# Only calls on a configuration. A ResultSet and a menu section answer to the same method names.
CONFIG_CALL = re.compile(
    r'\b(?:getConfig\(\)|config|cfg)'
    r'\.get(?:Boolean|Int|Long|Double|String|StringList|IntegerList|ConfigurationSection)'
    r'\(\s*"([a-zA-Z0-9._-]+)"')


def flatten(node, prefix=''):
    keys = set()
    if isinstance(node, dict):
        for key, value in node.items():
            path = f'{prefix}{key}'
            keys.add(path)
            keys |= flatten(value, path + '.')
    return keys


def check_config():
    print('== configuration keys')
    for project, base in PROJECTS.items():
        config_path = os.path.join(ROOT, base, 'src/main/resources/config.yml')
        shipped = flatten(load_yaml(config_path) or {})
        if not shipped:
            continue
        for path in java_files(base):
            # XAddon is the base every addon extends, so its getConfig() is the addon's, never
            # XCore's. Its keys are checked against every addon instead, below.
            if os.path.basename(path) == 'XAddon.java':
                continue
            text = strip_comments(open(path, encoding='utf-8', errors='replace').read())
            for m in CONFIG_CALL.finditer(text):
                key = m.group(1)
                if key in shipped:
                    continue
                # A key built by concatenation, or one read out of a section the admin names.
                if text[m.end():m.end() + 3].lstrip().startswith('+'):
                    continue
                if any(s.startswith(key + '.') or s.endswith('.' + key) for s in shipped):
                    continue
                line = text[:m.start()].count('\n') + 1
                report(project, f'{rel(path)}:{line}: "{key}" is read and absent from config.yml')

    # What the shared base reads belongs to whichever addon is running, so every addon has to
    # ship it or the setting is one nobody can discover.
    base_path = os.path.join(ROOT, 'src/main/java/fr/xyness/XCore/Addon/XAddon.java')
    if os.path.isfile(base_path):
        shared = set(CONFIG_CALL.findall(strip_comments(open(base_path, encoding='utf-8').read())))
        for project, base in PROJECTS.items():
            if not base:
                continue
            shipped = flatten(load_yaml(os.path.join(ROOT, base, 'src/main/resources/config.yml')) or {})
            if not shipped:
                continue
            raw = open(os.path.join(ROOT, base, 'src/main/resources/config.yml'), encoding='utf-8').read()
            for key in sorted(shared - shipped):
                # A key shown commented out counts as documented: some settings must not be active
                # by default, and writing them in would change behaviour on every existing server.
                if re.search(r'(?m)^\s*#\s*' + re.escape(key.split('.')[0]) + r'\s*:', raw):
                    continue
                report(project, f'config.yml: "{key}" is read by XAddon and not documented here')


# ---------------------------------------------------------------------------
# Placeholders
# ---------------------------------------------------------------------------

PH_IN_TEXT = re.compile(r'[{%]([a-zA-Z][a-zA-Z0-9_-]*)[}%]')
PH_CALL = re.compile(r'\.(?:getComponent|getMessage)\(\s*"([a-zA-Z0-9._-]+)"\s*,(.{0,400}?)\)\s*[;,)\.]', re.S)


def supplied_names(args):
    """The placeholder names of an alternating name/value list: the odd arguments only."""
    parts, depth, current = [], 0, ''
    for ch in args:
        if ch in '([':
            depth += 1
        elif ch in ')]':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(current)
            current = ''
        else:
            current += ch
    parts.append(current)
    names = []
    for index, part in enumerate(parts):
        if index % 2:
            continue
        literal = re.fullmatch(r'\s*"([a-zA-Z0-9_-]+)"\s*', part)
        if literal:
            names.append(literal.group(1))
    return names


def check_placeholders():
    print('== placeholders')
    for project, base in PROJECTS.items():
        lang_path = os.path.join(ROOT, base, 'src/main/resources/lang/en.yml')
        messages = load_yaml(lang_path)
        if messages is None:
            continue
        sources = list(java_files(base))
        # XCore supplies some names on an addon's behalf — a paginated screen's page and max, for
        # one — so its sources count when deciding whether a name is ever provided.
        if base:
            sources += list(java_files(''))
        blob = '\n'.join(strip_comments(open(p, encoding='utf-8', errors='replace').read())
                         for p in sources)

        # Reported only when a message uses none of what it is given: one unused name is an
        # author keeping a placeholder available, all of them unused is a text left behind.
        seen = set()
        for m in PH_CALL.finditer(blob):
            key, args = m.group(1), m.group(2)
            if key not in messages or key in seen:
                continue
            seen.add(key)
            text = str(messages[key])
            given = supplied_names(args)
            if not given:
                continue
            used = [n for n in given if '{' + n + '}' in text or '%' + n + '%' in text]
            if not used:
                report(project, f'"{key}" is given {given} and shows none of them')

        for key, text in messages.items():
            text = str(text)
            names = set(PH_IN_TEXT.findall(text))
            if not names:
                continue
            # Every context the key appears in, plus the helpers that add names of their own.
            contexts = [blob[m.end():m.end() + 600] for m in re.finditer('"' + re.escape(key) + '"', blob)]
            if not contexts:
                continue
            for name in sorted(names):
                # Anywhere in the sources, not only beside the key: a helper that takes the key as
                # a parameter supplies the names from its own body, and looking only around the key
                # made every one of those look broken.
                if '"' + name + '"' in blob:
                    continue
                # Substituted by hand rather than passed as a name. Two shapes: a lore template
                # built with replace(), and a helper that splices a component in at the token —
                # which is how an item name becomes hoverable instead of being flattened to text.
                if f'replace("%{name}%"' in blob or f'replace("{{{name}}}"' in blob:
                    continue
                if f'"{{{name}}}"' in blob or f'"%{name}%"' in blob:
                    continue
                # Supplied by a helper that appends names of its own to the caller's list.
                if '"' + name + '"' in blob and 'allReplacements' in blob:
                    continue
                report(project, f'"{key}" shows {{{name}}}, which nothing supplies')


# ---------------------------------------------------------------------------
# Threading and pools
# ---------------------------------------------------------------------------

def check_sql_pool():
    print('== database work on the wrong pool')
    for project, base in PROJECTS.items():
        for path in java_files(base):
            text = open(path, encoding='utf-8', errors='replace').read()
            for m in re.finditer(r'getExecutor\(\)\.(?:execute|submit)\(', text):
                if 'getConnection()' in text[m.end():m.end() + 600]:
                    line = text[:m.start()].count('\n') + 1
                    report(project, f'{rel(path)}:{line}: opens a connection on the work pool')
            for m in re.finditer(r'(?:supplyAsync|runAsync)\((.{0,800}?)\}\s*,\s*\w+\.getExecutor\(\)\)', text, re.S):
                # Blocking on the economy is the one reason to stay off the database pool.
                if 'getConnection()' in m.group(1) and '.join()' not in m.group(1):
                    line = text[:m.start()].count('\n') + 1
                    report(project, f'{rel(path)}:{line}: opens a connection on the work pool')


def check_listener_config():
    print('== configuration read inside a listener')
    for project, base in PROJECTS.items():
        for path in java_files(base):
            if 'Listener' not in os.path.basename(path):
                continue
            for i, line in enumerate(open(path, encoding='utf-8', errors='replace'), 1):
                stripped = line.strip()
                if stripped.startswith('*') or stripped.startswith('//'):
                    continue
                if re.search(r'getConfig\(\)\.get\w+\(', line):
                    report(project, f'{rel(path)}:{i}: reads config.yml in a listener')


def check_folia():
    print('== entity state off its region thread')
    pattern = re.compile(r'(setAware|\.remove\(\)|addPotionEffect|setHealth|setFireTicks|setVelocity)\s*[\(;]')
    for project, base in PROJECTS.items():
        for path in java_files(base):
            text = open(path, encoding='utf-8', errors='replace').read()
            for m in pattern.finditer(text):
                context = text[max(0, m.start() - 450):m.start()]
                if 'runEntityTask' in context or 'runChunkTask' in context:
                    continue
                if 'runGlobalTask' in context or 'runAsyncTask' in context:
                    line = text[:m.start()].count('\n') + 1
                    report(project, f'{rel(path)}:{line}: {m.group(1)} outside the entity thread')


def check_italic():
    print('== lore set without turning italics off')
    for project, base in PROJECTS.items():
        for path in java_files(base):
            text = open(path, encoding='utf-8', errors='replace').read()
            for m in re.finditer(r'(?<!\w)meta\.lore\(([^)]+)\)', text):
                if 'noItalic' in m.group(1):
                    continue
                line = text[:m.start()].count('\n') + 1
                report(project, f'{rel(path)}:{line}: lore set without noItalic, it will render italic')


CHECKS = {
    'lang': check_lang,
    'config': check_config,
    'placeholders': check_placeholders,
    'sql': check_sql_pool,
    'listeners': check_listener_config,
    'folia': check_folia,
    'italic': check_italic,
}

if __name__ == '__main__':
    wanted = sys.argv[1:] or list(CHECKS)
    unknown = [w for w in wanted if w not in CHECKS]
    if unknown:
        print('unknown check(s):', ', '.join(unknown))
        print('available:', ', '.join(CHECKS))
        sys.exit(255)
    for name in wanted:
        CHECKS[name]()
    print()
    print(f'{len(findings)} finding(s)' if findings else 'nothing to report')
    sys.exit(min(len(findings), 254))
