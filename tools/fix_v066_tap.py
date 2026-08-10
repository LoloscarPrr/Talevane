from pathlib import Path
p = Path('app/src/main/java/app/talevane/reader/ui/TalevaneRoot.kt')
s = p.read_text()
bad = "charArrayOf('\\\"', ''', '“', '”', '‘', '’', '«', '»', '—')"
good = "charArrayOf('\\\"', '“', '”', '‘', '’', '«', '»', '—')"
if bad not in s:
    raise SystemExit('Malformed tap punctuation marker not found')
s = s.replace(bad, good, 1)
p.write_text(s)
