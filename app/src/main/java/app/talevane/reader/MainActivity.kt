package app.talevane.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.talevane.reader.ui.TalevaneRoot

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val repository=(application as TalevaneApp).repository
        setContent{ TalevaneRoot(repository) }
    }
}
