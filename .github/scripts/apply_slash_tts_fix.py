from pathlib import Path

service = Path("app/src/main/java/app/talevane/reader/speech/NarrationService.kt")
text = service.read_text()

old_guard = "if (raw.none { it == '\\n' || it == '\\r' || it == '\\t' }) return raw"
new_guard = "if (raw.none { it == '\\n' || it == '\\r' || it == '\\t' || it == '/' }) return raw"
if old_guard not in text:
    raise SystemExit("Expected TTS guard not found")
text = text.replace(old_guard, new_guard, 1)

needle = """            when (raw[i]) {
                '\\t' -> {
"""
replacement = """            when (raw[i]) {
                '/' -> {
                    val previous = raw.getOrNull(i - 1)
                    val next = raw.getOrNull(i + 1)
                    if (previous?.isLetter() == true && next?.isLetter() == true) {
                        // Some Android TTS engines spell slash-joined words letter by letter.
                        // Replace only letter/letter slashes in the speech-only copy. The comma
                        // preserves the exact character count used by karaoke highlighting.
                        chars[i] = ','
                    }
                    i += 1
                }
                '\\t' -> {
"""
if needle not in text:
    raise SystemExit("Expected TTS slash insertion point not found")
text = text.replace(needle, replacement, 1)
service.write_text(text)

gradle = Path("app/build.gradle.kts")
build = gradle.read_text()
if 'versionCode = 26' not in build or 'versionName = "0.6.9.5"' not in build:
    raise SystemExit("Expected v0.6.9.5 version not found")
build = build.replace('versionCode = 26', 'versionCode = 27', 1)
build = build.replace('versionName = "0.6.9.5"', 'versionName = "0.6.9.6"', 1)
gradle.write_text(build)
