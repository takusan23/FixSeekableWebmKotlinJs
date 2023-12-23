package webm

/**
 * EBMLの要素を表すデータクラス
 *
 * @param matroskaId [MatroskaId]
 * @param data データ
 */
data class MatroskaElement(
    val matroskaId: MatroskaId,
    val data: ByteArray
)