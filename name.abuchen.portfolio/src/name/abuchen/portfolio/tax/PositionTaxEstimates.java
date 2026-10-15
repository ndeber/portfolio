package name.abuchen.portfolio.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Basis;
import name.abuchen.portfolio.tax.TaxEstimateSettings.LossTreatment;
import name.abuchen.portfolio.tax.TaxEstimateSettings.Settings;

/** Read-only valuation. No tax transaction or payment is produced by this class. */
@SuppressWarnings("nls")
public final class PositionTaxEstimates
{
    public enum Status
    {
        READY, NOT_CONFIGURED, BASIS_CHANGED, REVIEW_DUE, CLOSED, UNAVAILABLE
    }

    public record Estimate(long marketValue, long taxableGain, long contribution, long netValue)
    {
    }

    public record Row(Portfolio portfolio, Security security, long shares, LocalDate quoteDate, Long marketValue,
                    Basis basis, Status status, Estimate estimate, String detail)
    {
        public String key()
        {
            return TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID());
        }
    }

    private PositionTaxEstimates()
    {
    }

    public static Estimate calculate(long marketValue, long fiscalBasis, int rateBps, LossTreatment losses)
    {
        Objects.requireNonNull(losses);
        if (marketValue < 0 || fiscalBasis < 0 || rateBps < 0 || rateBps > 10_000)
            throw new IllegalArgumentException("Valeur, base ou taux invalide.");
        long gain = Math.subtractExact(marketValue, fiscalBasis);
        long taxable = losses == LossTreatment.ZERO_FLOOR ? Math.max(0, gain) : gain;
        long tax = BigDecimal.valueOf(taxable).multiply(BigDecimal.valueOf(rateBps)).movePointLeft(4)
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new Estimate(marketValue, gain, tax, Math.subtractExact(marketValue, tax));
    }

    public static long sharesAt(Portfolio portfolio, Security security, LocalDate date)
    {
        return portfolio.getTransactions().stream()
                        .filter(t -> t.getSecurity() == security && !t.getDateTime().toLocalDate().isAfter(date))
                        .mapToLong(t -> t.getType().isPurchase() ? t.getShares() : -t.getShares())
                        .reduce(0, Math::addExact);
    }

    public static List<Row> rows(Client client, Settings settings, CurrencyConverter converter, LocalDate date)
    {
        var result = new ArrayList<Row>();
        for (var portfolio : client.getPortfolios())
        {
            var securities = portfolio.getTransactions().stream().map(Transaction::getSecurity).distinct()
                            .sorted(Comparator.comparing(Security::getName)).toList();
            for (var security : securities)
            {
                long shares = sharesAt(portfolio, security, date);
                var basis = settings.positions().get(TaxEstimateSettings.key(portfolio.getUUID(), security.getUUID()));
                result.add(row(client, portfolio, security, shares, basis, converter, date));
            }
        }
        return List.copyOf(result);
    }

    private static Row row(Client client, Portfolio portfolio, Security security, long shares, Basis basis,
                    CurrencyConverter converter, LocalDate date)
    {
        Long value = null;
        LocalDate quoteDate = null;
        try
        {
            if (shares == 0)
                return new Row(portfolio, security, shares, null, 0L, basis, Status.CLOSED, null,
                                "Position clôturée ; fiscalité de cession à traiter séparément.");
            if (shares < 0 || security.getCurrencyCode() == null)
                throw new IllegalArgumentException("Cette position ne peut pas être estimée.");
            var quote = security.getSecurityPrice(date);
            quoteDate = quote.getDate();
            if (quoteDate.isAfter(date) || quoteDate.equals(LocalDate.MIN)
                            || (security.getPricesIncludingLatest().stream().noneMatch(p -> !p.getDate().isAfter(date))
                                            && !security.hasCapitalFlowsAt(date)))
                throw new IllegalArgumentException("Aucune valorisation disponible à cette date.");
            if (quote.getValue() < 0)
                throw new IllegalArgumentException("Valorisation négative.");
            var amount = BigDecimal.valueOf(shares).multiply(BigDecimal.valueOf(quote.getValue()))
                            .movePointLeft(Values.Share.precision() + Values.Quote.precisionDeltaToMoney());
            if (!"EUR".equals(security.getCurrencyCode()))
            {
                var eur = converter.with("EUR");
                var rate = eur instanceof CurrencyConverterImpl impl
                                ? impl.getRateStrict(date, security.getCurrencyCode())
                                : eur.getRate(date, security.getCurrencyCode());
                if (rate.getTime().equals(LocalDate.MIN) || rate.getTime().isAfter(date)
                                || rate.getValue().signum() <= 0)
                    throw new IllegalArgumentException("Taux de change EUR indisponible.");
                amount = amount.multiply(rate.getValue());
            }
            value = amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
            Status status;
            String detail;
            if (basis == null)
            {
                status = Status.NOT_CONFIGURED;
                detail = "Renseigner une base fiscale et un taux explicite.";
            }
            else if (date.isBefore(LocalDate.parse(basis.validatedOn())) || shares != basis.shares()
                            || !basis.movements().equals(movements(client, portfolio, security, date)))
            {
                status = Status.BASIS_CHANGED;
                detail = "Les opérations ont changé ; revalider la base pour la quantité actuelle.";
            }
            else if (basis.reviewOn() != null && !date.isBefore(LocalDate.parse(basis.reviewOn())))
            {
                status = Status.REVIEW_DUE;
                detail = "Clôture ou révision atteinte ; saisir la base fiscale actualisée.";
            }
            else
            {
                var estimate = calculate(value, basis.amount(), basis.rateBps(), basis.losses());
                return new Row(portfolio, security, shares, quoteDate, value, basis, Status.READY, estimate,
                                estimate.contribution() < 0 ? "Économie potentielle, non garantie." : "Estimation par titre.");
            }
            return new Row(portfolio, security, shares, quoteDate, value, basis, status, null, detail);
        }
        catch (RuntimeException e)
        {
            return new Row(portfolio, security, shares, quoteDate, value, basis, Status.UNAVAILABLE, null,
                            "Calcul indisponible : " + e.getMessage());
        }
    }

    /** Detects movements, including round trips and PE flows, but not quotes or tax payments. */
    public static String movements(Client client, Portfolio portfolio, Security security, LocalDate date)
    {
        var entries = new ArrayList<String>();
        entries.add("currency:" + security.getCurrencyCode());
        portfolio.getTransactions().stream()
                        .filter(t -> t.getSecurity() == security && !t.getDateTime().toLocalDate().isAfter(date))
                        .forEach(t -> entries.add(t.getType().name() + ":" + signature(t)));
        client.getAccounts().forEach(a -> a.getTransactions().stream()
                        .filter(t -> t.getSecurity() == security && t.getType().isCapitalFlow()
                                        && !t.getDateTime().toLocalDate().isAfter(date))
                        .forEach(t -> entries.add(a.getUUID() + ":" + t.getType().name() + ":" + signature(t))));
        entries.sort(String::compareTo);
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(String.join("\n", entries).getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static String signature(Transaction transaction)
    {
        return transaction.getUUID() + ":" + transaction.getDateTime() + ":" + transaction.getShares() + ":"
                        + transaction.getCurrencyCode() + ":" + transaction.getAmount() + ":"
                        + transaction.getUnits().map(u -> u.getType() + ":" + u.getAmount() + ":" + u.getForex() + ":"
                                        + u.getExchangeRate()).sorted().toList();
    }
}
