package name.abuchen.portfolio.ui.tax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.filter.ClientFilter;
import name.abuchen.portfolio.snapshot.filter.PortfolioClientFilter;
import name.abuchen.portfolio.tax.PositionTaxEstimates;
import name.abuchen.portfolio.tax.TaxEstimateSettings;
import name.abuchen.portfolio.tax.TaxPaymentLinks;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Basis;
import name.abuchen.portfolio.tax.TaxEstimateSettings.LossTreatment;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Profile;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Settings;
import name.abuchen.portfolio.ui.tax.StatementOfAssetsTaxValues.Metric;
import name.abuchen.portfolio.ui.views.StatementOfAssetsViewer.Element;
import name.abuchen.portfolio.ui.views.StatementOfAssetsViewer.Model;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class StatementOfAssetsTaxValuesTest
{
    private final LocalDate start = LocalDate.of(2026, 9, 19);
    private final LocalDate end = LocalDate.of(2026, 9, 29);
    private final Interval interval = Interval.of(start, end);
    private final Client client = new Client();
    private final Portfolio cto = new Portfolio("CTO");
    private final Portfolio holding = new Portfolio("Holding");
    private final Account cash = new Account("Espèces");
    private final Security security = new Security("Titre commun", "EUR");

    public StatementOfAssetsTaxValuesTest()
    {
        client.setBaseCurrency("EUR");
        cash.setCurrencyCode("EUR");
        client.addAccount(cash);
        client.addSecurity(security);
        for (var portfolio : List.of(cto, holding))
        {
            client.addPortfolio(portfolio);
            portfolio.setReferenceAccount(cash);
            portfolio.addTransaction(new PortfolioTransaction(start.atStartOfDay(), "EUR", 10_000_00,
                            security, 100L * Values.Share.factor(), PortfolioTransaction.Type.DELIVERY_INBOUND, 0, 0));
        }
        security.addPrice(new SecurityPrice(start, 110L * Values.Quote.factor()));
        security.addPrice(new SecurityPrice(end, 120L * Values.Quote.factor()));
        cash.addTransaction(new AccountTransaction(start.atStartOfDay(), "EUR", 1_000_00, null,
                        AccountTransaction.Type.DEPOSIT));
        cash.addTransaction(new AccountTransaction(end.atStartOfDay(), "EUR", 500_00, null,
                        AccountTransaction.Type.TAXES));
        save(new Settings(1, Map.of(key(cto), basis(cto, 2500), key(holding), basis(holding, 1000))));
        TaxPaymentLinks.save(client, new TaxPaymentLinks.Settings(1, List.of(new TaxPaymentLinks.Link(cash.getUUID(),
                        cash.getTransactions().getLast().getUUID(), key(cto), 500_00)), Map.of(key(cto), 500_00L)), null);
    }

    private String key(Portfolio portfolio)
    {
        return TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID());
    }

    private Basis basis(Portfolio portfolio, int rate)
    {
        return new Basis(Profile.CUSTOM, rate, LossTreatment.POTENTIAL_SAVING, 10_000_00,
                        100L * Values.Share.factor(), start.toString(), null,
                        PositionTaxEstimates.movements(client, portfolio, security, start), "Test");
    }

    private void save(Settings settings)
    {
        TaxEstimateSettings.save(client, settings, client.getProperty(TaxEstimateSettings.PROPERTY));
    }

    private Model model(ClientFilter filter, Taxonomy taxonomy)
    {
        return new Model(new PreferenceStore(), client, filter, new TestCurrencyConverter(), end, taxonomy);
    }

    private Element security(Model model)
    {
        return model.getElements().stream().filter(Element::isSecurity).findFirst().orElseThrow();
    }

    private Element total(Model model)
    {
        return model.getElements().stream().filter(Element::isGroupByTaxonomy).findFirst().orElseThrow();
    }

    private Long value(Model model, Element element, Metric metric)
    {
        return model.getTaxValues().value(element, metric, interval).amount();
    }

    @Test
    public void aggregatesSeparateTaxProfilesAndKeepsActualCash()
    {
        var model = model(ClientFilter.NO_FILTER, null);
        var title = security(model);
        assertEquals(Long.valueOf(700_00), value(model, title, Metric.LATENT));
        assertEquals(Long.valueOf(23_300_00), value(model, title, Metric.NET_VALUE));
        assertEquals(Long.valueOf(23_800_00), value(model, total(model), Metric.NET_VALUE));
        assertEquals(Long.valueOf(2_000_00), value(model, title, Metric.BEFORE));
        assertEquals(Long.valueOf(0), value(model, title, Metric.ACCRUED));
        assertEquals(Long.valueOf(350_00), value(model, title, Metric.CHANGE));
        assertEquals(Long.valueOf(1_650_00), value(model, total(model), Metric.AFTER));
        assertEquals(2, cash.getTransactions().size());
    }

    @Test
    public void nativePortfolioFilterUsesOriginalAccountSettings()
    {
        var model = model(new PortfolioClientFilter(cto), null);
        assertEquals(Long.valueOf(500_00), value(model, security(model), Metric.LATENT));
        assertEquals(Long.valueOf(750_00), value(model, security(model), Metric.AFTER));
    }

    @Test
    public void unassignedCategoryKeepsThePerformanceOfFullySoldTitles()
    {
        for (var portfolio : List.of(cto, holding))
        {
            var sale = new name.abuchen.portfolio.model.BuySellEntry(portfolio, cash);
            sale.setType(PortfolioTransaction.Type.SELL);
            sale.setDate(end.atTime(12, 0));
            sale.setSecurity(security);
            sale.setShares(100L * Values.Share.factor());
            sale.setCurrencyCode("EUR");
            sale.setAmount(12_000_00);
            sale.insert();
        }
        var model = model(ClientFilter.NO_FILTER, null);
        var category = total(model).getChildren().filter(Element::isCategory).findFirst().orElseThrow();
        assertEquals(Long.valueOf(1_650_00), value(model, category, Metric.AFTER));
        assertEquals(value(model, total(model), Metric.AFTER), value(model, category, Metric.AFTER));
    }

    @Test
    public void ownershipWeightScalesOnlySelectedPortfolio()
    {
        var filter = new PortfolioClientFilter(List.of(cto), List.of(),
                        Map.of(cto, Classification.ONE_HUNDRED_PERCENT / 2));
        var model = model(filter, null);
        assertEquals(Long.valueOf(250_00), value(model, security(model), Metric.LATENT));
        assertEquals(Long.valueOf(5_750_00), value(model, security(model), Metric.NET_VALUE));
        assertEquals(Long.valueOf(375_00), value(model, security(model), Metric.AFTER));
    }

    @Test
    public void missingConfigurationNeverCreatesFalseCompleteTotals()
    {
        save(new Settings(1, Map.of(key(cto), basis(cto, 2500))));
        var model = model(ClientFilter.NO_FILTER, null);
        assertNull(value(model, security(model), Metric.LATENT));
        assertNull(value(model, total(model), Metric.NET_VALUE));
        assertNull(value(model, total(model), Metric.AFTER));
        assertEquals(Long.valueOf(2_000_00), value(model, total(model), Metric.BEFORE));
    }

    @Test
    public void quoteRefreshRecalculatesSignedSavings()
    {
        security.addPrice(new SecurityPrice(end, 90L * Values.Quote.factor()));
        var model = model(ClientFilter.NO_FILTER, null);
        assertEquals(Long.valueOf(-350_00), value(model, security(model), Metric.LATENT));
        assertEquals(Long.valueOf(18_350_00), value(model, security(model), Metric.NET_VALUE));
        assertEquals(Long.valueOf(-3_300_00), value(model, security(model), Metric.AFTER));
    }

    @Test
    public void taxonomyRoundingReconcilesEveryCent()
    {
        security.addPrice(new SecurityPrice(end, Values.Quote.factorize(120.01)));
        var taxonomy = new Taxonomy("Répartition");
        var root = new Classification("root", "Racine");
        taxonomy.setRootNode(root);
        for (int i = 0; i < 3; i++)
        {
            var child = new Classification(root, "part" + i, "Part " + i);
            root.addChild(child);
            child.addAssignment(new Classification.Assignment(security,
                            i < 2 ? 3333 : Classification.ONE_HUNDRED_PERCENT - 6666));
        }
        var model = model(ClientFilter.NO_FILTER, taxonomy);
        var flat = model(ClientFilter.NO_FILTER, null);
        for (var metric : List.of(Metric.LATENT, Metric.BEFORE, Metric.ACCRUED, Metric.CHANGE, Metric.AFTER))
        {
            long sum = model.getElements().stream().filter(Element::isSecurity)
                            .mapToLong(e -> value(model, e, metric)).sum();
            assertEquals(value(flat, security(flat), metric).longValue(), sum);
            assertEquals(Long.valueOf(sum), value(model, total(model), metric));
        }
    }

    @Test
    public void periodsBeforeValidationAndTradesRemainUnavailable()
    {
        var model = model(ClientFilter.NO_FILTER, null);
        assertNull(model.getTaxValues().value(security(model), Metric.AFTER,
                        Interval.of(start.minusDays(1), end)).amount());
        cto.addTransaction(new PortfolioTransaction(start.plusDays(1).atStartOfDay(), "EUR", 100_00,
                        security, Values.Share.factor(), PortfolioTransaction.Type.DELIVERY_OUTBOUND, 0, 0));
        model = model(ClientFilter.NO_FILTER, null);
        assertNull(value(model, security(model), Metric.AFTER));
        assertTrue(model.getTaxValues().value(security(model), Metric.LATENT, interval).detail().contains("sortante"));
    }
}
