package name.abuchen.portfolio.factors;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.InvestmentVehicle;
import name.abuchen.portfolio.model.Taxonomy;

/** Independent currency look-through; listing currencies are never used for funds. */
@SuppressWarnings("nls")
public final class CurrencyExposure
{
    public static final String NAME = "Expositions – Devises";
    public static final String SOURCE = "fork.factor-map.currencies.v1";
    private static final String PREFIX = "factor.fx.";

    private CurrencyExposure() { }

    public static Taxonomy find(Client client)
    {
        var matches = client.getTaxonomies().stream().filter(t -> SOURCE.equals(t.getSource()) || NAME.equals(t.getName())).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Taxonomie devises ambiguë : plusieurs correspondances.");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    /** Idempotent: existing assignments and user edits are preserved. */
    public static Taxonomy ensure(Client client)
    {
        var existing = find(client);
        if (existing != null)
            return existing;
        var taxonomy = new Taxonomy(NAME);
        taxonomy.setSource(SOURCE);
        var root = new Classification(UUID.randomUUID().toString(), NAME);
        root.setNote("Exposition économique après couverture de change, en % du support. "
                        + "Ne pas utiliser la devise de cotation des fonds. Inconnu ≠ EUR. "
                        + "Les coefficients signés ou avec levier sont à saisir dans le paramétrage du support. "
                        + "DBi reste traité séparément dans son onglet.");
        taxonomy.setRootNode(root);
        var classes = new LinkedHashMap<String, Classification>();
        for (String code : new String[] { "EUR", "USD", "JPY", "GBP", "CHF", "CAD", "AUD", "CNY", "HKD", "OTHER", "UNKNOWN", "DBI" })
        {
            String label = switch (code) {
                case "OTHER" -> "Autres devises";
                case "UNKNOWN" -> "À renseigner";
                case "DBI" -> "DBi – traité séparément";
                default -> code;
            };
            var c = new Classification(root, UUID.randomUUID().toString(), label);
            c.setKey(PREFIX + code);
            c.setNote(code.equals("UNKNOWN") ? "Exposition inconnue, exclue des montants connus de la Factor Map."
                            : "Ventiler les supports selon leurs sous-jacents et leur couverture. Documenter la source et sa date dans la note de l'affectation.");
            root.addChild(c);
            classes.put(code, c);
        }
        // No target allocation is implied by this descriptive taxonomy.
        client.getAccounts().forEach(a -> classes.getOrDefault(a.getCurrencyCode(), classes.get("OTHER"))
                        .addAssignment(new Classification.Assignment(a)));
        client.getSecurities().forEach(s -> classes.get(FactorMap.isDbi(s) ? "DBI" : "UNKNOWN")
                        .addAssignment(new Classification.Assignment(s)));
        client.addTaxonomy(taxonomy);
        client.touch();
        return taxonomy;
    }

    /** Only USD/EUR and JPY/EUR are displayed by the current DBi factor map. */
    public static String apply(Taxonomy taxonomy, InvestmentVehicle vehicle, Map<Factor, Double> exposures,
                    Map<Factor, Double> coverage)
    {
        if (taxonomy == null)
            return "";
        double total = 0;
        double known = 0;
        boolean assigned = false;
        boolean invalid = false;
        var provenance = new java.util.LinkedHashSet<String>();
        var fx = new EnumMap<Factor, Double>(Factor.class);
        fx.put(Factor.USD_EUR, 0d);
        fx.put(Factor.JPY_EUR, 0d);
        for (var c : taxonomy.getAllClassifications())
            for (var a : c.getAssignments())
            {
                if (!a.getInvestmentVehicle().equals(vehicle) || a.getWeight() == 0)
                    continue;
                assigned = true;
                Object audit = a.getData(CurrencyProposals.AUDIT);
                if (audit instanceof String json)
                {
                    var evidence = CurrencyProposals.JSON.fromJson(json, CurrencyProposals.Evidence.class);
                    provenance.add((evidence.estimated() ? "ESTIMATION VALIDÉE · " : "Source publiée · ")
                                    + evidence.method() + " · " + evidence.source() + " · "
                                    + (evidence.date() == null ? "date non documentée" : evidence.date()));
                }
                double weight = a.getWeight() / (double) Classification.ONE_HUNDRED_PERCENT;
                invalid |= weight < 0;
                total += weight;
                String code = c.getKey().startsWith(PREFIX) ? c.getKey().substring(PREFIX.length()) : c.getName();
                if (code.equals("EUR") || code.equals("USD") || code.equals("JPY"))
                {
                    known += weight;
                    if (code.equals("USD"))
                        fx.merge(Factor.USD_EUR, weight, Double::sum);
                    if (code.equals("JPY"))
                        fx.merge(Factor.JPY_EUR, weight, Double::sum);
                }
            }
        if (!assigned)
            return "";
        exposures.remove(Factor.USD_EUR);
        exposures.remove(Factor.JPY_EUR);
        coverage.remove(Factor.USD_EUR);
        coverage.remove(Factor.JPY_EUR);
        if (invalid || total > 1.000001)
            return " Affectation devises invalide (poids négatifs ou somme supérieure à 100 %) : axes FX inconnus.";
        if (known > 0)
        {
            exposures.putAll(fx);
            coverage.put(Factor.USD_EUR, known);
            coverage.put(Factor.JPY_EUR, known);
        }
        return " Taxonomie Expositions – Devises ; USD/EUR et JPY/EUR projetés au premier ordre. "
                        + "Les autres devises et la part à renseigner ne sont pas projetées sur ces deux axes. " + String.join(" ; ", provenance);
    }
}
