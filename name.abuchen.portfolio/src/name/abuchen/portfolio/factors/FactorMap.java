package name.abuchen.portfolio.factors;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.InvestmentVehicle;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRate;
import name.abuchen.portfolio.snapshot.ClientSnapshot;

/** Read-only factor attribution using actual PP positions and saved look-through classifications. */
@SuppressWarnings("nls")
public final class FactorMap
{
    public static final String DBMF = "US53700T8273";
    public static final String UCITS_HEDGED = "LU3359622902";
    public static final String UCITS_USD = "LU2951555585";
    private static final Set<String> GOLD_ISINS = Set.of("IE00B4ND3602", "JE00B1VS3770", "JE00B588CD74", "IE00B579F325", "DE000A0S9GB0");
    private static final Set<String> EAFE_COUNTRIES = Set.of("France", "Allemagne", "Grande-Bretagne", "Royaume-Uni",
                    "Espagne", "Suisse", "Pays-Bas", "Italie", "Suède", "Irlande", "Norvège", "Autriche", "Danemark",
                    "Israël", "Belgique", "Finlande", "Portugal", "Japon", "Australie", "Hong Kong", "Singapour", "Nouvelle-Zélande");
    private static final Set<String> EM_COUNTRIES = Set.of("Taïwan", "Chine", "Inde", "Indonésie", "Thaïlande", "Malaisie",
                    "Philippines", "Corée du Sud", "Grèce", "Afrique du Sud", "Arabie Saoudite", "Emirats Arabes Unis",
                    "Turquie", "Koweït", "Qatar", "Hongrie", "République Tchèque", "Égypte", "Brésil", "Mexique",
                    "Chili", "Colombie", "Pérou", "Pologne");

    public record Position(InvestmentVehicle vehicle, Long value, LocalDate quoteDate, boolean dbi,
                    Map<Factor, Double> exposures, Map<Factor, Double> coverage, String detail)
    {
    }

    public record Row(Factor factor, Double baseline, double coverage, Double fund, Double overlay, Double total)
    {
        /** A partial subtotal is not a complete total; callers must show coverage alongside it. */
        public Double knownAfter()
        {
            return baseline == null || overlay == null ? null : baseline + overlay;
        }
    }

    public record Result(List<Position> positions, List<Row> rows, long assets, boolean valued, long dbiValue,
                    Double dbiWeight, String scope, List<String> issues)
    {
    }

    private FactorMap()
    {
    }

    public static boolean isDbi(Security security)
    {
        return Set.of(DBMF, UCITS_HEDGED, UCITS_USD).contains(security.getIsin() == null ? "" : security.getIsin())
                        || security.getName().toLowerCase(java.util.Locale.ROOT).matches(".*\\b(dbi|dbmf)\\b.*");
    }

    /** No PP implicit 1:1 fallback is allowed in this analysis. */
    public static CurrencyConverter strict(CurrencyConverter source)
    {
        return new CurrencyConverter()
        {
            @Override
            public String getTermCurrency() { return source.getTermCurrency(); }

            @Override
            public ExchangeRate getRate(LocalDate date, String currency)
            {
                var rate = source instanceof CurrencyConverterImpl impl ? impl.getRateStrict(date, currency)
                                : source.getRate(date, currency);
                if (rate.getTime().isAfter(date) || rate.getTime().equals(LocalDate.MIN) || rate.getValue().signum() <= 0)
                    throw new IllegalArgumentException("Change absent ou futur : " + currency);
                return rate;
            }

            @Override
            public CurrencyConverter with(String currency) { return strict(source.with(currency)); }
        };
    }

    public static Result calculate(Client client, CurrencyConverter converter, LocalDate date, boolean pilotable,
                    boolean proxy, boolean volAdjusted, DbiData.Snapshot snapshot)
    {
        return calculate(client, converter, date, pilotable, proxy, volAdjusted, snapshot, InvescoData.load(client));
    }

    public static Result calculate(Client client, CurrencyConverter converter, LocalDate date, boolean pilotable,
                    boolean proxy, boolean volAdjusted, DbiData.Snapshot snapshot, InvescoData.Snapshot commodities)
    {
        var settings = FactorSettings.load(client);
        var issues = new ArrayList<String>();
        var positions = new ArrayList<Position>();
        var pp = ClientSnapshot.create(client, strict(converter.with("EUR")), date);
        Taxonomy scopeTax = findTaxonomy(client, "Pilotage Global");
        if (pilotable && scopeTax == null)
            throw new IllegalArgumentException("Taxonomie Pilotage Global absente.");
        var scope = pilotable ? weights(scopeTax.getRoot()) : null;
        var assetTax = findTaxonomy(client, "Classes d'actifs");
        var geography = findTaxonomy(client, "Actions - Régions (MSCI)");
        var currencies = CurrencyExposure.find(client);
        long assets = 0;
        long dbiValue = 0;
        boolean valued = true;
        for (var entry : pp.getPositionsByVehicle().entrySet())
        {
            var vehicle = entry.getKey();
            double weight = scope == null ? 1 : scope.getOrDefault(vehicle.getUUID(), 0d);
            if (weight == 0)
                continue;
            if (weight < 0 || weight > 1.000001)
                throw new IllegalArgumentException("Affectation de périmètre invalide : " + vehicle.getName());
            Security security = vehicle instanceof Security s ? s : null;
            boolean dbi = security != null && isDbi(security);
            Long value = null;
            LocalDate quoteDate = null;
            String detail = "";
            try
            {
                if (security != null)
                {
                    var quote = security.getSecurityPrice(date);
                    quoteDate = quote.getDate();
                    if (quoteDate.isAfter(date) || quoteDate.equals(LocalDate.MIN)
                                    || (security.getPricesIncludingLatest().stream().noneMatch(p -> !p.getDate().isAfter(date))
                                                    && !security.hasCapitalFlowsAt(date)))
                        throw new IllegalArgumentException("Cours absent à la date choisie");
                    if (quote.getValue() < 0)
                        throw new IllegalArgumentException("Cours négatif");
                }
                value = Math.round(entry.getValue().getValuation().getAmount() * weight);
                assets = Math.addExact(assets, value);
                if (dbi)
                    dbiValue = Math.addExact(dbiValue, value);
            }
            catch (RuntimeException e)
            {
                valued = false;
                detail = e.getMessage();
                issues.add(vehicle.getName() + " : " + detail);
            }
            var mapping = settings.mappings().get(vehicle.getUUID());
            Map<Factor, Double> exposures;
            var coverage = new EnumMap<Factor, Double>(Factor.class);
            if (dbi)
            {
                exposures = Map.of();
                detail += " DBi exclu de la baseline ; " + security.getIsin();
            }
            else if (mapping != null && !LocalDate.parse(mapping.date()).isAfter(date))
            {
                exposures = mapping.notional();
                detail += " Hypothèses explicites au " + mapping.date() + " · " + mapping.source();
            }
            else
            {
                exposures = automatic(vehicle, assetTax, geography);
                detail += " Taxonomies du fichier actuel ; actions par géographie, sans estimation de bêta. "
                                + "Devises des fonds et sensibilités obligataires non déduites du libellé ou de l'échéance.";
                if (mapping != null)
                    detail += " Paramétrage futur ignoré.";
                if (security != null && geography != null)
                {
                    var partial = new EnumMap<Factor, Double>(Factor.class);
                    partial.putAll(exposures);
                    partialGeography(vehicle, assetTax, geography, partial, coverage);
                    exposures = Map.copyOf(partial);
                    if (!coverage.isEmpty())
                        detail += " Ventilation géographique partielle : montants connus seulement, sans renormalisation.";
                }
                var enriched = new EnumMap<Factor, Double>(Factor.class);
                enriched.putAll(exposures);
                detail += CurrencyExposure.apply(currencies, vehicle, enriched, coverage);
                if (security != null && InvescoData.ISIN.equals(security.getIsin()) && commodities != null
                                && !LocalDate.parse(commodities.date()).isAfter(date))
                {
                    enriched.put(Factor.GOLD, commodities.exposure(Factor.GOLD, date));
                    enriched.put(Factor.OIL, commodities.exposure(Factor.OIL, date));
                    for (var f : List.of(Factor.US_EQUITY, Factor.EAFE, Factor.EMERGING))
                        enriched.put(f, 0d);
                    detail += " Composition de l'indice Bloomberg publiée par Invesco au " + commodities.date()
                                    + " ; approximation de l'exposition de l'ETF, WTI seul sur l'axe pétrole ; Brent et autres matières visibles dans l'onglet Invesco. "
                                    + commodities.source();
                }
                exposures = Map.copyOf(enriched);
            }
            for (var factor : exposures.keySet())
                coverage.putIfAbsent(factor, 1d);
            positions.add(new Position(vehicle, value, quoteDate, dbi, exposures, Map.copyOf(coverage), detail.strip()));
        }
        positions.sort(java.util.Comparator.comparing(p -> p.vehicle().getName()));
        boolean valid = valued && assets > 0;
        var rows = new ArrayList<Row>();
        double baselineAbs = positions.stream().filter(p -> !p.dbi() && p.value() != null)
                        .mapToDouble(p -> Math.abs((double) p.value())).sum();
        for (var factor : volAdjusted ? Factor.publishedAxes() : Factor.portfolioAxes())
        {
            double sum = 0;
            double covered = 0;
            for (var position : positions)
            {
                if (position.dbi() || position.value() == null)
                    continue;
                Double exposure = position.exposures().get(factor);
                if (exposure != null)
                {
                    sum += position.value() * exposure;
                    covered += Math.abs((double) position.value()) * position.coverage().getOrDefault(factor, 0d);
                }
            }
            double coverage = baselineAbs == 0 ? 1 : covered / baselineAbs;
            // The published VA series has no documented calibration for our baseline.
            Double baseline = !volAdjusted && valid && (covered > 0 || baselineAbs == 0) ? sum / assets : null;
            double overlay = 0;
            boolean overlayKnown = valid;
            Double fund = snapshot == null ? null : snapshot.exposure(factor, volAdjusted, date);
            for (var position : positions)
            {
                if (!position.dbi() || position.value() == null || position.value() == 0)
                    continue;
                String isin = ((Security) position.vehicle()).getIsin();
                boolean exact = snapshot != null && snapshot.isin().equals(isin);
                boolean supportedProxy = proxy && (UCITS_HEDGED.equals(isin) || UCITS_USD.equals(isin));
                if (fund == null || !(exact || supportedProxy))
                    overlayKnown = false;
                else
                    overlay += (double) position.value() / assets * fund;
            }
            Double contribution = overlayKnown ? overlay : null;
            Double total = baseline != null && coverage >= .999999 && contribution != null ? baseline + contribution : null;
            rows.add(new Row(factor, baseline, volAdjusted ? 0 : coverage, fund, contribution, total));
        }
        if (!valid)
            issues.add("Poids indisponibles : valorisation incomplète ou patrimoine net non positif.");
        if (proxy)
            issues.add("SIMULATION : positions DBMF US appliquées au poids UCITS ; couverture EUR et différences de stratégie exclues.");
        if (volAdjusted)
            issues.add("VA publié × poids DBi actuel ; baseline VA non calibrée, total et contribution au risque non calculables.");
        if (positions.stream().anyMatch(p -> p.detail().contains("ESTIMATION VALIDÉE")))
            issues.add("Devises : la baseline inclut des estimations validées dans l'aperçu. La couverture inclut ces hypothèses ; détail et sources dans les positions.");
        return new Result(List.copyOf(positions), List.copyOf(rows), assets, valid, dbiValue,
                        valid ? (double) dbiValue / assets : null, pilotable ? "Pilotage Global" : "Portefeuille complet", List.copyOf(issues));
    }

    static Map<Factor, Double> automatic(InvestmentVehicle vehicle, Taxonomy assets, Taxonomy geography)
    {
        var result = new EnumMap<Factor, Double>(Factor.class);
        if (vehicle instanceof Account)
        {
            for (var f : Factor.portfolioAxes())
                if (f != Factor.USD_EUR && f != Factor.JPY_EUR)
                    result.put(f, 0d);
            switch (vehicle.getCurrencyCode())
            {
                case "EUR" -> { result.put(Factor.USD_EUR, 0d); result.put(Factor.JPY_EUR, 0d); }
                case "USD" -> { result.put(Factor.USD_EUR, 1d); result.put(Factor.JPY_EUR, 0d); }
                case "JPY" -> { result.put(Factor.USD_EUR, 0d); result.put(Factor.JPY_EUR, 1d); }
                default -> { /* another FX axis, unknown here */ }
            }
            return Map.copyOf(result);
        }
        var security = (Security) vehicle;
        boolean gold = GOLD_ISINS.contains(security.getIsin() == null ? "" : security.getIsin())
                        || ("Gold 1 oz".equals(security.getName()) && "USD".equals(security.getCurrencyCode()));
        if (gold)
        {
            for (var f : Factor.portfolioAxes())
                if (f != Factor.USD_EUR && f != Factor.JPY_EUR)
                    result.put(f, f == Factor.GOLD ? 1d : 0d);
            return Map.copyOf(result);
        }
        double equity = classWeight(assets, "Actions", vehicle);
        double bonds = classWeight(assets, "Obligations", vehicle);
        if (equity + bonds > 1.000001)
            return Map.of();
        if (equity + bonds >= .999999)
        {
            result.put(Factor.GOLD, 0d);
            result.put(Factor.OIL, 0d);
        }
        if (bonds >= .999999)
        {
            result.put(Factor.US_EQUITY, 0d);
            result.put(Factor.EAFE, 0d);
            result.put(Factor.EMERGING, 0d);
        }
        if (equity >= .999999)
        {
            for (var f : Factor.portfolioAxes())
                if (f.rates())
                    result.put(f, 0d);
        }
        if (equity > 0 && geography != null)
        {
            var geo = new EnumMap<Factor, Double>(Factor.class);
            double classified = 0;
            boolean ambiguous = false;
            for (var classification : geography.getAllClassifications())
            {
                double weight = classification.getAssignments().stream().filter(a -> a.getInvestmentVehicle().equals(vehicle))
                                .mapToDouble(a -> a.getWeight() / (double) Classification.ONE_HUNDRED_PERCENT).sum();
                if (weight == 0)
                    continue;
                if (weight < 0)
                    ambiguous = true;
                classified += weight;
                String name = classification.getName();
                Factor factor = name.equals("États-Unis") ? Factor.US_EQUITY
                                : EAFE_COUNTRIES.contains(name) || name.equals("EAFE") ? Factor.EAFE
                                                : EM_COUNTRIES.contains(name) || name.equals("Marchés émergents") ? Factor.EMERGING : null;
                if (factor != null)
                    geo.merge(factor, weight, Double::sum);
                else if (!name.equals("Canada"))
                    ambiguous = true;
            }
            // Broad/Other regions cannot silently become zero US/EAFE/EM exposure.
            if (!ambiguous && classified >= .999999 && classified <= 1.000001 && equity + bonds >= .999999)
                for (var f : List.of(Factor.US_EQUITY, Factor.EAFE, Factor.EMERGING))
                    result.put(f, equity * geo.getOrDefault(f, 0d));
        }
        return Map.copyOf(result);
    }

    private static void partialGeography(InvestmentVehicle vehicle, Taxonomy assets, Taxonomy geography,
                    Map<Factor, Double> exposures, Map<Factor, Double> coverage)
    {
        if (exposures.containsKey(Factor.US_EQUITY))
            return;
        double equity = classWeight(assets, "Actions", vehicle);
        double bonds = classWeight(assets, "Obligations", vehicle);
        if (equity <= 0 || equity + bonds > 1.000001)
            return;
        var amounts = new EnumMap<Factor, Double>(Factor.class);
        double known = 0;
        double all = 0;
        for (var c : geography.getAllClassifications())
        {
            double weight = c.getAssignments().stream().filter(a -> a.getInvestmentVehicle().equals(vehicle))
                            .mapToDouble(a -> a.getWeight() / (double) Classification.ONE_HUNDRED_PERCENT).sum();
            if (weight < 0)
                return;
            all += weight;
            String name = c.getName();
            Factor factor = name.equals("États-Unis") ? Factor.US_EQUITY
                            : EAFE_COUNTRIES.contains(name) || name.equals("EAFE") ? Factor.EAFE
                                            : EM_COUNTRIES.contains(name) || name.equals("Marchés émergents") ? Factor.EMERGING : null;
            if (factor != null)
            {
                amounts.merge(factor, equity * weight, Double::sum);
                known += weight;
            }
            else if (name.equals("Canada"))
                known += weight;
        }
        if (all > 1.000001 || known <= 0)
            return;
        for (var f : List.of(Factor.US_EQUITY, Factor.EAFE, Factor.EMERGING))
        {
            exposures.put(f, amounts.getOrDefault(f, 0d));
            coverage.put(f, Math.min(1, equity * known + bonds));
        }
    }

    private static Taxonomy findTaxonomy(Client client, String name)
    {
        return client.getTaxonomies().stream().filter(t -> name.equals(t.getName())).findFirst().orElse(null);
    }

    private static double classWeight(Taxonomy taxonomy, String label, InvestmentVehicle vehicle)
    {
        if (taxonomy == null)
            return 0;
        return taxonomy.getAllClassifications().stream().filter(c -> label.equals(c.getName()))
                        .mapToDouble(c -> weights(c).getOrDefault(vehicle.getUUID(), 0d)).sum();
    }

    private static Map<String, Double> weights(Classification root)
    {
        var result = new HashMap<String, Double>();
        collect(root, result);
        return result;
    }

    private static void collect(Classification root, Map<String, Double> result)
    {
        root.getAssignments().forEach(a -> result.merge(a.getInvestmentVehicle().getUUID(),
                        a.getWeight() / (double) Classification.ONE_HUNDRED_PERCENT, Double::sum));
        root.getChildren().forEach(c -> collect(c, result));
    }
}
