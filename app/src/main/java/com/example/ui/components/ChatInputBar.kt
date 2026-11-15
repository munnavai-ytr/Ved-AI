package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.entity.SkillEntity
import com.example.data.util.AttachmentTypes
import com.example.data.util.ImageProcessor
import com.example.data.util.PendingAttachment

/**
 * Fixed, elevated bottom composer bar with optional editing banner, multi-file attachment
 * preview strip (images, PDFs, text/code), optional pinned skill chip, file picker trigger,
 * and Send <-> Stop morphing action button.
 */
@Composable
fun ChatInputBar(
  text: String,
  onTextChange: (String) -> Unit,
  isGenerating: Boolean,
  onSend: () -> Unit,
  onStop: () -> Unit,
  isEditing: Boolean = false,
  onCancelEdit: () -> Unit = {},
  pendingAttachments: List<PendingAttachment> = emptyList(),
  isGeminiActive: Boolean = true,
  onAttachCameraClick: () -> Unit = {},
  onAttachPhotosClick: () -> Unit = {},
  onAttachFilesClick: () -> Unit = {},
  onAttachUnsupportedClick: () -> Unit = {},
  onRemoveAttachment: (String) -> Unit = {},
  hasEnabledSkills: Boolean = false,
  pinnedSkill: SkillEntity? = null,
  onOpenSkillPicker: () -> Unit = {},
  isListening: Boolean = false,
  onVoiceInputClick: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val haptic = LocalHapticFeedback.current
  val canSend = text.isNotBlank() || pendingAttachments.isNotEmpty()
  var showAttachMenu by remember { mutableStateOf(false) }

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 14.dp, vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 4.dp,
      shadowElevation = 6.dp,
      modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = isEditing) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                  .padding(start = 16.dp, end = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.chat_editing_banner),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
              )
              IconButton(
                onClick = {
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  onCancelEdit()
                },
                modifier = Modifier.size(48.dp).testTag("cancel_edit_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Close,
                  contentDescription = stringResource(R.string.cd_cancel_edit),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          }
        }

        AnimatedVisibility(visible = isListening) {
          ListeningStatusBanner(onStopListening = onVoiceInputClick)
        }

        AnimatedVisibility(visible = pendingAttachments.isNotEmpty()) {
          LazyRow(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().testTag("pending_attachments_row"),
          ) {
            items(items = pendingAttachments, key = { it.id }) { attachment ->
              PendingAttachmentChip(
                attachment = attachment,
                onRemove = {
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  onRemoveAttachment(attachment.id)
                },
              )
            }
          }
        }

        if (pinnedSkill != null) {
          Row(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
              onClick = onOpenSkillPicker,
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
              modifier = Modifier.heightIn(min = 32.dp).testTag("pinned_skill_chip"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.AutoAwesome,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(14.dp),
                )
                Text(
                  text = stringResource(R.string.chat_skill_pill, pinnedSkill.name),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }

        Row(
          modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
          verticalAlignment = Alignment.Bottom,
        ) {
          Box {
            IconButton(
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (isGeminiActive) {
                  showAttachMenu = true
                } else {
                  onAttachUnsupportedClick()
                }
              },
              enabled = !isGenerating,
              modifier = Modifier.size(48.dp).testTag("attach_files_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = stringResource(R.string.cd_attach_files),
                tint =
                  if (isGeminiActive && !isGenerating) MaterialTheme.colorScheme.onSurfaceVariant
                  else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(22.dp),
              )
            }

            DropdownMenu(
              expanded = showAttachMenu,
              onDismissRequest = { showAttachMenu = false },
              modifier = Modifier.testTag("attach_options_menu"),
            ) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_camera)) },
                leadingIcon = {
                  Icon(imageVector = Icons.Outlined.PhotoCamera, contentDescription = null)
                },
                onClick = {
                  showAttachMenu = false
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  onAttachCameraClick()
                },
                modifier = Modifier.testTag("attach_option_camera"),
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_photos)) },
                leadingIcon = {
                  Icon(imageVector = Icons.Outlined.PhotoLibrary, contentDescription = null)
                },
                onClick = {
                  showAttachMenu = false
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  onAttachPhotosClick()
                },
                modifier = Modifier.testTag("attach_option_photos"),
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_files)) },
                leadingIcon = {
                  Icon(imageVector = Icons.Outlined.FolderOpen, contentDescription = null)
                },
                onClick = {
                  showAttachMenu = false
                  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  onAttachFilesClick()
                },
                modifier = Modifier.testTag("attach_option_files"),
              )
            }
          }

          VoiceMicIconButton(
            isListening = isListening,
            enabled = !isGenerating,
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onVoiceInputClick()
            },
          )

          if (hasEnabledSkills && pinnedSkill == null) {
            IconButton(
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onOpenSkillPicker()
              },
              enabled = !isGenerating,
              modifier = Modifier.size(48.dp).testTag("select_skill_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = stringResource(R.string.cd_select_skill),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            }
          }

          Box(
            modifier = Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
          ) {
            if (text.isEmpty()) {
              Text(
                text = stringResource(R.string.chat_input_placeholder),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            BasicTextField(
              value = text,
              onValueChange = onTextChange,
              textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                  color = MaterialTheme.colorScheme.onSurface
                ),
              cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
              maxLines = 5,
              keyboardOptions =
                KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
              modifier = Modifier.fillMaxWidth().testTag("chat_input_field"),
            )
          }

          Spacer(modifier = Modifier.width(6.dp))

          FilledIconButton(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              if (isGenerating) onStop() else onSend()
            },
            enabled = isGenerating || canSend,
            shape = CircleShape,
            colors =
              IconButtonDefaults.filledIconButtonColors(
                containerColor =
                  if (isGenerating) MaterialTheme.colorScheme.errorContainer
                  else MaterialTheme.colorScheme.primary,
                contentColor =
                  if (isGenerating) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
              ),
            modifier = Modifier.size(48.dp).testTag("send_or_stop_button"),
          ) {
            AnimatedContent(
              targetState = isGenerating,
              transitionSpec = {
                (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.85f)) togetherWith
                  (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f))
              },
              label = "send_stop_morph",
            ) { streaming ->
              if (streaming) {
                Icon(
                  imageVector = Icons.Rounded.Stop,
                  contentDescription = stringResource(R.string.cd_stop_generation),
                  modifier = Modifier.size(22.dp),
                )
              } else {
                Icon(
                  imageVector = Icons.Rounded.ArrowUpward,
                  contentDescription = stringResource(R.string.cd_send_message),
                  modifier = Modifier.size(22.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PendingAttachmentChip(
  attachment: PendingAttachment,
  onRemove: () -> Unit,
) {
  if (attachment.type == AttachmentTypes.IMAGE) {
    val context = LocalContext.current
    val bitmap by
      produceState<Bitmap?>(initialValue = null, key1 = attachment.localPath) {
        value = ImageProcessor.loadThumbnailBitmap(context, attachment.localPath, maxPx = 256)
      }

    Box(modifier = Modifier.size(64.dp)) {
      val loaded = bitmap
      if (loaded != null) {
        Image(
          bitmap = loaded.asImageBitmap(),
          contentDescription = attachment.displayName,
          contentScale = ContentScale.Crop,
          modifier =
            Modifier.size(64.dp)
              .clip(RoundedCornerShape(12.dp))
              .testTag("pending_image_preview"),
        )
      } else {
        Box(
          modifier =
            Modifier.size(64.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
      }

      Surface(
        onClick = onRemove,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
        modifier =
          Modifier.align(Alignment.TopEnd)
            .padding(3.dp)
            .size(22.dp)
            .testTag("remove_attachment_${attachment.id}"),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.cd_remove_attachment, attachment.displayName),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(14.dp),
          )
        }
      }
    }
  } else {
    val icon =
      when (attachment.type) {
        AttachmentTypes.PDF -> Icons.Outlined.PictureAsPdf
        AttachmentTypes.AUDIO -> Icons.Outlined.MusicNote
        AttachmentTypes.VIDEO -> Icons.Outlined.Videocam
        else -> Icons.Outlined.Description
      }
    val badgeText =
      when (attachment.type) {
        AttachmentTypes.PDF -> stringResource(R.string.chat_attachment_pdf_badge)
        AttachmentTypes.AUDIO -> stringResource(R.string.chat_attachment_audio_badge)
        AttachmentTypes.VIDEO -> stringResource(R.string.chat_attachment_video_badge)
        else -> null
      }

    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      modifier = Modifier.heightIn(min = 48.dp).testTag("pending_file_chip"),
    ) {
      Row(
        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.widthIn(max = 140.dp)) {
          Text(
            text = attachment.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (badgeText != null) {
            Text(
              text = badgeText,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        IconButton(
          onClick = onRemove,
          modifier = Modifier.size(36.dp).testTag("remove_attachment_${attachment.id}"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.cd_remove_attachment, attachment.displayName),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun VoiceMicIconButton(
  isListening: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  IconButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.size(48.dp).testTag("mic_input_button"),
  ) {
    if (isListening) {
      val pulseTransition = rememberInfiniteTransition(label = "mic_pulse")
      val pulseAlpha by
        pulseTransition.animateFloat(
          initialValue = 0.45f,
          targetValue = 1f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(durationMillis = 550),
              repeatMode = RepeatMode.Reverse,
            ),
          label = "mic_pulse_alpha",
        )
      Box(
        modifier =
          Modifier.size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = pulseAlpha)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.Mic,
          contentDescription = stringResource(R.string.cd_stop_listening),
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(20.dp),
        )
      }
    } else {
      Icon(
        imageVector = Icons.Outlined.Mic,
        contentDescription = stringResource(R.string.cd_voice_input),
        tint =
          if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
          else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.size(22.dp),
      )
    }
  }
}

@Composable
private fun ListeningStatusBanner(onStopListening: () -> Unit) {
  val transition = rememberInfiniteTransition(label = "voice_banner_pulse")
  val dotAlpha by
    transition.animateFloat(
      initialValue = 0.3f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 500),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "voice_banner_dot",
    )

  Column(modifier = Modifier.fillMaxWidth().testTag("voice_listening_indicator")) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
          .padding(horizontal = 16.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier =
          Modifier.size(8.dp)
            .alpha(dotAlpha)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
      )
      Text(
        text = stringResource(R.string.chat_listening),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.weight(1f),
      )
      Surface(
        onClick = onStopListening,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.heightIn(min = 32.dp),
      ) {
        Text(
          text = stringResource(R.string.action_stop_speaking),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  }
}
