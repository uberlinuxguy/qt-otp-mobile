package com.qtotp.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtotp.mobile.R

/**
 * The app's mark, sized by height because it is a tall shape — the kitten adds
 * roughly half again to the badge it sits on, so asking for a square would
 * either crop it or leave the badge tiny.
 */
@Composable
fun VaultMark(
    height: Dp = 120.dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.ic_vault_mark),
        contentDescription = contentDescription,
        modifier = modifier.height(height),
    )
}
