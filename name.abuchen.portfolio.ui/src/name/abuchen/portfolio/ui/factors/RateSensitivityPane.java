package name.abuchen.portfolio.ui.factors;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.*;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.*;

import name.abuchen.portfolio.factors.*;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.util.TableViewerCSVExporter;
import name.abuchen.portfolio.ui.util.viewers.ColumnViewerSorter;

@SuppressWarnings("nls")
public final class RateSensitivityPane
{
    private final Client client;
    private final Runnable edited;
    private final Composite body;
    private final ScrolledComposite scroll;
    private final Label summary;
    private final Label source;
    private final Canvas chart;
    private final Button segments;
    private TableViewer rates;
    private TableViewer details;
    private final Button allSupports;
    private RateSensitivity.Result result;

    public RateSensitivityPane(Composite parent, Client client, Runnable edited, Runnable refresh)
    {
        this.client = client;
        this.edited = edited;
        scroll = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        body = new Composite(scroll, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(12, 10).spacing(0, 8).applyTo(body);
        label(body).setText("TAUX · COMPENSATION PAR DBi     |     Sensibilités en €/point de base (0,01 point de taux)");
        label(body).setText("+100 = environ 100 € perdus si le taux ou l'écart monte de 0,01 point, les autres facteurs restant fixes. "
                        + "Les lignes ne s'additionnent pas entre familles. Une compensation du mouvement commun laisse le risque d'écart entre courbes.");
        summary = label(body);
        source = label(body);
        chart = new Canvas(body, SWT.DOUBLE_BUFFERED);
        GridDataFactory.fillDefaults().grab(true, false).hint(1000, 230).applyTo(chart);
        chart.addPaintListener(e -> paint(e.gc));
        chart.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter()
        {
            @Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent event)
            { event.result = "Sensibilités taux : portefeuille, DBi et ensemble. Valeurs accessibles dans le tableau."; }
        });
        var buttons = new Composite(body, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(4).spacing(12, 0).applyTo(buttons);
        segments = new Button(buttons, SWT.CHECK);
        segments.setText("Détail courts / intermédiaires / longs");
        segments.addListener(SWT.Selection, e -> showRows());
        button(buttons, "Paramétrer le support sélectionné…", this::edit);
        button(buttons, "Exporter les sensibilités…", () -> new TableViewerCSVExporter(rates).export("Sensibilites-taux.csv"));
        button(buttons, "Actualiser iMGP", refresh);
        rates = table(body, 160);
        column(rates, "Facteur", 295, SWT.LEFT, (RateSensitivity.Row r) -> r.label());
        column(rates, "Portefeuille connu €/pb", 180, SWT.RIGHT, (RateSensitivity.Row r) -> number(r.baseline(), r.coverage() < .999999));
        column(rates, "Couverture", 100, SWT.RIGHT, (RateSensitivity.Row r) -> String.format(Locale.FRANCE, "%.1f %%", r.coverage() * 100));
        column(rates, "DBi €/pb", 145, SWT.RIGHT, (RateSensitivity.Row r) -> number(r.overlay(), false));
        column(rates, "Ensemble connu €/pb", 190, SWT.RIGHT, (RateSensitivity.Row r) -> number(r.after(), r.coverage() < .999999));
        label(body).setText("* Sous-total documenté ; — inconnu. Couverture = valeur des supports documentés pour ce facteur / valeur des supports du périmètre obligataire ou non classés. "
                        + "Les actions, l'or et les comptes sans position obligataire directe sont hors de ce périmètre.\n"
                        + "Ventilation des obligations calculées : projection des flux sur des repères 2/10/30 ans, indicative. Les segments DBi sont ceux du reporting. "
                        + "Le total mesure un mouvement parallèle ; les sous-lignes ne constituent pas une couverture exacte de chaque maturité.");
        label(body).setText("SUPPORTS · Double-cliquer pour renseigner une duration sourcée. Aucun fonds non documenté n'est affecté automatiquement.");
        allSupports = new Button(body, SWT.CHECK);
        allSupports.setText("Afficher aussi les supports hors périmètre obligataire");
        allSupports.addListener(SWT.Selection, e -> showDetails());
        details = table(body, 190);
        column(details, "Support", 295, SWT.LEFT, (RateSensitivity.Detail d) -> d.position().vehicle().getName());
        column(details, "Valeur EUR", 125, SWT.RIGHT, (RateSensitivity.Detail d) -> d.position().value() == null ? "—" : Values.Amount.format(d.position().value()));
        column(details, "Courbe / statut", 245, SWT.LEFT, (RateSensitivity.Detail d) -> d.mapping() == null ? "À documenter" : d.mapping().curve().label());
        var dv = column(details, "Sensibilité €/pb", 140, SWT.RIGHT, (RateSensitivity.Detail d) -> number(d.dv01(), false));
        ColumnViewerSorter.create(java.util.Comparator.comparing(RateSensitivity.Detail::dv01, java.util.Comparator.nullsLast(Double::compare))).attachTo(details, dv);
        column(details, "Date sensibilité", 125, SWT.LEFT, (RateSensitivity.Detail d) -> d.mapping() == null || java.util.Arrays.stream(d.mapping().durations()).allMatch(java.util.Objects::isNull) ? "—" : d.mapping().date());
        column(details, "Méthode / source", 900, SWT.LEFT, RateSensitivity.Detail::status);
        details.addDoubleClickListener(e -> edit());
        button(body, "Ouvrir le reporting de la part EUR couverte", () -> Program.launch(DbiData.UCITS_FACTSHEET_URL));
        scroll.setContent(body);
        resize();
    }

    public Control control() { return scroll; }

    public void update(FactorMap.Result portfolio, RateSettings.Mapping ucits)
    {
        try
        {
            result = RateSensitivity.calculate(client, portfolio, LocalDate.now(), ucits);
            summary.setText(portfolio.scope() + " · Supports avec duration documentée : " + Values.Amount.format(result.documentedValue())
                            + " / " + Values.Amount.format(result.consideredValue()) + " EUR hors DBi"
                            + (result.valued() ? "" : " · Valorisation incomplète : couverture non déterminée"));
            source.setText("Référence automatique DBi : duration des dérivés de la part UCITS EUR couverte publiée au " + ucits.date()
                            + " · " + java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(ucits.date()), LocalDate.now())
                            + " jours · Référence mensuelle, pas les positions du jour.\n"
                            + "Indépendant du proxy US de la carte notionnelle. Géographie des taux non publiée : écarts DBi inconnus ; collatéral et couverture EUR exclus.");
            showDetails();
            showRows();
        }
        catch (RuntimeException e) { clear("Calcul des taux indisponible : " + e.getMessage()); }
    }

    public void clear(String message)
    {
        result = null;
        rates.setInput(List.of());
        details.setInput(List.of());
        summary.setText(message);
        source.setText("");
        chart.redraw();
    }

    private void showDetails()
    {
        details.setInput(result == null ? List.of() : result.details().stream().filter(d -> allSupports.getSelection() || d.included()).toList());
    }

    private void showRows()
    {
        rates.setInput(result == null ? List.of() : result.rows().stream().filter(r -> segments.getSelection() || r.segment() < 0).toList());
        ((org.eclipse.swt.layout.GridData) rates.getTable().getLayoutData()).heightHint = segments.getSelection() ? 330 : 160;
        chart.redraw();
        resize();
    }

    private void resize()
    {
        scroll.setMinSize(body.computeSize(1080, SWT.DEFAULT));
        body.layout(true, true);
    }

    private void edit()
    {
        if (!(details.getStructuredSelection().getFirstElement() instanceof RateSensitivity.Detail detail))
        {
            org.eclipse.jface.dialogs.MessageDialog.openInformation(body.getShell(), "Sensibilité taux", "Sélectionner un support dans le tableau du bas.");
            return;
        }
        if (new RateMappingDialog(body.getShell(), client, detail).open() == Window.OK) edited.run();
    }

    private static Label label(Composite parent)
    {
        var label = new Label(parent, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).hint(1000, SWT.DEFAULT).applyTo(label);
        return label;
    }

    private static void button(Composite parent, String text, Runnable action)
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
        GridDataFactory.fillDefaults().grab(true, false).hint(1000, height).applyTo(viewer.getTable());
        ColumnViewerToolTipSupport.enableFor(viewer);
        return viewer;
    }

    private static <T> TableViewerColumn column(TableViewer viewer, String title, int width, int alignment, Function<T, String> text)
    {
        var column = new TableViewerColumn(viewer, alignment);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.getColumn().setMoveable(true);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override @SuppressWarnings("unchecked") public String getText(Object value) { return text.apply((T) value); }
            @Override public String getToolTipText(Object value) { return value instanceof RateSensitivity.Detail d ? d.status() : getText(value); }
        });
        return column;
    }

    private static String number(Double value, boolean partial)
    {
        return value == null ? "—" : String.format(Locale.FRANCE, "%+,.2f", value) + (partial ? "*" : "");
    }

    private void paint(GC gc)
    {
        if (result == null) return;
        var rows = result.rows().stream().filter(r -> r.segment() < 0).toList();
        double extent = rows.stream().flatMap(r -> java.util.stream.Stream.of(r.baseline(), r.overlay(), r.after()))
                        .filter(java.util.Objects::nonNull).mapToDouble(Math::abs).max().orElse(1);
        extent = Math.max(.01, extent) * 1.05;
        int name = 280, column = Math.max(230, (chart.getClientArea().width - name) / 3), plot = column - 90;
        var display = chart.getDisplay();
        gc.setAntialias(SWT.ON);
        for (int c = 0; c < 3; c++)
        {
            gc.drawText(List.of("PORTEFEUILLE · CONNU", "DBi · CONNU", "ENSEMBLE · CONNU").get(c), name + c * column, 5, true);
            gc.drawText("Même échelle · €/pb", name + c * column, 26, true);
        }
        int y = 55;
        for (var row : rows)
        {
            gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            gc.drawText(row.label(), 0, y + 3, true);
            Double[] values = { row.baseline(), row.overlay(), row.after() };
            for (int c = 0; c < 3; c++)
            {
                int start = name + c * column, zero = start + plot / 2;
                gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
                gc.drawLine(zero, y, zero, y + 23);
                if (values[c] != null)
                {
                    int length = (int) Math.round(Math.abs(values[c]) / extent * plot / 2);
                    gc.setBackground(display.getSystemColor(values[c] < 0 ? SWT.COLOR_DARK_RED : SWT.COLOR_DARK_GREEN));
                    gc.fillRectangle(values[c] < 0 ? zero - length : zero, y + 4, length, 15);
                }
                gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
                gc.drawText(number(values[c], c != 1 && row.coverage() < .999999), start + plot + 5, y + 3, true);
            }
            y += 32;
        }
    }
}
