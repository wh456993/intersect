package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.data.Item
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.ui.theme.AppTheme

/**
 * 物品管理器页:中间是已添加物品的目录,最底部固定一个「添加物品」。
 *
 * 每件物品一行:名称 + 简介(可留空),右侧「删除」可以把它移除。
 */
@Composable
fun ItemManagerPage(
  modifier: Modifier = Modifier,
  items: List<Item>,
  onBack: () -> Unit,
  onAdd: () -> Unit,
  onDelete: (Item) -> Unit,
) {
  BackHandler { onBack() }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = "物品管理器", onBack = onBack)
    Box(
      Modifier
        .weight(1f)
        .fillMaxWidth()
    ) {
      if (items.isEmpty()) {
        EmptyItemHint()
      } else {
        LazyColumn(
          Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        ) {
          items(items, key = { it.id }) { item ->
            ItemRow(item = item, onDelete = { onDelete(item) })
            Spacer(Modifier.height(8.dp))
          }
        }
      }
    }
    AddItemButton(onClick = onAdd)
  }
}

@Composable
private fun EmptyItemHint() {
  Column(
    Modifier
      .fillMaxSize()
      .padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Text("还没有添加物品", fontSize = 16.sp, color = AppTheme.colors.textPrimary)
  }
}

@Composable
private fun ItemRow(item: Item, onDelete: () -> Unit) {
  var confirmDelete by remember { mutableStateOf(false) }

  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(AppTheme.colors.listItem)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        item.name,
        fontSize = 16.sp,
        color = AppTheme.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (item.description.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
          item.description,
          fontSize = 12.sp,
          color = AppTheme.colors.textSecondary,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    Spacer(Modifier.width(8.dp))
    Text(
      "删除",
      Modifier
        .clickable { confirmDelete = true }
        .padding(horizontal = 4.dp, vertical = 4.dp),
      fontSize = 13.sp,
      color = AppTheme.colors.more
    )
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("删除这件物品?", color = AppTheme.colors.textPrimary, fontSize = 16.sp) },
      text = { Text("删除后无法恢复。", fontSize = 13.sp, color = AppTheme.colors.textSecondary) },
      confirmButton = {
        Text(
          "删除",
          Modifier
            .clickable {
              confirmDelete = false
              onDelete()
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.badge
        )
      },
      dismissButton = {
        Text(
          "取消",
          Modifier
            .clickable { confirmDelete = false }
            .padding(horizontal = 8.dp, vertical = 4.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    )
  }
}

@Composable
private fun AddItemButton(onClick: () -> Unit) {
  Box(
    Modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(horizontal = 12.dp, vertical = 12.dp)
      .clip(RoundedCornerShape(10.dp))
      .background(AppTheme.colors.iconCurrent)
      .clickable(onClick = onClick)
      .padding(vertical = 13.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      "添加物品",
      fontSize = 16.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.listItem
    )
  }
}

/**
 * 添加物品页:名称 + 简介,没有字数要求。
 *
 * 名称必填(它是关键词扫描的依据),简介可留空。填完点「完成」落盘,返回则丢弃。
 */
@Composable
fun AddItemPage(
  modifier: Modifier = Modifier,
  onBack: () -> Unit,
  onSave: (name: String, description: String) -> Unit,
) {
  BackHandler { onBack() }

  var name by remember { mutableStateOf("") }
  var description by remember { mutableStateOf("") }
  val trimmedName = name.trim()
  val canSave = trimmedName.isNotEmpty()

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = "添加物品",
      actionText = "完成",
      actionEnabled = canSave,
      onBack = onBack,
      onAction = {
        if (!canSave) return@EditTopBar
        onSave(trimmedName, description.trim())
      }
    )

    Column(
      Modifier
        .fillMaxSize()
        .imePadding()
        .verticalScroll(rememberScrollState())
    ) {
      Column(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
      ) {
        ItemNameField(
          label = "名称",
          value = name,
          onValueChange = { name = it }
        )
        ItemFormDivider()
        ItemDescriptionField(
          label = "简介",
          value = description,
          onValueChange = { description = it }
        )
      }
    }
  }
}

/** 名称输入行:单行,不限制字数。 */
@Composable
private fun ItemNameField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(label, Modifier.width(64.dp), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.weight(1f),
      textStyle = TextStyle(
        fontSize = 16.sp,
        color = AppTheme.colors.textPrimary,
        textAlign = TextAlign.End
      ),
      singleLine = true,
      cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
    )
  }
}

/** 简介输入区:多行,不限制字数。 */
@Composable
private fun ItemDescriptionField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Text(label, fontSize = 16.sp, color = AppTheme.colors.textPrimary)
    Spacer(Modifier.height(8.dp))
    Box(
      Modifier
        .fillMaxWidth()
        .heightIn(min = 96.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(AppTheme.colors.textFieldBackground)
        .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
      if (value.isEmpty()) {
        Text("可留空,补充这件物品的说明", fontSize = 14.sp, color = AppTheme.colors.more)
      }
      BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontSize = 14.sp, color = AppTheme.colors.textPrimary),
        cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
      )
    }
  }
}

@Composable
private fun ItemFormDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(start = 16.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}
