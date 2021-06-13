package com.dirk.acamera.utils

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
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
