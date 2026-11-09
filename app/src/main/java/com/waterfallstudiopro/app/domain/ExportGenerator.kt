package com.waterfallstudiopro.app.domain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.waterfallstudiopro.app.model.BridgeResult
import com.waterfallstudiopro.app.model.ExecutivePalettes
import com.waterfallstudiopro.app.model.WaterfallAppState
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ExportGenerator {

    private fun escapeXml(str: String): String =
        str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    fun generateTsv(
        bridge: BridgeResult,
        state: WaterfallAppState,
        rawNumbersOnly: Boolean
    ): String {
        val baseVal = bridge.initialBase
        val decSep = if (state.displayLocale == "en-US") '.' else ','

        fun formatRawNum(n: Double): String {
            val clean = BigDecimal.valueOf(if (n.isFinite()) n else 0.0)
                .setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
            return clean.replace('.', decSep)
        }

        val header = listOf(
            "Etapa / Categoria",
            "Classificacao",
            "Impacto ou Nivel",
            "Percentual sobre Base",
            "Saldo Acumulado"
        ).joinToString("\t")

        val lines = bridge.steps.map { s ->
            val tipo = when {
                s.isBase && !s.isTotal -> "Base Inicial"
                s.isSubtotal -> "Subtotal"
                s.isTotal -> "Base Final"
                s.isAdjustment -> "Plug / Nao Explicado"
                s.diff >= 0.0 -> "Impacto (+)"
                else -> "Impacto (-)"
            }
            val valNum = if (s.isPillar) s.end else s.diff
            val pctNum = if (baseVal != 0.0) valNum / abs(baseVal) else 0.0

            if (rawNumbersOnly) {
                listOf(
                    s.label,
                    tipo,
                    formatRawNum(valNum),
                    if (baseVal != 0.0) formatRawNum(pctNum) else "0",
                    formatRawNum(s.end)
                ).joinToString("\t")
            } else {
                listOf(
                    s.label,
                    tipo,
                    WaterfallEngine.formatNumber(valNum, state, includePlusSign = !s.isPillar),
                    if (baseVal != 0.0) WaterfallEngine.formatPercent(pctNum, state, includePlusSign = !s.isPillar) else "—",
                    WaterfallEngine.formatNumber(s.end, state, includePlusSign = false)
                ).joinToString("\t")
            }
        }

        return (listOf(header) + lines).joinToString("\n")
    }

    fun generateSvg(bridge: BridgeResult, state: WaterfallAppState): String {
        val steps = bridge.steps
        if (steps.isEmpty()) return ""

        val width = 1280.0
        val height = 720.0
        val padLeft = 110.0
        val padRight = 60.0
        val padTop = 110.0
        val padBottom = 95.0
        val plotW = width - padLeft - padRight
        val plotH = height - padTop - padBottom

        val bounds = WaterfallEngine.computeAxisBounds(steps, state.enableAxisBreak, state.showDeltaBracket)
        val yMin = bounds.ySuggestedMin
        val yMax = bounds.ySuggestedMax
        val ySpan = if (abs(yMax - yMin) < 1e-6) 1.0 else (yMax - yMin)
        fun valToY(v: Double): Double = padTop + plotH - ((v - yMin) / ySpan) * plotH

        val slotW = plotW / steps.size
        val barW = min(slotW * 0.58, 76.0)

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 720" width="1280" height="720" font-family="Inter, Arial, sans-serif">""").append("\n")
        sb.append(
            """  <defs>
    <pattern id="plugHatch" width="10" height="10" patternTransform="rotate(45 0 0)" patternUnits="userSpaceOnUse">
      <rect width="10" height="10" fill="#fef3c7"/>
      <line x1="0" y1="0" x2="0" y2="10" stroke="#d97706" stroke-width="4"/>
    </pattern>
  </defs>"""
        ).append("\n")
        sb.append("""  <rect width="100%" height="100%" fill="#ffffff"/>""").append("\n")
        sb.append("""  <text x="${width / 2}" y="46" text-anchor="middle" font-size="22" font-weight="700" fill="#0f172a">${escapeXml(state.title)}</text>""").append("\n")

        val gridTicks = 5
        for (t in 0..gridTicks) {
            val gv = yMin + (ySpan * (t.toDouble() / gridTicks.toDouble()))
            val gy = valToY(gv)
            sb.append("""  <line x1="$padLeft" y1="${"%.1f".format(gy)}" x2="${width - padRight}" y2="${"%.1f".format(gy)}" stroke="#e2e8f0" stroke-width="1"/>""").append("\n")
            sb.append("""  <text x="${padLeft - 12}" y="${"%.1f".format(gy + 4)}" text-anchor="end" font-size="12" fill="#475569">${escapeXml(WaterfallEngine.formatNumber(gv, state, false))}</text>""").append("\n")
        }

        steps.forEachIndexed { i, s ->
            val cx = padLeft + i * slotW + slotW / 2.0
            val bx = cx - barW / 2.0
            val rawStart = if (bounds.axisBreakActive && s.isPillar) bounds.effectiveMin else s.start
            val y1 = valToY(max(rawStart, s.end))
            val y2 = valToY(min(rawStart, s.end))
            val bh = max(abs(y2 - y1), 3.0)

            var fill = "#2563eb"
            var labelColor = "#1d4ed8"
            if (!s.isPillar) {
                when {
                    s.isAdjustment -> {
                        fill = "url(#plugHatch)"
                        labelColor = "#b45309"
                    }
                    s.diff >= 0.0 -> {
                        fill = "#10b981"
                        labelColor = "#047857"
                    }
                    else -> {
                        fill = "#f43f5e"
                        labelColor = "#be123c"
                    }
                }
            }

            sb.append("""  <rect x="${"%.1f".format(bx)}" y="${"%.1f".format(y1)}" width="${"%.1f".format(barW)}" height="${"%.1f".format(bh)}" rx="5" fill="$fill"/>""").append("\n")

            if (state.showConnectors && i < steps.lastIndex) {
                val nextCx = padLeft + (i + 1) * slotW + slotW / 2.0
                val cy = valToY(s.end)
                sb.append("""  <line x1="${"%.1f".format(bx + barW)}" y1="${"%.1f".format(cy)}" x2="${"%.1f".format(nextCx - barW / 2.0)}" y2="${"%.1f".format(cy)}" stroke="#94a3b8" stroke-width="1.5" stroke-dasharray="4,4"/>""").append("\n")
            }

            if (state.showDataLabels) {
                val isNeg = !s.isPillar && s.diff < 0.0
                val ly = if (isNeg) (y2 + 18.0) else (y1 - 10.0)
                var txt = if (s.isPillar) {
                    WaterfallEngine.formatNumber(s.end, state, false)
                } else {
                    WaterfallEngine.formatNumber(s.diff, state, true)
                }
                if (state.showPercentInLabels && !s.isPillar && bridge.initialBase != 0.0) {
                    val pct = WaterfallEngine.formatPercent(s.diff / abs(bridge.initialBase), state, true)
                    txt += " ($pct)"
                }
                sb.append("""  <text x="${"%.1f".format(cx)}" y="${"%.1f".format(ly)}" text-anchor="middle" font-size="13" font-weight="600" fill="$labelColor">${escapeXml(txt)}</text>""").append("\n")
            }

            sb.append("""  <text x="${"%.1f".format(cx)}" y="${height - padBottom + 28}" text-anchor="middle" font-size="12" font-weight="500" fill="#334155">${escapeXml(s.label)}</text>""").append("\n")
        }

        sb.append("</svg>")
        return sb.toString()
    }

    fun buildNativeXlsxBytes(bridge: BridgeResult): ByteArray {
        val baseVal = bridge.initialBase
        val headers = listOf(
            "Etapa / Categoria",
            "Classificação",
            "Impacto / Nível",
            "% s/ Base Inicial",
            "Saldo Acumulado"
        )

        val colLetter = { idx: Int -> ('A'.code + idx).toChar().toString() }
        val sheetRowsXml = StringBuilder()

        sheetRowsXml.append("""<row r="1">""")
        headers.forEachIndexed { cIdx, h ->
            val ref = "${colLetter(cIdx)}1"
            sheetRowsXml.append("""<c r="$ref" t="inlineStr"><is><t>${escapeXml(h)}</t></is></c>""")
        }
        sheetRowsXml.append("""</row>""")

        bridge.steps.forEachIndexed { rIdx, s ->
            val rowNum = rIdx + 2
            val tipo = when {
                s.isBase && !s.isTotal -> "Base Inicial"
                s.isSubtotal -> "Subtotal"
                s.isTotal -> "Base Final"
                s.isAdjustment -> "Plug / Não Explicado"
                s.diff >= 0.0 -> "Impacto (+)"
                else -> "Impacto (-)"
            }
            val valNum = BigDecimal.valueOf(if (s.isPillar) s.end else s.diff)
                .setScale(4, RoundingMode.HALF_UP)
                .toDouble()
            val pctNum = if (baseVal != 0.0) {
                BigDecimal.valueOf((if (s.isPillar) s.end else s.diff) / abs(baseVal))
                    .setScale(4, RoundingMode.HALF_UP)
                    .toDouble()
            } else {
                0.0
            }
            val endNum = BigDecimal.valueOf(s.end).setScale(4, RoundingMode.HALF_UP).toDouble()

            sheetRowsXml.append("""<row r="$rowNum">""")
            sheetRowsXml.append("""<c r="A$rowNum" t="inlineStr"><is><t>${escapeXml(s.label)}</t></is></c>""")
            sheetRowsXml.append("""<c r="B$rowNum" t="inlineStr"><is><t>${escapeXml(tipo)}</t></is></c>""")
            sheetRowsXml.append("""<c r="C$rowNum"><v>$valNum</v></c>""")
            sheetRowsXml.append("""<c r="D$rowNum"><v>$pctNum</v></c>""")
            sheetRowsXml.append("""<c r="E$rowNum"><v>$endNum</v></c>""")
            sheetRowsXml.append("""</row>""")
        }

        val files = listOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Ponte Waterfall" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>$sheetRowsXml</sheetData>
</worksheet>"""
        )

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            files.forEach { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                val crc = CRC32().apply { update(bytes) }.value
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc
                }
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    fun renderStandard16x9LightBitmap(bridge: BridgeResult, state: WaterfallAppState): Bitmap {
        val width = 1920
        val height = 1080
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Executive white background
        canvas.drawColor(android.graphics.Color.WHITE)

        val steps = bridge.steps
        if (steps.isEmpty()) return bitmap

        val palette = ExecutivePalettes.forType(state.colorPalette)
        val bounds = WaterfallEngine.computeAxisBounds(steps, state.enableAxisBreak, state.showDeltaBracket)

        val padLeft = 180f
        val padRight = 90f
        val padTop = 160f
        val padBottom = 160f
        val plotW = width - padLeft - padRight
        val plotH = height - padTop - padBottom

        // Title
        if (state.title.isNotBlank()) {
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#0F172A")
                textSize = 36f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(state.title, width / 2f, 76f, titlePaint)
        }

        val yMin = bounds.ySuggestedMin
        val yMax = bounds.ySuggestedMax
        val ySpan = if (abs(yMax - yMin) < 1e-6) 1.0 else (yMax - yMin)
        fun valToY(v: Double): Float =
            (padTop + plotH - ((v - yMin) / ySpan).toFloat() * plotH)

        // Grid & Y-axis ticks
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#E2E8F0")
            strokeWidth = 2f
        }
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#334155")
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }

        val gridTicks = 5
        for (t in 0..gridTicks) {
            val gv = yMin + ySpan * (t.toDouble() / gridTicks.toDouble())
            val gy = valToY(gv)
            canvas.drawLine(padLeft, gy, width - padRight, gy, gridPaint)
            canvas.drawText(
                WaterfallEngine.formatNumber(gv, state, false),
                padLeft - 20f,
                gy + 8f,
                tickPaint
            )
        }

        // Y-axis title
        if (state.yAxisName.isNotBlank()) {
            canvas.save()
            val yTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#475569")
                textSize = 22f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.rotate(-90f, 44f, padTop + plotH / 2f)
            canvas.drawText(state.yAxisName, 44f, padTop + plotH / 2f, yTitlePaint)
            canvas.restore()
        }

        // X-axis title
        if (state.xAxisName.isNotBlank()) {
            val xTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#475569")
                textSize = 22f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(state.xAxisName, padLeft + plotW / 2f, height - 32f, xTitlePaint)
        }

        val slotW = plotW / steps.size
        val barW = min(slotW * 0.58f, 110f)
        val barTopYs = FloatArray(steps.size)
        val barBottomYs = FloatArray(steps.size)
        val barCenterXs = FloatArray(steps.size)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = android.graphics.Color.parseColor("#8C64748B")
            pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = android.graphics.Color.parseColor("#334155")
            textAlign = Paint.Align.CENTER
        }

        steps.forEachIndexed { i, s ->
            val cx = padLeft + i * slotW + slotW / 2f
            val bx = cx - barW / 2f
            val rawStart = if (bounds.axisBreakActive && s.isPillar) bounds.effectiveMin else s.start
            val topY = valToY(max(rawStart, s.end))
            val bottomY = max(valToY(min(rawStart, s.end)), topY + 6f)

            barTopYs[i] = topY
            barBottomYs[i] = bottomY
            barCenterXs[i] = cx

            val rect = RectF(bx, topY, bx + barW, bottomY)
            when {
                s.isPillar -> {
                    fillPaint.color = palette.baseBg.toArgb()
                    strokePaint.color = palette.baseBorder.toArgb()
                }
                s.isAdjustment -> {
                    fillPaint.color = android.graphics.Color.parseColor("#FEF3C7")
                    strokePaint.color = palette.adjBorder.toArgb()
                }
                s.diff >= 0.0 -> {
                    fillPaint.color = palette.posBg.toArgb()
                    strokePaint.color = palette.posBorder.toArgb()
                }
                else -> {
                    fillPaint.color = palette.negBg.toArgb()
                    strokePaint.color = palette.negBorder.toArgb()
                }
            }

            canvas.drawRoundRect(rect, 10f, 10f, fillPaint)
            if (s.isAdjustment) {
                canvas.save()
                val clipPath = Path().apply { addRoundRect(rect, 10f, 10f, Path.Direction.CW) }
                canvas.clipPath(clipPath)
                val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.adjBorder.toArgb()
                    strokeWidth = 5f
                }
                var d = rect.left - rect.height()
                while (d < rect.right + rect.height()) {
                    canvas.drawLine(d, rect.bottom, d + rect.height(), rect.top, stripePaint)
                    d += 18f
                }
                canvas.restore()
            }
            canvas.drawRoundRect(rect, 10f, 10f, strokePaint)

            // X label
            val cleanLabel = if (s.label.length > 22) s.label.take(20) + "…" else s.label
            canvas.drawText(cleanLabel, cx, padTop + plotH + 42f, xLabelPaint)
        }

        // Connectors
        if (state.showConnectors && steps.size > 1) {
            for (i in 0 until steps.lastIndex) {
                val connY = valToY(steps[i].end)
                val startX = barCenterXs[i] + barW / 2f
                val endX = barCenterXs[i + 1] - barW / 2f
                canvas.drawLine(startX, connY, endX, connY, connectorPaint)
            }
        }

        // Data labels
        if (state.showDataLabels) {
            steps.forEachIndexed { i, s ->
                var txt = if (s.isPillar) {
                    WaterfallEngine.formatNumber(s.end, state, false)
                } else {
                    WaterfallEngine.formatNumber(s.diff, state, true)
                }
                if (state.showPercentInLabels && !s.isPillar && bridge.initialBase != 0.0) {
                    val pct = WaterfallEngine.formatPercent(s.diff / abs(bridge.initialBase), state, true)
                    txt += " ($pct)"
                }
                labelPaint.color = when {
                    s.isPillar -> palette.baseText.toArgb()
                    s.isAdjustment -> palette.adjText.toArgb()
                    s.diff >= 0.0 -> palette.posText.toArgb()
                    else -> palette.negText.toArgb()
                }
                val isNeg = !s.isPillar && s.diff < 0.0
                val ly = if (isNeg) barBottomYs[i] + 30f else barTopYs[i] - 14f
                canvas.drawText(txt, barCenterXs[i], ly, labelPaint)
            }
        }

        // Top Delta Bracket
        if (state.showDeltaBracket && steps.size >= 2) {
            val highestPixelY = barTopYs.minOrNull() ?: padTop
            val bracketY = max(padTop - 20f, highestPixelY - 52f)
            val firstX = barCenterXs.first()
            val lastX = barCenterXs.last()

            val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = android.graphics.Color.parseColor("#475569")
            }
            val path = Path().apply {
                moveTo(firstX, max(bracketY + 16f, barTopYs.first() - 32f))
                lineTo(firstX, bracketY)
                lineTo(lastX, bracketY)
                lineTo(lastX, max(bracketY + 16f, barTopYs.last() - 32f))
            }
            canvas.drawPath(path, bracketPaint)

            val deltaValStr = WaterfallEngine.formatNumber(bridge.netVariance, state, true)
            val deltaPctStr = bridge.pctDelta?.let { " (${WaterfallEngine.formatPercent(it, state, true)})" }.orEmpty()
            val pillText = "Δ Total: $deltaValStr$deltaPctStr"

            val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 22f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                color = if (bridge.netVariance >= 0.0) palette.posText.toArgb() else palette.negText.toArgb()
            }
            val textW = pillTextPaint.measureText(pillText)
            val midX = (firstX + lastX) / 2f
            val pillW = textW + 36f
            val pillH = 42f
            val pillRect = RectF(midX - pillW / 2f, bracketY - pillH / 2f, midX + pillW / 2f, bracketY + pillH / 2f)

            val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = android.graphics.Color.parseColor("#F1F5F9")
            }
            val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = android.graphics.Color.parseColor("#CBD5E1")
            }
            canvas.drawRoundRect(pillRect, 999f, 999f, pillBgPaint)
            canvas.drawRoundRect(pillRect, 999f, 999f, pillStrokePaint)
            canvas.drawText(pillText, midX, bracketY + 8f, pillTextPaint)
        }

        // Axis Break indicator //
        if (bounds.axisBreakActive) {
            val breakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = android.graphics.Color.parseColor("#D97706")
            }
            val breakY = padTop + plotH - 20f
            canvas.drawLine(padLeft - 12f, breakY - 8f, padLeft + 12f, breakY - 18f, breakPaint)
            canvas.drawLine(padLeft - 12f, breakY + 4f, padLeft + 12f, breakY - 6f, breakPaint)
        }

        return bitmap
    }

    fun copyTextToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun saveAndCopy16x9Image(context: Context, bridge: BridgeResult, state: WaterfallAppState): File {
        val bitmap = renderStandard16x9LightBitmap(bridge, state)
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val cleanTitle = state.title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "grafico-cascata" }
        val outFile = File(exportsDir, "$cleanTitle-16x9.png")
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newUri(context.contentResolver, "Waterfall 16:9 PNG", uri)
        cm.setPrimaryClip(clip)
        return outFile
    }

    fun shareExportFile(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        chooserTitle: String
    ) {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(exportsDir, fileName)
        outFile.writeBytes(bytes)
        shareExistingFile(context, outFile, mimeType, chooserTitle)
    }

    fun shareExistingFile(
        context: Context,
        file: File,
        mimeType: String,
        chooserTitle: String
    ) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
