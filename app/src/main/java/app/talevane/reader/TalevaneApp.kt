package app.talevane.reader

import android.app.Application
import app.talevane.reader.application.library.BookLibrary
import app.talevane.reader.data.RoomBookRepository
import app.talevane.reader.data.TalevaneDatabase
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class TalevaneApp : Application() {
    lateinit var repository: BookLibrary
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        repository = RoomBookRepository(this, TalevaneDatabase.get(this).bookDao())
    }
}
