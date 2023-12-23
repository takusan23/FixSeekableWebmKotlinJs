import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.FileReader
import org.w3c.files.get
import webm.FixWebmSeek
import kotlin.js.Date

fun main() {
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
            seekableWebmByteArray.downloadFromByteArray()

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

/** ByteArray をダウンロードさせる */
private fun ByteArray.downloadFromByteArray() {
    // Blob Url
    val blob = Blob(arrayOf(this), BlobPropertyBag(type = "video/webm"))
    val blobUrl = URL.createObjectURL(blob)
    // <a> タグを作って押す
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = blobUrl
    anchor.download = "fix-seekable-webm-kotlinjs-${Date.now()}.webm"
    document.body?.appendChild(anchor)
    anchor.click()
    anchor.remove()
}