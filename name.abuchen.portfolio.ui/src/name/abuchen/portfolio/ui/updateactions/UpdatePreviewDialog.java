package name.abuchen.portfolio.ui.updateactions;

import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/** Common before/after preview for independent on-demand update actions. */
public final class UpdatePreviewDialog extends TitleAreaDialog
{
    public record Change(String location, String before, String after)
    {
    }

    private final String title;
    private final String summary;
    private final List<Change> changes;

    public UpdatePreviewDialog(Shell parent, String title, String summary, List<Change> changes)
    {
        super(parent);
        this.title = title;
        this.summary = summary;
        this.changes = List.copyOf(changes);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        var area = (Composite) super.createDialogArea(parent);
        setTitle(title);
        setMessage(summary);
        var table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(760, 310).applyTo(table);
        String[] headers = { "Catégorie / affectation", "Avant", "Après" };
        for (int i = 0; i < headers.length; i++)
        {
            var column = new TableColumn(table, SWT.NONE);
            column.setText(headers[i]);
            column.setWidth(i == 0 ? 510 : 115);
        }
        for (var change : changes)
        {
            var item = new TableItem(table, SWT.NONE);
            item.setText(new String[] { change.location(), change.before(), change.after() });
        }
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, "Créer la copie…", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Annuler", false);
    }
}
