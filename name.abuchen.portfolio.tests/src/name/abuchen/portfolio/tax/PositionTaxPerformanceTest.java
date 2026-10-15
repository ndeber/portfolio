package name.abuchen.portfolio.tax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.Map;

import org.junit.Test;

import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Basis;
import name.abuchen.portfolio.tax.TaxEstimateSettings.LossTreatment;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Profile;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Settings;

@SuppressWarnings("nls")
public class PositionTaxPerformanceTest
{
    private final LocalDate start = LocalDate.of(2026, 9, 19);
    private final LocalDate end = LocalDate.of(2026, 9, 29);
    private final Client client = new Client();
    private final Portfolio portfolio = new Portfolio("CTO");
    private final Account account = new Account("Espèces");
    private final Security security = new Security("Titre", "EUR");
    private final String key = TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID());

    public PositionTaxPerformanceTest()
    {
        client.setBaseCurrency("EUR");
        account.setCurrencyCode("EUR");
        client.addAccount(account);
        portfolio.setReferenceAccount(account);
        client.addPortfolio(portfolio);
        client.addSecurity(security);
        transaction(PortfolioTransaction.Type.DELIVERY_INBOUND, start, 100);
        prices(110, 120);
    }

    private void transaction(PortfolioTransaction.Type type, LocalDate date, long shares)
    {
        portfolio.addTransaction(new PortfolioTransaction(date.atStartOfDay(), "EUR", shares * 100_00,
                        security, shares * Values.Share.factor(), type, 0, 0));
    }

    private void prices(long opening, long closing)
    {
        security.addPrice(new SecurityPrice(start, opening * Values.Quote.factor()));
        security.addPrice(new SecurityPrice(end, closing * Values.Quote.factor()));
    }

    private Settings settings(LocalDate validated, long amount, LossTreatment losses, String review)
    {
        return new Settings(1, Map.of(key, new Basis(review == null ? Profile.CTO : Profile.HOLDING_ANNUAL,
                        2500, losses, amount, 100L * Values.Share.factor(), validated.toString(), review,
                        PositionTaxEstimates.movements(client, portfolio, security, validated), "Fixture")));
    }

    private Settings settings()
    {
        return settings(start, 10_000_00, LossTreatment.POTENTIAL_SAVING, null);
    }

    private PositionTaxPerformance.Result result(Settings settings)
    {
        var converter = new TestCurrencyConverter();
        var opening = PositionTaxEstimates.rows(client, settings, converter, start).getFirst();
        var closing = PositionTaxEstimates.rows(client, settings, converter, end).getFirst();
        return PositionTaxPerformance.between(client, opening, closing, start, end);
    }

    @Test
    public void subtractsChangeInTaxRatherThanClosingTax()
    {
        var result = result(settings());
        assertTrue(result.detail(), result.isComplete());
        assertEquals(Long.valueOf(1_000_00), result.beforeTax());
        assertEquals(Long.valueOf(0), result.accruedTax());
        assertEquals(Long.valueOf(250_00), result.latentTaxChange());
        assertEquals(Long.valueOf(750_00), result.afterTax());
        prices(110, 130);
        assertEquals(Long.valueOf(1_500_00), result(settings()).afterTax());
    }

    @Test
    public void lossesAndCrossingZeroUseBothEndpoints()
    {
        prices(95, 90);
        var loss = result(settings());
        assertEquals(Long.valueOf(-500_00), loss.beforeTax());
        assertEquals(Long.valueOf(-125_00), loss.latentTaxChange());
        assertEquals(Long.valueOf(-375_00), loss.afterTax());
        prices(95, 105);
        var floor = result(settings(start, 10_000_00, LossTreatment.ZERO_FLOOR, null));
        assertEquals(Long.valueOf(125_00), floor.latentTaxChange());
        assertEquals(Long.valueOf(875_00), floor.afterTax());
    }

    @Test
    public void adjustedTaxBasisIsNotTreatedAsEconomicStartingValue()
    {
        var result = result(settings(start, 11_500_00, LossTreatment.POTENTIAL_SAVING, "2026-12-31"));
        assertEquals(Long.valueOf(1_000_00), result.beforeTax());
        assertEquals(Long.valueOf(750_00), result.afterTax());
    }

    @Test
    public void paymentsWithoutAttributionDoNotCreateAnotherCharge()
    {
        account.addTransaction(new AccountTransaction(end.atStartOfDay(), "EUR", 500_00, null,
                        AccountTransaction.Type.TAXES));
        var result = result(settings());
        assertEquals(Long.valueOf(0), result.accruedTax());
        assertEquals(Long.valueOf(750_00), result.afterTax());
        assertEquals(500_00, account.getTransactions().getFirst().getAmount());
    }

    @Test
    public void fiscalReclassificationUsesTheLatentReversalOnce()
    {
        var result = PositionTaxPerformance.reconcile(1_000_00, 750_00, 500_00, 0);
        assertEquals(Long.valueOf(-500_00), result.latentTaxChange());
        assertEquals(Long.valueOf(750_00), result.afterTax());
    }

    @Test
    public void unsupportedMovementAndSameQuantityRoundTripDoNotShowFalsePerformance()
    {
        var settings = settings();
        transaction(PortfolioTransaction.Type.DELIVERY_OUTBOUND, start.plusDays(1), 10);
        assertFalse(result(settings).isComplete());
        transaction(PortfolioTransaction.Type.DELIVERY_INBOUND, start.plusDays(2), 10);
        var result = result(settings);
        assertNull(result.beforeTax());
        assertNull(result.afterTax());
    }

    @Test
    public void incomeCannotBeSilentlyOmittedFromPerformance()
    {
        account.addTransaction(new AccountTransaction(end.atStartOfDay(), "EUR", 100_00, security,
                        AccountTransaction.Type.DIVIDENDS));
        assertFalse(result(settings()).isComplete());
        assertNull(result(settings()).beforeTax());
    }

    @Test
    public void parametersValidatedLaterAndAnnualCloseDoNotInventHistoricalTax()
    {
        var recent = result(settings(end, 10_000_00, LossTreatment.POTENTIAL_SAVING, null));
        assertEquals(Long.valueOf(1_000_00), recent.beforeTax());
        assertNull(recent.afterTax());
        assertNull(recent.accruedTax());
        var closed = result(settings(start, 10_000_00, LossTreatment.POTENTIAL_SAVING, end.toString()));
        assertNull(closed.afterTax());
    }

    @Test
    public void missingOpeningQuoteDoesNotFallBackToAcquisitionCost()
    {
        security.removeAllPrices();
        security.addPrice(new SecurityPrice(end, 120 * Values.Quote.factor()));
        var result = result(settings());
        assertNull(result.beforeTax());
        assertNull(result.afterTax());
    }
}
