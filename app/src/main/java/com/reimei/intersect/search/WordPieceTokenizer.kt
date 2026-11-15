package com.reimei.intersect.search

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自研 WordPiece 分词器(BERT 风格),供 [EmbeddingEngine] 把文本切成 bge-micro-v2 的输入 id 序列。
 *
 * 词表来自 assets/models/vocab.txt(30522 行,BERT uncased 词表):
 * 第 i 行(0 起)的字符串对应的 token id 就是 i,与模型训练时的约定一致。
 *
 * 分词 = BasicTokenizer(先切出"词") + WordpieceTokenizer(词内再切成词表子词):
 * 1. 清洗:去掉控制字符,空白归一;
 * 2. 按空白切分;
 * 3. 中日韩表意文字逐字独立(汉字本身就是词,不需要空格分隔);
 * 4. 标点逐字独立;
 * 5. 词表是 uncased 的,做 lowercase + 去音标(NFD 分解后丢弃组合记号);
 * 6. WordPiece 贪心最长匹配:每词从左往右取词表里最长的前缀子词,
 *    续段加 "##" 前缀("##" 表示"接在前一子词之后");
 *    任何一步找不到匹配就整词记 [UNK](逐词粒度,不吞掉整句)。
 *
 * 大小写策略自动探测:词表含 "the" 而不含 "THE" 即 uncased(做 lowercase),
 * 反之 cased(原样保留)——换模型不需要改代码。
 */
class WordPieceTokenizer private constructor(
  private val vocab: Map<String, Int>,
  private val lowercase: Boolean,
) {

  /** 分词结果:id 序列 + 掩码 + token 类型,可直接喂 ONNX 三个输入张量。 */
  class EncodedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray,
  )

  companion object {
    const val PAD_TOKEN = "[PAD]"
    const val UNK_TOKEN = "[UNK]"
    const val CLS_TOKEN = "[CLS]"
    const val SEP_TOKEN = "[SEP]"

    /** BERT 词表的固定 id(词表里查不到特殊符号时的兜底,与 bert-base 一致)。 */
    private const val FALLBACK_PAD_ID = 0L
    private const val FALLBACK_UNK_ID = 100L
    private const val FALLBACK_CLS_ID = 101L
    private const val FALLBACK_SEP_ID = 102L

    /** 单词最多切 100 个字符,防止病态长词让贪心匹配退化为 O(n2)。 */
    private const val MAX_INPUT_CHARS_PER_WORD = 100

    /** 模型上限:bge-micro-v2 的 max_seq_length = 512。 */
    const val MAX_SEQ_LEN = 512

    /** 在 Dispatchers.IO 上读 assets 构建分词器(读文件是 I/O,严禁主线程)。 */
    suspend fun loadFromAssets(context: Context): WordPieceTokenizer =
      withContext(Dispatchers.IO) {
        val map = HashMap<String, Int>(32768)
        context.assets.open("models/vocab.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
          lines.forEachIndexed { index, token -> map[token] = index }
        }
        // uncased 探测:标准 BERT uncased 词表有小写 "the"、没有大写 "THE"
        val lower = map.containsKey("the") && !map.containsKey("THE")
        WordPieceTokenizer(map, lower)
      }
  }

  private val unkId = vocab[UNK_TOKEN] ?: FALLBACK_UNK_ID.toInt()
  private val clsId = vocab[CLS_TOKEN] ?: FALLBACK_CLS_ID.toInt()
  private val sepId = vocab[SEP_TOKEN] ?: FALLBACK_SEP_ID.toInt()

  /** 纯分词(不带特殊符号),供展示/调试;[encode] 才是模型输入。 */
  fun tokenize(text: String): List<String> {
    val out = ArrayList<String>()
    for (piece in basicTokenize(text)) {
      wordPieceTokenize(piece, out)
    }
    return out
  }

  /**
   * 编码为模型输入:[CLS] + 词片段 + [SEP],超过 [maxSeqLen] 截断(保头保尾)。
   * attention_mask 逐位 1(无 padding,单句推理);token_type_ids 全 0(单句)。
   */
  fun encode(text: String, maxSeqLen: Int = MAX_SEQ_LEN): EncodedInput {
    val pieces = tokenize(text)
    // 为 [CLS]/[SEP] 预留 2 个位置
    val budget = maxOf(maxSeqLen - 2, 0)
    val kept = if (pieces.size > budget) pieces.subList(0, budget) else pieces

    val ids = LongArray(kept.size + 2)
    ids[0] = clsId.toLong()
    for (i in kept.indices) ids[i + 1] = vocab[kept[i]]?.toLong() ?: unkId.toLong()
    ids[ids.size - 1] = sepId.toLong()

    return EncodedInput(
      inputIds = ids,
      attentionMask = LongArray(ids.size) { 1L },
      tokenTypeIds = LongArray(ids.size) { 0L },
    )
  }

  // ---------- BasicTokenizer ----------

  private fun basicTokenize(text: String): List<String> {
    val cleaned = cleanText(text)
    val out = ArrayList<String>()
    for (whitespaceToken in cleaned.split(Regex("\\s+"))) {
      if (whitespaceToken.isEmpty()) continue
      // lowercase + 去音标必须在标点/中日韩切分之前做,与 HF BertTokenizer 的顺序一致
      val normalized = maybeNormalize(whitespaceToken)
      var current = StringBuilder()
      for (ch in normalized) {
        when {
          isCjkChar(ch) -> {
            if (current.isNotEmpty()) { out.add(current.toString()); current = StringBuilder() }
            out.add(ch.toString())
          }
          isPunctuation(ch) -> {
            if (current.isNotEmpty()) { out.add(current.toString()); current = StringBuilder() }
            out.add(ch.toString())
          }
          else -> current.append(ch)
        }
      }
      if (current.isNotEmpty()) out.add(current.toString())
    }
    return out
  }

  /** 去控制字符、空白归一。 */
  private fun cleanText(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
      when {
        ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(' ')
        // Character.CONTROL 用 ISO control 判定,含 C0/C1 控制码
        Character.isISOControl(ch) -> Unit
        else -> sb.append(ch)
      }
    }
    return sb.toString()
  }

  private fun maybeNormalize(token: String): String {
    if (!lowercase) return token
    // 1) NFD 分解(如 "é" → "e" + 组合重音) 2) 丢弃组合记号(Mn 类) 3) 小写
    // 数学上等价于去掉所有 NonSpacingMark:token' = lower(NFD(token)) \ {c : type(c)=Mn}
    val decomposed = Normalizer.normalize(token, Normalizer.Form.NFD)
    val sb = StringBuilder(decomposed.length)
    for (ch in decomposed) {
      // Character.NON_SPACING_MARK 是 Java Byte 常量,比较前转 Int
      if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
    }
    return sb.toString().lowercase(Locale.ROOT)
  }

  /**
   * 是否 CJK 表意文字(与 BERT BasicTokenizer 的区间一致):这些字逐字成词,
   * 覆盖扩展 A/兼容/扩展 B~D 等区段;区间两端闭合。
   */
  private fun isCjkChar(ch: Char): Boolean {
    val cp = ch.code
    return cp in 0x4E00..0x9FFF   // CJK 统一表意
        || cp in 0x3400..0x4DBF   // 扩展 A
        || cp in 0x20000..0x2A6DF // 扩展 B
        || cp in 0x2A700..0x2B73F // 扩展 C
        || cp in 0x2B740..0x2B81F // 扩展 D
        || cp in 0x2B820..0x2CEAF // 扩展 E
        || cp in 0xF900..0xFAFF   // 兼容表意
        || cp in 0x2F800..0x2FA1F // 兼容补充
  }

  /** 标点判定:ASCII 标点区段 + Unicode 标点类别(Pe/Pf/Pi/Pc/Pd/Ps/Po)。 */
  private fun isPunctuation(ch: Char): Boolean {
    val cp = ch.code
    if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
    return when (Character.getType(ch)) {
      // Java Byte 常量,when 分支比较前转 Int
      Character.CONNECTOR_PUNCTUATION.toInt(),
      Character.DASH_PUNCTUATION.toInt(),
      Character.START_PUNCTUATION.toInt(),
      Character.END_PUNCTUATION.toInt(),
      Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
      Character.FINAL_QUOTE_PUNCTUATION.toInt(),
      Character.OTHER_PUNCTUATION.toInt() -> true
      else -> false
    }
  }

  // ---------- WordpieceTokenizer ----------

  /**
   * 贪心最长匹配:对 token 的字符区间 [start, end) 从右往左收缩,
   * 找到词表里最长的前缀子词即切下,剩余部分继续;
   * 非首位子词加 "##" 前缀再查表。
   * 复杂度 O(词长2),由 [MAX_INPUT_CHARS_PER_WORD] 封顶。
   */
  private fun wordPieceTokenize(token: String, out: MutableList<String>) {
    if (token.length > MAX_INPUT_CHARS_PER_WORD) {
      out.add(UNK_TOKEN) // 整词过长直接 [UNK]
      return
    }
    var start = 0
    val pieces = ArrayList<String>()
    while (start < token.length) {
      var end = token.length
      var matched: String? = null
      while (start < end) {
        val raw = token.substring(start, end)
        val candidate = if (start > 0) "##$raw" else raw
        if (vocab.containsKey(candidate)) {
          matched = candidate
          break
        }
        end -= 1
      }
      if (matched == null) {
        // 任一残段切不出来,整词 [UNK](与 HF WordpieceTokenizer 语义一致)
        out.add(UNK_TOKEN)
        return
      }
      pieces.add(matched)
      start = end
    }
    out.addAll(pieces)
  }
}
