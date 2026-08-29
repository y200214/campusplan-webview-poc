package jp.naramed.campusplanpoc.nfc

import android.nfc.Tag
import android.nfc.tech.NfcF
import android.util.Log

/**
 * Phase 6: 学生証（FeliCa）を読む。
 *
 * なぜ既定の NFC では反応しないのか:
 *   Android が黙って処理するのは NDEF を持つタグだけ。学生証の FeliCa は
 *   NDEF を持たないので、OS は「知らないカード」として何もしない。
 *   NfcF として明示的にポーリングして初めて読める。
 *
 * ここで読むもの:
 *   - IDm  : カードの製造番号にあたる 8 バイト。ポーリング応答にそのまま入っている
 *   - Edy 番号 : Edy のサービスから読む 16 桁。搭載カードでのみ取れる
 *
 * 識別子として使うなら IDm を推奨する。Edy 番号は決済手段の口座番号であり、
 * 「どの利用者か」を知るだけの用途に決済情報を持ち出す必要はない。
 *
 * セキュリティ上の注意（重要）:
 *   IDm も Edy 番号も **秘密ではない**。NFC が読める端末なら誰でも近づけるだけで
 *   読める。これらは「本人である証明」ではなく「本人だと名乗る識別子」でしかない。
 *   認証の要素として単独で使ってはいけない。
 */
object FelicaReader {

    private const val TAG = "FelicaReader"

    /** Edy のシステムコード。学生証に Edy が載っていればこれで応答する */
    private const val SYSTEM_CODE_EDY = 0xFE00

    /** 共通領域のシステムコード。多くの FeliCa が応答する */
    private const val SYSTEM_CODE_COMMON = 0xFE00

    /** どのシステムでもよいときのワイルドカード */
    private const val SYSTEM_CODE_ANY = 0xFFFF

    /** Edy のカード番号が入っているサービスコード */
    private const val SERVICE_CODE_EDY_CARD_NO = 0x110B

    data class Result(
        /** カードの製造番号（16 進 16 文字）。読めた場合は必ず入る */
        val idm: String,
        /** ポーリングに応答したシステムコード（16 進 4 文字） */
        val systemCode: String = "",
        /** Edy 番号（16 桁）。Edy 非搭載なら null */
        val edyNumber: String? = null,
        /** 読み取り中に起きた問題（部分的に読めた場合の補足） */
        val note: String? = null,
    )

    /**
     * タグから読める範囲を読む。ワーカースレッドから呼ぶこと（IO を伴う）。
     *
     * @return 読めなければ null（FeliCa でない、通信失敗など）
     */
    fun read(tag: Tag): Result? {
        val idm = tag.id?.toHex() ?: return null
        val nfcF = NfcF.get(tag) ?: run {
            // NfcF ではないカード（Type A/B）。IDm 相当だけ返しておく
            Log.d(TAG, "NfcF ではないタグ。ID のみ返す")
            return Result(idm = idm, note = "FeliCa ではないカードです")
        }

        return try {
            nfcF.connect()
            // NfcF が保持しているシステムコード（ポーリングで得たもの）
            val systemCode = nfcF.systemCode?.toHex().orEmpty()
            val edy = runCatching { readEdyNumber(nfcF, tag.id) }
                .onFailure { Log.d(TAG, "Edy 番号は読めなかった: ${it.javaClass.simpleName}") }
                .getOrNull()
            Result(
                idm = idm,
                systemCode = systemCode,
                edyNumber = edy,
                note = if (edy == null) "Edy 番号は読み取れませんでした" else null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "読み取り失敗: ${e.javaClass.simpleName}")
            Result(idm = idm, note = "カードとの通信に失敗しました")
        } finally {
            runCatching { nfcF.close() }
        }
    }

    /**
     * Edy のカード番号を読む。
     *
     * FeliCa の「Read Without Encryption」コマンドを組み立てて、
     * Edy のカード番号サービス（0x110B）のブロック 0 を 1 ブロックだけ読む。
     * 返る 16 バイトのうち先頭 8 バイトが番号（BCD）。
     */
    private fun readEdyNumber(nfcF: NfcF, idmBytes: ByteArray): String? {
        // コマンド: [len][0x06][IDm 8][サービス数 1][サービスコード 2(LE)][ブロック数 1][ブロック指定 2]
        val command = byteArrayOf(
            0x00,                                   // 長さ（後で埋める）
            0x06,                                   // Read Without Encryption
            *idmBytes,
            0x01,                                   // サービス数
            (SERVICE_CODE_EDY_CARD_NO and 0xFF).toByte(),
            ((SERVICE_CODE_EDY_CARD_NO shr 8) and 0xFF).toByte(),
            0x01,                                   // ブロック数
            0x80.toByte(), 0x00,                    // ブロックリスト（2 バイト形式・ブロック 0）
        )
        command[0] = command.size.toByte()

        val response = nfcF.transceive(command)
        // 応答: [len][0x07][IDm 8][ステータス1][ステータス2][ブロック数][データ 16...]
        if (response.size < 13) return null
        val status1 = response[10].toInt() and 0xFF
        val status2 = response[11].toInt() and 0xFF
        if (status1 != 0x00 || status2 != 0x00) {
            // このカードに Edy が載っていない、またはサービスが無い
            return null
        }
        if (response.size < 28) return null

        // 先頭 8 バイトが BCD の番号。16 桁になる
        val digits = buildString {
            for (i in 12 until 20) {
                val b = response[i].toInt() and 0xFF
                append((b shr 4) and 0x0F)
                append(b and 0x0F)
            }
        }
        return digits.takeIf { it.length == 16 }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }
}
