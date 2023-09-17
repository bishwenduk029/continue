package com.github.continuedev.continueintellijextension.`continue`

import com.github.continuedev.continueintellijextension.`continue`.DiffManager

import com.github.continuedev.continueintellijextension.services.ContinuePluginService
import com.github.continuedev.continueintellijextension.utils.dispatchEventToWebview
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.*
import okhttp3.*
import java.net.NetworkInterface

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.awt.RelativePoint
import java.awt.Color
import java.io.File

data class WebSocketMessage<T>(val messageType: String, val data: T)
data class WorkspaceDirectory(val workspaceDirectory: String);
data class UniqueId(val uniqueId: String);
data class ReadFile(val contents: String);
data class VisibleFiles(val visibleFiles: List<String>);
data class Position(val line: Int, val character: Int);
data class Range(val start: Position, val end: Position);
data class RangeInFile(val filepath: String, val range: Range);

fun getMachineUniqueID(): String {
    val sb = StringBuilder()
    val networkInterfaces = NetworkInterface.getNetworkInterfaces()

    while (networkInterfaces.hasMoreElements()) {
        val networkInterface = networkInterfaces.nextElement()
        val mac = networkInterface.hardwareAddress

        if (mac != null) {
            for (i in mac.indices) {
                sb.append(String.format("%02X%s", mac[i], if (i < mac.size - 1) "-" else ""))
            }
            return sb.toString()
        }
    }

    return "No MAC Address Found"
}

class IdeProtocolClient(
    private val serverUrl: String = "ws://localhost:65432/ide/ws",
    private val continuePluginService: ContinuePluginService,
    private val textSelectionStrategy: TextSelectionStrategy,
    private val coroutineScope: CoroutineScope,
    private val workspacePath: String,
    private val project: Project
) {
    private val eventListeners = mutableListOf<WebSocketEventListener>()
    private var okHttpClient: OkHttpClient = OkHttpClient()
    private var webSocket: WebSocket? = null

    private val diffManager = DiffManager(project)

    init {
        initWebSocket()
    }

    var sessionId: String? = null

    fun getSessionIdAsync(): Deferred<String?> = coroutineScope.async {
        withTimeoutOrNull(10000) {
            while ((webSocket?.queueSize() ?: 0) > 0) {
                delay(1000)
            }
        }
        println("Getting session ID")
        val respDeferred = sendAndReceive("getSessionId", mapOf())
        val resp = respDeferred.await()  // Awaiting the deferred response
        println(resp)
        val data = (resp as? Map<*, *>)?.get("data") as? Map<*, *>
        sessionId = data?.get("sessionId").toString()
        println("New Continue session with ID: $sessionId")
        sessionId
    }

    private val pendingResponses: MutableMap<String, CompletableDeferred<Any>> =
        mutableMapOf()

    fun sendAndReceive(
        messageType: String,
        data: Map<String, Any>
    ): CompletableDeferred<Any> {
        val deferred = CompletableDeferred<Any>()
        pendingResponses[messageType] =
            deferred  // Store the deferred object for later resolution

        sendMessage(messageType, data)
        return deferred
    }

    private fun serializeMessage(data: Map<String, Any>): String {
        val gson = Gson()
        return gson.toJson(data)
    }

    private fun initWebSocket() {
        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // handle onOpen
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                coroutineScope.launch(Dispatchers.Main) {
                    val parsedMessage: Map<String, Any> = Gson().fromJson(
                        text,
                        object : TypeToken<Map<String, Any>>() {}.type
                    )
                    val messageType = parsedMessage["messageType"] as? String
                    val data = parsedMessage["data"] as Map<String, Any>
                    when (messageType) {
                        "workspaceDirectory" -> {
                            webSocket?.send(
                                    Gson().toJson(
                                            WebSocketMessage(
                                                    "workspaceDirectory",
                                                    WorkspaceDirectory(workspaceDirectory())
                                            )
                                    )
                            );
                        }
                        "uniqueId" -> webSocket?.send(Gson().toJson(WebSocketMessage("uniqueId", UniqueId(uniqueId()))))
                        "showDiff" -> {
                            diffManager.showDiff(data["filepath"] as String, data["replacement"] as String, data["step_index"] as Int)
                        }
                        "readFile" -> {
                            val msg = ReadFile(readFile(data["filepath"] as String))
                            webSocket?.send(Gson().toJson(WebSocketMessage("readFile", msg)))
                        }
                        "visibleFiles" -> {
                            val msg = VisibleFiles(visibleFiles())
                            webSocket?.send(Gson().toJson(WebSocketMessage("visibleFiles", msg)))
                        }
                        "saveFile" -> saveFile(data["filepath"] as String)
                        "showVirtualFile" -> showVirtualFile(data["name"] as String, data["contents"] as String)
                        "connected" -> {}
                        "showMessage" -> showMessage(data["message"] as String)
                        "setFileOpen" -> setFileOpen(data["filepath"] as String, data["open"] as Boolean)
                        "highlightCode" -> {
                            val gson = Gson()
                            val json = gson.toJson(data["rangeInFile"])
                            val type = object : TypeToken<RangeInFile>() {}.type
                            val rangeInFile = gson.fromJson<RangeInFile>(json, type)
                            highlightCode(rangeInFile, data["color"] as String)
                        }
                        else -> {
                            println("Unknown messageType")
                        }
                    }

                    if (messageType != null) {
                        pendingResponses[messageType]?.complete(parsedMessage)
                        pendingResponses.remove(messageType)
                    }
                }
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?
            ) {
                eventListeners.forEach { it.onErrorOccurred(t) }
            }
        }
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, webSocketListener)
    }

    fun addEventListener(listener: WebSocketEventListener) {
        eventListeners.add(listener)
    }

    fun connect() {
        // Connection is handled automatically by OkHttp
    }

    fun disconnect() {
        webSocket?.close(1000, null)
    }

    private fun sendMessage(messageType: String, message: Map<String, Any>) {
        val sendData = mapOf("messageType" to messageType, "data" to message)
        val jsonMessage = serializeMessage(sendData)
        webSocket?.send(jsonMessage)
    }

    fun workspaceDirectory(): String {
        return this.workspacePath
    }

    fun uniqueId(): String {
        return getMachineUniqueID()
    }

    fun onTextSelected(
        selectedText: String,
        filepath: String,
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int
    ) = coroutineScope.launch {
        val jsonMessage = textSelectionStrategy.handleTextSelection(
            selectedText,
            filepath,
            startLine,
            startCharacter,
            endLine,
            endCharacter
        );
        sendMessage("highlightedCodePush", jsonMessage)
        dispatchEventToWebview(
            "highlightedCode",
            jsonMessage,
            continuePluginService.continuePluginWindow.webView
        )
    }

    fun readFile(filepath: String): String {
        val file = LocalFileSystem.getInstance().findFileByPath(filepath) ?: return ""
        val documentManager = FileDocumentManager.getInstance()
        val document: Document? = documentManager.getDocument(file)
        return document?.text ?: ""
    }

    fun saveFile(filepath: String) {
        val file = LocalFileSystem.getInstance().findFileByPath(filepath) ?: return
        val fileDocumentManager = FileDocumentManager.getInstance()
        val document = fileDocumentManager.getDocument(file)

        document?.let {
            fileDocumentManager.saveDocument(it)
        }
    }

    fun setFileOpen(filepath: String, open: Boolean = true) {
        val file = LocalFileSystem.getInstance().findFileByPath(filepath)

        file?.let {
            if (open) {
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
            } else {
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).closeFile(it)
                }
            }
        }
    }

    fun showVirtualFile(name: String, contents: String) {
        val virtualFile = LightVirtualFile(name, contents)
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    fun visibleFiles(): List<String> {
        val fileEditorManager = FileEditorManager.getInstance(project)
        return fileEditorManager.openFiles.toList().map{it.path}
    }

    fun showMessage(msg: String) {
        val statusBar = WindowManager.getInstance().getStatusBar(project)

        JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(msg, MessageType.INFO, null)
                .setFadeoutTime(5000)
                .createBalloon()
                .show(RelativePoint.getSouthEastOf(statusBar.component), Balloon.Position.atRight)
    }

    fun highlightCode(rangeInFile: RangeInFile, color: String) {
        val file = LocalFileSystem.getInstance().findFileByPath(rangeInFile.filepath)

        setFileOpen(rangeInFile.filepath, true)

        ApplicationManager.getApplication().invokeLater {
            val editor = file?.let {
                val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(it)
                (fileEditor as? TextEditor)?.editor
            }

            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(File(rangeInFile.filepath))
            val document = FileDocumentManager.getInstance().getDocument(virtualFile!!)
            val startIdx = document!!.getLineStartOffset(rangeInFile.range.start.line) + rangeInFile.range.start.character
            val endIdx = document!!.getLineEndOffset(rangeInFile.range.end.line) + rangeInFile.range.end.character

            val markupModel = editor!!.markupModel
//            val textAttributes = TextAttributes(Color.decode(color.drop(1).toInt(color)), null, null, null, 0)

//            markupModel.addRangeHighlighter(startIdx, endIdx, 0, textAttributes, HighlighterTargetArea.EXACT_RANGE)
        }
    }
}

interface TextSelectionStrategy {
    fun handleTextSelection(
        selectedText: String,
        filepath: String,
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int
    ): Map<String, Any>
}

class DefaultTextSelectionStrategy : TextSelectionStrategy {

    override fun handleTextSelection(
        selectedText: String,
        filepath: String,
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int
    ): Map<String, Any> {

        return mapOf(
            "highlightedCode" to arrayOf(
                mapOf(
                    "filepath" to filepath,
                    "contents" to selectedText,
                    "range" to mapOf(
                        "start" to mapOf(
                            "line" to startLine,
                            "character" to startCharacter
                        ),
                        "end" to mapOf(
                            "line" to endLine,
                            "character" to endCharacter
                        )
                    )
                )
            )
        )
    }
}