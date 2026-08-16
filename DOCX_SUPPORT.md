# DOCX support

Talevane v0.7.8 adds compatibility for older Android file managers on top of the native
`.docx` importer introduced in v0.7.7.

- Reads `word/document.xml` directly from the Office Open XML package.
- Preserves paragraph order, basic line breaks and table paragraph text.
- Reads title/author from `docProps/core.xml` when available.
- Works offline and keeps the imported text as Talevane's canonical source.
- Registers the official DOCX MIME type for Android “Open with…” and the library picker.
- Accepts the legacy `application/msword`, ZIP and generic-binary labels used incorrectly for
  `.docx` by some older file managers, then validates the OOXML package before importing it.
- Accepts Word files through both Android “Open with…” and “Share”.

Legacy binary `.doc` files are detected and receive a clear explanation; they are not parsed in
this version and should be saved as `.docx` first.
