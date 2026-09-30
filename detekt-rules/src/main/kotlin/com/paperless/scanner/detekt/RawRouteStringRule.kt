package com.paperless.scanner.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Flags raw, route-shaped string literals passed to `navigate(...)`.
 *
 * Compose navigation routes must go through the typed `Screen.<X>.route` /
 * `Screen.<X>.createRoute(...)` factories (centralized `Uri.encode`, compile-time
 * param types). Raw string routes are typo-prone and fail silently. Plan-08 (#45).
 */
class RawRouteStringRule(config: Config = Config.empty) : Rule(
    config,
    description = "Navigation routes must use Screen.<X>.route or " +
        "Screen.<X>.createRoute(), not raw strings.",
) {
    override val ruleName = RuleName("RawRouteString")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "navigate") return
        for (argument in expression.valueArguments) {
            val argExpr = argument.getArgumentExpression() as? KtStringTemplateExpression ?: continue
            if (looksLikeRoute(argExpr.text)) {
                report(
                    Finding(
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
