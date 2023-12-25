# FixSeekableWebmKotlinJs

`JavaScript`の`MediaRecorder`で作った`WebM`ファイルはシークできないので、シークできるようにする Kotlin/JS 製の Web ページ。

![Imgur](https://imgur.com/m3WR6y6.png)

# URL

https://webm.negitoro.dev

# 開発者向け

## 開発環境構築
`Kotlin`で書かれており、`Kotlin/JS`（Node.js ではなくブラウザ）で吐き出されたものがこちらです。  
以下が必要です。

- IntelliJ IDEA

手順です。

- git clone でこのリポジトリを
- IDEA でこのリポジトリを開く
- Gradle sync が終わるのを待つ
- Gradle のタスクを実行します
  - `gradle jsRun --continuous`
  - ここからタスクを起動するやつを起動できます
    - ![Imgur](https://imgur.com/DCbnm8d.png)
- しばらく待つとブラウザが起動するはず

## デプロイ手順
以下のコマンドだと思います。

- IDEA の Execute Gradle Task から
  - `gradle build`
- ターミナルから
  - `./gradlew build`

ビルド結果は`build/dist/js/productionExecutable`の中にあるので、このフォルダの中身を適当な静的サイトホスティングサービスに上げれば公開できると思います。  
このサイトは`S3 + CloudFront`なので、ビルドしてビルド結果の中身を`S3`バケットに入れて、`CloudFront`のキャッシュを消す必要があります。  
`GitHub Actions`作ってないので、手動で更新してね...