package app.talevane.reader

import android.app.Application
import app.talevane.reader.application.narration.NarrationGateway
import app.talevane.reader.data.BookRepository
import app.talevane.reader.data.RoomBookRepository
import app.talevane.reader.data.TalevaneDatabase
import app.talevane.reader.speech.AndroidNarrationGateway
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class TalevaneApp : Application() {
    lateinit var repository: BookRepository
        private set

    lateinit var narrationGateway: NarrationGateway
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        repository = RoomBookRepository(this, TalevaneDatabase.get(this).bookDao())
        narrationGateway = AndroidNarrationGateway(this)
    }
}
