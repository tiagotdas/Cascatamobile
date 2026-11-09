package com.waterfallstudiopro.app.domain

import com.waterfallstudiopro.app.model.AnalysisMode
import com.waterfallstudiopro.app.model.AxisBounds
import com.waterfallstudiopro.app.model.BridgeResult
import com.waterfallstudiopro.app.model.BridgeStatusType
import com.waterfallstudiopro.app.model.ImportPreviewSummary
import com.waterfallstudiopro.app.model.NumberParseResult
import com.waterfallstudiopro.app.model.ParsedSpreadsheetRow
import com.waterfallstudiopro.app.model.WaterfallAppState
import com.waterfallstudiopro.app.model.WaterfallEvent
import com.waterfallstudiopro.app.model.WaterfallStep
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

object WaterfallEngine {
    const val SCALE_FACTOR = 10000L
    private val BIGINT_SCALE = BigInteger.valueOf(SCALE_FACTOR)

    fun toScaledInt(num: Double): Long {
        if (!num.isFinite()) return 0L
        return (num * SCALE_FACTOR).roundToLong()
    }

    fun fromScaledInt(scaled: Long): Double {
        return scaled.toDouble() / SCALE_FACTOR.toDouble()
    }

    fun toBigIntScaled(valNum: Double): BigInteger {
        if (!valNum.isFinite()) return BigInteger.ZERO
        return BigDecimal.valueOf(valNum)
            .multiply(BigDecimal.valueOf(SCALE_FACTOR))
            .setScale(0, RoundingMode.HALF_UP)
            .toBigInteger()
    }

    fun computeAllowedToleranceScaled(tolSetting: String?, netDeltaScaled: Long): Long {
        val raw = (tolSetting ?: "strict").trim()
        if (raw == "strict" || raw == "0") return 0L
        if (raw.startsWith("abs:")) {
            val v = raw.removePrefix("abs:").toDoubleOrNull()
            return if (v != null && v.isFinite()) toScaledInt(abs(v)) else 0L
        }
        if (raw.startsWith("delta:")) {
            val ratio = raw.removePrefix("delta:").toDoubleOrNull()
            return if (ratio != null && ratio.isFinite()) (abs(netDeltaScaled) * ratio).roundToLong() else 0L
        }
        return 0L
    }

    fun buildBridge(state: WaterfallAppState): BridgeResult {
        val isBridge = state.analysisMode == AnalysisMode.BRIDGE
        val steps = mutableListOf<WaterfallStep>()
        var runningScaled = 0L
        var sumPosScaled = 0L
        var sumNegScaled = 0L
        var initialBaseScaled = 0L
        var unexplainedGapScaled = 0L
        var statusType = BridgeStatusType.AUTO
        var q1Missing = false

        var oracleInitial = BigInteger.ZERO
        var oracleEventsSum = BigInteger.ZERO

        if (isBridge) {
            val rawQ1 = state.startPeriodValue.trim()
            val parsedQ1 = rawQ1.replace(',', '.').toDoubleOrNull()
            q1Missing = rawQ1.isEmpty() || parsedQ1 == null || !parsedQ1.isFinite()
            val q1Val = if (q1Missing) 0.0 else parsedQ1!!
            initialBaseScaled = toScaledInt(q1Val)
            runningScaled = initialBaseScaled
            oracleInitial = toBigIntScaled(q1Val)

            val q1Label = state.startPeriodLabel.trim().ifEmpty { "Base Q1" }
            steps.add(
                WaterfallStep(
                    label = q1Label,
                    start = 0.0,
                    end = fromScaledInt(initialBaseScaled),
                    diff = fromScaledInt(initialBaseScaled),
                    isBase = true
                )
            )

            state.events.forEach { ev ->
                if (ev.isSubtotal) {
                    val subVal = fromScaledInt(runningScaled)
                    steps.add(
                        WaterfallStep(
                            label = ev.label.ifEmpty { "Subtotal" },
                            start = 0.0,
                            end = subVal,
                            diff = subVal,
                            isSubtotal = true
                        )
                    )
                } else {
                    val changeScaled = toScaledInt(ev.value)
                    val startScaled = runningScaled
                    val endScaled = startScaled + changeScaled
                    runningScaled = endScaled
                    oracleEventsSum = oracleEventsSum.add(toBigIntScaled(ev.value))

                    if (changeScaled >= 0L) sumPosScaled += changeScaled
                    else sumNegScaled += changeScaled

                    steps.add(
                        WaterfallStep(
                            label = ev.label.ifEmpty { "Evento" },
                            start = fromScaledInt(startScaled),
                            end = fromScaledInt(endScaled),
                            diff = fromScaledInt(changeScaled)
                        )
                    )
                }
            }

            val q2Label = state.endPeriodLabel.trim().ifEmpty { "Base Q2" }
            val rawQ2 = state.endPeriodValue.trim()
            val parsedQ2 = rawQ2.replace(',', '.').toDoubleOrNull()
            val hasExplicitQ2 = rawQ2.isNotEmpty() && parsedQ2 != null && parsedQ2.isFinite()
            val targetQ2Scaled = if (hasExplicitQ2) toScaledInt(parsedQ2!!) else runningScaled

            val gapScaled = targetQ2Scaled - runningScaled
            val netDeltaScaled = targetQ2Scaled - initialBaseScaled
            val allowedDiffScaled = computeAllowedToleranceScaled(state.reconcileTolerancePct, netDeltaScaled)

            var finalBarScaled = runningScaled

            if (!hasExplicitQ2) {
                statusType = BridgeStatusType.AUTO
                unexplainedGapScaled = 0L
                finalBarScaled = runningScaled
            } else if (gapScaled == 0L) {
                statusType = BridgeStatusType.RECONCILED
                unexplainedGapScaled = 0L
                finalBarScaled = targetQ2Scaled
            } else if (abs(gapScaled) <= allowedDiffScaled) {
                statusType = BridgeStatusType.RECONCILED_WITH_TOL
                unexplainedGapScaled = gapScaled
                finalBarScaled = targetQ2Scaled
            } else if (state.autoReconcileGap) {
                statusType = BridgeStatusType.PLUGGED
                unexplainedGapScaled = gapScaled
                val startScaled = runningScaled
                val endScaled = targetQ2Scaled
                runningScaled = endScaled
                finalBarScaled = targetQ2Scaled

                steps.add(
                    WaterfallStep(
                        label = "Outros / Não Explicado",
                        start = fromScaledInt(startScaled),
                        end = fromScaledInt(endScaled),
                        diff = fromScaledInt(gapScaled),
                        isAdjustment = true
                    )
                )
            } else {
                statusType = BridgeStatusType.DIVERGENT
                unexplainedGapScaled = gapScaled
                finalBarScaled = targetQ2Scaled
            }

            steps.add(
                WaterfallStep(
                    label = q2Label,
                    start = 0.0,
                    end = fromScaledInt(finalBarScaled),
                    diff = fromScaledInt(finalBarScaled),
                    isBase = true,
                    isTotal = true,
                    expectedEnd = fromScaledInt(runningScaled)
                )
            )

            val explainedVarianceScaled = sumPosScaled + sumNegScaled
            val plugDrawnScaled = if (statusType == BridgeStatusType.PLUGGED) unexplainedGapScaled else 0L

            val oraclePlug = BigInteger.valueOf(plugDrawnScaled)
            val oracleTotalDrawn = oracleInitial.add(oracleEventsSum).add(oraclePlug)
            val oracleFinalBar = BigInteger.valueOf(finalBarScaled)

            val exactVisualMatch = (oracleTotalDrawn == oracleFinalBar) && !q1Missing

            return BridgeResult(
                mode = AnalysisMode.BRIDGE,
                q1Missing = q1Missing,
                statusType = statusType,
                steps = steps,
                initialBase = fromScaledInt(initialBaseScaled),
                sumPositives = fromScaledInt(sumPosScaled),
                sumNegatives = fromScaledInt(sumNegScaled),
                explainedVariance = fromScaledInt(explainedVarianceScaled),
                unexplainedGap = fromScaledInt(unexplainedGapScaled),
                unexplainedPctOfBase = if (initialBaseScaled != 0L) unexplainedGapScaled.toDouble() / abs(initialBaseScaled).toDouble() else 0.0,
                unexplainedPctOfDelta = if (netDeltaScaled != 0L) unexplainedGapScaled.toDouble() / abs(netDeltaScaled).toDouble() else 0.0,
                netVariance = fromScaledInt(netDeltaScaled),
                pctDelta = if (initialBaseScaled != 0L) netDeltaScaled.toDouble() / abs(initialBaseScaled).toDouble() else null,
                finalValue = fromScaledInt(finalBarScaled),
                calculatedEnd = fromScaledInt(initialBaseScaled + explainedVarianceScaled),
                invariantHolds = exactVisualMatch,
                oracleDeltaScaled = oracleFinalBar.subtract(oracleTotalDrawn).toLong()
            )
        } else {
            initialBaseScaled = 0L
            runningScaled = 0L

            state.events.forEach { ev ->
                if (ev.isSubtotal) {
                    val subVal = fromScaledInt(runningScaled)
                    steps.add(
                        WaterfallStep(
                            label = ev.label.ifEmpty { "Subtotal" },
                            start = 0.0,
                            end = subVal,
                            diff = subVal,
                            isSubtotal = true
                        )
                    )
                } else {
                    val changeScaled = toScaledInt(ev.value)
                    val startScaled = runningScaled
                    val endScaled = startScaled + changeScaled
                    runningScaled = endScaled
                    oracleEventsSum = oracleEventsSum.add(toBigIntScaled(ev.value))

                    if (changeScaled >= 0L) sumPosScaled += changeScaled
                    else sumNegScaled += changeScaled

                    steps.add(
                        WaterfallStep(
                            label = ev.label.ifEmpty { "Etapa" },
                            start = fromScaledInt(startScaled),
                            end = fromScaledInt(endScaled),
                            diff = fromScaledInt(changeScaled)
                        )
                    )
                }
            }

            if (state.showTotalBar && steps.isNotEmpty()) {
                steps.add(
                    WaterfallStep(
                        label = state.totalBarLabel.trim().ifEmpty { "Saldo Final" },
                        start = 0.0,
                        end = fromScaledInt(runningScaled),
                        diff = fromScaledInt(runningScaled),
                        isTotal = true
                    )
                )
            }

            val lastDrawnStep = steps.lastOrNull()
            val finalDrawnScaled = if (lastDrawnStep != null) toScaledInt(lastDrawnStep.end) else 0L
            val exactVisualMatch = oracleEventsSum == BigInteger.valueOf(finalDrawnScaled)

            return BridgeResult(
                mode = AnalysisMode.CUMULATIVE,
                q1Missing = false,
                statusType = BridgeStatusType.RECONCILED,
                steps = steps,
                initialBase = 0.0,
                sumPositives = fromScaledInt(sumPosScaled),
                sumNegatives = fromScaledInt(sumNegScaled),
                explainedVariance = fromScaledInt(runningScaled),
                unexplainedGap = 0.0,
                unexplainedPctOfBase = 0.0,
                unexplainedPctOfDelta = 0.0,
                netVariance = fromScaledInt(runningScaled),
                pctDelta = null,
                finalValue = fromScaledInt(finalDrawnScaled),
                calculatedEnd = fromScaledInt(runningScaled),
                invariantHolds = exactVisualMatch,
                oracleDeltaScaled = BigInteger.valueOf(finalDrawnScaled).subtract(oracleEventsSum).toLong()
            )
        }
    }

    fun runEngineSelfTest(): Boolean {
        val t1 = buildBridge(
            WaterfallAppState(
                analysisMode = AnalysisMode.BRIDGE,
                startPeriodValue = "1000.15",
                endPeriodValue = "1250.05",
                autoReconcileGap = false,
                reconcileTolerancePct = "strict",
                events = listOf(
                    WaterfallEvent("1", "A", 150.10, false),
                    WaterfallEvent("2", "B", 199.80, false),
                    WaterfallEvent("3", "C", -100.00, false)
                )
            )
        )
        if (!t1.invariantHolds || t1.unexplainedGap != 0.0) return false

        val tGap = buildBridge(
            WaterfallAppState(
                analysisMode = AnalysisMode.BRIDGE,
                startPeriodValue = "1000",
                endPeriodValue = "1300",
                autoReconcileGap = false,
                reconcileTolerancePct = "delta:0.005",
                events = listOf(WaterfallEvent("1", "A", 299.0, false))
            )
        )
        if (tGap.invariantHolds || tGap.statusType != BridgeStatusType.RECONCILED_WITH_TOL) return false

        val rng = Random(42)
        for (i in 0 until 35) {
            val q1 = ((rng.nextDouble() * 50000.0 - 10000.0) * 100.0).roundToLong() / 100.0
            var oracleBig = toBigIntScaled(q1)
            val evs = mutableListOf<WaterfallEvent>()
            val numEv = 3 + (i % 6)
            for (j in 0 until numEv) {
                val v = ((rng.nextDouble() * 4000.0 - 1800.0) * 100.0).roundToLong() / 100.0
                oracleBig = oracleBig.add(toBigIntScaled(v))
                evs.add(WaterfallEvent("e_$j", "E_$j", v, false))
            }
            val expectedQ2 = oracleBig.toDouble() / BIGINT_SCALE.toDouble()
            val res = buildBridge(
                WaterfallAppState(
                    analysisMode = AnalysisMode.BRIDGE,
                    startPeriodValue = q1.toString(),
                    endPeriodValue = expectedQ2.toString(),
                    autoReconcileGap = false,
                    reconcileTolerancePct = "strict",
                    events = evs
                )
            )
            if (!res.invariantHolds || toBigIntScaled(res.finalValue) != oracleBig) {
                return false
            }
        }
        return true
    }

    fun computeAxisBounds(
        steps: List<WaterfallStep>,
        enableAxisBreak: Boolean,
        showDeltaBracket: Boolean
    ): AxisBounds {
        if (steps.isEmpty()) {
            return AxisBounds(0.0, 100.0, false, 0.0)
        }

        var minVal = Double.POSITIVE_INFINITY
        var maxVal = Double.NEGATIVE_INFINITY
        var lowestFloatingPoint = Double.POSITIVE_INFINITY

        steps.forEach { s ->
            minVal = min(minVal, min(s.start, s.end))
            maxVal = max(maxVal, max(s.start, s.end))
            if (!s.isBase && !s.isTotal && !s.isSubtotal) {
                lowestFloatingPoint = min(lowestFloatingPoint, min(s.start, s.end))
            } else {
                lowestFloatingPoint = min(lowestFloatingPoint, s.end)
            }
        }

        var axisBreakActive = false
        var effectiveMin = 0.0

        if (enableAxisBreak && lowestFloatingPoint > 0.0 && maxVal > 0.0) {
            val span = maxVal - lowestFloatingPoint
            if (lowestFloatingPoint > span * 0.8) {
                axisBreakActive = true
                effectiveMin = max(0.0, lowestFloatingPoint - max(span * 0.35, maxVal * 0.05))
            }
        }

        val baseMin = if (axisBreakActive) effectiveMin else min(0.0, minVal)
        val range = max(abs(maxVal - baseMin), 10.0)
        val topPadFactor = if (showDeltaBracket) 0.28 else 0.16
        val bottomPadFactor = if (minVal < 0.0) 0.16 else 0.04

        return AxisBounds(
            ySuggestedMin = if (axisBreakActive) effectiveMin else (if (minVal < 0.0) minVal - range * bottomPadFactor else 0.0),
            ySuggestedMax = maxVal + range * topPadFactor,
            axisBreakActive = axisBreakActive,
            effectiveMin = effectiveMin
        )
    }

    private fun buildSymbols(localeStr: String): DecimalFormatSymbols {
        val isPtBr = localeStr != "en-US"
        return DecimalFormatSymbols(if (isPtBr) Locale("pt", "BR") else Locale.US).apply {
            groupingSeparator = if (isPtBr) '.' else ','
            decimalSeparator = if (isPtBr) ',' else '.'
        }
    }

    fun formatNumber(
        num: Double,
        state: WaterfallAppState,
        includePlusSign: Boolean = false
    ): String {
        val decimals = state.decimalPlaces.coerceIn(0, 4)
        val clean = BigDecimal.valueOf(if (num.isFinite()) num else 0.0)
            .setScale(decimals, RoundingMode.HALF_UP)
            .toDouble()
        val absVal = abs(if (clean == 0.0) 0.0 else clean)

        val pattern = if (decimals > 0) {
            "#,##0." + "0".repeat(decimals)
        } else {
            "#,##0"
        }
        val df = DecimalFormat(pattern, buildSymbols(state.displayLocale))
        val signStr = when {
            clean < 0.0 -> "-"
            includePlusSign && clean > 0.0 -> "+"
            else -> ""
        }
        return "$signStr${state.numberPrefix}${df.format(absVal)}${state.numberSuffix}"
    }

    fun formatExactDifference(
        num: Double,
        state: WaterfallAppState
    ): String {
        val clean = BigDecimal.valueOf(if (num.isFinite()) num else 0.0)
            .setScale(4, RoundingMode.HALF_UP)
            .toDouble()
        val absVal = abs(if (clean == 0.0) 0.0 else clean)
        val hasDecimals = abs(absVal - absVal.roundToLong()) > 1e-6
        val minDecimals = if (hasDecimals) max(state.decimalPlaces, 2) else state.decimalPlaces

        val df = DecimalFormat("#,##0", buildSymbols(state.displayLocale)).apply {
            minimumFractionDigits = minDecimals
            maximumFractionDigits = 4
        }
        val signStr = when {
            clean < 0.0 -> "-"
            clean > 0.0 -> "+"
            else -> ""
        }
        return "$signStr${state.numberPrefix}${df.format(absVal)}${state.numberSuffix}"
    }

    fun formatPercent(
        ratioFloat: Double,
        state: WaterfallAppState,
        includePlusSign: Boolean = false
    ): String {
        val pctValue = (if (ratioFloat.isFinite()) ratioFloat else 0.0) * 100.0
        val clean = BigDecimal.valueOf(pctValue)
            .setScale(1, RoundingMode.HALF_UP)
            .toDouble()
        val absVal = abs(if (clean == 0.0) 0.0 else clean)
        val df = DecimalFormat("#,##0.0", buildSymbols(state.displayLocale))
        val signStr = when {
            clean < 0.0 -> "-"
            includePlusSign && clean > 0.0 -> "+"
            else -> ""
        }
        return "$signStr${df.format(absVal)}%"
    }

    private val ptBrGroupedRegex = Regex("""^(\d{1,3}(\.\d{3})+|\d+)(,\d+)?$""")
    private val enUsGroupedRegex = Regex("""^(\d{1,3}(,\d{3})+|\d+)(\.\d+)?$""")
    private val singleDotAmbiguous = Regex("""^\d{1,3}\.\d{3}$""")
    private val singleCommaAmbiguous = Regex("""^\d{1,3},\d{3}$""")

    fun parseLocaleNumberDetailed(rawInput: String?, localeMode: String = "pt-BR"): NumberParseResult {
        if (rawInput == null) {
            return NumberParseResult(Double.NaN, false, null, "Valor vazio")
        }
        var str = rawInput.trim()
        if (str.isEmpty()) {
            return NumberParseResult(Double.NaN, false, null, "Valor vazio")
        }

        var sign = 1.0
        if (str.startsWith("(") && str.endsWith(")")) {
            sign = -1.0
            str = str.substring(1, str.length - 1).trim()
        }

        str = str.replace(Regex("""[R$US€£%\s]""", RegexOption.IGNORE_CASE), "")

        if (str.endsWith("+") || str.endsWith("-")) {
            if (str.endsWith("-")) sign *= -1.0
            str = str.dropLast(1).trim()
        } else if (str.startsWith("+") || str.startsWith("-")) {
            if (str.startsWith("-")) sign *= -1.0
            str = str.drop(1).trim()
        }

        if (str.isEmpty() || !Regex("""^\d[\d.,]*$""").matches(str)) {
            return NumberParseResult(Double.NaN, false, null, "Formato numérico inválido")
        }

        val hasDot = str.contains('.')
        val hasComma = str.contains(',')
        var warning: String? = null
        val normalized: String

        when (localeMode) {
            "pt-BR" -> {
                if (!ptBrGroupedRegex.matches(str)) {
                    val hint = if (hasDot && !hasComma) {
                        "Em PT-BR use vírgula para decimal (ex: 12,5) ou grupos de 3 dígitos para milhar (1.250)."
                    } else {
                        "Agrupamento numérico inválido para PT-BR."
                    }
                    return NumberParseResult(Double.NaN, false, null, hint)
                }
                if (singleDotAmbiguous.matches(str)) {
                    warning = "Ambiguidade: \"$str\" lido como milhar (${str.replace(".", "")}). Use vírgula se for decimal."
                }
                normalized = str.replace(".", "").replace(',', '.')
            }
            "en-US" -> {
                if (!enUsGroupedRegex.matches(str)) {
                    val hint = if (hasComma && !hasDot) {
                        "Em EN-US use ponto para decimal (ex: 12.5) ou grupos de 3 dígitos para milhar (1,250)."
                    } else {
                        "Agrupamento numérico inválido para EN-US."
                    }
                    return NumberParseResult(Double.NaN, false, null, hint)
                }
                if (singleCommaAmbiguous.matches(str)) {
                    warning = "Ambiguidade: \"$str\" lido como milhar (${str.replace(",", "")}). Use ponto se for decimal."
                }
                normalized = str.replace(",", "")
            }
            else -> {
                if (hasDot && hasComma) {
                    if (str.lastIndexOf(',') > str.lastIndexOf('.')) {
                        if (!ptBrGroupedRegex.matches(str)) {
                            return NumberParseResult(Double.NaN, false, null, "Separadores mistos inválidos (esperado 1.234,56).")
                        }
                        normalized = str.replace(".", "").replace(',', '.')
                    } else {
                        if (!enUsGroupedRegex.matches(str)) {
                            return NumberParseResult(Double.NaN, false, null, "Separadores mistos inválidos (esperado 1,234.56).")
                        }
                        normalized = str.replace(",", "")
                    }
                } else if (hasComma) {
                    if (singleCommaAmbiguous.matches(str)) {
                        warning = "Ambíguo no modo Auto: \"$str\" assumido como decimal (${str.replace(',', '.')}). Selecione EN-US se for milhar."
                        normalized = str.replace(',', '.')
                    } else if (Regex("""^\d{1,3}(,\d{3}){2,}$""").matches(str)) {
                        normalized = str.replace(",", "")
                    } else if (Regex("""^\d+(,\d+)$""").matches(str)) {
                        normalized = str.replace(',', '.')
                    } else {
                        return NumberParseResult(Double.NaN, false, null, "Formato com vírgula inválido.")
                    }
                } else if (hasDot) {
                    if (singleDotAmbiguous.matches(str)) {
                        warning = "Ambíguo no modo Auto: \"$str\" assumido como milhar (${str.replace(".", "")}). Selecione EN-US se for decimal."
                        normalized = str.replace(".", "")
                    } else if (Regex("""^\d{1,3}(\.\d{3}){2,}$""").matches(str)) {
                        normalized = str.replace(".", "")
                    } else if (Regex("""^\d+(\.\d+)$""").matches(str)) {
                        normalized = str
                    } else {
                        return NumberParseResult(Double.NaN, false, null, "Formato com ponto inválido.")
                    }
                } else {
                    normalized = str
                }
            }
        }

        val num = normalized.toDoubleOrNull()
        if (num == null || !num.isFinite()) {
            return NumberParseResult(Double.NaN, false, null, "Número inválido")
        }
        val signedVal = if (num == 0.0) 0.0 else sign * num
        return NumberParseResult(signedVal, true, warning, null)
    }

    fun parseSpreadsheetPreview(
        rawText: String,
        includeEndpoints: Boolean,
        state: WaterfallAppState
    ): ImportPreviewSummary {
        val rawLines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val tempRows = mutableListOf<ParsedSpreadsheetRow>()
        val spaceSplitRegex = Regex("""^(.+?)\s+([+-]?\(?R?\$?\s*\d[\d.,]*[+-]?\)?|\S+)$""", RegexOption.IGNORE_CASE)

        rawLines.forEachIndexed { idx, line ->
            var labelPart = ""
            var valPart = ""

            if (line.contains('\t')) {
                val parts = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    valPart = parts.last()
                    labelPart = parts.dropLast(1).joinToString(" - ")
                } else {
                    labelPart = parts.firstOrNull().orEmpty()
                }
            } else if (line.contains(';')) {
                val parts = line.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    valPart = parts.last()
                    labelPart = parts.dropLast(1).joinToString(" - ")
                } else {
                    labelPart = parts.firstOrNull().orEmpty()
                }
            } else {
                val match = spaceSplitRegex.find(line)
                if (match != null) {
                    labelPart = match.groupValues[1].trim().replace(Regex("""[,:]$"""), "").trim()
                    valPart = match.groupValues[2].trim()
                } else {
                    labelPart = line
                }
            }

            if (idx == 0 && !valPart.any { it.isDigit() }) {
                return@forEachIndexed
            }

            val parsed = parseLocaleNumberDetailed(valPart, state.importLocale)
            tempRows.add(
                ParsedSpreadsheetRow(
                    lineNumber = idx + 1,
                    raw = line,
                    label = labelPart.ifEmpty { "Linha ${idx + 1}" },
                    rawValue = valPart,
                    value = parsed.value,
                    valid = labelPart.isNotEmpty() && parsed.valid,
                    warning = parsed.warning,
                    error = if (labelPart.isEmpty()) "Rótulo ausente" else parsed.error,
                    role = "Etapa"
                )
            )
        }

        val isBridge = state.analysisMode == AnalysisMode.BRIDGE
        val useEndpoints = includeEndpoints && tempRows.size >= 3

        val rows = tempRows.mapIndexed { physicalIdx, r ->
            val role = if (isBridge && useEndpoints) {
                when (physicalIdx) {
                    0 -> "Q1"
                    tempRows.lastIndex -> "Q2"
                    else -> "Evento"
                }
            } else {
                "Etapa"
            }
            r.copy(role = role)
        }

        val validRows = rows.filter { it.valid }
        val errorRows = rows.filter { !it.valid }
        val warnRows = rows.filter { it.valid && it.warning != null }

        if (rows.isEmpty()) {
            return ImportPreviewSummary(
                rows = emptyList(),
                validCount = 0,
                errorCount = 0,
                warningCount = 0,
                controlSumText = "Aguardando dados",
                bridgeEquationText = null,
                isError = false,
                isWarning = false,
                canApply = false
            )
        }

        if (errorRows.isNotEmpty()) {
            return ImportPreviewSummary(
                rows = rows,
                validCount = validRows.size,
                errorCount = errorRows.size,
                warningCount = warnRows.size,
                controlSumText = "✕ ${errorRows.size} erro(s) bloqueando importação",
                bridgeEquationText = "Corrija a(s) linha(s) inválida(s) antes de importar.",
                isError = true,
                isWarning = false,
                canApply = false
            )
        }

        if (isBridge && useEndpoints) {
            val q1Scaled = toScaledInt(rows.first().value)
            val q2Scaled = toScaledInt(rows.last().value)
            var evSumScaled = 0L
            for (i in 1 until rows.lastIndex) {
                evSumScaled += toScaledInt(rows[i].value)
            }
            val calcQ2Scaled = q1Scaled + evSumScaled
            val diffScaled = q2Scaled - calcQ2Scaled

            val controlSumText = if (diffScaled == 0L) {
                "✓ Ponte Fechada (Dif: 0)"
            } else {
                "⚠ Diferença Q2: ${formatExactDifference(fromScaledInt(diffScaled), state)}"
            }
            val equation = "Q1 (${formatNumber(fromScaledInt(q1Scaled), state)}) + ΣEventos (${formatNumber(fromScaledInt(evSumScaled), state, true)}) = ${formatNumber(fromScaledInt(calcQ2Scaled), state)} vs Q2 (${formatNumber(fromScaledInt(q2Scaled), state)})"

            return ImportPreviewSummary(
                rows = rows,
                validCount = validRows.size,
                errorCount = 0,
                warningCount = warnRows.size,
                controlSumText = controlSumText,
                bridgeEquationText = equation,
                isError = false,
                isWarning = diffScaled != 0L,
                canApply = validRows.isNotEmpty()
            )
        } else {
            var sumScaled = 0L
            validRows.forEach { sumScaled += toScaledInt(it.value) }
            return ImportPreviewSummary(
                rows = rows,
                validCount = validRows.size,
                errorCount = 0,
                warningCount = warnRows.size,
                controlSumText = "Saldo Acumulado: ${formatNumber(fromScaledInt(sumScaled), state, true)}",
                bridgeEquationText = null,
                isError = false,
                isWarning = false,
                canApply = validRows.isNotEmpty()
            )
        }
    }
}
