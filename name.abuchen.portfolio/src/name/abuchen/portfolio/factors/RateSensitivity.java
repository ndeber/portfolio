package name.abuchen.portfolio.factors;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.updates.bonds.BondMath;
import name.abuchen.portfolio.updates.bonds.BondSpec;
import name.abuchen.portfolio.updates.bonds.BondUpdate;

/** First-order rate exposure. No notional or VA coefficient is interpreted as a duration. */
@SuppressWarnings("nls")
public final class RateSensitivity
{
    public enum Family
    {
        COMMON("Taux communs · référence US"), DE_US("Écart Allemagne–US"), FR_DE("Écart France–Allemagne"),
        REAL("Taux réels EUR · séparés"), MONEY("Monétaire EUR · séparé");
        private final String label;
        Family(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Row(Family family, int segment, Double baseline, Double overlay, double coverage, boolean overlayComplete)
    {
        public String label()
        {
            return segment < 0 ? family.label() : "    " + List.of("Courts", "Intermédiaires", "Longs").get(segment);
        }
        public Double after() { return baseline == null || overlay == null || !overlayComplete ? null : baseline + overlay; }
    }

    public record Detail(FactorMap.Position position, RateSettings.Mapping mapping, Double dv01, boolean included, String status) { }
    public record Result(List<Row> rows, List<Detail> details, long consideredValue, long documentedValue, boolean valued) { }
    public record Bond(LocalDate maturity, double coupon, LocalDate interestStart, LocalDate firstCoupon) { }
    private record Flow(double years, double amount) { }

    private static final Map<String, Bond> BUNDS = Map.of(
                    "DE0001102622", new Bond(LocalDate.of(2029, 11, 15), .021, LocalDate.of(2022, 10, 20), LocalDate.of(2023, 11, 15)),
                    "DE000BU27014", new Bond(LocalDate.of(2032, 11, 15), .025, LocalDate.of(2025, 8, 29), LocalDate.of(2026, 11, 15)),
                    "DE000BU2Z072", new Bond(LocalDate.of(2036, 8, 15), .03, LocalDate.of(2026, 7, 10), LocalDate.of(2027, 8, 15)));

    private RateSensitivity() { }

    /** Yield-based duration with annual ACT/ACT coupons, including the first long coupon of new Bunds.
     * Each discounted cash-flow derivative is projected linearly onto 2/10/30-year anchors.
     * This is an indicative decomposition, not market-curve key-rate duration. */
    public static double[] durations(Bond bond, LocalDate settlement, double dirty)
    {
        if (!settlement.isBefore(bond.maturity()) || !Double.isFinite(dirty) || dirty <= 0
                        || !Double.isFinite(bond.coupon()) || bond.coupon() < 0)
            throw new IllegalArgumentException("Prix ou conventions obligataires invalides.");
        LocalDate next = bond.maturity();
        while (next.minusYears(1).isAfter(settlement)) next = next.minusYears(1);
        double fraction = ChronoUnit.DAYS.between(settlement, next)
                        / (double) ChronoUnit.DAYS.between(next.minusYears(1), next);
        var flows = new ArrayList<Flow>();
        for (LocalDate pay = next; !pay.isAfter(bond.maturity()); pay = pay.plusYears(1))
        {
            double coupon = 100 * bond.coupon();
            if (pay.equals(bond.firstCoupon()) && bond.interestStart() != null)
            {
                var regularStart = pay.minusYears(1);
                coupon *= 1 + ChronoUnit.DAYS.between(bond.interestStart(), regularStart)
                                / (double) ChronoUnit.DAYS.between(regularStart.minusYears(1), regularStart);
            }
            flows.add(new Flow(fraction + flows.size(), coupon + (pay.equals(bond.maturity()) ? 100 : 0)));
        }
        double low = -.99, high = 10;
        if (price(flows, low) < dirty || price(flows, high) > dirty)
            throw new IllegalArgumentException("Rendement obligataire non résolu.");
        for (int i = 0; i < 160; i++)
        {
            double mid = (low + high) / 2;
            if (price(flows, mid) > dirty) low = mid; else high = mid;
        }
        double yield = (low + high) / 2;
        double[] result = new double[3];
        for (var flow : flows)
        {
            double duration = flow.years() * flow.amount() / Math.pow(1 + yield, flow.years() + 1) / dirty;
            double[] weights = weights(flow.years());
            for (int i = 0; i < 3; i++) result[i] += duration * weights[i];
        }
        return result;
    }

    private static double price(List<Flow> flows, double yield)
    {
        return flows.stream().mapToDouble(f -> f.amount() / Math.pow(1 + yield, f.years())).sum();
    }

    private static double[] weights(double years)
    {
        if (years <= 2) return new double[] { 1, 0, 0 };
        if (years < 10) return new double[] { (10 - years) / 8, (years - 2) / 8, 0 };
        if (years < 30) return new double[] { 0, (30 - years) / 20, (years - 10) / 20 };
        return new double[] { 0, 0, 1 };
    }

    /** Positive sensitivity means a loss when the named yield/spread rises, all other factors held fixed. */
    public static Double exposure(RateSettings.Mapping mapping, Family family, int segment)
    {
        if (mapping == null) return null;
        var curve = mapping.curve();
        boolean unknownSpread = curve == RateSettings.Curve.COMMON_ONLY && (family == Family.DE_US || family == Family.FR_DE);
        if (unknownSpread) return null;
        boolean relevant = switch (family)
        {
            case COMMON -> List.of(RateSettings.Curve.US, RateSettings.Curve.DE, RateSettings.Curve.FR, RateSettings.Curve.COMMON_ONLY).contains(curve);
            case DE_US -> curve == RateSettings.Curve.DE || curve == RateSettings.Curve.FR;
            case FR_DE -> curve == RateSettings.Curve.FR;
            case REAL -> curve == RateSettings.Curve.REAL_EUR;
            case MONEY -> curve == RateSettings.Curve.MONEY_EUR;
        };
        if (!relevant) return 0d;
        var values = mapping.durations();
        if (segment >= 0) return values[segment];
        return Arrays.stream(values).anyMatch(v -> v == null) ? null : Arrays.stream(values).mapToDouble(Double::doubleValue).sum();
    }

    public static Result calculate(Client client, FactorMap.Result portfolio, LocalDate date, RateSettings.Mapping ucits)
    {
        var settings = RateSettings.load(client);
        var details = new ArrayList<Detail>();
        long considered = 0, documented = 0;
        for (var position : portfolio.positions())
        {
            var mapping = settings.mappings().get(position.vehicle().getUUID());
            boolean future = mapping != null && LocalDate.parse(mapping.date()).isAfter(date);
            if (future) mapping = null;
            boolean included = true;
            String status = future ? "Paramétrage futur ignoré. " : "";
            if (mapping != null) status += "Duration explicite · " + mapping.source();
            else if (position.dbi())
            {
                if (FactorMap.UCITS_HEDGED.equals(((Security) position.vehicle()).getIsin()) && ucits != null
                                && !LocalDate.parse(ucits.date()).isAfter(date))
                {
                    mapping = ucits;
                    status += "Duration UCITS publiée (arrondie), appliquée à la valeur PP actuelle. Géographie non publiée : écarts inconnus. Collatéral et couverture de change exclus. " + ucits.source();
                }
                else status += "DV01 / duration non documenté pour ce fonds. Ni notionnel ni VA converti automatiquement.";
            }
            else if (position.vehicle() instanceof Security security && (BUNDS.containsKey(java.util.Objects.toString(security.getIsin(), ""))
                            || BondSpec.find(security.getIsin()).filter(BondSpec::indexed).isPresent()))
            {
                boolean real = !BUNDS.containsKey(java.util.Objects.toString(security.getIsin(), ""));
                var curve = real ? RateSettings.Curve.REAL_EUR : RateSettings.Curve.DE;
                try
                {
                    mapping = bondMapping(security, date);
                    status += "Duration calculée depuis les flux et le cours dirty daté ; ventilation indicative 2/10/30 ans. " + mapping.source();
                    if (real) status += " Taux réels seulement : indexation inflation et spread France non décomposés.";
                }
                catch (IllegalArgumentException e)
                {
                    mapping = new RateSettings.Mapping(date.toString(), "Instrument identifié ; sensibilité à documenter", curve, null, null, null);
                    status += "Sensibilité inconnue : " + e.getMessage();
                }
            }
            else if (position.vehicle() instanceof Account || directRateFree(client, position))
            {
                included = false;
                mapping = new RateSettings.Mapping(date.toString(), "Sans position obligataire directe identifiée", RateSettings.Curve.NONE, 0d, 0d, 0d);
                status += "Hors périmètre obligataire direct ; effets indirects des taux sur actions / actifs réels non modélisés.";
            }
            else
            {
                String name = position.vehicle().getName().toLowerCase(Locale.ROOT);
                if (name.contains("liq") && (name.contains("eur") || name.contains("short")))
                {
                    mapping = new RateSettings.Mapping(date.toString(), "Monétaire identifié par le libellé ; duration non renseignée",
                                    RateSettings.Curve.MONEY_EUR, null, null, null);
                    status += "Monétaire EUR à documenter ; aucune assimilation aux Treasuries courts.";
                }
                else status += "À documenter : fonds obligataire ou support non classé. Les anciens coefficients notionnels sont ignorés ici.";
            }
            if (mapping != null && mapping.curve() == RateSettings.Curve.NONE) included = false;
            Double duration = mapping == null || Arrays.stream(mapping.durations()).anyMatch(v -> v == null) ? null
                            : Arrays.stream(mapping.durations()).mapToDouble(Double::doubleValue).sum();
            Double dv01 = duration == null || position.value() == null ? null : position.value() / 100d * duration * .0001;
            if (included && !position.dbi() && position.value() != null)
            {
                considered += Math.abs(position.value());
                if (dv01 != null) documented += Math.abs(position.value());
            }
            details.add(new Detail(position, mapping, dv01, included, status));
        }
        var rows = new ArrayList<Row>();
        for (var family : Family.values())
            for (int segment = -1; segment < (family == Family.MONEY ? 0 : 3); segment++)
            {
                double base = 0, overlay = 0, covered = 0;
                boolean overlayComplete = true, missingValue = false;
                for (var detail : details)
                {
                    if (!detail.included()) continue;
                    var p = detail.position();
                    if (p.value() == null) { missingValue = true; if (p.dbi()) overlayComplete = false; continue; }
                    if (p.value() == 0) continue;
                    Double duration = exposure(detail.mapping(), family, segment);
                    if (p.dbi())
                    {
                        if (duration == null) overlayComplete = false;
                        else overlay += p.value() / 100d * duration * .0001;
                    }
                    else if (duration != null)
                    {
                        covered += Math.abs(p.value());
                        base += p.value() / 100d * duration * .0001;
                    }
                }
                double coverage = considered == 0 ? 1 : covered / considered;
                if (missingValue || !portfolio.valued()) coverage = 0;
                rows.add(new Row(family, segment, covered > 0 || considered == 0 ? base : null,
                                overlayComplete ? overlay : null, coverage, overlayComplete));
            }
        return new Result(List.copyOf(rows), List.copyOf(details), considered, documented, portfolio.valued());
    }

    private static boolean directRateFree(Client client, FactorMap.Position position)
    {
        if (!(position.vehicle() instanceof Security security)) return false;
        var taxonomy = client.getTaxonomies().stream().filter(t -> "Classes d'actifs".equals(t.getName())).findFirst().orElse(null);
        // Use taxonomy-based evidence, never the user-entered US rate coefficients (e.g. monetary != Treasury).
        var automatic = FactorMap.automatic(security, taxonomy, null);
        return automatic.get(Factor.US_2Y) != null && automatic.get(Factor.US_2Y) == 0
                        && automatic.get(Factor.US_10Y) != null && automatic.get(Factor.US_10Y) == 0
                        && automatic.get(Factor.US_LONG) != null && automatic.get(Factor.US_LONG) == 0;
    }

    private static RateSettings.Mapping bondMapping(Security security, LocalDate date)
    {
        if (!"EUR".equals(security.getCurrencyCode()) || !BondUpdate.managed(security))
            throw new IllegalArgumentException("Convention dirty PP non confirmée ; utiliser l'outil de cours obligataires.");
        var quote = security.getSecurityPrice(date);
        if (quote.getDate().equals(LocalDate.MIN) || quote.getDate().isAfter(date))
            throw new IllegalArgumentException("Cours daté absent.");
        Bond bond = BUNDS.get(security.getIsin());
        double price = quote.getValue() / (double) Values.Quote.factor();
        boolean real = bond == null;
        if (real)
        {
            var spec = BondSpec.find(security.getIsin()).orElseThrow();
            bond = new Bond(spec.maturity(), spec.coupon().doubleValue(), null, null);
            String note = security.getNote();
            var coefficient = Pattern.compile("Coefficient AFT : ([0-9.]+)").matcher(note);
            if (!note.contains("Date de cotation : " + quote.getDate()) || !coefficient.find())
                throw new IllegalArgumentException("Coefficient d'indexation AFT absent à la date du cours.");
            double index = Double.parseDouble(coefficient.group(1));
            if (!Double.isFinite(index) || index <= 0) throw new IllegalArgumentException("Coefficient AFT invalide.");
            price /= index;
        }
        if (!date.isBefore(bond.maturity())) throw new IllegalArgumentException("Obligation échue : position à vérifier.");
        var duration = durations(bond, BondMath.settlement(quote.getDate()), price);
        return new RateSettings.Mapping(quote.getDate().toString(), real ? "https://www.aft.gouv.fr/fr/oati-presentation"
                        : "https://www.deutsche-finanzagentur.de/bundeswertpapiere/factsheet/isin/" + security.getIsin(),
                        real ? RateSettings.Curve.REAL_EUR : RateSettings.Curve.DE, duration[0], duration[1], duration[2]);
    }
}
