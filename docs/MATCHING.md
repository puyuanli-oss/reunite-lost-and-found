# Matching and disclosure policy

Matching is a deterministic discovery aid. It never establishes ownership, approves a claim, or exposes verification answers. Public search uses the visitor's filters; it does **not** require a lost report. Private found-report access additionally requires the requesting user's own active lost report to pass the rules below. The server must repeat the same authorization for details and photo bytes; hiding cards in JavaScript does not protect an item.

## Vetoes before ranking

Reject a pair when either report is missing/inactive, the report types are not LOST then FOUND, both belong to the same account, dates are missing/invalid, or the found date precedes the lost date. Reject contradictions between two known primary categories or two known item subcategories. `OTHER`, missing, and unknown values do not veto or receive agreement points. Both descriptions must share at least one meaningful word.

Use a veto for a fact people can reliably observe without opening bags or unlocking devices: a phone is not a laptop; a passport is not a USC ID. Color, brand, and possible location are weaker observations and must not veto a pair. One primary item type and one optional subtype avoid ambiguous contradictory checkbox combinations. A person unsure of a classification can choose `OTHER` and supply a fuller description.

## Fixed evidence score

| Evidence | Maximum | Rule |
| --- | ---: | --- |
| Known primary category | 30 | Exact agreement |
| Known reliable subtype | 20 | Exact agreement |
| Title and description | 25 | Token-set Dice similarity: `25 × 2 × shared / (lost words + found words)` |
| Possible location | 10 | Token-set Jaccard similarity |
| Date proximity | 10 | `10 × 0.5^(days after loss / 14)` |
| Color | 3 | Exact normalized known value |
| Brand | 2 | Exact normalized known value |
| **Total** | **100** | Missing evidence earns zero; never rescale to the available fields |

Word comparison normalizes Unicode compatibility forms, lowercase, common filler words, and conservative English plurals. It compares sets, so repeated keywords cannot inflate the score. It intentionally avoids an LLM, image recognition, or secret identifying details. Location is approximate text overlap; no unimplemented 200-metre geospatial precision is implied.

A match needs **at least 65/100**, plus **two shared content words or an exact known subtype and one shared content word**. At least one shared word must describe more than the generic item type: `black wallet` can qualify, whereas `wallet` alone cannot, and `USC ID` alone cannot. This prevents type-only wording from unlocking an item. The score is an evidence rank, **not a probability**. Scores use fixed weights and are rounded to two decimals; unknown fields cannot turn a weak pair into a perfect match. The overload accepting a threshold allows 65–100 for stricter policies.

## Limits and review

These initial weights are deliberate product settings, not values learned or calibrated from labeled campus data. Evaluate precision and recall with real, consented examples before changing them. Track false negatives from misclassified subtypes and let an administrator review them; avoid relaxing a reliable-category veto silently. A category typo should be corrected on the source report. Dates are strict because the specified product rule requires found-on-or-after-lost; if users are unsure of dates, add an explicit date-range model before relaxing this condition.

The discovery gate reduces accidental disclosure. It is not proof that the submitted lost report is truthful: someone who guesses matching descriptions may still qualify. Keep verification evidence private, throttle repeated searches/report creation, restrict direct photo endpoints, audit access, and require a human claim decision. Exact private counts are also an existence oracle: the requested count feature trades some privacy for discovery and should be reviewed before a wider deployment.

The matching engine's explanation contains fixed evidence labels and awarded points, never raw shared words, identifying information, verification questions, or answers. A report update/removal/visibility change must invalidate previously stored eligibility; cached match records alone must never authorize access.

## Executable tests

`MatchingEngineTest` checks vetoes, unknown fields, fixed-denominator behavior, category/subtype reliability, soft color disagreements, text normalization, date decay, sparse evidence, threshold validation, secret-field exclusion, deterministic explanations, and score bounds. Its main method throws on failure without relying on Java's optional `-ea` flag.
