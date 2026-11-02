#!/usr/bin/env python3
"""Importa strings do app Android (res/values*/strings.xml) para Localization.kt do Desktop.

Uso: scripts/import_android_strings.py <regex-de-chaves> [...]
Mantém a chave Android como chave do Desktop. Chaves já presentes são ignoradas (idempotente).
Idioma sem tradução usa o texto em inglês (o Desktop não tem fallback). ms (malaio) não existe no Desktop.
"""
import re, sys, xml.etree.ElementTree as ET
from pathlib import Path

RES = Path.home() / "Projects/EcuManager/app/src/main/res"
LOC = Path(__file__).resolve().parent.parent / "desktopApp/src/desktopMain/kotlin/com/speeduino/manager/desktop/Localization.kt"
DIRS = {"EN": "values", "PT": "values-pt-rBR", "ES": "values-es", "FR": "values-fr", "ID": "values-in", "ZH": "values-zh"}

def unescape(t):
    t = t.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
    t = t.replace("\\u2022", "\u2022")
    return t

def load(d):
    out = {}
    # O Android divide os textos em vários arquivos (strings.xml, strings_messages.xml, strings_coverage_new.xml...).
    # strings.xml é lido por último para prevalecer em chave repetida.
    files = sorted((RES / d).glob("strings*.xml"), key=lambda f: f.name == "strings.xml")
    for f in files:
        for e in ET.parse(f).getroot().findall("string"):
            if e.get("translatable") == "false": continue
            out[e.get("name")] = unescape("".join(e.itertext()))
    return out

def kt(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n") + '"'

pats = [re.compile(p) for p in sys.argv[1:]]
data = {l: load(d) for l, d in DIRS.items()}
keys = [k for k in data["EN"] if any(p.fullmatch(k) for p in pats)]
src = LOC.read_text(encoding="utf-8").split("\n")
out, lang, i, added = [], None, 0, 0
body = {}
# primeiro passo: descobrir chaves existentes por idioma
cur = None
for l in src:
    m = re.match(r"\s*val (EN|PT|ES|FR|ID|ZH) = mapOf\(", l)
    if m: cur = m.group(1); body[cur] = set()
    elif cur:
        m2 = re.match(r'\s*"([^"]+)" to ', l)
        if m2: body[cur].add(m2.group(1))
        if l == "    )": cur = None
cur = None
for l in src:
    m = re.match(r"\s*val (EN|PT|ES|FR|ID|ZH) = mapOf\(", l)
    if m: cur = m.group(1)
    if cur and l == "    )":
        if out and out[-1].strip() and not out[-1].rstrip().endswith(","):
            out[-1] = out[-1].rstrip() + ","
        for k in keys:
            if k in body[cur]: continue
            v = data[cur].get(k) or data["EN"][k]
            out.append(f"        {kt(k)} to {kt(v)},"); added += 1
        cur = None
    out.append(l)
LOC.write_text("\n".join(out), encoding="utf-8")
print(f"{len(keys)} chaves casadas, {added} entradas adicionadas")
