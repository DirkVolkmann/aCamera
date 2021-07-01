package com.dirk.acamera.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.BulletSpan

fun buildBulletList(array: Array<out String>, gapWidth: Int = BulletSpan.STANDARD_GAP_WIDTH): CharSequence {
    val sStringBuilder = SpannableStringBuilder()
    array.forEach {
        val sString = SpannableString(it)
        sString.setSpan(BulletSpan(gapWidth), 0, sString.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        sStringBuilder.append(sString)
        sStringBuilder.append("\n")
    }
    sStringBuilder.delete(sStringBuilder.length - 1, sStringBuilder.length) // delete last "\n"
    return sStringBuilder
}

// TODO: Add support for IPv6 and/or hostname
fun getDeviceIp(context: Context): String {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
}

data class Ratio(val width: Int, val height: Int)

fun reduceRatio(width: Int, height: Int) : Ratio {
    // take care of the simple case
    if (width == height) return Ratio(1, 1);

    // make sure numerator is always the larger number
    var isVertical = (width < height)

    val divisor = greatestCommonDivisor(width, height)

    var left: Int
    var right: Int
    if (!isVertical) {
        left = width / divisor;
        right = height / divisor;
    } else {
        left = height / divisor;
        right = width / divisor;
    }

    // handle special cases
    if (8 == left && 5 == right) {
        left = 16;
        right = 10;
    }

    return Ratio(left, right)
}

fun greatestCommonDivisor(val1: Int, val2: Int): Int {
    var n1 = val1
    var n2 = val2
    while (n1 != n2) {
        if (n1 > n2)
            n1 -= n2
        else
            n2 -= n1
    }
    return n1
}
