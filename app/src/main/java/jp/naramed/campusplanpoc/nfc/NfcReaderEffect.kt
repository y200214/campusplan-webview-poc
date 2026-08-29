package jp.naramed.campusplanpoc.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** NFC の利用可否 */
enum class NfcAvailability { AVAILABLE, DISABLED, UNSUPPORTED }

fun nfcAvailability(activity: Activity?): NfcAvailability {
    val adapter = activity?.let { NfcAdapter.getDefaultAdapter(it) }
        ?: return NfcAvailability.UNSUPPORTED
    return if (adapter.isEnabled) NfcAvailability.AVAILABLE else NfcAvailability.DISABLED
}

/**
 * この Composable が画面に出ている間だけ、FeliCa のリーダーモードを有効にする。
 *
 * 方針:
 *  - 読むのは画面を開いている間だけ。マニフェストに intent-filter を置いて
 *    バックグラウンドでタグを拾う作りにはしない。意図しないときに読まないため。
 *  - NFC-F のみを対象にする。決済カードなど他方式を無用に起こさない。
 *  - プラットフォームの音・画面遷移は止める（SKIP_NDEF_CHECK / NO_PLATFORM_SOUNDS）。
 *
 * @param onRead 読み取り結果。**ワーカースレッドで呼ばれる**ので UI 更新は呼び先で移すこと。
 */
@Composable
fun NfcReaderEffect(
    enabled: Boolean,
    onRead: (FelicaReader.Result) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnRead by rememberUpdatedState(onRead)

    DisposableEffect(enabled, activity, lifecycleOwner) {
        val adapter = activity?.let { NfcAdapter.getDefaultAdapter(it) }
        if (!enabled || activity == null || adapter == null) {
            return@DisposableEffect onDispose { }
        }

        val callback = NfcAdapter.ReaderCallback { tag ->
            FelicaReader.read(tag)?.let { currentOnRead(it) }
        }

        fun enable() {
            val extras = Bundle().apply {
                // ポーリング間隔を少し延ばして、読み取りの取りこぼしを減らす
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            }
            adapter.enableReaderMode(
                activity,
                callback,
                NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                extras,
            )
        }

        fun disable() = runCatching { adapter.disableReaderMode(activity) }

        // 画面が前面にある間だけ有効にする
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> enable()
                Lifecycle.Event.ON_PAUSE -> disable()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) enable()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            disable()
        }
    }
}
