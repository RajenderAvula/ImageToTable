package com.example.imagetotable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun TableCalendarView(
    activeDates: Set<String>,
    selectedDate: String?,
    onDateSelected: (String?) -> Unit
) {
    var currentYearMonth by remember { mutableStateOf(YearMonth.now()) }
    val daysInMonth = currentYearMonth.lengthOfMonth()
    val firstDayOfWeek = currentYearMonth.atDay(1).dayOfWeek.value % 7 // 0 for Sunday

    Card(
        shape = RoundedCornerShape(8.dp),
        elevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Calendar Month Navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { currentYearMonth = currentYearMonth.minusMonths(1) }) {
                    Text("◀", fontSize = 16.sp)
                }
                Text(
                    text = "${currentYearMonth.month.name} ${currentYearMonth.year}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                IconButton(onClick = { currentYearMonth = currentYearMonth.plusMonths(1) }) {
                    Text("▶", fontSize = 16.sp)
                }
            }

            // Day Labels
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEach {
                    Text(it, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            // Calendar Days Matrix
            val totalSlots = firstDayOfWeek + daysInMonth
            val rows = (totalSlots + 6) / 7

            for (r in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    for (c in 0..6) {
                        val dayNumber = (r * 7 + c) - firstDayOfWeek + 1
                        if (dayNumber in 1..daysInMonth) {
                            val dateString = currentYearMonth.atDay(dayNumber).toString()
                            val hasEntries = activeDates.contains(dateString)
                            val isSelected = selectedDate == dateString

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        color = if (isSelected) Color(0xFF1E88E5) else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        if (isSelected) onDateSelected(null) else onDateSelected(dateString)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "$dayNumber",
                                        fontSize = 12.sp,
                                        color = if (isSelected) Color.White else Color.Black
                                    )
                                    if (hasEntries) {
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .background(
                                                    if (isSelected) Color.White else Color(0xFF2E7D32),
                                                    CircleShape
                                                )
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }

            if (selectedDate != null) {
                TextButton(
                    onClick = { onDateSelected(null) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Clear Date Filter ($selectedDate)", fontSize = 11.sp, color = Color.Red)
                }
            }
        }
    }
}
