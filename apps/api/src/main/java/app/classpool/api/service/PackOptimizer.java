package app.classpool.api.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The bulk-pack optimizer's DP core (PRD §7.1), deliberately separated from {@link
 * PurchasePlanService} so it can be unit-tested with no HTTP/authorization/persistence layer
 * involved — a plain deterministic integer program, not a heuristic.
 *
 * <p><b>The problem.</b> Given a {@code need} (a requirement's residual demand) and a set of
 * candidate {@link OfferInput}s (pack size + price), find non-negative pack counts per offer whose
 * combined quantity covers at least {@code need}, minimizing total cost first and, among equally
 * cheap combinations, the fewest total units purchased (least waste) as the deterministic
 * tie-break.
 *
 * <p><b>The algorithm — classic unbounded-knapsack "minimum cost to cover at least N units".</b>
 * {@code dp[0] = 0}, {@code dp[q] = infinity} for {@code q} in {@code 1..upperBound}, where {@code
 * upperBound = need + max(packQuantity across offers)} — one full extra pack of the largest size
 * is always enough headroom, since buying {@code ceil(need / maxPack)} copies of the largest pack
 * alone always lands at or under {@code upperBound} (see the loop below that finds {@code bestQ} —
 * it is always reachable for exactly this reason, so this method never needs to signal
 * "unreachable"). For each offer, for {@code q} from {@code packQuantity} to {@code upperBound}:
 * {@code dp[q] = min(dp[q], dp[q - packQuantity] + priceCents)}, recording which offer produced
 * the improvement at each {@code q} (a parent pointer) for backtracking. The answer is {@code
 * min(dp[q] for q in need..upperBound)}; scanning {@code q} ascending and only overwriting the
 * best candidate on a <em>strict</em> improvement means the first (smallest) {@code q} that
 * achieves the minimum cost is kept automatically — the least-waste tie-break falls out of the
 * scan order for free, no separate tie-break pass needed.
 *
 * <p><b>Reconstruction — one line per distinct offer, waste on exactly one.</b> Backtracking from
 * {@code bestQ} to {@code 0} via the parent pointers can revisit the same offer more than once
 * (unbounded knapsack) or use more than one distinct offer for the same requirement (the PRD's own
 * worked example: 2×144-pack + 1×48-pack) — counts per offer are accumulated into a map, so the
 * final result has exactly one {@link ChosenOffer} per distinct offer used, not one per DP step.
 * {@code totalUnitsPurchased} is {@code bestQ} by construction, so {@code wasteQuantity =
 * bestQ - need}; per the contract's own instruction, that whole number is attributed to a single
 * line rather than split or double-counted — {@link PurchasePlanService} does this by always
 * putting it on the first {@link ChosenOffer} in {@link OptimizationResult#chosenOffers()}, which
 * this method returns sorted by {@code offerId} for exactly that reason (a stable, deterministic
 * "first" with no dependence on iteration/map order).
 */
final class PackOptimizer {

    private PackOptimizer() {
    }

    record OfferInput(UUID offerId, int packQuantity, int priceCents) {
    }

    record ChosenOffer(UUID offerId, int packCount, int lineCostCents) {
    }

    record OptimizationResult(List<ChosenOffer> chosenOffers, int totalCostCents, int totalUnitsPurchased,
                               int wasteQuantity) {
    }

    /**
     * @param need   the requirement's residual demand; must be &gt; 0 (callers are expected to
     *               skip requirements with zero residual demand before ever calling this, per the
     *               PRD's "for each Requirement with residualDemand &gt; 0" scoping).
     * @param offers every candidate offer entered for that requirement; must be non-empty (callers
     *               are expected to validate "at least one offer per requirement" before calling
     *               this — see {@code PurchasePlanService.generate}'s pre-DP validation pass).
     */
    static OptimizationResult optimize(int need, List<OfferInput> offers) {
        if (need <= 0) {
            throw new IllegalArgumentException("need must be > 0: " + need);
        }
        if (offers.isEmpty()) {
            throw new IllegalArgumentException("offers must not be empty");
        }

        int maxPackQuantity = offers.stream().mapToInt(OfferInput::packQuantity).max().orElseThrow();
        int upperBound = need + maxPackQuantity;

        int[] dp = new int[upperBound + 1];
        int[] parentOfferIndex = new int[upperBound + 1];
        Arrays.fill(dp, Integer.MAX_VALUE);
        Arrays.fill(parentOfferIndex, -1);
        dp[0] = 0;

        for (int offerIndex = 0; offerIndex < offers.size(); offerIndex++) {
            OfferInput offer = offers.get(offerIndex);
            for (int q = offer.packQuantity(); q <= upperBound; q++) {
                int fromCost = dp[q - offer.packQuantity()];
                if (fromCost == Integer.MAX_VALUE) {
                    continue;
                }
                int candidateCost = fromCost + offer.priceCents();
                if (candidateCost < dp[q]) {
                    dp[q] = candidateCost;
                    parentOfferIndex[q] = offerIndex;
                }
            }
        }

        int bestQ = -1;
        int bestCost = Integer.MAX_VALUE;
        for (int q = need; q <= upperBound; q++) {
            if (dp[q] < bestCost) {
                bestCost = dp[q];
                bestQ = q;
            }
        }
        if (bestQ == -1) {
            // Not reachable in practice — see the class Javadoc for why upperBound always admits a
            // solution using the largest single offer repeated. Guarded defensively regardless.
            throw new IllegalStateException("No combination of offers can cover a need of " + need);
        }

        Map<UUID, int[]> countAndCostByOfferId = new LinkedHashMap<>(); // offerId -> [packCount, lineCostCents]
        int q = bestQ;
        while (q > 0) {
            int offerIndex = parentOfferIndex[q];
            OfferInput offer = offers.get(offerIndex);
            int[] entry = countAndCostByOfferId.computeIfAbsent(offer.offerId(), k -> new int[2]);
            entry[0] += 1;
            entry[1] += offer.priceCents();
            q -= offer.packQuantity();
        }

        List<ChosenOffer> chosenOffers = new ArrayList<>(countAndCostByOfferId.size());
        countAndCostByOfferId.forEach((offerId, entry) -> chosenOffers.add(new ChosenOffer(offerId, entry[0], entry[1])));
        chosenOffers.sort(Comparator.comparing(ChosenOffer::offerId));

        return new OptimizationResult(chosenOffers, bestCost, bestQ, bestQ - need);
    }
}
