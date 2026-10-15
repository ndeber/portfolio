package name.abuchen.portfolio.ui.factors;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import jakarta.inject.Inject;
import com.google.gson.Gson;

import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;

import name.abuchen.portfolio.factors.DbiData;
import name.abuchen.portfolio.factors.InvescoData;
import name.abuchen.portfolio.factors.Factor;
import name.abuchen.portfolio.factors.FactorMap;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.editor.AbstractFinanceView;
import name.abuchen.portfolio.ui.util.viewers.ColumnViewerSorter;
import name.abuchen.portfolio.util.TextUtil;

@SuppressWarnings("nls")
public final class FactorMapView extends AbstractFinanceView
{
    public static final String TITLE = "Positions & impact DBi";
    private static final String CACHE = "fork.factor-map.dbmf.cache.v1";
    private static final String UCITS_CACHE = "fork.factor-map.ucits.cache.v1";
    private static final String PROXY = "fork.factor-map.us-proxy.v1";
    private static final String CHECKED = "fork.factor-map.checked.v1";

    @Inject
    private ExchangeRateProviderFactory factory;
    private Composite body;
    private Combo scope;
    private Button proxy;
    private Label summary;
    private Label status;
    private Label dates;
    private Label explanation;
    private Canvas chart;
    private TableViewer map;
    private TableViewer positions;
    private TableViewer futures;
    private TableViewer ucitsPositions;
    private Label ucitsDate;
    private Label dbmfDate;
    private Button refreshButton;
    private TableViewer commodities;
    private Label commodityDate;
    private Label commodityStatus;
    private InvescoData.Snapshot invesco;
    private boolean fetchingInvesco;
    private DbiData.Snapshot snapshot;
    private DbiData.Ucits ucits;
    private FactorMap.Result result;
    private boolean fetching;
    private boolean loaded;

    @Override
    protected String getDefaultTitle()
    {
        return TITLE;
    }

    @Override
    protected void addButtons(ToolBarManager toolbar)
    {
        // Source actions live in their tab; no actions in the view header.
    }

    @Override
    protected Control createBody(Composite parent)
    {
        body = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(12, 8).spacing(0, 9).applyTo(body);
        var controls = new Composite(body, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).spacing(10, 0).applyTo(controls);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(controls);
        new Label(controls, SWT.NONE).setText("Périmètre");
        scope = new Combo(controls, SWT.READ_ONLY);
        scope.setItems("Portefeuille complet", "Pilotage Global");
        scope.select(getClient().getTaxonomies().stream().anyMatch(t -> "Pilotage Global".equals(t.getName())) ? 1 : 0);
        scope.addListener(SWT.Selection, e -> notifyModelUpdated());
        summary = label(body);
        dates = label(body);
        var tabs = new TabFolder(body, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(tabs);


        var scroll = new ScrolledComposite(tabs, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        var mapBody = new Composite(scroll, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(10, 8).spacing(0, 8).applyTo(mapBody);
        chart = new Canvas(mapBody, SWT.DOUBLE_BUFFERED);
        GridDataFactory.fillDefaults().grab(true, false).hint(940, 125 + 32 * Factor.portfolioAxes().size()).applyTo(chart);
        chart.addPaintListener(e -> paint(e.gc));
        chart.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter()
        {
            @Override
            public void getName(org.eclipse.swt.accessibility.AccessibleEvent e)
            {
                e.result = "Carte des facteurs actions, devises, matières premières et taux US. Toutes les valeurs figurent dans le tableau ci-dessous.";
            }
        });
        map = table(mapBody, 245);
        column(map, "Facteur", 220, SWT.LEFT, (FactorMap.Row r) -> r.factor().label());
        column(map, "Baseline connue (pts)", 155, SWT.RIGHT, (FactorMap.Row r) -> knownPoints(r.baseline(), r.coverage()));
        column(map, "Couverture baseline", 145, SWT.RIGHT, (FactorMap.Row r) -> percent(r.coverage()));
        column(map, "DBMF / actif net (%)", 150, SWT.RIGHT, (FactorMap.Row r) -> points(r.fund()));
        column(map, "Apport DBi (pts)", 145, SWT.RIGHT, (FactorMap.Row r) -> points(r.overlay()));
        column(map, "Après DBi connu (pts)", 160, SWT.RIGHT, (FactorMap.Row r) -> knownPoints(r.knownAfter(), r.coverage()));
        explanation = label(mapBody);
        scroll.setContent(mapBody);
        scroll.setMinSize(980, 520 + 32 * Factor.portfolioAxes().size());
        tab(tabs, "Expositions", scroll);

        var posBody = new Composite(tabs, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(10, 8).spacing(0, 8).applyTo(posBody);
        label(posBody).setText("Positions actuelles du fichier ouvert, comptes inclus. Cliquer sur un en-tête pour trier, puis inverser le sens. Double-cliquer pour préciser les expositions d'un support. "
                        + "— = inconnu ; zéro = absence identifiée ; * = exposition partiellement documentée. Les paramètres seront conservés lors de l'enregistrement du portefeuille.");
        positions = table(posBody, SWT.DEFAULT);
        positionColumn("Support", 275, SWT.LEFT, p -> p.vehicle().getName(), p -> p.vehicle().getName());
        positionColumn("Valeur EUR", 130, SWT.RIGHT, p -> p.value() == null ? "—" : Values.Amount.format(p.value()), FactorMap.Position::value);
        positionColumn("Date du cours", 105, SWT.LEFT, p -> p.quoteDate() == null ? "—" : p.quoteDate().toString(), FactorMap.Position::quoteDate);
        positionColumn("Rôle", 80, SWT.LEFT, p -> p.dbi() ? "DBi" : "Baseline", p -> p.dbi() ? "DBi" : "Baseline");
        for (var factor : Factor.portfolioAxes())
            positionColumn(factor.label() + " %", 155, SWT.RIGHT, p -> points(p.exposures().get(factor))
                            + (p.exposures().containsKey(factor) && p.coverage().getOrDefault(factor, 0d) < .999999 ? "*" : ""),
                            p -> p.exposures().get(factor));
        positionColumn("Méthode / source", 600, SWT.LEFT, FactorMap.Position::detail, FactorMap.Position::detail);
        positions.addDoubleClickListener(e -> edit());
        tab(tabs, "Positions du portefeuille", posBody);

        var sourceBody = new Composite(tabs, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(10, 8).spacing(0, 8).applyTo(sourceBody);
        var sourceActions = new Composite(sourceBody, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).spacing(16, 0).applyTo(sourceActions);
        refreshButton = new Button(sourceActions, SWT.PUSH);
        refreshButton.setText("Mettre à jour les positions DBi");
        refreshButton.addListener(SWT.Selection, e -> refresh());
        proxy = new Button(sourceActions, SWT.CHECK);
        proxy.setText("Carte notionnelle : proxy DBMF US");
        proxy.setToolTipText("Activé par défaut faute de notionnels UCITS complets. Approximation : les différences de stratégie et la couverture EUR ne sont pas reproduites.");
        getPreferenceStore().setDefault(PROXY, true);
        proxy.setSelection(getPreferenceStore().getBoolean(PROXY));
        proxy.addListener(SWT.Selection, e -> {
            getPreferenceStore().setValue(PROXY, proxy.getSelection());
            notifyModelUpdated();
        });
        status = label(sourceBody);
        status.setText("Les dates ci-dessous sont celles des positions publiées, pas celles de leur téléchargement.");
        ucitsDate = label(sourceBody);
        var links = new Composite(sourceBody, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(3).spacing(12, 0).applyTo(links);
        link(links, "Reporting de la part EUR couverte", () -> Program.launch(DbiData.UCITS_FACTSHEET_URL));
        link(links, "Composition UCITS officielle", () -> Program.launch(ucits.source()));
        link(links, "Positions DBMF officielles", () -> Program.launch(DbiData.DBMF_URL));
        ucitsPositions = table(sourceBody, 260);
        column(ucitsPositions, "Type", 155, SWT.LEFT, DbiData.UcitsHolding::group);
        column(ucitsPositions, "Identifiant", 150, SWT.LEFT, DbiData.UcitsHolding::identifier);
        column(ucitsPositions, "Instrument UCITS", 430, SWT.LEFT, DbiData.UcitsHolding::name);
        column(ucitsPositions, "Quantité publiée", 170, SWT.RIGHT, DbiData.UcitsHolding::quantity);
        column(ucitsPositions, "% NAV comptable", 160, SWT.RIGHT, (DbiData.UcitsHolding h) -> percent(h.navWeight()));
        dbmfDate = label(sourceBody);
        futures = table(sourceBody, 220);
        column(futures, "Facteur", 225, SWT.LEFT, (DbiData.Holding h) -> h.factor().label());
        column(futures, "Contrat", 270, SWT.LEFT, DbiData.Holding::name);
        column(futures, "Ticker", 80, SWT.LEFT, DbiData.Holding::ticker);
        column(futures, "Quantité publiée", 160, SWT.RIGHT, DbiData.Holding::quantity);
        column(futures, "Notionnel USD", 180, SWT.RIGHT, (DbiData.Holding h) -> String.format(Locale.FRANCE, "%,.2f", h.notional()));
        column(futures, "% actif net DBMF", 150, SWT.RIGHT, (DbiData.Holding h) -> points(h.notional() / snapshot.nav()));
        tab(tabs, "Positions DBi & sources", sourceBody);

        var commodityBody = new Composite(tabs, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(10, 8).spacing(0, 8).applyTo(commodityBody);
        commodityDate = label(commodityBody);
        label(commodityBody).setText("Composition économique de l'indice répliqué par l'ETF Invesco Bloomberg Commodity (IE00BD6FTQ80). "
                        + "L'or et le WTI alimentent la baseline ; le Brent et les autres matières restent détaillés ici. "
                        + "Les pondérations publiées sont appliquées à la valeur PP actuelle du support, sans renormalisation. "
                        + "Une hypothèse explicite saisie sur le support reste prioritaire.");
        var commodityButtons = new Composite(commodityBody, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).spacing(12, 0).applyTo(commodityButtons);
        link(commodityButtons, "Mettre à jour depuis Invesco", () -> refreshInvesco());
        link(commodityButtons, "Ouvrir la source officielle", () -> Program.launch(InvescoData.PAGE));
        commodities = table(commodityBody, 530);
        column(commodities, "Matière première", 190, SWT.LEFT, InvescoData.Holding::label);
        column(commodities, "Poids dans l'indice", 145, SWT.RIGHT, (InvescoData.Holding h) -> percent(h.weight()));
        column(commodities, "Exposition connue EUR", 185, SWT.RIGHT, (InvescoData.Holding h) -> commodityAmount(h.weight()));
        column(commodities, "Facteur DBi", 200, SWT.LEFT, (InvescoData.Holding h) -> h.factor() == null ? "Hors des dix axes DBi" : h.factor().label());
        column(commodities, "Libellé publié", 650, SWT.LEFT, InvescoData.Holding::name);
        commodityStatus = label(commodityBody);
        commodityStatus.setText("La date affichée est celle de la composition, pas celle du téléchargement. Les données sont conservées en cas d'échec.");
        tab(tabs, "Invesco · matières premières", commodityBody);
        // Cocoa can leave the first row underneath the translucent header when
        // a table is populated while its tab is hidden. Restore the initial
        // scroll origin after the tab's first layout, without resetting later scrolling.
        var revealed = new java.util.HashSet<TabItem>();
        tabs.addListener(SWT.Selection, e -> {
            if (tabs.getSelection().length == 0 || !revealed.add(tabs.getSelection()[0]))
                return;
            body.getDisplay().asyncExec(() -> {
                if (body.isDisposed())
                    return;
                body.layout(true, true);
                for (var viewer : List.of(map, positions, futures, ucitsPositions, commodities))
                {
                    var table = viewer.getTable();
                    if (table.isVisible() && table.getItemCount() > 0)
                    {
                        table.setTopIndex(Math.min(1, table.getItemCount() - 1));
                        table.setTopIndex(0);
                    }
                }
            });
        });
        loadData();
        notifyModelUpdated();
        if (!LocalDate.now().toString().equals(getPreferenceStore().getString(CHECKED)))
            body.getDisplay().asyncExec(() -> { if (!body.isDisposed()) refresh(); });
        return body;
    }

    private static void tab(TabFolder tabs, String text, Control content)
    {
        var tab = new TabItem(tabs, SWT.NONE);
        tab.setText(text);
        tab.setControl(content);
    }

    private static Label label(Composite parent)
    {
        var label = new Label(parent, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).hint(950, SWT.DEFAULT).applyTo(label);
        return label;
    }

    private static void link(Composite parent, String text, Runnable action)
    {
        var button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.addListener(SWT.Selection, e -> action.run());
    }

    private static TableViewer table(Composite parent, int height)
    {
        var viewer = new TableViewer(parent, SWT.FULL_SELECTION | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(SWT.DEFAULT, height).applyTo(viewer.getTable());
        ColumnViewerToolTipSupport.enableFor(viewer);
        return viewer;
    }

    private static <T> TableViewerColumn column(TableViewer viewer, String title, int width, int alignment, Function<T, String> function)
    {
        var column = new TableViewerColumn(viewer, alignment);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.getColumn().setMoveable(true);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            @SuppressWarnings("unchecked")
            public String getText(Object element) { return function.apply((T) element); }

            @Override
            public String getToolTipText(Object element)
            {
                return element instanceof FactorMap.Position p ? p.detail() : getText(element);
            }
        });
        return column;
    }

    private <V extends Comparable<? super V>> void positionColumn(String title, int width, int alignment,
                    Function<FactorMap.Position, String> label, Function<FactorMap.Position, V> value)
    {
        var column = column(positions, title, width, alignment, label);
        Comparator<FactorMap.Position> comparator = (left, right) -> {
            V a = value.apply(left);
            V b = value.apply(right);
            if (a == null || b == null)
            {
                // The viewer reverses this comparator for descending order.
                // Compensate so missing values stay last in both directions.
                int direction = ColumnViewerSorter.SortingContext.getSortDirection() == SWT.UP ? 1 : -1;
                return a == b ? 0 : (a == null ? 1 : -1) * direction;
            }
            return a instanceof String x && b instanceof String y ? TextUtil.compare(x, y) : a.compareTo(b);
        };
        ColumnViewerSorter.create(comparator).attachTo(positions, column);
    }

    private void loadData()
    {
        if (loaded)
            return;
        snapshot = DbiData.bundled();
        ucits = DbiData.bundledUcits();
        try
        {
            String cached = getPreferenceStore().getString(CACHE);
            if (!cached.isBlank())
            {
                var candidate = new Gson().fromJson(cached, DbiData.Snapshot.class);
                if (!LocalDate.parse(candidate.date()).isAfter(LocalDate.now())
                                && !LocalDate.parse(candidate.date()).isBefore(LocalDate.parse(snapshot.date())))
                    snapshot = candidate;
            }
            String cachedUcits = getPreferenceStore().getString(UCITS_CACHE);
            if (!cachedUcits.isBlank())
            {
                var candidate = new Gson().fromJson(cachedUcits, DbiData.Ucits.class);
                if (!LocalDate.parse(candidate.date()).isAfter(LocalDate.now())
                                && !LocalDate.parse(candidate.date()).isBefore(LocalDate.parse(ucits.date())))
                    ucits = candidate;
            }
        }
        catch (RuntimeException e)
        {
            status.setText("Cache illisible ; références datées embarquées utilisées.");
        }
        loaded = true;
    }

    @Override
    public void notifyModelUpdated()
    {
        if (body == null || body.isDisposed())
            return;
        try
        {
            loadData();
            invesco = InvescoData.load(getClient());
            result = FactorMap.calculate(getClient(), new CurrencyConverterImpl(factory, "EUR"), LocalDate.now(),
                            scope.getSelectionIndex() == 1, proxy.getSelection(), false, snapshot, invesco);
            summary.setText(result.scope() + " · Valeur PP " + Values.Amount.format(result.assets()) + " EUR"
                            + (result.valued() ? "" : " (incomplète)") + " · DBi " + Values.Amount.format(result.dbiValue())
                            + " EUR · Poids " + percent(result.dbiWeight())
                            + (proxy.getSelection() ? " · Carte notionnelle : proxy DBMF US — couverture EUR exclue" : " · Fonds détenu"));
            dates.setText("Positions PP : " + LocalDate.now() + " · Futures DBMF : " + snapshot.date()
                            + " (" + age(snapshot.date()) + ")"
                            + " · Composition UCITS : " + ucits.date() + " (" + age(ucits.date()) + ")");
            int chartHeight = 125 + 32 * result.rows().size();
            ((org.eclipse.swt.layout.GridData) chart.getLayoutData()).heightHint = chartHeight;
            ((ScrolledComposite) chart.getParent().getParent()).setMinHeight(395 + chartHeight);
            map.setInput(result.rows());
            positions.setInput(result.positions());
            futures.setInput(snapshot.holdings());
            dbmfDate.setText("DBMF US · Positions publiées au " + snapshot.date() + " (" + age(snapshot.date()) + ")\n"
                            + "Notionnels des futures en USD, distincts des valeurs comptables ; cash et collatéral exclus de la carte.");
            ucitsDate.setText("DBi UCITS EUR couvert · LU3359622902 · Publication au " + ucits.date() + "\n"
                            + "Composition : quantités et % NAV comptables, sans notionnels complets ni détail de la couverture EUR. "
                            + "Le reporting mensuel de cette part publie aussi des expositions en % NAV, mais les taux en duration : ces unités ne sont pas interchangeables. "
                            + "Proxy US activé par défaut pour la carte ; désactivable, couverture EUR exclue.");
            ucitsPositions.setInput(ucits.positions());
            commodityDate.setText("Invesco Bloomberg Commodity · IE00BD6FTQ80 · Composition de l'indice au " + invesco.date()
                            + " (" + age(invesco.date()) + ") · " + invesco.holdings().size() + " lignes · " + result.scope());
            commodities.setInput(invesco.holdings());
            String help = "Points de portefeuille = exposition / valeur totale du périmètre, DBi inclus dans le dénominateur et exclu de la baseline. "
                                            + "Une couverture inférieure à 100 % signale des sous-totaux connus avant/après DBi (marqués *) ; l'exposition totale reste inconnue. "
                                            + "Actions = ventilation économique par géographie, pas bêta aux indices. Taux US = notionnels des futures, pas sensibilités en duration. "
                                            + "FX notionnel : +USD/EUR = long dollar ; +JPY/EUR = long yen. Les contreparties USD des futures DBi sont conservées. Les axes ne s'additionnent pas entre eux. Les dates des compositions diffèrent de celles des valorisations.";
            explanation.setText(help + (result.issues().isEmpty() ? "" : "\n" + String.join("\n", result.issues())));
        }
        catch (RuntimeException e)
        {
            result = null;
            map.setInput(List.of());
            positions.setInput(List.of());
            commodities.setInput(List.of());
            summary.setText("Calcul indisponible : " + e.getMessage());
            explanation.setText("Aucun ancien résultat n'est présenté comme actuel.");
        }
        chart.redraw();
        body.layout(true, true);
    }

    private static String age(String date)
    {
        long days = ChronoUnit.DAYS.between(LocalDate.parse(date), LocalDate.now());
        return days + " jours" + (days > 7 ? ", ancien" : "");
    }

    private String commodityAmount(double weight)
    {
        if (result == null || invesco == null || LocalDate.parse(invesco.date()).isAfter(LocalDate.now()))
            return "—";
        var holdings = result.positions().stream().filter(p -> p.vehicle() instanceof name.abuchen.portfolio.model.Security s
                        && InvescoData.ISIN.equals(s.getIsin())).toList();
        if (holdings.isEmpty())
            return "Non détenu";
        if (holdings.stream().anyMatch(p -> p.value() == null))
            return "—";
        return Values.Amount.format(Math.round(holdings.stream().mapToLong(FactorMap.Position::value).sum() * weight));
    }

    private void refreshInvesco()
    {
        if (fetchingInvesco || body.isDisposed())
            return;
        fetchingInvesco = true;
        var previous = invesco;
        var expected = getClient().getProperty(InvescoData.PROPERTY);
        var display = body.getDisplay();
        commodityStatus.setText("Lecture de la composition officielle Invesco…");
        Thread.startVirtualThread(() -> {
            InvescoData.Snapshot updated = null;
            String error = null;
            try { updated = InvescoData.fetch(previous, LocalDate.now()); }
            catch (Exception e) { error = e.getMessage(); }
            var received = updated;
            var failure = error;
            if (!display.isDisposed())
                display.asyncExec(() -> {
                    if (body.isDisposed())
                        return;
                    fetchingInvesco = false;
                    if (received == null)
                        commodityStatus.setText("Actualisation impossible ; composition précédente conservée. " + failure);
                    else
                    {
                        try
                        {
                            InvescoData.save(getClient(), received, expected);
                            markDirty();
                            notifyModelUpdated();
                            commodityStatus.setText("Source vérifiée aujourd'hui · Composition publiée au " + received.date()
                                            + " (" + age(received.date()) + "). Enregistrer le portefeuille pour conserver cette publication.");
                        }
                        catch (RuntimeException e) { commodityStatus.setText(e.getMessage()); }
                    }
                    body.layout(true, true);
                });
        });
    }

    private void edit()
    {
        if (positions == null || !(positions.getStructuredSelection().getFirstElement() instanceof FactorMap.Position position))
        {
            MessageDialog.openInformation(getActiveShell(), TITLE, "Sélectionner un support dans « Positions du portefeuille ».");
            return;
        }
        if (position.dbi())
        {
            MessageDialog.openInformation(getActiveShell(), TITLE,
                            "DBi est traité séparément de la baseline. Ses publications sont dans « Positions DBi & sources ».");
            return;
        }
        if (new FactorMappingDialog(getActiveShell(), getClient(), position).open() == Window.OK)
        {
            markDirty();
            notifyModelUpdated();
        }
    }

    private void refresh()
    {
        if (body == null || body.isDisposed() || fetching)
            return;
        fetching = true;
        refreshButton.setEnabled(false);
        var display = body.getDisplay();
        var previous = snapshot;
        var previousUcits = ucits;
        var preferences = getPreferenceStore();
        preferences.setValue(CHECKED, LocalDate.now().toString());
        status.setText("Lecture des publications officielles iMGP…");
        Thread.startVirtualThread(() -> {
            var source = new DbiData();
            DbiData.Snapshot updated = previous;
            DbiData.Ucits updatedUcits = previousUcits;
            var messages = new java.util.ArrayList<String>();
            try { updated = source.fetch(previous, LocalDate.now()); }
            catch (Exception e) { messages.add("DBMF : " + e.getMessage()); }
            try { updatedUcits = source.fetchUcits(previousUcits, LocalDate.now()); }
            catch (Exception e) { messages.add("UCITS : " + e.getMessage()); }
            var finalSnapshot = updated;
            var finalUcits = updatedUcits;
            if (!display.isDisposed())
                display.asyncExec(() -> {
                    if (body.isDisposed())
                        return;
                    snapshot = finalSnapshot;
                    ucits = finalUcits;
                    preferences.setValue(CACHE, new Gson().toJson(snapshot));
                    preferences.setValue(UCITS_CACHE, new Gson().toJson(ucits));
                    fetching = false;
                    refreshButton.setEnabled(true);
                    status.setText(messages.isEmpty()
                                    ? "Sources vérifiées le " + LocalDate.now() + " · Dates des positions publiées affichées ci-dessous."
                                    : "Actualisation partielle ; dernières publications conservées. " + String.join(" · ", messages));
                    notifyModelUpdated();
                });
        });
    }

    private static String points(Double value)
    {
        return value == null ? "—" : String.format(Locale.FRANCE, "%+.2f", value * 100);
    }

    private static String percent(Double value)
    {
        return value == null ? "—" : String.format(Locale.FRANCE, "%.2f %%", value * 100);
    }

    private static String knownPoints(Double value, double coverage)
    {
        return points(value) + (value != null && coverage < .999999 ? "*" : "");
    }

    private void paint(GC gc)
    {
        if (result == null)
            return;
        gc.setAntialias(SWT.ON);
        var display = chart.getDisplay();
        int width = chart.getClientArea().width;
        int nameWidth = 215;
        int column = Math.max(205, (width - nameWidth) / 3);
        var minimum = new double[3];
        var maximum = new double[3];
        var known = new boolean[3];
        for (var row : result.rows())
        {
            Double[] values = { row.baseline(), row.overlay(), row.knownAfter() };
            for (int c = 0; c < 3; c++)
                if (values[c] != null)
                {
                    known[c] = true;
                    minimum[c] = Math.min(minimum[c], values[c]);
                    maximum[c] = Math.max(maximum[c], values[c]);
                }
        }
        // Equal pixels per point before/after DBi, but no unused negative half
        // for a long-only baseline. The overlay has its own explicitly labelled zoom.
        double portfolioSpan = Math.max(.01, Math.max(maximum[0], maximum[2]) - Math.min(minimum[0], minimum[2])) * 1.05;
        double overlaySpan = Math.max(.0001, maximum[1] - minimum[1]) * 1.05;
        double[] spans = { portfolioSpan, overlaySpan, portfolioSpan };
        String[] headers = { "PORTEFEUILLE · CONNU", proxy.getSelection() ? "APPORT DBMF · PROXY US" : "APPORT DBi", "APRÈS DBi · CONNU" };
        int plotWidth = column - 92;
        for (int c = 0; c < 3; c++)
        {
            int start = nameWidth + c * column + 8;
            gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            gc.drawText(headers[c], start, 8, true);
            String scale = c == 1
                            ? (!known[0] && !known[2] || maximum[1] == minimum[1] ? "Échelle propre · points de portefeuille"
                                                            : String.format(Locale.FRANCE, "Zoom ×%.1f · points de portefeuille", portfolioSpan / overlaySpan))
                            : "Échelle portefeuille commune";
            gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_DARK_SHADOW));
            gc.drawText(known[c] ? scale : "Non comparable dans cette unité", start, 28, true);
            if (known[c])
            {
                String left = String.format(Locale.FRANCE, "%.1f", minimum[c] * 100);
                String right = String.format(Locale.FRANCE, "%.1f", (minimum[c] + spans[c]) * 100);
                gc.drawText(left, start, 49, true);
                gc.drawText(right, start + plotWidth - gc.textExtent(right).x, 49, true);
                gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
                gc.drawLine(start, 68, start + plotWidth, 68);
            }
        }
        int y = 78;
        for (var row : result.rows())
        {
            gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            gc.drawText(row.factor().label(), 0, y + 4, true);
            Double[] values = { row.baseline(), row.overlay(), row.knownAfter() };
            for (int c = 0; c < 3; c++)
            {
                int start = nameWidth + c * column;
                int zero = start + 8 + (int) Math.round(plotWidth * -minimum[c] / spans[c]);
                gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
                if (known[c])
                    gc.drawLine(zero, y - 2, zero, y + 26);
                Double value = values[c];
                if (value != null)
                {
                    int length = (int) Math.round(plotWidth * Math.abs(value) / spans[c]);
                    gc.setBackground(display.getSystemColor(value < 0 ? SWT.COLOR_DARK_RED : SWT.COLOR_DARK_GREEN));
                    gc.setAlpha(c == 0 ? 100 : 220);
                    gc.fillRectangle(value < 0 ? zero - length : zero, y + 4, length, 16);
                    gc.setAlpha(255);
                }
                gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
                gc.drawText(points(value) + (c != 1 && row.coverage() < .999999 && value != null ? "*" : ""),
                                start + column - 65, y + 3, true);
            }
            y += 32;
        }
        gc.drawText("+ Long   − Short   ·   " + "Points de portefeuille"
                        + "   ·   Zoom DBi indépendant : comparer les valeurs chiffrées entre colonnes", 0, y + 6, true);
        gc.drawText("* Sous-total partiel : couverture dans le tableau   ·   — Inconnu / non comparable", 0, y + 26, true);
    }
}
