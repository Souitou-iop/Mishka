package top.yukonga.mishka.cli

import android.content.Context
import java.io.File

internal object MishkaCliTransfer {
    private const val BACKUP_FILE = "mishka-cli-backup.zip"
    private const val RESPONSE_FILE = "mishka-cli-response.json"

    fun backupFile(context: Context): File = File(context.cacheDir, BACKUP_FILE)

    fun responseFile(context: Context): File = File(context.cacheDir, RESPONSE_FILE)
}
