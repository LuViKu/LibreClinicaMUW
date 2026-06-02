/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Phase E RX.5 — request body for {@code POST /api/v1/rule-sets}.
 *
 * <p>{@code target} is the target expression — the OID path the rule
 * set attaches to. Must pass
 * {@code ExpressionService.ruleSetExpressionChecker} against the
 * active study's scope.
 *
 * <p>The three scope OIDs ({@code studyEventDefinitionOid},
 * {@code crfOid}, {@code crfVersionOid}) are optional and narrow the
 * rule_set to a specific SED / CRF / CRF version. When omitted the
 * rule_set applies study-wide for the target item. The controller
 * resolves each to its persistent bean via the legacy DAOs; an
 * unresolvable OID returns 400 with the offending field name. A
 * {@code crfVersionOid} without a matching {@code crfOid} is allowed
 * (the CRF can be looked up from the version), but if both are
 * present the version must belong to the named CRF.
 *
 * <p>{@code ruleOids} is the list of rule OIDs to bind to the
 * rule_set. At least one is required; each must resolve to an
 * existing {@code rule} in the active study (typically including the
 * rule just created via {@code POST /api/v1/rules}).
 */
@Schema(name = "CreateRuleSetRequest")
public record CreateRuleSetRequest(
        String target,
        String studyEventDefinitionOid,
        String crfOid,
        String crfVersionOid,
        List<String> ruleOids
) {}
