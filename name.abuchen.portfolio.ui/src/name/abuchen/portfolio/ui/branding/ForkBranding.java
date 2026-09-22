package name.abuchen.portfolio.ui.branding;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;

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

    /** Uses the existing window title and tab, without adding content height. */
    public static void applyWindowIdentity(MPart part, MWindow window)
    {
        if (!isEnabled())
            return;

        window.setLabel("Portfolio Performance PE · Fork"); //$NON-NLS-1$
        var icon = "platform:/plugin/name.abuchen.portfolio.ui/icons/fork-vivid/pp_16.png"; //$NON-NLS-1$
        window.setIconURI(icon);
        part.setIconURI(icon);
    }
}
