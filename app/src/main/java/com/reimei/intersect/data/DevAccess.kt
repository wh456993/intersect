package com.reimei.intersect.data

import java.security.MessageDigest

/**
 * 开发者入口的口令校验。
 *
 * 源码里只留盐和摘要,不留明文口令:校验时把「盐 + 输入」算一次 SHA-256 再比对摘要,
 * 所以从反编译出来的 APK 里也读不到口令本身,只能靠猜(8 位、含大小写/数字/符号)。
 */
object DevAccess {
  /** 口令长度固定 8 位;长度不对就不必算哈希了。 */
  const val PASSWORD_LENGTH = 8

  private const val SALT = "wecompose-dev-salt-2026"

  /** SHA-256(SALT + 口令) 的小写十六进制。 */
  private const val DIGEST = "278381085a2fad854f163a5d1330957976de79bc014829933444a896a0126155"

  private const val HEX = "0123456789abcdef"

  /** 输入正确返回 true;只做定长比对,不记录输错次数。 */
  fun verify(input: String): Boolean {
    if (input.length != PASSWORD_LENGTH) return false
    return sha256(SALT + input) == DIGEST
  }

  private fun sha256(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val builder = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
      val value = byte.toInt() and 0xFF
      builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
    return builder.toString()
  }
}
