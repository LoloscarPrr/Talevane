from pathlib import Path

p = Path('app/src/main/java/app/talevane/reader/speech/VoiceLabActivity.kt')
s = p.read_text()
needle = 'import androidx.compose.runtime.*\n'
replacement = 'import androidx.compose.runtime.*\nimport androidx.compose.runtime.saveable.rememberSaveable\n'
if 'import androidx.compose.runtime.saveable.rememberSaveable' not in s:
    if needle not in s:
        raise SystemExit('Compose runtime import marker not found')
    s = s.replace(needle, replacement, 1)
p.write_text(s)
