package app.talevane.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class ImportedBook(val title:String,val author:String="Autor desconocido",val format:String,val sourceName:String,val content:String)

object Importers {
    fun import(context: Context, uri: Uri): ImportedBook {
        val name = displayName(context, uri)
        return when (name.substringAfterLast('.', "").lowercase()) {
            "txt" -> importTxt(context, uri, name)
            "epub" -> importEpub(context, uri, name)
            "pdf" -> importPdf(context, uri, name)
            else -> error("Formato no compatible")
        }
    }
    private fun importTxt(context: Context, uri: Uri, name: String): ImportedBook {
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        return ImportedBook(name.substringBeforeLast('.'), format="TXT", sourceName=name, content=text)
    }
    private fun importPdf(context: Context, uri: Uri, name: String): ImportedBook {
        val tmp = File.createTempFile("talevane-", ".pdf", context.cacheDir)
        context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        val doc = PDDocument.load(tmp)
        val text = try { PDFTextStripper().getText(doc) } finally { doc.close(); tmp.delete() }
        return ImportedBook(name.substringBeforeLast('.'), format="PDF", sourceName=name, content=text)
    }
    private fun importEpub(context: Context, uri: Uri, name: String): ImportedBook {
        val tmp = File.createTempFile("talevane-", ".epub", context.cacheDir)
        context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        ZipFile(tmp).use { zip ->
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val containerDoc = factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry("META-INF/container.xml")))
            val rootFile = containerDoc.getElementsByTagNameNS("*", "rootfile").item(0).attributes.getNamedItem("full-path").nodeValue
            val opf = factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(rootFile)))
            fun metadata(tag:String) = opf.getElementsByTagNameNS("*",tag).item(0)?.textContent?.trim()?.takeIf{it.isNotBlank()}
            val title = metadata("title") ?: name.substringBeforeLast('.')
            val author = metadata("creator") ?: "Autor desconocido"
            val base = rootFile.substringBeforeLast('/', "")
            val manifest = mutableMapOf<String,String>()
            val items = opf.getElementsByTagNameNS("*","item")
            for (i in 0 until items.length) {
                val n=items.item(i); val id=n.attributes.getNamedItem("id")?.nodeValue?:continue; val href=n.attributes.getNamedItem("href")?.nodeValue?:continue
                manifest[id] = if (base.isBlank()) href else "$base/$href"
            }
            val content=StringBuilder(); val spine=opf.getElementsByTagNameNS("*","itemref")
            for(i in 0 until spine.length){
                val idref=spine.item(i).attributes.getNamedItem("idref")?.nodeValue?:continue
                val path=manifest[idref]?:continue; val entry=zip.getEntry(path)?:continue
                val html=zip.getInputStream(entry).bufferedReader().use{it.readText()}
                val plain=html.replace(Regex("""<(script|style)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)," ")
                    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE),"\n")
                    .replace(Regex("</(p|div|h[1-6]|li)>",RegexOption.IGNORE_CASE),"\n")
                    .replace(Regex("<[^>]+>")," ").replace("&nbsp;"," ").replace("&amp;","&")
                    .replace(Regex("[ \t]+")," ").replace(Regex("\n{3,}"),"\n\n").trim()
                if(plain.isNotBlank()) content.append(plain).append("\n\n")
            }
            tmp.delete(); return ImportedBook(title,author,"EPUB",name,content.toString())
        }
    }
    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri,null,null,null,null)?.use { c ->
            val idx=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(idx>=0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "Libro"
    }
}
