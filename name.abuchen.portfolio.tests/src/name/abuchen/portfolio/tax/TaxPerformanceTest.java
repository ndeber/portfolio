package name.abuchen.portfolio.tax;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;
import name.abuchen.portfolio.tax.TaxEstimateSettings.*;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class TaxPerformanceTest
{
    private final LocalDate start = LocalDate.of(2025, 10, 6);
    private final LocalDate end = start.plusYears(1);
    private final Client client = new Client();
    private final Account cash = new Account("Cash");
    private final Portfolio portfolio = new Portfolio("CTO");
    private final Security security = new Security("Action", "EUR");
    private final TestCurrencyConverter converter = new TestCurrencyConverter();
    private final String key = TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID());

    public TaxPerformanceTest()
    {
        client.setBaseCurrency("EUR");
        cash.setCurrencyCode("EUR");
        client.addAccount(cash);
        client.addPortfolio(portfolio);
        portfolio.setReferenceAccount(cash);
        client.addSecurity(security);
        cash.addTransaction(new AccountTransaction(start.atStartOfDay(), "EUR", 10_000_00, null, AccountTransaction.Type.DEPOSIT));
        trade(PortfolioTransaction.Type.BUY, start, 100, 10_000_00);
        price(start, 100);
        price(end, 120);
        configure(Profile.CTO, LossTreatment.POTENTIAL_SAVING, null);
    }

    private void price(LocalDate date, long price)
    {
        security.addPrice(new SecurityPrice(date, price * Values.Quote.factor()));
    }

    private BuySellEntry trade(PortfolioTransaction.Type type, LocalDate date, long quantity, long amount)
    {
        var entry = new BuySellEntry(portfolio, cash);
        entry.setType(type);
        entry.setDate(date.atTime(12, 0));
        entry.setSecurity(security);
        entry.setShares(quantity * Values.Share.factor());
        entry.setCurrencyCode("EUR");
        entry.setAmount(amount);
        entry.insert();
        return entry;
    }

    private void configure(Profile profile, LossTreatment losses, String review)
    {
        var basis = new Basis(profile, profile == Profile.EXEMPT ? 0 : 2500, losses, 10_000_00, 100L * Values.Share.factor(),
                        start.toString(), review, PositionTaxEstimates.movements(client, portfolio, security, start), "Test");
        TaxEstimateSettings.save(client, new Settings(1, Map.of(key, basis)), client.getProperty(TaxEstimateSettings.PROPERTY));
    }

    private TaxPerformance.Result result()
    {
        return TaxPerformance.calculate(client, TaxScope.of(client, ClientFilter.NO_FILTER, null), converter, Interval.of(start, end));
    }

    private TaxPerformance.Result title()
    {
        return TaxPerformance.calculate(client, TaxScope.security(client, ClientFilter.NO_FILTER, security), converter, Interval.of(start, end));
    }

    private AccountTransaction payment(LocalDate date, long amount, long openingDebt)
    {
        var transaction = new AccountTransaction(date.atStartOfDay(), "EUR", amount, null, AccountTransaction.Type.TAXES);
        cash.addTransaction(transaction);
        TaxPaymentLinks.save(client, new TaxPaymentLinks.Settings(1,
                        List.of(new TaxPaymentLinks.Link(cash.getUUID(), transaction.getUUID(), key, amount)), Map.of(key, openingDebt)),
                        client.getProperty(TaxPaymentLinks.PROPERTY));
        return transaction;
    }

    @Test
    public void fiveIndicatorsAgreeWithoutFlowsOverOneYear()
    {
        var r = result();
        assertEquals(r.detail(), Long.valueOf(1_500_00), r.after().gain());
        assertEquals(0.15, r.after().percent(), 1e-10);
        assertEquals(0.15, r.after().twr(), 1e-10);
        assertEquals(0.15, r.after().annualized(), 1e-10);
        assertEquals(0.15, r.after().irr(), 1e-6);
        assertEquals(Long.valueOf(500_00), r.latent());
        assertEquals(r.after().gain(), title().after().gain());
    }

    @Test
    public void datedContributionAffectsIrrButNotTimeWeightedReturn()
    {
        var mid = start.plusDays(182);
        cash.addTransaction(new AccountTransaction(mid.atStartOfDay(), "EUR", 10_000_00, null, AccountTransaction.Type.DEPOSIT));
        trade(PortfolioTransaction.Type.BUY, mid, 100, 10_000_00);
        var r = result();
        assertEquals(r.detail(), Long.valueOf(3_000_00), r.after().gain());
        assertEquals(0.15, r.after().percent(), 1e-10);
        assertEquals(0.15, r.after().twr(), 1e-10);
        assertTrue(r.after().irr() > r.after().twr());
        assertEquals(Long.valueOf(23_000_00), r.netValue());
    }

    @Test
    public void partialSaleKeepsSameNetWealthAndReleasesOnlySoldBasis()
    {
        price(end.minusDays(1), 120);
        trade(PortfolioTransaction.Type.SELL, end.minusDays(1), 60, 7_200_00);
        var r = result();
        assertEquals(r.detail(), Long.valueOf(1_500_00), r.after().gain());
        assertEquals(Long.valueOf(200_00), r.latent());
        assertEquals(Long.valueOf(300_00), r.debt());
        assertEquals(Long.valueOf(11_500_00), r.netValue());
        assertEquals(300_00, r.events().getFirst().tax());
        assertEquals(r.after().gain(), title().after().gain());
        assertEquals(0.15, title().after().twr(), 1e-10);
    }

    @Test
    public void matchedSaleTaxPaymentIsNotASecondExpense()
    {
        price(end.minusDays(1), 120);
        trade(PortfolioTransaction.Type.SELL, end.minusDays(1), 60, 7_200_00);
        payment(end, 300_00, 0);
        var r = result();
        assertEquals(r.detail(), Long.valueOf(1_500_00), r.after().gain());
        assertEquals(Long.valueOf(0), r.debt());
        assertEquals(Long.valueOf(11_500_00), r.netValue());
        assertEquals(0.15, r.after().twr(), 1e-10);
        assertEquals(0.15, r.after().irr(), 1e-6);
        assertEquals(Long.valueOf(1_500_00), title().after().gain());
    }

    @Test
    public void previousDebtPaymentDoesNotReducePeriodPerformance()
    {
        cash.addTransaction(new AccountTransaction(start.atStartOfDay(), "EUR", 500_00, null, AccountTransaction.Type.DEPOSIT));
        payment(start.plusDays(3), 500_00, 500_00);
        var r = result();
        assertEquals(r.detail(), Long.valueOf(1_500_00), r.after().gain());
        assertEquals(0.15, r.after().twr(), 1e-10);
    }

    @Test
    public void annualCloseCrystallizesAndRebasesWithoutAnotherTaxOnSameGain()
    {
        var close = end.minusDays(1);
        price(close, 110);
        configure(Profile.HOLDING_ANNUAL, LossTreatment.POTENTIAL_SAVING, close.toString());
        var r = result();
        assertEquals(r.detail(), Long.valueOf(250_00), r.debt());
        assertEquals(Long.valueOf(250_00), r.latent());
        assertEquals(Long.valueOf(1_500_00), r.after().gain());
        payment(end, 250_00, 0);
        assertEquals(Long.valueOf(1_500_00), result().after().gain());
    }

    @Test
    public void dividendsIncludeWithholdingOnlyOnceAndReserveTheBalance()
    {
        var income = new AccountTransaction(end.atStartOfDay(), "EUR", 900_00, security, AccountTransaction.Type.DIVIDENDS);
        income.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of("EUR", 100_00)));
        cash.addTransaction(income);
        var r = result();
        assertEquals(r.detail(), Long.valueOf(2_250_00), r.after().gain());
        assertEquals(Long.valueOf(150_00), r.debt());
        assertEquals(r.after().gain(), title().after().gain());
        assertEquals(r.after().twr(), title().after().twr(), 1e-10);
    }

    @Test
    public void negativeContributionsRespectLossPolicy()
    {
        price(end, 90);
        assertEquals(Long.valueOf(-750_00), result().after().gain());
        assertEquals(-0.075, result().after().twr(), 1e-10);
        configure(Profile.CTO, LossTreatment.ZERO_FLOOR, null);
        assertEquals(Long.valueOf(-1_000_00), result().after().gain());
    }

    @Test
    public void unconfiguredAndUnmatchedAreUnavailableNotZero()
    {
        var tx = new AccountTransaction(end.atStartOfDay(), "EUR", 100_00, null, AccountTransaction.Type.TAXES);
        cash.addTransaction(tx);
        assertNull(result().after().gain());
        assertTrue(result().detail().contains("rapprocher"));
        TaxEstimateSettings.save(client, new Settings(1, Map.of()), client.getProperty(TaxEstimateSettings.PROPERTY));
        assertNull(result().after().twr());
        assertTrue(result().detail().contains("paramétrer"));
    }

    @Test
    public void filtersUseOriginalPortfolioKeysAndWeights()
    {
        var filter = new PortfolioClientFilter(List.of(portfolio), List.of(cash), Map.of(portfolio, 5000, cash, 5000));
        var r = TaxPerformance.calculate(client, TaxScope.of(client, filter, null), converter, Interval.of(start, end));
        assertEquals(r.detail(), Long.valueOf(750_00), r.after().gain());
        assertEquals(0.15, r.after().twr(), 1e-10);
    }

    @Test
    public void emptyPeriodBeforeFirstPurchaseDoesNotRequireFictitiousHistoricalBasis()
    {
        var r = TaxPerformance.calculate(client, TaxScope.of(client, ClientFilter.NO_FILTER, null), converter, Interval.of(start.minusDays(1), end));
        assertEquals(r.detail(), Long.valueOf(1_500_00), r.after().gain());
        assertEquals(0.15, r.after().twr(), 1e-10);
    }

    @Test
    public void fullLiquidationDoesNotCreateNegativeTerminalPosition()
    {
        trade(PortfolioTransaction.Type.SELL, end, 100, 12_000_00);
        var r = title();
        assertEquals(r.detail(), Long.valueOf(0), r.netValue());
        assertEquals(Long.valueOf(1_500_00), r.after().gain());
        assertEquals(0.15, r.after().twr(), 1e-10);
        assertEquals(0.15, r.after().irr(), 1e-6);
    }

    @Test
    public void zeroPeriodHasNoInventedAnnualReturn()
    {
        var r = TaxPerformance.calculate(client, TaxScope.of(client, ClientFilter.NO_FILTER, null), converter, Interval.of(start, start));
        assertEquals(Long.valueOf(0), r.after().gain());
        assertNull(r.after().annualized());
        assertNull(r.after().irr());
    }
    @Test
    public void peDistributionNeedsAnExplicitTaxablePart()
    {
        var distribution = new AccountTransaction(end.minusDays(1).atStartOfDay(), "EUR", 2_000_00,
                        security, AccountTransaction.Type.DISTRIBUTION);
        cash.addTransaction(distribution);
        assertNull(result().after().gain());
        assertTrue(result().detail().contains("Ventiler"));
        TaxDistributionSplits.save(client, distribution.getUUID(), 500_00);
        var ledger = new TaxPositionLedger(client, portfolio, security, TaxEstimateSettings.load(client).positions().get(key),
                        TaxPaymentLinks.load(client), converter, end);
        assertEquals(8_500_00, ledger.at(end).basis());
        assertEquals(125_00, ledger.at(end).debt());
        assertNotNull(result().after().gain());
    }

    @Test
    public void capitalCallIncreasesTaxBasisWithoutCreatingTaxableIncome()
    {
        cash.addTransaction(new AccountTransaction(start.plusDays(30).atStartOfDay(), "EUR", 1_000_00,
                        security, AccountTransaction.Type.CAPITAL_CALL));
        var ledger = new TaxPositionLedger(client, portfolio, security, TaxEstimateSettings.load(client).positions().get(key),
                        TaxPaymentLinks.load(client), converter, end);
        assertEquals(11_000_00, ledger.at(end).basis());
        assertEquals(0, ledger.at(end).debt());
    }

    @Test
    public void invalidPaymentDoesNotSilentlyBecomeAnotherTaxCredit()
    {
        var tx = payment(end, 100_00, 100_00);
        tx.setAmount(50_00);
        assertNull(result().after().gain());
        assertTrue(result().detail().contains("dépasse"));
    }

    @Test
    public void scopeReturnIsCalculatedOnCapitalNotAnAverageOfTitleReturns()
    {
        var other = new Security("Stable", "EUR");
        client.addSecurity(other);
        other.addPrice(new SecurityPrice(start, 10L * Values.Quote.factor()));
        cash.addTransaction(new AccountTransaction(start.atStartOfDay(), "EUR", 1_000_00, null, AccountTransaction.Type.DEPOSIT));
        var buy = new BuySellEntry(portfolio, cash);
        buy.setType(PortfolioTransaction.Type.BUY); buy.setDate(start.atTime(12, 0));
        buy.setSecurity(other); buy.setShares(100L * Values.Share.factor()); buy.setCurrencyCode("EUR"); buy.setAmount(1_000_00); buy.insert();
        var old = TaxEstimateSettings.load(client);
        TaxEstimateSettings.save(client, old.with(TaxEstimateSettings.key(portfolio.getUUID(), other.getUUID()),
                        new Basis(Profile.EXEMPT, 0, LossTreatment.ZERO_FLOOR, 1_000_00, 100L * Values.Share.factor(), start.toString(), null,
                                        PositionTaxEstimates.movements(client, portfolio, other, start), "Exempt")), client.getProperty(TaxEstimateSettings.PROPERTY));
        var r = result();
        assertEquals(r.detail(), 1_500d / 11_000, r.after().twr(), 1e-10);
        assertNotEquals(0.075, r.after().twr(), 1e-10);
    }

    @Test
    public void aZeroOpeningBasisCanStartBeforeTheFirstPurchase()
    {
        var date = start.minusDays(1);
        TaxEstimateSettings.save(client, new Settings(1, Map.of(key, new Basis(Profile.CTO, 2500, LossTreatment.POTENTIAL_SAVING,
                        0, 0, date.toString(), null, PositionTaxEstimates.movements(client, portfolio, security, date), "Depuis le début"))),
                        client.getProperty(TaxEstimateSettings.PROPERTY));
        var r = TaxPerformance.calculate(client, TaxScope.of(client, ClientFilter.NO_FILTER, null), converter, Interval.of(date, end));
        assertEquals(r.detail(), Long.valueOf(1_500_00), r.after().gain());
        assertEquals(0.15, r.after().twr(), 1e-10);
    }

    @Test
    public void avSaleKeepsItsTaxVirtualInsideTheEnvelope()
    {
        configure(Profile.LIFE_INSURANCE, LossTreatment.POTENTIAL_SAVING, null);
        trade(PortfolioTransaction.Type.SELL, end, 100, 12_000_00);
        var r = result();
        assertEquals(r.detail(), Long.valueOf(500_00), r.latent());
        assertEquals(Long.valueOf(0), r.debt());
        assertEquals(Long.valueOf(1_500_00), r.after().gain());
    }

    @Test
    public void matchedRefundDoesNotCreateAnArtificialInflowOnAGainDay()
    {
        price(end.minusDays(1), 90);
        price(end, 95);
        trade(PortfolioTransaction.Type.SELL, end.minusDays(1), 60, 5_400_00);
        var refund = payment(end, 150_00, 0);
        refund.setType(AccountTransaction.Type.TAX_REFUND);
        var r = result();
        assertEquals(r.detail(), Long.valueOf(-600_00), r.after().gain());
        assertEquals(Long.valueOf(0), r.debt());
        assertEquals(-0.06, r.after().twr(), 1e-10);
    }

    @Test
    public void dividendTaxBaseIncludesFeesWhilePerformanceDeductsThem()
    {
        var income = new AccountTransaction(end.atStartOfDay(), "EUR", 890_00, security, AccountTransaction.Type.DIVIDENDS);
        income.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of("EUR", 100_00)));
        income.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of("EUR", 10_00)));
        cash.addTransaction(income);
        var r = result();
        assertEquals(r.detail(), Long.valueOf(2_240_00), r.after().gain());
        assertEquals(Long.valueOf(150_00), r.debt());
    }

    @Test
    public void simultaneousPaymentAndRefundDoNotDiluteTheDailyReturn()
    {
        var paid = payment(end, 150_00, 0);
        var refund = new AccountTransaction(end.atStartOfDay(), "EUR", 150_00, null, AccountTransaction.Type.TAX_REFUND);
        cash.addTransaction(refund);
        TaxPaymentLinks.save(client, new TaxPaymentLinks.Settings(1, List.of(
                        new TaxPaymentLinks.Link(cash.getUUID(), paid.getUUID(), key, 150_00),
                        new TaxPaymentLinks.Link(cash.getUUID(), refund.getUUID(), key, 150_00)), Map.of()),
                        client.getProperty(TaxPaymentLinks.PROPERTY));
        var r = result();
        assertEquals(r.detail(), Long.valueOf(1_500_00), r.after().gain());
        assertEquals(0.15, r.after().twr(), 1e-10);
    }

}
