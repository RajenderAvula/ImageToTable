package com.example.imagetotable.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun ImageCropperDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onCropConfirmed: (Bitmap) -> Unit
) {
    var cropLeftPct by remember { mutableFloatStateOf(0.05f) }
    var cropRightPct by remember { mutableFloatStateOf(0.95f) }
    var cropTopPct by remember { mutableFloatStateOf(0.10f) }
    var cropBottomPct by remember { mutableFloatStateOf(0.90f) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(8.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Adjust Table Crop Margins", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))

                // Image Preview with Highlighted Crop Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = sourceBitmap.asImageBitmap(),
                        contentDescription = "Original",
                        modifier = Modifier.fillMaxSize()
                    )

                    // Crop Selection Outline
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = (cropLeftPct * 200).dp,
                                end = ((1f - cropRightPct) * 200).dp,
                                top = (cropTopPct * 200).dp,
                                bottom = ((1f - cropBottomPct) * 200).dp
                            )
                            .border(2.dp, Color.Green)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Interactive Sliders for Margins
                Text("Left Margin: ${(cropLeftPct * 100).toInt()}%", fontSize = 11.sp)
                Slider(value = cropLeftPct, onValueChange = { if (it < cropRightPct - 0.05f) cropLeftPct = it })

                Text("Right Margin: ${(cropRightPct * 100).toInt()}%", fontSize = 11.sp)
                Slider(value = cropRightPct, onValueChange = { if (it > cropLeftPct + 0.05f) cropRightPct = it })

                Text("Top Margin: ${(cropTopPct * 100).toInt()}%", fontSize = 11.sp)
                Slider(value = cropTopPct, onValueChange = { if (it < cropBottomPct - 0.05f) cropTopPct = it })

                Text("Bottom Margin: ${(cropBottomPct * 100).toInt()}%", fontSize = 11.sp)
                Slider(value = cropBottomPct, onValueChange = { if (it > cropTopPct + 0.05f) cropBottomPct = it })

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val startX = (cropLeftPct * sourceBitmap.width).toInt().coerceIn(0, sourceBitmap.width - 1)
                            val startY = (cropTopPct * sourceBitmap.height).toInt().coerceIn(0, sourceBitmap.height - 1)
                            val width = ((cropRightPct - cropLeftPct) * sourceBitmap.width).toInt().coerceAtLeast(10)
                            val height = ((cropBottomPct - cropTopPct) * sourceBitmap.height).toInt().coerceAtLeast(10)

                            val safeWidth = width.coerceAtMost(sourceBitmap.width - startX)
                            val safeHeight = height.coerceAtMost(sourceBitmap.height - startY)

                            val cropped = Bitmap.createBitmap(sourceBitmap, startX, startY, safeWidth, safeHeight)
                            onCropConfirmed(cropped)
                        }
                    ) {
                        Text("Apply Crop")
                    }
                }
            }
        }
    }
}
