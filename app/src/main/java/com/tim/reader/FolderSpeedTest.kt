package com.tim.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Measures how fast Android's folder permission system is in the chosen library folder. Everything
 * happens inside one new test folder, which is deleted at the end.
 */
suspend fun runFolderSpeedTest(context: Context, tree: Uri) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val fileCount = 30
    val fileBytes = 1024 * 1024
    var testFolder: Uri? = null
    try {
        ResultsLog.add("--- Library folder speed")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val folder = DocumentsContract.createDocument(
            resolver,
            root,
            DocumentsContract.Document.MIME_TYPE_DIR,
            "Quartz Reader speed test",
        ) ?: error("Android couldn't create the test folder")
        testFolder = folder

        val payload = Random.nextBytes(fileBytes)
        val files = timed("Write $fileCount files of 1 MB") {
            List(fileCount) { index ->
                val file = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", "test-$index.bin")
                    ?: error("Android couldn't create test file $index")
                resolver.openOutputStream(file)!!.use { it.write(payload) }
                file
            }
        }

        val listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        timed("List the folder 20 times") {
            repeat(20) {
                resolver.query(
                    listing,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null,
                )?.use { rows -> while (rows.moveToNext()) rows.getString(0) }
            }
        }

        timed("Read $fileCount files of 1 MB") {
            files.forEach { file -> resolver.openInputStream(file)!!.use { it.readBytes() } }
        }

        timed("Open a file 100 times") {
            repeat(100) { resolver.openFileDescriptor(files.first(), "r")?.close() }
        }
    } catch (error: Throwable) {
        ResultsLog.add("FAILED folder test: ${error::class.simpleName}: ${error.message}")
    } finally {
        testFolder?.let { folder ->
            try {
                DocumentsContract.deleteDocument(resolver, folder)
            } catch (error: Throwable) {
                ResultsLog.add("Couldn't delete the test folder: ${error.message}")
            }
        }
    }
}
