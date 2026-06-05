package com.paperless.scanner.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Flags raw, route-shaped string literals passed to `navigate(...)`.
 *
 * Compose navigation routes must go through the typed `Screen.<X>.route` /
 * `Screen.<X>.createRoute(...)` factories (centralized `Uri.encode`, compile-time
 * param types). Raw string routes are typo-prone and fail silently. Plan-08 (#45).
 */
class RawRouteStringRule(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "RawRouteString",
        severity = Severity.Maintainability,
        description = "Navigation routes must use Screen.<X>.route or " +
            "Screen.<X>.createRoute(), not raw strings.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "navigate") return
        for (argument in expression.valueArguments) {
            val argExpr = argument.getArgumentExpression() as? KtStringTemplateExpression ?: continue
            if (looksLikeRoute(argExpr.text)) {
                report(
                    CodeSmell(
                        issue,
                        Entity.from(argExpr),
                        "Raw route string ${argExpr.text} passed to navigate() — use " +
                            "Screen.<X>.route or Screen.<X>.createRoute() instead.",
                    ),
                )
            }
        }
    }

    private fun looksLikeRoute(text: String): Boolean =
        text.contains('/') || text.contains('?') || text.contains('{')
}
