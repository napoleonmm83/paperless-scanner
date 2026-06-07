package com.paperless.scanner.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Flags a hardcoded `letterSpacing = <number>.sp` override.
 *
 * Label typography spacing must come from the design tokens in Type.kt (e.g.
 * `labelSmall` with `0.1.em`), not an inline `.sp` literal that drifts from the
 * scalable `.em` token. Token references and `.em` values are not flagged.
 * Plan-04 (#266 enforcement).
 */
class LabelLetterSpacingOverrideRule(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "LabelLetterSpacingOverride",
        severity = Severity.Warning,
        description = "Hardcoded letterSpacing .sp override instead of a typography token.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitArgument(argument: KtValueArgument) {
        super.visitArgument(argument)
        if (argument.getArgumentName()?.asName?.asString() != "letterSpacing") return
        val value = argument.getArgumentExpression() as? KtDotQualifiedExpression ?: return
        val receiver = value.receiverExpression
        // Only a non-zero hardcoded `.sp` literal is a drift; `0.sp` is a harmless no-op.
        if (value.selectorExpression?.text == "sp" &&
            receiver is KtConstantExpression &&
            receiver.text.toDoubleOrNull() != 0.0
        ) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(value),
                    "Hardcoded letterSpacing ${value.text} — use a typography token " +
                        "(e.g. Type.kt labelSmall with .em) instead of an inline .sp literal.",
                ),
            )
        }
    }
}
