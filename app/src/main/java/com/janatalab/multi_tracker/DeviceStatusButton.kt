package com.janatalab.multi_tracker

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun DeviceStatusButton(viewModel: MultiViewModel, showButton: Boolean) {
    val coroutineScope = rememberCoroutineScope()

    if (showButton) {
        Button(
            modifier = Modifier
                .padding(16.dp)
                .height(50.dp)                 // Taller button
                .width(180.dp),               // Wider button (or use .fillMaxWidth())
            shape = RoundedCornerShape(50),   // Elliptical shape
            onClick = {
            coroutineScope.launch {
                viewModel.checkDeviceStatus()
            }
        }) {
            Text("Check Device Status")
        }
    }
}
