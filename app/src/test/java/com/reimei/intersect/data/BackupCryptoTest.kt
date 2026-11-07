package com.reimei.intersect.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 口令加密的单元测试。
 *
 * 这里覆盖的都是「错了用户也不会知道」的地方:口令不对必须失败、文件被改必须失败、
 * 同口令两次加密的密文必须不同。迭代次数在测试里调小(生产是 20 万次),
 * 只验证逻辑,不验证耗时。
 */
class BackupCryptoTest {
  private val password = "123456"
  private val plaintext = "{\"hello\":\"世界\"}".toByteArray(Charsets.UTF_8)
  private val aad = "intersect.backup.v1|sections=chats".toByteArray(Charsets.UTF_8)

  private fun encrypt(
    pass: String = password,
    aad: ByteArray = this.aad,
    iterations: Int = 2_000
  ): EncryptedBlock = BackupCrypto.encrypt(pass, plaintext, aad, iterations)

  @Test
  fun `同口令加密后能解回原文`() {
    val block = encrypt()
    assertArrayEquals(plaintext, BackupCrypto.decrypt(password, block, aad))
  }

  @Test
  fun `盐与 IV 每次都是新的`() {
    val first = encrypt()
    val second = encrypt()
    assertFalse(first.salt.contentEquals(second.salt))
    assertFalse(first.iv.contentEquals(second.iv))
    // 密文因此也不会相同:同一条消息重复备份不会产生可比的密文
    assertFalse(first.ciphertext.contentEquals(second.ciphertext))
  }

  @Test
  fun `参数被完整带出来,换机器也能解`() {
    val block = encrypt()
    assertEquals(BackupCrypto.SALT_BYTES, block.salt.size)
    assertEquals(BackupCrypto.IV_BYTES, block.iv.size)
    assertEquals(2_000, block.iterations)
    assertTrue(block.kdf == BackupCrypto.KDF_SHA256 || block.kdf == BackupCrypto.KDF_SHA1)
    // 密文 = 明文 + 16 字节 GCM 认证标签
    assertEquals(plaintext.size + BackupCrypto.TAG_BITS / 8, block.ciphertext.size)
  }

  @Test
  fun `口令错了解密失败`() {
    val block = encrypt()
    try {
      BackupCrypto.decrypt("654321", block, aad)
      fail("错误口令不应该解出内容")
    } catch (e: BackupCryptoException) {
      assertTrue(e.message.orEmpty().contains("口令"))
    }
  }

  @Test
  fun `密文被改动就会解密失败`() {
    val block = encrypt()
    val tampered = block.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    val broken = EncryptedBlock(block.kdf, block.iterations, block.salt, block.iv, tampered)
    try {
      BackupCrypto.decrypt(password, broken, aad)
      fail("密文被改动后仍然解出了内容")
    } catch (e: BackupCryptoException) {
      // GCM 的认证标签负责这件事
    }
  }

  @Test
  fun `附加认证数据不一致就解不开`() {
    val block = encrypt()
    val otherAad = "intersect.backup.v1|sections=characters".toByteArray(Charsets.UTF_8)
    try {
      BackupCrypto.decrypt(password, block, otherAad)
      fail("AAD 不一致时不应该解密成功")
    } catch (e: BackupCryptoException) {
      // 文件头声明的内容范围被改过,正是这条兜住的
    }
  }

  @Test
  fun `空内容也能正常加密`() {
    val block = BackupCrypto.encrypt(password, ByteArray(0), aad, 1_000)
    assertArrayEquals(ByteArray(0), BackupCrypto.decrypt(password, block, aad))
  }

  @Test
  fun `参数不完整时报错而不是崩溃`() {
    val block = encrypt()
    val bad = EncryptedBlock(block.kdf, block.iterations, ByteArray(3), block.iv, block.ciphertext)
    try {
      BackupCrypto.decrypt(password, bad, aad)
      fail("盐长度不合法时不应该继续解密")
    } catch (e: BackupCryptoException) {
      assertNotEquals("", e.message.orEmpty())
    }
  }
}
