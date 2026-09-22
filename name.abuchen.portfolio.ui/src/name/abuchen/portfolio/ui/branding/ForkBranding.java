package name.abuchen.portfolio.ui.branding;

/** Optional visual identity, independent of the fork's financial features. */
public final class ForkBranding
{
    private ForkBranding()
    {
    }

    public static String logo(String original)
    {
        return Boolean.getBoolean("portfolio.vividBranding") ? "fork-vivid/" + original : original; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
