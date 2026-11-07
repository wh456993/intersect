package com.reimei.intersect.data

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 加解密失败:口令不对、文件被改动,或者本机缺少需要的算法。 */
class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 一段用口令加密出来的密文,连同解密它所需的参数。
 *
 * [kdf] / [iterations] / [salt] / [iv] 都要跟密文一起存进备份文件:
 * 换一台机器、换一个系统版本,只要这些参数还在,同样的口令就能算出同一把钥匙。
 */
class EncryptedBlock(
  val kdf: String,
  val iterations: Int,
  val salt: ByteArray,
  val iv: ByteArray,
  val ciphertext: ByteArray,
)

/**
 * 口令加密:PBKDF2 派生密钥 + AES-256-GCM 加密。
 *
 * 为什么是口令而不是非对称密钥:备份文件要能脱离本机使用(换机恢复、拷给别人),
 * 私钥存在本机 Keystore 里的话文件一离开这台机器就解不开了。代价是安全强度取决于口令本身——
 * 6 位纯数字的空间只有一百万种,只能靠 KDF 的迭代次数把每次尝试的成本做高([ITERATIONS]),
 * 所以口令一定要自己保管好,备份文件也不要随手发给别人。
 *
 * 每次加密都用新的随机盐与 IV(同口令不同文件、同文件不同批次密文都不同),
 * GCM 自带的认证标签同时负责机密性与完整性:口令错或文件被改过,解密会直接失败,不会返回半份数据。
 */
object BackupCrypto {
  const val CIPHER = "AES/GCM/NoPadding"

  /** 密钥长度:256 位,Android 平台一直支持(不像 JRE 需要装无限制强度策略)。 */
  const val KEY_BITS = 256

  /** KDF 迭代次数:把 6 位数字口令的暴力破解成本拉到可感知的量级。 */
  const val ITERATIONS = 200_000

  const val SALT_BYTES = 16
  const val IV_BYTES = 12
  const val TAG_BITS = 128

  /** API 26 起才有 PBKDF2-HMAC-SHA256;更低的系统回落到 SHA1。 */
  const val KDF_SHA256 = "PBKDF2WithHmacSHA256"
  const val KDF_SHA1 = "PBKDF2WithHmacSHA1"

  private val random = SecureRandom()

  /** 本机优先用的 KDF;写进备份文件的 `encryption.kdf`,解密时按它来。 */
  fun preferredKdf(): String =
    if (runCatching { SecretKeyFactory.getInstance(KDF_SHA256) }.isSuccess) KDF_SHA256 else KDF_SHA1

  /**
   * 用 [password] 加密 [plaintext]。
   *
   * [aad] 是「附加认证数据」:不加密但参与完整性校验,备份文件用它来锁定自己的明文头
   * (schema 版本、内容范围),防止有人把头部改掉再让你去解密。
   */
  fun encrypt(
    password: String,
    plaintext: ByteArray,
    aad: ByteArray,
    iterations: Int = ITERATIONS
  ): EncryptedBlock {
    val kdf = preferredKdf()
    val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
    val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
    val key = derive(password, salt, iterations, kdf)
    val cipher = try {
      Cipher.getInstance(CIPHER)
    } catch (e: Exception) {
      throw BackupCryptoException("本机不支持 $CIPHER", e)
    }
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
    cipher.updateAAD(aad)
    val ciphertext = try {
      cipher.doFinal(plaintext)
    } catch (e: Exception) {
      throw BackupCryptoException("加密失败: ${e.message}", e)
    }
    return EncryptedBlock(kdf, iterations, salt, iv, ciphertext)
  }

  /**
   * 用 [password] 解密 [block]。
   *
   * @throws BackupCryptoException 口令不对、文件被改动或其参数非法
   */
  fun decrypt(password: String, block: EncryptedBlock, aad: ByteArray): ByteArray {
    if (block.salt.size != SALT_BYTES || block.iv.size != IV_BYTES) {
      throw BackupCryptoException("备份文件的加密参数不完整")
    }
    // 密文至少要有 GCM 的认证标签那么长;再短连校验都做不了
    if (block.ciphertext.size < TAG_BITS / 8) {
      throw BackupCryptoException("备份文件内容为空")
    }
    val key = derive(password, block.salt, block.iterations, block.kdf)
    val cipher = try {
      Cipher.getInstance(CIPHER)
    } catch (e: Exception) {
      throw BackupCryptoException("本机不支持 $CIPHER", e)
    }
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, block.iv))
    cipher.updateAAD(aad)
    return try {
      cipher.doFinal(block.ciphertext)
    } catch (e: AEADBadTagException) {
      // GCM 的认证标签不匹配:口令错和文件被改在密码学上没有区别,只能给同一个提示
      throw BackupCryptoException("口令不正确,或文件内容已被改动", e)
    } catch (e: Exception) {
      throw BackupCryptoException("解密失败: ${e.message}", e)
    }
  }

  private fun derive(password: String, salt: ByteArray, iterations: Int, kdf: String): ByteArray {
    if (iterations < 1) throw BackupCryptoException("备份文件的加密参数非法")
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
    return try {
      SecretKeyFactory.getInstance(kdf).generateSecret(spec).encoded
    } catch (e: Exception) {
      throw BackupCryptoException("本机不支持密钥派生算法 $kdf", e)
    } finally {
      spec.clearPassword()
    }
  }
}
