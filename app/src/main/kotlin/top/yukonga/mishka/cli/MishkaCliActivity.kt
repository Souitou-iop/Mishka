package top.yukonga.mishka.cli

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.util.Base64
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject

/**
 * Transparent shell-only bridge for commands that start a foreground service.
 * Android rejects a background ContentProvider from calling startForegroundService;
 * an Activity makes the same controller call while the app has a visible foreground.
 */
class MishkaCliActivity : ComponentActivity() {

    private val handler: MishkaCliCommandHandler by inject()
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MishkaCliTransfer.responseFile(this).delete()
        val encoded = intent.getStringExtra(EXTRA_REQUEST)
        if (encoded.isNullOrBlank()) {
            writeResponse(MishkaCliResponse(ok = false, error = MishkaCliError("invalid_request", "missing request")))
            return
        }
        lifecycleScope.launch {
            lifecycle.withResumed { }
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
                    val request = json.decodeFromString(MishkaCliRequest.serializer(), raw)
                    handler.handle(request)
                }.getOrElse { error ->
                    MishkaCliResponse(
                        ok = false,
                        error = MishkaCliError(
                            code = "failed",
                            message = error.message.orEmpty().ifBlank { error::class.simpleName ?: "command failed" },
                        ),
                    )
                }
            }
            writeResponse(response)
        }
    }

    private fun writeResponse(response: MishkaCliResponse) {
        MishkaCliTransfer.responseFile(this).writeText(
            json.encodeToString(MishkaCliResponse.serializer(), response),
            Charsets.UTF_8,
        )
        finish()
    }

    companion object {
        const val EXTRA_REQUEST = "request"
    }
}
