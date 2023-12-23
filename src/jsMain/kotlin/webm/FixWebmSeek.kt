package webm

object FixWebmSeek {

    /**
     * WebM をパースする
     *
     * @param webmFileByteArray WebM ファイル
     * @return [MatroskaElement]の配列
     */
    fun parseWebm(webmFileByteArray: ByteArray): List<MatroskaElement> {

        /**
         * [byteArray] から次の EBML のパースを行う
         *
         * @param byteArray EBML の[ByteArray]
         * @param startPosition EBML 要素の開始位置
         * @return パース結果[MatroskaElement]と、パースしたデータの位置
         */
        fun parseEbmlElement(byteArray: ByteArray, startPosition: Int): Pair<MatroskaElement, Int> {
            var currentPosition = startPosition

            // ID をパース
            val idLength = byteArray[currentPosition].getElementLength()
            val idByteArray = byteArray.copyOfRange(currentPosition, currentPosition + idLength)
            val matroskaId = idByteArray.toMatroskaId()
            currentPosition += idLength

            // DataSize をパース
            val dataSizeLength = byteArray[currentPosition].getElementLength()
            // JavaScript の MediaRecorder は Segment / Cluster が DataSize が長さ不明になる
            val dataSize = byteArray.copyOfRange(currentPosition, currentPosition + dataSizeLength).getDataSize().let { dataSizeOrUnknown ->
                if (dataSizeOrUnknown == -1) {
                    // 長さ不明の場合は上から舐めて出す
                    val dataByteArray = byteArray.copyOfRange(currentPosition + UNKNOWN_DATA_SIZE.size, byteArray.size)
                    when (matroskaId) {
                        MatroskaId.Segment -> dataByteArray.analyzeUnknownDataSizeForSegmentData()
                        MatroskaId.Cluster -> dataByteArray.analyzeUnknownDataSizeForClusterData()
                        else -> throw RuntimeException("Cluster Segment 以外は長さ不明に対応していません；；")
                    }
                } else {
                    // 長さが求まっていればそれを使う
                    dataSizeOrUnknown
                }
            }
            currentPosition += dataSizeLength

            // Data を取り出す
            val dataByteArray = byteArray.copyOfRange(currentPosition, currentPosition + dataSize)
            currentPosition += dataSize

            // 返す
            val matroskaElement = MatroskaElement(matroskaId, dataByteArray)
            return matroskaElement to currentPosition
        }

        /**
         * [byteArray] から EBML 要素をパースする。
         * 親要素の場合は子要素まで再帰的に見つける。
         *
         * 一つだけ取りたい場合は [parseEbmlElement]
         * @param byteArray WebM のバイト配列
         * @return 要素一覧
         */
        fun parseAllEbmlElement(byteArray: ByteArray): List<MatroskaElement> {
            var readPosition = 0
            val elementList = arrayListOf<MatroskaElement>()

            while (true) {
                // 要素を取得し位置を更新
                val (element, currentPosition) = parseEbmlElement(byteArray, readPosition)
                elementList += element
                readPosition = currentPosition

                // 親要素の場合は子要素の解析をする
                if (element.matroskaId.isParent) {
                    val children = parseAllEbmlElement(element.data)
                    elementList += children
                }

                // 次のデータが 3 バイト以上ない場合は break（解析できない）
                // 3 バイトの理由ですが ID+DataSize+Data それぞれ1バイト以上必要なので
                if (byteArray.size <= readPosition + 3) {
                    break
                }
            }

            return elementList
        }

        // WebM の要素を全部パースする
        return parseAllEbmlElement(webmFileByteArray)
    }

    /**
     * [parseWebm]で出来た要素一覧を元に、シークできる WebM を作成する
     *
     * @param elementList [parseWebm]
     * @return シークできる WebM のバイナリ
     */
    fun fixSeekableWebM(elementList: List<MatroskaElement>): ByteArray {

        /** SimpleBlock と Timestamp から動画の時間を出す */
        fun getVideoDuration(): Int {
            val lastTimeStamp = elementList.filterId(MatroskaId.Timestamp).last().data.toInt()
            // Cluster は 2,3 バイト目が相対時間になる
            val lastRelativeTime = elementList.filterId(MatroskaId.SimpleBlock).last().data.copyOfRange(1, 3).toInt()
            return lastTimeStamp + lastRelativeTime
        }

        /** 映像トラックの番号を取得 */
        fun getVideoTrackNumber(): Int {
            var videoTrackIndex = -1
            var latestTrackNumber = -1
            var latestTrackType = -1
            for (element in elementList) {
                if (element.matroskaId == MatroskaId.TrackNumber) {
                    latestTrackNumber = element.data.toInt()
                }
                if (element.matroskaId == MatroskaId.TrackType) {
                    latestTrackType = element.data.toInt()
                }
                if (latestTrackType != -1 && latestTrackNumber != -1) {
                    if (latestTrackType == 1) {
                        videoTrackIndex = latestTrackNumber
                        break
                    }
                }
            }
            return videoTrackIndex
        }

        /**
         * Cluster を見て [MatroskaId.CuePoint] を作成する
         *
         * @param clusterStartPosition Cluster 開始位置
         * @return CuePoint
         */
        fun createCuePoint(clusterStartPosition: Int): List<MatroskaElement> {
            // Cluster を上から見ていって CuePoint を作る
            val cuePointList = mutableListOf<MatroskaElement>()
            // 前回追加した Cluster の位置
            var prevPosition = clusterStartPosition
            elementList.forEachIndexed { index, element ->
                if (element.matroskaId == MatroskaId.Cluster) {
                    // Cluster の子から時間を取り出して Cue で使う
                    var childIndex = index
                    var latestTimestamp = -1
                    var latestSimpleBlockRelativeTime = -1
                    while (true) {
                        val childElement = elementList[childIndex++]
                        // Cluster のあとにある Timestamp を控える
                        if (childElement.matroskaId == MatroskaId.Timestamp) {
                            latestTimestamp = childElement.data.toInt()
                        }
                        // Cluster から見た相対時間
                        if (childElement.matroskaId == MatroskaId.SimpleBlock) {
                            latestSimpleBlockRelativeTime = childElement.data.copyOfRange(1, 3).toInt()
                        }
                        // Cluster の位置と時間がわかったので
                        if (latestTimestamp != -1 && latestSimpleBlockRelativeTime != -1) {
                            cuePointList += MatroskaElement(
                                MatroskaId.CuePoint,
                                byteArrayOf(
                                    *MatroskaElement(MatroskaId.CueTime, (latestTimestamp + latestSimpleBlockRelativeTime).toByteArray()).toEbmlByteArray(),
                                    *MatroskaElement(
                                        MatroskaId.CueTrackPositions,
                                        byteArrayOf(
                                            *MatroskaElement(MatroskaId.CueTrack, getVideoTrackNumber().toByteArray()).toEbmlByteArray(),
                                            *MatroskaElement(MatroskaId.CueClusterPosition, prevPosition.toByteArray()).toEbmlByteArray()
                                        )
                                    ).toEbmlByteArray()
                                )
                            )
                            break
                        }
                    }
                    // 進める
                    prevPosition += element.toEbmlByteArray().size
                }
            }
            return cuePointList
        }

        /** SeekHead を組み立てる */
        fun reclusiveCreateSeekHead(
            infoByteArraySize: Int,
            tracksByteArraySize: Int,
            clusterByteArraySize: Int
        ): MatroskaElement {

            /**
             * SeekHead を作成する
             * 注意しないといけないのは、SeekHead に書き込んだ各要素の位置は、SeekHead 自身のサイズを含めた位置にする必要があります。
             * なので、SeekHead のサイズが変わった場合、この後の Info Tracks の位置もその分だけズレていくので、注意が必要。
             */
            fun createSeekHead(seekHeadSize: Int): MatroskaElement {
                val infoPosition = seekHeadSize
                val tracksPosition = infoPosition + infoByteArraySize
                val clusterPosition = tracksPosition + tracksByteArraySize
                // Cue は最後
                val cuePosition = clusterPosition + clusterByteArraySize
                // トップレベル要素、この子たちの位置を入れる
                val topLevelElementList = listOf(
                    MatroskaId.Info to infoPosition,
                    MatroskaId.Tracks to tracksPosition,
                    MatroskaId.Cluster to clusterPosition,
                    MatroskaId.Cues to cuePosition
                ).map { (tag, position) ->
                    MatroskaElement(
                        MatroskaId.Seek,
                        byteArrayOf(
                            *MatroskaElement(MatroskaId.SeekID, tag.byteArray).toEbmlByteArray(),
                            *MatroskaElement(MatroskaId.SeekPosition, position.toByteArray()).toEbmlByteArray()
                        )
                    )
                }
                val seekHead = MatroskaElement(MatroskaId.SeekHead, topLevelElementList.map { it.toEbmlByteArray() }.concatByteArray())
                return seekHead
            }

            // まず一回 SeekHead 自身のサイズを含めない SeekHead を作る。
            // これで SeekHead 自身のサイズが求められるので、SeekHead 自身を考慮した SeekHead を作成できる。
            var prevSeekHeadSize = createSeekHead(0).toEbmlByteArray().size
            var seekHead: MatroskaElement
            while (true) {
                seekHead = createSeekHead(prevSeekHeadSize)
                val seekHeadSize = seekHead.toEbmlByteArray().size
                // サイズが同じになるまで SeekHead を作り直す
                if (prevSeekHeadSize == seekHeadSize) {
                    break
                } else {
                    prevSeekHeadSize = seekHeadSize
                }
            }

            return seekHead
        }

        // Duration 要素を作る
        val durationElement = MatroskaElement(MatroskaId.Duration, getVideoDuration().toFloat().toBits().toByteArray())
        // Duration を追加した Info を作る
        val infoElement = elementList.filterId(MatroskaId.Info).first().let { before ->
            before.copy(data = before.data + durationElement.toEbmlByteArray())
        }

        // ByteArray にしてサイズが分かるように
        val infoByteArray = infoElement.toEbmlByteArray()
        val tracksByteArray = elementList.filterId(MatroskaId.Tracks).first().toEbmlByteArray()
        val clusterByteArray = elementList.filterId(MatroskaId.Cluster).map { it.toEbmlByteArray() }.concatByteArray()
        // SeekHead を作る
        val seekHeadByteArray = reclusiveCreateSeekHead(
            infoByteArraySize = infoByteArray.size,
            tracksByteArraySize = tracksByteArray.size,
            clusterByteArraySize = clusterByteArray.size
        ).toEbmlByteArray()

        // Cues を作る
        val cuePointList = createCuePoint(seekHeadByteArray.size + infoByteArray.size + tracksByteArray.size)
        val cuesByteArray = MatroskaElement(MatroskaId.Cues, cuePointList.map { it.toEbmlByteArray() }.concatByteArray()).toEbmlByteArray()

        // Segment 要素に書き込むファイルが完成
        // 全部作り直しになる副産物として DataSize が不定長ではなくなります。
        val segment = MatroskaElement(
            MatroskaId.Segment,
            byteArrayOf(
                *seekHeadByteArray,
                *infoByteArray,
                *tracksByteArray,
                *clusterByteArray,
                *cuesByteArray
            )
        )

        // シークできるように修正した WebM のバイナリ
        // WebM 先頭の EBML を忘れずに、これは書き換える必要ないのでそのまま
        return byteArrayOf(
            *elementList.filterId(MatroskaId.EBML).first().toEbmlByteArray(),
            *segment.toEbmlByteArray()
        )
    }
}