package io.github.gcbond.openhc.pc

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 把二维码内容画成终端里的方块图（手机扫屏幕就行，不需要图形界面）。 */
internal object QrText {
    fun render(text: String, quietModules: Int = 2): String {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to quietModules)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val sb = StringBuilder()
        // 终端字符高约为宽的两倍，一行画两个模块的“上半个”近似正方形。
        for (y in 0 until matrix.height step 2) {
            for (x in 0 until matrix.width) {
                val top = matrix.get(x, y)
                val bottom = if (y + 1 < matrix.height) matrix.get(x, y + 1) else false
                sb.append(
                    when {
                        top && bottom -> '█'
                        top -> '▀'
                        bottom -> '▄'
                        else -> ' '
                    },
                )
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}