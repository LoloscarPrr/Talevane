package app.talevane.reader

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import app.talevane.reader.data.TalevaneDatabase
import app.talevane.reader.data.BookRepository

class TalevaneApp : Application() {
    lateinit var repository: BookRepository
        private set
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        repository = BookRepository(this, TalevaneDatabase.get(this).bookDao())
    }
}
