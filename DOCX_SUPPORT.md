# DOCX support

Talevane v0.7.7 adds native `.docx` import support without Apache POI.

- Reads `word/document.xml` directly from the Office Open XML package.
- Preserves paragraph order, basic line breaks and table paragraph text.
- Reads title/author from `docProps/core.xml` when available.
- Works offline and keeps the imported text as Talevane's canonical source.
- Registers the official DOCX MIME type for Android “Open with…” and the library picker.

Legacy binary `.doc` files are not supported in this version.
