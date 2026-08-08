package app.talevane.reader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.talevane.reader.data.*
import kotlinx.coroutines.launch

@Composable fun TalevaneRoot(repository:BookRepository){
    var readerId by remember{ mutableStateOf<Long?>(null) }
    MaterialTheme(colorScheme=darkColorScheme()){ Surface(Modifier.fillMaxSize()){
        if(readerId==null) LibraryScreen(repository){readerId=it} else ReaderScreen(repository,readerId!!){readerId=null}
    }}
}

@Composable private fun LibraryScreen(repository:BookRepository,openBook:(Long)->Unit){
    val books by repository.books.collectAsState(initial=emptyList()); val scope=rememberCoroutineScope(); var error by remember{mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?-> if(uri!=null) scope.launch{ runCatching{repository.import(uri)}.onSuccess{openBook(it)}.onFailure{error=it.message} } }
    Scaffold(floatingActionButton={ExtendedFloatingActionButton(onClick={picker.launch(arrayOf("text/plain","application/pdf","application/epub+zip"))},icon={Icon(Icons.Default.Add,null)},text={Text("Añadir libro")})}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{Spacer(Modifier.height(14.dp));Text("Talevane",style=MaterialTheme.typography.headlineLarge);Text("Tus historias, llevadas a la vida.");Spacer(Modifier.height(14.dp))}
            error?.let{msg->item{Card{Text(msg?:"Error",Modifier.padding(14.dp),color=MaterialTheme.colorScheme.error)}}}
            if(books.isEmpty()) item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(22.dp)){Text("Tu biblioteca está esperando",style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(8.dp));Text("Importa un EPUB, PDF o TXT para empezar.")}}}
            else { item{Text("Tu biblioteca",style=MaterialTheme.typography.titleLarge)}; items(books,key={it.id}){book->BookRow(book){openBook(book.id)}}; item{Spacer(Modifier.height(90.dp))} }
        }
    }
}

@Composable private fun BookRow(book:BookEntity,onClick:()->Unit){
    val percent=if(book.content.isBlank())0f else (book.progressChars.toFloat()/book.content.length).coerceIn(0f,1f)
    Card(Modifier.fillMaxWidth().clickable(onClick=onClick)){Column(Modifier.padding(16.dp)){Text(book.title,style=MaterialTheme.typography.titleMedium);Text("${book.author} · ${book.format}",style=MaterialTheme.typography.bodySmall);Spacer(Modifier.height(10.dp));LinearProgressIndicator(progress={percent},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(4.dp));Text("${(percent*100).toInt()}%")}}
}

@Composable private fun ReaderScreen(repository:BookRepository,bookId:Long,back:()->Unit){
    val scope=rememberCoroutineScope(); var book by remember{mutableStateOf<BookEntity?>(null)}; var fontSize by remember{mutableFloatStateOf(19f)}; val scroll=rememberScrollState()
    LaunchedEffect(bookId){book=repository.get(bookId)}
    val current=book ?: return Box(Modifier.fillMaxSize().padding(24.dp)){CircularProgressIndicator()}
    LaunchedEffect(scroll.value,scroll.maxValue){if(scroll.maxValue>0 && current.content.isNotBlank()){val fraction=scroll.value.toFloat()/scroll.maxValue;repository.saveProgress(current.id,(fraction*current.content.length).toInt())}}
    Scaffold(topBar={TopAppBar(title={Text(current.title,maxLines=1)},navigationIcon={IconButton(onClick=back){Icon(Icons.Default.ArrowBack,"Volver")}},actions={IconButton(onClick={scope.launch{repository.toggleBookmark(current);book=repository.get(current.id)}}){Icon(if(current.bookmarked)Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,"Marcador")}})},bottomBar={Surface(tonalElevation=3.dp){Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={fontSize=(fontSize-1).coerceAtLeast(14f)}){Text("A−")};Text("${fontSize.toInt()} sp",Modifier.padding(top=12.dp));TextButton(onClick={fontSize=(fontSize+1).coerceAtMost(34f)}){Text("A+")}}}}){padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(24.dp)){Text(current.content.ifBlank{"No se pudo extraer texto legible de este archivo."},fontSize=fontSize.sp,lineHeight=(fontSize*1.55f).sp,fontFamily=FontFamily.Serif);Spacer(Modifier.height(80.dp))}
    }
}
