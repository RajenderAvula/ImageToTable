package com.example.imagetotable.ui

import androidx.compose.foundation.background
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
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun TableCalendarView(
    currentTableDateTime: String,
    onDateBound: (String) -> Unit
) {
    var currentYearMonth by remember { mutableStateOf(YearMonth.now()) }
    val daysInMonth = currentYearMonth.lengthOfMonth()
    val firstDayOfWeek = currentYearMonth.atDay(1).dayOfWeek.value % 7

    val boundDateOnly = remember(currentTableDateTime) {
        currentTableDateTime.take(10) // Extracts YYYY-MM-DD
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        elevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEach {
                    Text(it, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

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
                            val dayDate = currentYearMonth.atDay(dayNumber).toString()
                            val isTableDate = boundDateOnly == dayDate

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        color = if (isTableDate) Color(0xFF1E88E5) else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        val timePart = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                                        onDateBound("$dayDate $timePart")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$dayNumber",
                                    fontSize = 12.sp,
                                    color = if (isTableDate) Color.White else Color.Black,
                                    fontWeight = if (isTableDate) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap any date above to re-bind the entire table to that day.",
                fontSize = 10.sp,
                color = Color.DarkGray,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
