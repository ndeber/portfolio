package name.abuchen.portfolio.ui.tax;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.util.Map;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.*;
import name.abuchen.portfolio.snapshot.ReportingPeriod;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;
import name.abuchen.portfolio.tax.*;
import name.abuchen.portfolio.ui.views.StatementOfAssetsViewer;
import name.abuchen.portfolio.ui.views.dashboard.*;
import name.abuchen.portfolio.ui.views.dataseries.*;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class AfterTaxWidgetTest
{
    @Test
    public void widgetsAndNativeTotalsUseIdenticalScopeAndPeriod() throws Exception
    {
        var start = LocalDate.now().minusYears(1);
        var client = new Client();client.setBaseCurrency("EUR");
        var account = new Account("Cash");account.setCurrencyCode("EUR");client.addAccount(account);
        var portfolio = new Portfolio("Investissable");portfolio.setReferenceAccount(account);client.addPortfolio(portfolio);
        var security = new Security("Action", "EUR");client.addSecurity(security);
        security.addPrice(new SecurityPrice(start, 100L * Values.Quote.factor()));
        security.addPrice(new SecurityPrice(LocalDate.now(), 120L * Values.Quote.factor()));
        portfolio.addTransaction(new PortfolioTransaction(start.atStartOfDay(), "EUR", 10_000_00, security,
                        100L * Values.Share.factor(), PortfolioTransaction.Type.DELIVERY_INBOUND, 0, 0));
        var key = TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID());
        TaxEstimateSettings.save(client, new TaxEstimateSettings.Settings(1, Map.of(key,
                        new TaxEstimateSettings.Basis(TaxEstimateSettings.Profile.CTO, 2500, TaxEstimateSettings.LossTreatment.POTENTIAL_SAVING,
                                        10_000_00, 100L * Values.Share.factor(), start.toString(), null,
                                        PositionTaxEstimates.movements(client, portfolio, security, start), "Test"))), null);
        var preferences = new PreferenceStore();
        var period = new ReportingPeriod.FromXtoY(start, LocalDate.now());
        var nativeModel = new StatementOfAssetsViewer.Model(preferences, client, ClientFilter.NO_FILTER,
                        new TestCurrencyConverter(), LocalDate.now(), null);
        var total = nativeModel.getElements().stream().filter(StatementOfAssetsViewer.Element::isGroupByTaxonomy).findFirst().orElseThrow();
        var expected = nativeModel.getTaxValues().performance(total, Interval.of(start, LocalDate.now()));
        var constructor = DataSeries.class.getDeclaredConstructor(DataSeries.Type.class, Object.class, String.class, org.eclipse.swt.graphics.RGB.class);
        constructor.setAccessible(true);
        var series = constructor.newInstance(DataSeries.Type.CLIENT, DataSeries.ClientDataSeries.DELTA_PERCENTAGE, "Investissable", new org.eclipse.swt.graphics.RGB(0, 0, 0));
        assertTrue(AfterTaxWidget.supports(series));
        var actual = TaxPerformance.calculate(client, AfterTaxWidget.scope(client, series), new TestCurrencyConverter(), Interval.of(start, LocalDate.now()));
        assertEquals(expected.after().gain(), actual.after().gain());
        assertEquals(expected.after().percent(), actual.after().percent());
        assertEquals(expected.after().twr(), actual.after().twr());
        assertEquals(expected.after().annualized(), actual.after().annualized());
        assertEquals(expected.after().irr(), actual.after().irr());
        assertEquals(Long.valueOf(1_500_00), expected.after().gain());
        assertEquals(0.15, expected.after().twr(), 1e-9);
    }
}
