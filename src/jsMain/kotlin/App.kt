import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.mediacapture.MediaStream
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.FileReader
import org.w3c.files.get
import webm.FixWebmSeek
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.js.json

fun main() {
    setupScreenRecorde()
    setupWebmSeekable()
}

/** 画面録画のやつの初期化 */
private fun setupScreenRecorde() {
    val recordButtonElement = document.getElementById("record_button")!!
    var mediaStream: MediaStream? = null
    var mediaRecorder: dynamic = null

    /** 録画データ */
    val chunks = arrayListOf<Blob>()

    /** 録画中か */
    var isRecording = false

    fun startRecord() {
        // ここから先 Kotlin/JS 側で定義がなかったため全部 dynamic です。こわい！
        val mediaDevicesPrototype = js("window.navigator.mediaDevices")
        // Promise です
        mediaDevicesPrototype.getDisplayMedia(
            json(
                "audio" to json("channelCount" to 2),
                "video" to true
            )
        ).then { displayMedia: MediaStream ->
            mediaStream = displayMedia
            // どうやら変数とかはそのまま文字列の中に埋め込めば動くらしい。なんか気持ち悪いけど動くよ
            mediaRecorder = js(""" new MediaRecorder(displayMedia, { mimeType: 'video/webm; codecs="vp9"' }) """)
            // 録画データが細切れになって呼ばれる
            mediaRecorder.ondataavailable = { ev: dynamic ->
                chunks.add(ev.data as Blob)
                Unit
            }
            // 録画開始
            mediaRecorder.start(100)
            isRecording = true
            // 適当に値を返す
            Unit
        }.catch { err: dynamic -> console.log(err); window.alert("エラーが発生しました") }
    }

    fun stopRecord() {
        // 録画を止める
        isRecording = false
        mediaRecorder.stop()
        mediaStream?.getTracks()?.forEach { it.stop() }
        // Blob にして保存
        Blob(chunks.toTypedArray(), BlobPropertyBag(type = "video/webm"))
            .downloadFile(fileName = "javascript-mediarecorder-${Date.now()}.webm")
    }

    recordButtonElement.addEventListener("click", {
        if (!isRecording) {
            startRecord()
        } else {
            stopRecord()
        }
    })
}

/** WebM をシーク可能にするやつの初期化 */
private fun setupWebmSeekable() {
    val buttonElement = document.getElementById("start_button")!!
    val statusElement = document.getElementById("status_text")!!
    val inputElement = document.getElementById("webm_picker")!! as HTMLInputElement

    // ボタンを押した時
    buttonElement.addEventListener("click", {

        // 選択したファイル
        val selectWebmFile = inputElement.files?.get(0)
        if (selectWebmFile == null) {
            window.alert("WebM ファイルを選択して下さい")
            return@addEventListener
        }

        // JavaScript の FileReader で WebM の ByteArray を取得する
        val fileReader = FileReader()
        fileReader.onload = {
            // ロード完了
            statusElement.textContent = "ロード完了"

            // WebM のバイナリを取る
            val webmByteArray = (fileReader.result as ArrayBuffer).asByteArray()

            // 処理を始める
            // パース処理
            statusElement.textContent = "WebM を分解しています"
            val elementList = FixWebmSeek.parseWebm(webmByteArray)

            // 修正と再組み立て
            statusElement.textContent = "WebM へシーク時に必要な値を書き込んでいます"
            val seekableWebmByteArray = FixWebmSeek.fixSeekableWebM(elementList)

            // ダウンロード
            statusElement.textContent = "終了しました"
            Blob(arrayOf(seekableWebmByteArray), BlobPropertyBag(type = "video/webm"))
                .downloadFile(fileName = "fix-seekable-webm-kotlinjs-${Date.now()}.webm")

            // なんか適当に値を返しておく必要がある？
            Unit
        }

        // ロード開始
        statusElement.textContent = "ロード中です"
        fileReader.readAsArrayBuffer(selectWebmFile)
    })
}

/** JS の ArrayBuffer を Kotlin の ByteArray にする */
private fun ArrayBuffer.asByteArray(): ByteArray = Int8Array(this).unsafeCast<ByteArray>()

/** [Blob]をダウンロードする */
private fun Blob.downloadFile(fileName: String) {
    // Blob Url
    val blobUrl = URL.createObjectURL(this)
    // <a> タグを作って押す
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = blobUrl
    anchor.download = fileName
    document.body?.appendChild(anchor)
    anchor.click()
    anchor.remove()
}