package name.abuchen.portfolio.ui.commitments;

import java.time.LocalDate;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.swt.SWT;
import name.abuchen.portfolio.commitments.Commitments;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.util.viewers.*;

public final class CommitmentColumns
{
    private CommitmentColumns() { }
    public static Column create(Client client, AttributeType attribute, CurrencyConverter converter)
    {
        var column = new Column("attribute$" + attribute.getId(), attribute.getColumnLabel(), SWT.RIGHT, 130);
        column.setGroupLabel(Messages.GroupLabelAttributes);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override public String getText(Object element)
            {
                Long value = amount(client, element, attribute.getId(), converter);
                return value == null ? "—" : Values.Amount.format(value);
            }
            @Override public String getToolTipText(Object element)
            { return "Calcul en EUR : achat initial hors frais + appels de fonds. Distributions exclues. Détail dans Engagements PE."; }
        });
        column.setSorter(ColumnViewerSorter.create((a, b) -> java.util.Comparator.nullsLast(Long::compareTo)
                        .compare(amount(client, a, attribute.getId(), converter), amount(client, b, attribute.getId(), converter))));
        new AttributeEditingSupport(attribute) { @Override public boolean canEdit(Object element) { return false; } }.attachTo(column);
        return column;
    }
    private static Long amount(Client client, Object element, String id, CurrencyConverter converter)
    {
        var security = Adaptor.adapt(Security.class, element);
        if (security == null || !security.getAttributes().getMap().containsKey(Commitments.TOTAL)) return null;
        var row = Commitments.row(client, security, converter, LocalDate.now());
        return Commitments.PAID.equals(id) ? row.paid() : row.remaining();
    }
}
