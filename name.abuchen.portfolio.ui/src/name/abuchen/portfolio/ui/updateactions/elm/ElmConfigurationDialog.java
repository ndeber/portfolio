package name.abuchen.portfolio.ui.updateactions.elm;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.updates.elm.ElmAdjustment;
import name.abuchen.portfolio.updates.elm.ElmAdjustment.Mapping;
import name.abuchen.portfolio.updates.elm.ElmAdjustment.Options;
import name.abuchen.portfolio.updates.elm.ElmAllocation;

public final class ElmConfigurationDialog extends TitleAreaDialog
{
    private record Row(Taxonomy taxonomy, Button enabled, List<Classification> categories, Combo cash, Combo bonds, Combo equities)
    {
    }

    private final Client client;
    private final List<Security> securities;
    private final List<Row> rows = new ArrayList<>();
    private final Options saved;
    private Combo security;
    private Options result;

    public ElmConfigurationDialog(Shell shell, Client client)
    {
        super(shell);
        this.client = client;
        this.securities = client.getSecurities().stream().sorted(java.util.Comparator.comparing(Security::getName)).toList();
        Options settings = null;
        try
        {
            settings = new Gson().fromJson(client.getProperty(ElmAdjustment.SETTINGS), Options.class);
        }
        catch (RuntimeException e)
        {
            // Removed or older settings must never prevent explicit reconfiguration.
        }
        saved = settings;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Ajuster ELM");
        setMessage("Actualiser les affectations d'ELM depuis sa répartition cible publiée.\n"
                        + "Les objectifs des catégories et les autres titres seront conservés. La validation mettra à jour le portefeuille ouvert.");
        var body = new Composite(area, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(12, 12).applyTo(body);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(body);
        label(body, "Titre correspondant à ELM Market Navigator ETF");
        security = new Combo(body, SWT.READ_ONLY);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(security);
        for (var item : securities)
            security.add(item.getName() + " — " + (item.getIsin() == null ? "" : item.getIsin()));
        var candidates = securities.stream().filter(s -> saved != null ? s.getUUID().equals(saved.securityId())
                        : "ELM".equalsIgnoreCase(s.getTickerSymbol())
                                        || s.getName().toLowerCase(Locale.ROOT).contains("elm market navigator")).toList();
        if (candidates.size() == 1)
            security.select(securities.indexOf(candidates.getFirst()));

        label(body, "Taxonomies à ajuster : choisir trois catégories distinctes pour chaque ligne cochée.");
        var scroll = new ScrolledComposite(body, SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, true).hint(830, 210).applyTo(scroll);
        var grid = new Composite(scroll, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(4).margins(6, 6).spacing(10, 8).applyTo(grid);
        for (String header : List.of("Taxonomie", "Monétaire", "Obligations", "Actions"))
            label(grid, header);
        for (var taxonomy : client.getTaxonomies())
        {
            var categories = categories(taxonomy);
            var enabled = new Button(grid, SWT.CHECK);
            enabled.setText(taxonomy.getName());
            var cash = categoryCombo(grid, categories);
            var bonds = categoryCombo(grid, categories);
            var equities = categoryCombo(grid, categories);
            var setting = saved == null ? null : saved.mappings().stream()
                            .filter(m -> m.taxonomyId().equals(taxonomy.getId())).findFirst().orElse(null);
            if (setting != null)
            {
                selectId(cash, categories, setting.cashId());
                selectId(bonds, categories, setting.bondsId());
                selectId(equities, categories, setting.equitiesId());
            }
            else
            {
                selectName(cash, categories, List.of("Monétaire", "Monetaire", "Cash et Monétaire", "Cash et Monetaire", "Cash & Monétaire"));
                selectName(bonds, categories, List.of("Obligations"));
                selectName(equities, categories, List.of("Actions"));
            }
            enabled.setSelection(setting != null || (saved == null && !"Pilotage".equalsIgnoreCase(taxonomy.getName())
                            && cash.getSelectionIndex() > 0 && bonds.getSelectionIndex() > 0 && equities.getSelectionIndex() > 0));
            Runnable update = () -> {
                cash.setEnabled(enabled.getSelection());
                bonds.setEnabled(enabled.getSelection());
                equities.setEnabled(enabled.getSelection());
            };
            enabled.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> update.run()));
            update.run();
            rows.add(new Row(taxonomy, enabled, categories, cash, bonds, equities));
        }
        scroll.setContent(grid);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        scroll.setMinSize(grid.computeSize(SWT.DEFAULT, SWT.DEFAULT));

        return area;
    }

    private static void label(Composite parent, String text)
    {
        new Label(parent, SWT.WRAP).setText(text);
    }

    private static List<Classification> categories(Taxonomy taxonomy)
    {
        return taxonomy.getAllClassifications().stream().filter(c -> c != taxonomy.getRoot()).toList();
    }

    private static Combo categoryCombo(Composite parent, List<Classification> categories)
    {
        var combo = new Combo(parent, SWT.READ_ONLY);
        GridDataFactory.fillDefaults().grab(true, false).hint(190, SWT.DEFAULT).applyTo(combo);
        combo.add("— choisir —");
        categories.forEach(c -> combo.add(ElmAdjustment.path(c)));
        combo.select(0);
        return combo;
    }

    private static void selectId(Combo combo, List<Classification> categories, String id)
    {
        for (int i = 0; i < categories.size(); i++)
            if (categories.get(i).getId().equals(id))
                combo.select(i + 1);
    }

    private static void selectName(Combo combo, List<Classification> categories, List<String> names)
    {
        var matches = categories.stream().filter(c -> names.stream().anyMatch(n -> n.equalsIgnoreCase(c.getName()))).toList();
        if (matches.size() == 1)
            selectId(combo, categories, matches.getFirst().getId());
    }

    private static String selectedId(Combo combo, List<Classification> categories)
    {
        if (combo.getSelectionIndex() <= 0)
            throw new IllegalArgumentException("Choisissez toutes les catégories des lignes cochées.");
        return categories.get(combo.getSelectionIndex() - 1).getId();
    }

    @Override
    protected void okPressed()
    {
        try
        {
            if (security.getSelectionIndex() < 0)
                throw new IllegalArgumentException("Sélectionnez le titre ELM dans votre portefeuille.");
            var mappings = new ArrayList<Mapping>();
            for (var row : rows)
                if (row.enabled().getSelection())
                    mappings.add(new Mapping(row.taxonomy().getId(), selectedId(row.cash(), row.categories()),
                                    selectedId(row.bonds(), row.categories()), selectedId(row.equities(), row.categories())));
            result = new Options(securities.get(security.getSelectionIndex()).getUUID(), mappings);
            ElmAdjustment.prepare(client, result, new ElmAllocation(LocalDate.now(), 0, 0, 10000));
            super.okPressed();
        }
        catch (IllegalArgumentException e)
        {
            setErrorMessage(e.getMessage());
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Récupérer et prévisualiser", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false);
    }

    public Options getOptions()
    {
        return result;
    }
}
