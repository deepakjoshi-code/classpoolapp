package app.classpool.api.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Focused unit coverage of {@link PackOptimizer}'s DP core (PRD §7.1), independent of the
 * HTTP/authorization/persistence layers — see {@code PurchasePlanServiceTest} for the
 * service-level 403/409 and skip-logic coverage.
 */
class PackOptimizerTest {

    /**
     * The PRD's own worked example, verified numerically: need=320 pencils, offers 24-pack@$4.99
     * (499c), 48-pack@$8.49 (849c), 144-pack@$18.99 (1899c). Optimal combination is 2x144-pack +
     * 1x48-pack = 336 units for 4647 cents ($46.47), waste=16 — by hand: 3x144=432 units costs
     * 5697c (worse); 2x144+2x24=336 units costs 3798+998=4796c (worse); 2x144+1x48=336 units costs
     * 3798+849=4647c (cheapest found), confirming the DP's answer independently.
     */
    @Test
    void optimize_pencilExample_matchesPrdWorkedAnswerExactly() {
        UUID pack24 = UUID.randomUUID();
        UUID pack48 = UUID.randomUUID();
        UUID pack144 = UUID.randomUUID();
        List<PackOptimizer.OfferInput> offers = List.of(
                new PackOptimizer.OfferInput(pack24, 24, 499),
                new PackOptimizer.OfferInput(pack48, 48, 849),
                new PackOptimizer.OfferInput(pack144, 144, 1899));

        PackOptimizer.OptimizationResult result = PackOptimizer.optimize(320, offers);

        assertThat(result.totalCostCents()).isEqualTo(4647);
        assertThat(result.totalUnitsPurchased()).isEqualTo(336);
        assertThat(result.wasteQuantity()).isEqualTo(16);
        assertThat(result.chosenOffers()).hasSize(2);
        assertThat(result.chosenOffers()).extracting(PackOptimizer.ChosenOffer::offerId,
                        PackOptimizer.ChosenOffer::packCount, PackOptimizer.ChosenOffer::lineCostCents)
                .containsExactlyInAnyOrder(
                        tuple(pack144, 2, 3798),
                        tuple(pack48, 1, 849));
        // The whole requirement's waste is attributed to exactly one designated line by
        // PurchasePlanService (sorted by offerId) — verify the sum of lineCostCents across chosen
        // offers reconciles to the total, so nothing was double counted or dropped.
        int sumOfLineCosts = result.chosenOffers().stream().mapToInt(PackOptimizer.ChosenOffer::lineCostCents).sum();
        assertThat(sumOfLineCosts).isEqualTo(result.totalCostCents());
    }

    @Test
    void optimize_singleViableOffer_buysEnoughFullPacksToCoverNeed() {
        UUID offerId = UUID.randomUUID();
        List<PackOptimizer.OfferInput> offers = List.of(new PackOptimizer.OfferInput(offerId, 4, 30));

        PackOptimizer.OptimizationResult result = PackOptimizer.optimize(10, offers);

        assertThat(result.chosenOffers()).containsExactly(new PackOptimizer.ChosenOffer(offerId, 3, 90));
        assertThat(result.totalCostCents()).isEqualTo(90);
        assertThat(result.totalUnitsPurchased()).isEqualTo(12);
        assertThat(result.wasteQuantity()).isEqualTo(2);
    }

    /**
     * Two offers tie on total cost to cover need=5 — a single 5-pack for 100c (exact, no waste) vs
     * a single 6-pack for 100c (1 unit of waste). The contract's deterministic tie-break picks the
     * smallest total quantity purchased, i.e. the zero-waste 5-pack.
     */
    @Test
    void optimize_tiedOnCost_picksTheSmallestTotalQuantityAsTheDeterministicTieBreak() {
        UUID fivePack = UUID.randomUUID();
        UUID sixPack = UUID.randomUUID();
        List<PackOptimizer.OfferInput> offers = List.of(
                new PackOptimizer.OfferInput(fivePack, 5, 100),
                new PackOptimizer.OfferInput(sixPack, 6, 100));

        PackOptimizer.OptimizationResult result = PackOptimizer.optimize(5, offers);

        assertThat(result.totalCostCents()).isEqualTo(100);
        assertThat(result.totalUnitsPurchased()).isEqualTo(5);
        assertThat(result.wasteQuantity()).isZero();
        assertThat(result.chosenOffers()).containsExactly(new PackOptimizer.ChosenOffer(fivePack, 1, 100));
    }

    @Test
    void optimize_combinesMultipleOffersWhenCheaperThanAnySingleOffer() {
        // A 10-pack at 100c/unit-equivalent alone would cost 1000c for 100 units; mixing in a
        // cheaper 40-pack should be found automatically by the DP, not hardcoded.
        UUID tenPack = UUID.randomUUID();
        UUID fortyPack = UUID.randomUUID();
        List<PackOptimizer.OfferInput> offers = List.of(
                new PackOptimizer.OfferInput(tenPack, 10, 100),
                new PackOptimizer.OfferInput(fortyPack, 40, 200));

        // need=90: two 40-packs (80 units, 400c) + one 10-pack (10 units, 100c) = 90 units, 500c —
        // cheaper than e.g. nine 10-packs (900c) or three 40-packs (600c, 30 units of waste).
        PackOptimizer.OptimizationResult result = PackOptimizer.optimize(90, offers);

        assertThat(result.totalUnitsPurchased()).isEqualTo(90);
        assertThat(result.totalCostCents()).isEqualTo(500);
        assertThat(result.wasteQuantity()).isZero();
        assertThat(result.chosenOffers()).extracting(PackOptimizer.ChosenOffer::offerId,
                        PackOptimizer.ChosenOffer::packCount)
                .containsExactlyInAnyOrder(tuple(fortyPack, 2), tuple(tenPack, 1));
    }

    @Test
    void optimize_throwsIllegalArgument_whenNeedIsZeroOrNegative() {
        List<PackOptimizer.OfferInput> offers = List.of(new PackOptimizer.OfferInput(UUID.randomUUID(), 10, 100));
        assertThatThrownBy(() -> PackOptimizer.optimize(0, offers)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PackOptimizer.optimize(-1, offers)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optimize_throwsIllegalArgument_whenNoOffersGiven() {
        assertThatThrownBy(() -> PackOptimizer.optimize(10, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
