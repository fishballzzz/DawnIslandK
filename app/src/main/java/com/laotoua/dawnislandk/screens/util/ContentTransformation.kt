/*
 *  Copyright 2020 Fishballzzz
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.laotoua.dawnislandk.screens.util

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.*
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import com.laotoua.dawnislandk.DawnApp
import com.laotoua.dawnislandk.R
import com.laotoua.dawnislandk.screens.widgets.spans.HideSpan
import com.laotoua.dawnislandk.screens.widgets.spans.ReferenceSpan
import com.laotoua.dawnislandk.screens.widgets.spans.SegmentSpacingSpan
import com.laotoua.dawnislandk.util.ReadableTime
import java.time.LocalDateTime
import java.util.regex.Matcher
import java.util.regex.Pattern

object ContentTransformation {
    private val REFERENCE_PATTERN = Pattern.compile(">>?(?:No.)?(\\d+)")
    private val URL_PATTERN =
        Pattern.compile("(http|https)://[a-z0-9A-Z%-]+(\\.[a-z0-9A-Z%-]+)+(:\\d{1,5})?(/[a-zA-Z0-9-_~:#@!&',;=%/*.?+$\\[\\]()]+)?/?")
    private val AC_PATTERN = Pattern.compile("ac\\d+")
    private val HIDE_PATTERN = Pattern.compile("\\[h](.+?)\\[/h]")
    private const val CUSTOM_HIDE_PATTERN_OPEN = "`-hide-`"
    private const val CUSTOM_HIDE_PATTERN_CLOSE = "`/-hide-`"
    private val CUSTOM_HIDE_PATTERN =
        Pattern.compile("$CUSTOM_HIDE_PATTERN_OPEN(.+?)$CUSTOM_HIDE_PATTERN_CLOSE")

    @Suppress("DEPRECATION")
    fun htmlToSpanned(string: String): Spanned {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Html.fromHtml(string)
        } else {
            Html.fromHtml(string, Html.FROM_HTML_MODE_COMPACT)
        }
    }

    fun transformForumName(forumName: String) = htmlToSpanned(forumName)

    fun transformCookie(userId: String, admin: String, po: String = ""): Spannable {
        /**
         * 处理饼干
         * 普通饼干是灰色，红名是红色
         */
        val cookie = SpannableString(userId)
        if (admin == "1") {
            val adminColor = ForegroundColorSpan(Color.parseColor("#FF0F0F"))
            cookie.setSpan(adminColor, 0, cookie.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        if (userId == po) {
            cookie.setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                cookie.length,
                Spannable.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }
        return cookie
    }

    fun transformTime(now: String): String = ReadableTime.getDisplayTime(now)
    fun transformTime(now: LocalDateTime): String = ReadableTime.getDisplayTime(now)

    fun transformContent(
        context: Context,
        content: String,
        lineHeight: Int = DawnApp.applicationDataStore.lineHeight,
        segGap: Int = DawnApp.applicationDataStore.lineHeight,
        referenceClickListener: ReferenceSpan.ReferenceClickHandler? = null
    ): SpannableStringBuilder {
        return SpannableStringBuilder(htmlToSpanned(content))
            .replaceHideTag()
            .handleTextUrl()
            .handleReference(context, referenceClickListener)
            .handleAcUrl()
            .handleHideTag()
            .handleLineHeightAndSegGap(lineHeight, segGap)
    }

    private fun SpannableStringBuilder.replaceHideTag() = apply {
        val m: Matcher = HIDE_PATTERN.matcher(this)
        // surrounding [h][/h]
        val openTagLen = 3
        val closeTagLen = 4
        val newOpenTagLen = CUSTOM_HIDE_PATTERN_OPEN.length
        val newCloseTagLen = CUSTOM_HIDE_PATTERN_CLOSE.length
        val step = openTagLen + closeTagLen - newOpenTagLen - newCloseTagLen
        var matchCount = 0
        while (m.find()) {
            val start = m.start() - step * matchCount
            val end = m.end() - step * matchCount
            //  replace surrounding [h][/h] with new custom tag
            replace(
                start,
                end,
                CUSTOM_HIDE_PATTERN_OPEN + this.subSequence(
                    start + openTagLen,
                    end - closeTagLen
                ) + CUSTOM_HIDE_PATTERN_CLOSE
            )
            matchCount += 1
        }
    }

    private fun SpannableStringBuilder.handleLineHeightAndSegGap(lineHeight: Int, segGap: Int) =
        apply {
            // apply segGap if no clear newline in content
            val mSegGap = if (contains("\n\n")) lineHeight else segGap
            setSpan(
                SegmentSpacingSpan(lineHeight, mSegGap),
                0,
                length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }

    private fun SpannableStringBuilder.handleReference(
        context: Context,
        referenceClickListener: ReferenceSpan.ReferenceClickHandler? = null
    ) = apply {
        if (referenceClickListener != null) {
            val m: Matcher = REFERENCE_PATTERN.matcher(this)
            while (m.find()) {
                val start = m.start()
                val end = m.end()
                val referenceSpan = ReferenceSpan(m.group(1)!!, referenceClickListener)
                setSpan(
                    referenceSpan,
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    ForegroundColorSpan(context.resources.getColor(R.color.colorPrimary, null)),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    RelativeSizeSpan(1.1f),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun SpannableStringBuilder.handleTextUrl() = apply {
        val m: Matcher = URL_PATTERN.matcher(this)
        while (m.find()) {
            val start = m.start()
            val end = m.end()
            val links: Array<URLSpan> = getSpans(start, end, URLSpan::class.java)
            if (links.isNotEmpty()) {
                // There has been URLSpan already, leave it alone
                continue
            }
            val urlSpan = URLSpan(m.group(0))
            setSpan(urlSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun SpannableStringBuilder.handleAcUrl() = apply {
        val m: Matcher = AC_PATTERN.matcher(this)
        while (m.find()) {
            val start = m.start()
            val end = m.end()
            val links = this.getSpans(start, end, URLSpan::class.java)
            if (links.isNotEmpty()) {
                // There has been URLSpan already, leave it alone
                continue
            }
            val urlSpan = URLSpan("http://www.acfun.cn/v/" + m.group(0))
            this.setSpan(urlSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun SpannableStringBuilder.handleHideTag() = apply {
        val m: Matcher = CUSTOM_HIDE_PATTERN.matcher(this)
        val openTagLen = CUSTOM_HIDE_PATTERN_OPEN.length
        val closeTagLen = CUSTOM_HIDE_PATTERN_CLOSE.length
        val step = openTagLen + closeTagLen
        var matchCount = 0
        while (m.find()) {
            val start = m.start() - step * matchCount
            val end = m.end() - step * matchCount
            //  remove surrounding [h][/h]
            replace(start, end, this.subSequence(start + openTagLen, end - closeTagLen))

            val hideSpan = HideSpan(start, end - step)
            setSpan(hideSpan, start, end - step, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            hideSpan.hideSecret(this, start, end - step)
            matchCount += 1
        }
    }
}