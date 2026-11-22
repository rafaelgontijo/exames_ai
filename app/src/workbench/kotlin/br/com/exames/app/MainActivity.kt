package br.com.exames.app

import android.os.Bundle
import android.net.Uri
import javax.inject.Inject
import br.com.exames.datalocal.ResultShareStore
import br.com.exames.domain.summary.SummaryTextResult
import br.com.exames.featureresults.ResultSharing
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import br.com.exames.coreui.ExamesTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var shareStore: ResultShareStore
    private val sharing = object : ResultSharing {
        override suspend fun prepare(document: String, run: String, summary: SummaryTextResult.Rendered) =
            shareStore.prepare(document, run, summary)
        override suspend fun revoke(uri: Uri) = shareStore.revoke(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ExamesTheme { Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) { ExamesNavigation(sharing) } } }
    }
}
