package name.abuchen.portfolio.model;

import static org.junit.Assert.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.Test;

import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.security.LazySecurityPerformanceSnapshot;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class PrivateEquityCurrencyTrailTest
{
    private record FixedConverter(String termCurrency) implements CurrencyConverter
    {
        @Override
        public String getTermCurrency()
        {
            return termCurrency;
        }

        @Override
        public ExchangeRate getRate(LocalDate date, String currency)
        {
            return new ExchangeRate(date, currency.equals(termCurrency) ? BigDecimal.ONE
                            : new BigDecimal(termCurrency.equals("EUR") ? "0.5" : "2"));
        }

        @Override
        public CurrencyConverter with(String currency)
        {
            return new FixedConverter(currency);
        }
    }

    @Test
    public void dollarFlowsOnSingleLot()
    {
        verify("USD", 1);
    }

    @Test
    public void dollarFlowsOnMultipleLots()
    {
        verify("USD", 2);
    }

    @Test
    public void euroFlowsWithRecordedDollarAmountsOnSingleLot()
    {
        verify("EUR", 1);
    }

    @Test
    public void euroFlowsWithRecordedDollarAmountsOnMultipleLots()
    {
        verify("EUR", 2);
    }

    private void verify(String cashCurrency, int lots)
    {
        var start = LocalDate.of(2026, 1, 1);
        var client = new Client();
        client.setBaseCurrency("EUR");
        var fund = new Security("Synthetic dollar fund", "USD");
        client.addSecurity(fund);
        fund.addPrice(new SecurityPrice(start, Values.Quote.factorize(100)));
        var cash = new Account("Cash");
        cash.setCurrencyCode(cashCurrency);
        client.addAccount(cash);
        var portfolio = new Portfolio("Holdings");
        portfolio.setReferenceAccount(cash);
        client.addPortfolio(portfolio);
        for (int i = 0; i < lots; i++)
            portfolio.addTransaction(new PortfolioTransaction(start.atTime(i, 0), "USD", 1000000 / lots,
                            fund, Values.Share.factorize(100) / lots, PortfolioTransaction.Type.DELIVERY_INBOUND, 0, 0));
        for (var type : new AccountTransaction.Type[] { AccountTransaction.Type.CAPITAL_CALL,
                        AccountTransaction.Type.DISTRIBUTION })
        {
            long dollars = type == AccountTransaction.Type.CAPITAL_CALL ? 200000 : 50000;
            long amount = cashCurrency.equals("EUR") ? dollars / 2 : dollars;
            var flow = new AccountTransaction(start.plusDays(type == AccountTransaction.Type.CAPITAL_CALL ? 1 : 2)
                            .atStartOfDay(), cashCurrency, amount, fund, type);
            if (cashCurrency.equals("EUR"))
                flow.addUnit(new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE, Money.of("EUR", amount),
                                Money.of("USD", dollars), new BigDecimal("0.5")));
            cash.addTransaction(flow);
        }
        for (String term : new String[] { "EUR", "USD" })
        {
            var record = LazySecurityPerformanceSnapshot.create(client, new FixedConverter(term),
                            Interval.of(start.minusDays(1), start.plusDays(3))).getRecord(fund).orElseThrow();
            for (var method : CostMethod.values())
                for (var fees : TaxesAndFees.values())
                {
                    assertEquals(Money.of(term, term.equals("EUR") ? 575000 : 1150000), record.getCost(method, fees));
                    assertEquals(Money.of(term, 0), record.getRealizedCapitalGains(method, fees).getCapitalGains());
                    var gains = record.getUnrealizedCapitalGains(method, fees);
                    assertEquals(Money.of(term, 0), gains.getCapitalGains());
                    assertEquals(Money.of(term, 0), gains.getForexCaptialGains());
                    if (method == CostMethod.FIFO)
                        assertEquals(Money.of(term, 0), gains.getCapitalGainsTrail().getValue());
                }
        }
    }
}
