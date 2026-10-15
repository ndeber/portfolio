package name.abuchen.portfolio.supportcosts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AttributeType;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.snapshot.ClientSnapshot;
import name.abuchen.portfolio.tax.TaxScope;
import name.abuchen.portfolio.util.Interval;

/** Disclosure estimate only: never creates transactions or changes NAV/performance. */
@SuppressWarnings("nls")
public final class SupportCosts
{
    public static final String SOURCE = "xapa.supportcost.source";
    public static final String CHECKED = "xapa.supportcost.checked";
    public static final String EXTRA = "xapa.supportcost.extra";
    public static final String NOTE = "xapa.supportcost.note";

    public record Row(Security security, Double annualRate, long estimatedCost, int days, String source, String note) {}
    public record Result(Interval period, long estimatedCost, Double annualRate, Double periodRate,
                    Double scopeAnnualRate, double coverage, long averageAssets, List<Row> rows,
                    int unavailableValuations)
    {
        public boolean complete() { return coverage >= 1 - 1e-10 && unavailableValuations == 0; }
    }

    private static final class Accumulator
    {
        Security security;
        Double rate;
        double cost;
        int days;
        int unavailable;
    }

    private SupportCosts() {}

    public static AttributeType ter(Client client)
    {
        return client.getSettings().getAttributeTypes()
                        .filter(a -> "ter".equals(a.getId()) && a.getTarget() == Security.class)
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Attribut TER absent."));
    }

    public static Double annualRate(Client client, Security security)
    {
        var value = security.getAttributes().get(ter(client));
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("TER invalide : " + security.getName());
        double rate = number.doubleValue();
        var extra = security.getAttributes().getMap().get(EXTRA);
        if (extra != null)
        {
            if (!(extra instanceof Number n)) throw new IllegalArgumentException("Complément de frais invalide.");
            validate(n.doubleValue());
            rate += n.doubleValue();
        }
        validate(number.doubleValue());
        validate(rate);
        return rate;
    }

    private static void validate(double rate)
    {
        if (!Double.isFinite(rate) || rate < 0 || rate > 1)
            throw new IllegalArgumentException("Les frais doivent être compris entre 0 et 100 % par an.");
    }

    public static double dailyCost(double value, double annualRate, LocalDate date)
    {
        validate(annualRate);
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Encours invalide.");
        return value * annualRate / date.lengthOfYear();
    }

    /** Current calendar periods include only days elapsed at the calculation date. */
    public static Interval effectivePeriod(Interval requested, LocalDate asOf)
    {
        var end = requested.getEnd().isAfter(asOf) ? asOf : requested.getEnd();
        if (!end.isAfter(requested.getStart()))
            throw new IllegalArgumentException("La période choisie ne contient aucun jour écoulé.");
        var period = Interval.of(requested.getStart(), end);
        if (period.getDays() > 36600)
            throw new IllegalArgumentException("Choisir une période de moins de 100 ans.");
        return period;
    }

    public static Result calculate(Client client, TaxScope scope, CurrencyConverter converter, Interval requested)
    {
        var period = effectivePeriod(requested, LocalDate.now());
        // Filtering once preserves PP's native transaction and classification handling.
        var selected = scope.filter().filter(client);
        var strict = strict(converter.with("EUR"));
        var original = new HashMap<String, Security>();
        client.getSecurities().forEach(s -> original.put(s.getUUID(), s));
        var rows = new HashMap<String, Accumulator>();
        double totalExposure = 0, coveredExposure = 0, coveredAnnualExposure = 0;
        double scopeAnnualExposure = 0, scopeExposure = 0, cost = 0;
        int unavailable = 0;
        for (LocalDate day = period.getStart().plusDays(1); !day.isAfter(period.getEnd()); day = day.plusDays(1))
        {
            var positions = ClientSnapshot.create(selected, strict, day).getPositionsByVehicle();
            for (var entry : positions.entrySet())
            {
                var vehicle = entry.getKey();
                var position = entry.getValue().getPosition();
                if (vehicle instanceof Account)
                {
                    long cash = strict.convert(day, position.calculateValue()).getAmount();
                    // Available cash has zero embedded support costs. Borrowing is not a support.
                    if (cash > 0) { scopeExposure += cash; scopeAnnualExposure += cash / (double) day.lengthOfYear(); }
                    continue;
                }
                if (!(vehicle instanceof Security filtered)) continue;
                var security = original.get(filtered.getUUID());
                if (security == null) throw new IllegalArgumentException("Support inconnu.");
                var row = rows.computeIfAbsent(security.getUUID(), k -> {
                    var a = new Accumulator(); a.security = security; a.rate = annualRate(client, security); return a;
                });
                var quote = filtered.getSecurityPrice(day);
                if (quote.getDate().equals(LocalDate.MIN) || quote.getDate().isAfter(day))
                { unavailable++; row.days++; row.unavailable++; continue; }
                var fx = strict.getRate(day, filtered.getCurrencyCode());
                if (fx.getTime().isAfter(day)) throw new IllegalArgumentException("Cours de change futur.");
                long value = strict.convert(day, position.calculateValue()).getAmount();
                if (value < 0) { unavailable++; row.days++; row.unavailable++; continue; }
                if (value == 0) continue;
                row.days++;
                totalExposure += value; scopeExposure += value;
                scopeAnnualExposure += value / (double) day.lengthOfYear();
                if (row.rate != null)
                {
                    double fee = dailyCost(value, row.rate, day);
                    row.cost += fee; cost += fee;
                    coveredExposure += value;
                    coveredAnnualExposure += value / (double) day.lengthOfYear();
                }
            }
        }
        var details = new ArrayList<Row>();
        rows.values().stream().filter(a -> a.days > 0).sorted(Comparator.comparing(a -> a.security.getName()))
                        .forEach(a -> details.add(new Row(a.security, a.rate, Math.round(a.cost), a.days,
                                        text(a.security, SOURCE), text(a.security, NOTE)
                                                        + (a.unavailable == 0 ? "" : " — " + a.unavailable
                                                                        + " journée(s) exclue(s) : valorisation indisponible ou position négative."))));
        double coverage = totalExposure == 0 ? 1 : coveredExposure / totalExposure;
        boolean complete = coverage >= 1 - 1e-10 && unavailable == 0;
        return new Result(period, Math.round(cost), coveredAnnualExposure == 0 ? null : cost / coveredAnnualExposure,
                        coveredExposure == 0 ? null : cost / (coveredExposure / period.getDays()),
                        !complete || scopeAnnualExposure == 0 ? null : cost / scopeAnnualExposure,
                        coverage, Math.round(scopeExposure / period.getDays()), List.copyOf(details), unavailable);
    }

    public static String text(Security security, String key)
    {
        var value = security.getAttributes().getMap().get(key);
        return value == null ? "" : value.toString();
    }

    private static CurrencyConverter strict(CurrencyConverter converter)
    {
        return new CurrencyConverter()
        {
            @Override public String getTermCurrency() { return converter.getTermCurrency(); }
            @Override public ExchangeRate getRate(LocalDate date, String currency)
            {
                var rate = converter instanceof CurrencyConverterImpl impl ? impl.getRateStrict(date, currency)
                                : converter.getRate(date, currency);
                if (!currency.equals(getTermCurrency()) && (rate.getTime().equals(LocalDate.MIN)
                                || rate.getTime().isAfter(date) || rate.getValue().signum() <= 0))
                    throw new IllegalArgumentException("Change indisponible : " + currency + " → " + getTermCurrency() + " au " + date);
                return rate;
            }
            @Override public CurrencyConverter with(String currency) { return strict(converter.with(currency)); }
        };
    }

    public static void installAttributes(Client client)
    {
        ter(client).setColumnLabel("Frais courants (TER)");
        attribute(client, SOURCE, "Frais VL — source", String.class, AttributeType.StringConverter.class);
        attribute(client, CHECKED, "Frais VL — vérifié le", String.class, AttributeType.StringConverter.class);
        attribute(client, NOTE, "Frais VL — périmètre / limites", String.class, AttributeType.StringConverter.class);
        attribute(client, EXTRA, "Frais VL — complément annuel", Double.class, AttributeType.PercentConverter.class);
    }

    private static void attribute(Client client, String id, String name, Class<?> type,
                    Class<? extends AttributeType.Converter> converter)
    {
        if (client.getSettings().getAttributeTypes().anyMatch(a -> id.equals(a.getId()))) return;
        var a = new AttributeType(id); a.setName(name); a.setColumnLabel(name); a.setTarget(Security.class);
        a.setType(type); a.setConverter(converter); client.getSettings().addAttributeType(a);
    }
}
