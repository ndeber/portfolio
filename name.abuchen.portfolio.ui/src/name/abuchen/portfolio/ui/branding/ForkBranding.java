package name.abuchen.portfolio.ui.branding;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import name.abuchen.portfolio.ui.Images;
import name.abuchen.portfolio.ui.UIConstants;

/** Optional visual identity, independent of the fork's financial features. */
public final class ForkBranding
{
    private ForkBranding()
    {
    }

    public static String logo(String original)
    {
        return isEnabled() ? "fork-vivid/" + original : original; //$NON-NLS-1$
    }

    public static boolean isEnabled()
    {
        return Boolean.getBoolean("portfolio.vividBranding"); //$NON-NLS-1$
    }

    /** Adds a persistent, theme-aware identity strip to a grid-layout parent. */
    public static void createIdentityMarker(Composite parent)
    {
        if (!isEnabled())
            return;

        var marker = new Composite(parent, SWT.NONE);
        marker.setData(UIConstants.CSS.CLASS_NAME, "forkIdentity"); //$NON-NLS-1$
        GridLayoutFactory.fillDefaults().numColumns(2).margins(12, 6).spacing(8, 0).applyTo(marker);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(marker);

        var icon = new Label(marker, SWT.NONE);
        icon.setImage(Images.LOGO_16.image());
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER).applyTo(icon);

        var label = new Label(marker, SWT.NONE);
        label.setText("Portfolio Performance PE \u00b7 Fork"); //$NON-NLS-1$
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER).applyTo(label);
    }
}
