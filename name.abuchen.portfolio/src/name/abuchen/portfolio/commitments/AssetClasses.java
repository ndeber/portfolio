package name.abuchen.portfolio.commitments;

import java.text.Normalizer;
import java.util.*;
import name.abuchen.portfolio.model.*;

/** Presentation grouping from the asset-class taxonomy, never inferred from quote providers. */
public final class AssetClasses
{
    private static final List<String> ORDER = List.of("PE", "VC", "Dette privée", "Obligations", "Actions", "Immobilier", "Hedge funds", "Matières premières", "Crypto", "Monétaire", "Fonds euros", "Cash", "Autres", "Non classé");
    public record Type(String label, int rank) { }
    private AssetClasses() { }
    private static String normalized(String text)
    { return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    private static String label(String name)
    {
        return switch (normalized(name))
        {
            case "privateequity", "capitalinvestissement" -> "PE";
            case "venturecapital", "capitalrisque" -> "VC";
            case "privatedebt", "detteprivee" -> "Dette privée";
            case "obligations", "obligation" -> "Obligations";
            case "actions", "action" -> "Actions";
            case "immobilier" -> "Immobilier";
            case "hedgefund", "hedgefunds" -> "Hedge funds";
            case "matierespremieres" -> "Matières premières";
            case "crypto", "cryptoactif", "cryptoactifs" -> "Crypto";
            case "monetaire" -> "Monétaire";
            case "fondseuros" -> "Fonds euros";
            case "cash", "liquidites" -> "Cash";
            default -> "Autres";
        };
    }
    public static Type type(Client client, InvestmentVehicle vehicle)
    {
        if (vehicle instanceof Account) return new Type("Cash", ORDER.indexOf("Cash"));
        var matches = client.getTaxonomies().stream().filter(t -> normalized(t.getName()).equals("classesdactifs")).toList();
        if (matches.size() != 1) return new Type("Non classé", ORDER.indexOf("Non classé"));
        var taxonomy = matches.getFirst(); var weights = new HashMap<String, Integer>();
        for (var category : taxonomy.getAllClassifications())
        {
            var top = category; while (top.getParent() != null && top.getParent() != taxonomy.getRoot()) top = top.getParent();
            for (var assignment : category.getAssignments())
                if (assignment.getInvestmentVehicle() == vehicle && assignment.getWeight() > 0)
                    weights.merge(label(top.getName()), assignment.getWeight(), Math::addExact);
        }
        if (weights.isEmpty()) return new Type("Non classé", ORDER.indexOf("Non classé"));
        var dominant = weights.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).findFirst().orElseThrow().getKey();
        return new Type(dominant + (weights.size() > 1 ? " (mixte)" : ""), ORDER.indexOf(dominant));
    }
    public static Comparator<InvestmentVehicle> comparator(Client client)
    {
        return Comparator.comparingInt((InvestmentVehicle v) -> type(client, v).rank()).thenComparing(v -> type(client, v).label())
                        .thenComparing(InvestmentVehicle::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(InvestmentVehicle::getUUID);
    }
}
